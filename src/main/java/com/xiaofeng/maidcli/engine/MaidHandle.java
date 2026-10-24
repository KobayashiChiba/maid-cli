package com.xiaofeng.maidcli.engine;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.MaidCliMod;
import com.xiaofeng.maidcli.engine.actions.WalkAction;
import com.xiaofeng.maidcli.infra.MaidCliConfig;
import com.xiaofeng.maidcli.ops.ReachOps;
import com.xiaofeng.maidcli.ops.WalkOps;
import com.xiaofeng.maidcli.transport.CmdException;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * ★ 一只女仆的容器 + 调度器（core-concepts §1 · class-design §3.1）。
 *
 * <p>我们这一侧的<b>全部状态</b>都住在这儿（旧实现散在 8 个类的静态 Map 里）。
 *
 * <p>两层生命周期（住一个对象里，活得不一样长）：
 * <ul>
 *   <li><b>身份层</b>（{@link #identity}）：落盘，跟世界存档走 —— 永远在</li>
 *   <li><b>运行时层</b>（queue / current / session / history / awake）：只在内存，重启就清</li>
 * </ul>
 *
 * <p>⚠️ <b>不持有 {@link EntityMaid} 引用</b> —— 她会卸载 / 死亡 / 复活（uuid 会变）
 * ⇒ 存着就是悬空引用。要用的时候按标签现找（{@link #maid()}）。
 */
public final class MaidHandle {

    /** 历史环的大小（COMMANDS.md §5.2.2：保留最近 128 条） */
    private static final int HISTORY_MAX = 128;

    // ── 身份（落盘）──
    public final MaidIdentity identity;

    // ── 运行时 ──
    /** 待办（上限见 config.queue_capacity） */
    public final Deque<Job> queue = new ArrayDeque<>();
    /** 当前作业 */
    public Job current;
    /** 会话（null = 界面没开） */
    public Session session;
    /** 历史（最近 128 条） */
    private final List<JobReport> history = new ArrayList<>();
    /** sense state 的 history=N 按它消费（since=<id> 不看它） */
    public long historyCursor;
    /** 受理时分配 = 响应里的 id */
    private long nextJobId = 1;

    // ── 唤醒状态（MaidLoader 维护）──
    /** 是否已唤醒（挂了加载票 + 接管） */
    public volatile boolean awake;
    /** 最后一次"收到女仆指令"的 tick（300 秒超时用） */
    public long lastCommandTick;
    /** 接管前的原任务 id（退出受控时还原用；null = 没接管过） */
    public String savedTaskId;
    /** 是否处于"受控"（自主行为已清） */
    public boolean controlled;
    /**
     * 接管前她是不是"跟随"模式 —— 退出受控时还原（规矩：不能擅自改用户设定）。
     * <p>接管时会强制切到跟随（T2：绕过留守范围传送），所以必须记原值。
     */
    public boolean originalFollow = true;
    /**
     * 我们最后一次设的任务 id —— T3 用来识别"任务被外部改了"。
     * <p>⚠️ 和 {@link #savedTaskId}（接管前的原任务）语义不同，别混用。
     */
    public String controlledTaskId;
    /** 两阶段唤醒：>0 表示"全圈已按只加载票挂上，等到这个 tick 再把内圈升成 tick 票" */
    public long wakeUpgradeAtTick;
    /** 唤醒时用的 key 玩家（谁把她拉起来的） */
    public java.util.UUID awakeBy;

    public MaidHandle(String tag) {
        this.identity = new MaidIdentity(tag);
    }

    // ────────────────────────── 实体句柄 ──────────────────────────

    /** ★ 按标签现找她（可能为 null —— 卸载 / 死亡 / 被收起来） */
    public EntityMaid maid() {
        MinecraftServer s = MaidCliMod.server();
        if (s == null) {
            return null;
        }
        return MaidRegistry.maidByTag(s, identity.tag);
    }

    public ServerLevel level(EntityMaid maid) {
        return (ServerLevel) maid.level();
    }

    // ────────────────────────── 受理 ──────────────────────────

    public long newJobId() {
        return nextJobId++;
    }

    /**
     * 入队（指令受理时调）。
     *
     * @throws CmdException {@code busy}（队列满 / 当前作业的待办也满了）
     */
    public void enqueue(Job job) {
        MinecraftServer s = MaidCliMod.server();
        int cap = s == null ? 256 : MaidCliConfig.get(s).queue_capacity;
        if (queue.size() + (current == null ? 0 : 1) >= cap) {
            throw new CmdException(CmdException.BUSY,
                    "她的待办队列满了（" + queue.size() + " 条）",
                    "先 stop 清一下，或者等一会儿");
        }
        queue.addLast(job);
    }

    /** 队列里排队中的条数（响应里的 queue_len） */
    public int queueLen() {
        return queue.size() + (current == null ? 0 : 1);
    }

    public boolean isIdle() {
        return current == null && queue.isEmpty();
    }

    // ────────────────────────── 唯一调度器 ──────────────────────────

    /**
     * ★ 每 tick 一次 —— 顺序即语义（class-design §3.1）：
     * <pre>
     * ① 体检：会话该关吗 / 唤醒该释放吗
     * ② 她不在世界里 ⇒ reset("gone")
     * ③ 没有 current 且队列非空 ⇒ 取一个
     * ④ 取 todo 队头；空 ⇒ finish(job) 出报告、写 history
     * ⑤ 需要够得着但够不着 ⇒ todo.addFirst(walk)（★ 防重复）
     * ⑥ 调 action.tick()；不在 RUNNING ⇒ 挪进 done
     * </pre>
     */
    public void tick(MinecraftServer server) {
        // ② 她一旦"不在世界里"就清掉未完成的作业（execution-model §3 ⑦b）
        EntityMaid maid = maid();
        if (maid == null) {
            if (current != null || !queue.isEmpty() || session != null) {
                reset("gone");
            }
            return;
        }
        // ① 体检
        checkSession(server, maid);
        // ③ 取作业
        if (current == null) {
            current = queue.pollFirst();
            if (current == null) {
                return;
            }
        }
        JobReport rep = current.report;
        // 超时（宏）：写一条失败历史，作业收口
        if (current.timeoutTicks > 0 && !current.aborted
                && server.getTickCount() - current.acceptedAtTick > current.timeoutTicks) {
            current.aborted = true;
            rep.result = JobReport.Result.ABORTED;
            rep.message = "超时";
            rep.addFailure("macro", "超时");
            finish(server, current, "timeout");
            return;
        }
        // ④ 取动作
        Action a = current.todo.peekFirst();
        if (a == null) {
            finish(server, current, null);
            return;
        }
        // ⑤ 需要够得着但够不着 ⇒ 插走位
 // ★ 判定与选点照旧版（搬回）：ReachOps.canReach()（含 place 收 1 格余量
 // + 自己占着目标格的判定）+ ReachOps.findStandPoint()（走 StandPoint.safe 球内选点，
 // **不是**"走到目标格本身"）。
 // ⚠️ v2 曾内联成一句 `d <= reach` 且直接把 reachTarget() 当走位目标 ⇒ 她贴脸站、
 // 碰撞箱压住目标格 ⇒ place 全报"原版判定放不上去"。
        if (a.needsReach() && a.reachTarget() != null
                && !ReachOps.canReach(maid, a, MaidCliConfig.get(server).reach)) {
            // 走位后仍够不到 ⇒ 判失败（旧版"一个动作最多走一次"的防死循环）
            if (a.walkTried) {
                current.todo.pollFirst();
                a.phase = Action.Phase.FAILED;
                a.failReason = "走位后仍够不到目标 " + a.reachTarget().toShortString();
                current.done.add(a);
                recordAction(rep, a, Action.Phase.FAILED);
                return;
            }
            if (!current.pendingWalk) {
                BlockPos stand = ReachOps.findStandPoint(maid, a);
                if (stand == null) {
                    // 旧版定稿：这里【不该有任何兜底】—— 找不到就跳过，绝不把目标格当落点
                    current.todo.pollFirst();
                    a.phase = Action.Phase.FAILED;
                    a.failReason = "目标 " + a.reachTarget().toShortString()
                            + " 周围找不到可站的落点 → 跳过";
                    current.done.add(a);
                    recordAction(rep, a, Action.Phase.FAILED);
                    return;
                }
                a.walkTried = true;
                current.pendingWalk = true;
                // ★ 旧实现：内部走位走不到也会 teleportFallback（传到落脚点）⇒ 必须开兜底，
 // 否则封顶后的房子/屋内高处永远够不到（复现建房时实测）
                current.todo.addFirst(new WalkAction(stand, WalkOps.ARRIVE_INTERNAL, true));
            }
            return;
        }
        current.pendingWalk = false;
        // ⑥ 推进这一步
        ServerLevel lv = (ServerLevel) maid.level();
        try {
            if (!a.started) {
                a.started = true;
                a.start(lv, maid);
            }
            Action.Phase ph = a.tick(lv, maid);
            if (ph != Action.Phase.RUNNING) {
                current.todo.pollFirst();
                current.done.add(a);
                recordAction(rep, a, ph);
                // 走位失败 ⇒ 被它"挡路"的那个动作直接判失败（防死循环，execution-model §3 ③b）
                if (ph == Action.Phase.FAILED && a instanceof WalkAction) {
                    Action blocked = current.todo.peekFirst();
                    if (blocked != null && blocked.needsReach()) {
                        current.todo.pollFirst();
                        blocked.phase = Action.Phase.FAILED;
                        blocked.failReason = "走不到目标（走位失败：" + a.failReason + "）";
                        current.done.add(blocked);
                        recordAction(rep, blocked, Action.Phase.FAILED);
                    }
                }
            }
        } catch (CmdException e) {
            // 已知错误（够不着 / 找不到 / 忙）—— 当这一步失败，继续下一个
            current.todo.pollFirst();
            a.phase = Action.Phase.FAILED;
            a.failReason = e.getMessage();
            current.done.add(a);
            recordAction(rep, a, Action.Phase.FAILED);
        } catch (Throwable t) {
            MaidCliMod.LOG.error("[MaidCLI] 动作执行异常 {} / {}", current.kind, a.op(), t);
            current.todo.pollFirst();
            a.phase = Action.Phase.FAILED;
            a.failReason = "内部错误：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            current.done.add(a);
            recordAction(rep, a, Action.Phase.FAILED);
        }
    }

    private void recordAction(JobReport rep, Action a, Action.Phase ph) {
        rep.total++;
        if (ph == Action.Phase.DONE) {
            rep.ok++;
        } else {
            rep.failed++;
            rep.addFailure(describe(a), a.failReason == null ? "失败" : a.failReason);
        }
        // ★ 把动作的详细结果塞进报告（宏 / 界面操作的 result()）——
 // 协议要求"做完写进历史：{op, name, ok, hit, landed, offset}"，不填就丢了
        try {
            Map<String, Object> extra = null;
            if (a instanceof com.xiaofeng.maidcli.engine.actions.SlotAction sa) {
                extra = sa.result();
            } else if (a instanceof com.xiaofeng.maidcli.engine.actions.MacroAction ma2) {
                extra = ma2.result();
            } else if (a instanceof com.xiaofeng.maidcli.engine.actions.ShootAction sh) {
                extra = sh.result();
            }
            // TODO(ride)：RideAction 还没有 result() 方法 ⇒ 它的"已骑上 X"暂时不进报告
 // （history 里仍有 ok/result；要看骑乘状态用 `ride_status` 查）。
 // 补法：给 RideAction 加一个 lastResult 字段 + result()，再在这里接上。
            if (extra != null) {
                rep.extra.putAll(extra);
            }
        } catch (Throwable ignored) {
        }
        a.abort();   // 收尾：把借的东西还回去
    }

    private static String describe(Action a) {
        BlockPos p = a.targetPos();
        return p == null ? a.op() : a.op() + " " + p.getX() + "," + p.getY() + "," + p.getZ();
    }

    /** 一个作业跑完（或被打断）—— 出报告、写 history、取下一个 */
    private void finish(MinecraftServer server, Job job, String forcedReason) {
        JobReport rep = job.report;
        if (rep.result != JobReport.Result.ABORTED) {
            rep.resolve();
        }
        rep.finishedAtTick = server.getTickCount();
        if (rep.message == null || rep.message.isEmpty()) {
            rep.message = switch (rep.result) {
                case DONE -> "完成";
                case PARTIAL -> "完成（部分失败 " + rep.failed + "）";
                case FAILED -> "失败";
                case ABORTED -> forcedReason == null ? "已中断" : forcedReason;
            };
        }
        pushHistory(rep);
        current = null;
        // 报告里带上当前队列长度（免得调用方还得再查一次）
        if (job.aborted && !"timeout".equals(forcedReason)) {
            MaidCliMod.LOG.info("[MaidCLI] {} 作业被打断（{}）", identity.tag, forcedReason);
        }
    }

    private void pushHistory(JobReport rep) {
        synchronized (history) {
            history.add(rep);
            while (history.size() > HISTORY_MAX) {
                history.remove(0);
            }
        }
    }

    // ────────────────────────── 历史 ──────────────────────────

    /** {@code history=N} —— 最多 N 条未读（按游标消费）；不填 = 未读的全返回 */
    public List<Map<String, Object>> readHistory(Integer limit) {
        List<JobReport> snap;
        synchronized (history) {
            snap = new ArrayList<>(history);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (JobReport r : snap) {
            if (r.id > historyCursor) {
                out.add(r.toHistoryEntry());
            }
        }
        if (limit != null && out.size() > limit) {
            out = out.subList(0, limit);
        }
        if (!out.isEmpty()) {
            historyCursor = Math.max(historyCursor, maxId(out));
        }
        return out;
    }

    /** {@code since=<id>} —— id 大于 N 的全部，不看游标 */
    public List<Map<String, Object>> readHistorySince(long since) {
        List<JobReport> snap;
        synchronized (history) {
            snap = new ArrayList<>(history);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (JobReport r : snap) {
            if (r.id > since) {
                out.add(r.toHistoryEntry());
            }
        }
        return out;
    }

    private static long maxId(List<Map<String, Object>> list) {
        long m = 0;
        for (Map<String, Object> o : list) {
            Object v = o.get("id");
            if (v instanceof Number n) {
                m = Math.max(m, n.longValue());
            }
        }
        return m;
    }

    /** 在 history 里精确等一条（CLI 轮询用） */
    public JobReport findReport(long id) {
        synchronized (history) {
            for (JobReport r : history) {
                if (r.id == id) {
                    return r;
                }
            }
        }
        return null;
    }

    /** 已完成的作业数（诊断用） */
    public int historySize() {
        synchronized (history) {
            return history.size();
        }
    }

    // ────────────────────────── 会话 ──────────────────────────

    /** 会话自动关：走远 > 8 格 / 开着超 180 秒（由唯一 tick 循环做，不另起事件订阅） */
    private void checkSession(MinecraftServer server, EntityMaid maid) {
        if (session == null) {
            return;
        }
        MaidCliConfig.Data cfg = MaidCliConfig.get(server);
        boolean tooFar = session.pos != null
                && Math.sqrt(maid.distanceToSqr(session.pos.getX() + 0.5, session.pos.getY() + 0.5,
                session.pos.getZ() + 0.5)) > cfg.session_close_distance;
        boolean timeout = server.getTickCount() - session.openedAtTick
                > cfg.session_timeout_seconds * 20L;
        if (tooFar || timeout) {
            closeSession(tooFar ? "走远了" : "开着太久");
        }
    }

    /** 关会话（退料 + 关盖）—— 收口在 ops/ContainerOps */
    public void closeSession(String reason) {
        Session s = session;
        if (s == null) {
            return;
        }
        session = null;
        try {
            com.xiaofeng.maidcli.ops.ContainerOps.close(s);
        } catch (Throwable t) {
            MaidCliMod.LOG.warn("[MaidCLI] 关会话失败: {}", t.toString());
        }
        MaidCliMod.LOG.info("[MaidCLI] {} 会话关闭（{}）", identity.tag, reason);
    }

    // ────────────────────────── 收口 ──────────────────────────

    /**
     * ★ 唯一的收口（execution-model §3 ⑦）—— {@code stop} / 取消唤醒 / 她死 都走它。
     * <p>清队列 + 停当前作业 + 关会话（退料）。动作的收尾要"把借的东西还回去"。
     */
    public void reset(String reason) {
        // 当前作业（先标 aborted 再收）
        Job cur = current;
        if (cur != null) {
            cur.aborted = true;
            cur.report.result = JobReport.Result.ABORTED;
            cur.report.message = reason;
            while (!cur.todo.isEmpty()) {
                Action a = cur.todo.pollFirst();
                try {
                    a.abort();
                } catch (Throwable ignored) {
                }
            }
            MinecraftServer s = MaidCliMod.server();
            if (s != null) {
                cur.report.finishedAtTick = s.getTickCount();
            }
            pushHistory(cur.report);
            current = null;
        }
        // 排队中的作业
        while (!queue.isEmpty()) {
            Job j = queue.pollFirst();
            j.aborted = true;
            j.report.result = JobReport.Result.ABORTED;
            j.report.message = reason;
        }
        // 会话（退料）
        closeSession(reason);
        MaidCliMod.LOG.info("[MaidCLI] {} reset: {}", identity.tag, reason);
    }
}
