package com.xiaofeng.maidcli.command.defs;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.xiaofeng.maidcli.MaidCliMod;
import com.xiaofeng.maidcli.command.MaidCmd;
import com.xiaofeng.maidcli.command.Param;
import com.xiaofeng.maidcli.engine.MaidHandle;
import com.xiaofeng.maidcli.loading.MaidLoader;
import com.xiaofeng.maidcli.ops.WalkOps;
import com.xiaofeng.maidcli.transport.CmdException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 控制类（COMMANDS.md §6.3）—— {@code stop} · {@code mode} · {@code control}。
 *
 * <p>这一类的共同点：<b>不只是"改个值"，还要做事</b>（有副作用）⇒ 单独成类，不走 {@code set}。
 * 三条都<b>立即生效</b>（不进队列）。
 *
 * <p>★ <b>接管（control）的实现就写在这里</b>（class-design §3.8）—— 不再有 {@code MaidOps}。
 */
public final class ControlCmds {

    private ControlCmds() {
    }

    public static void registerAll() {
        com.xiaofeng.maidcli.command.CmdRegistry.register(new Stop());
        com.xiaofeng.maidcli.command.CmdRegistry.register(new Mode());
        com.xiaofeng.maidcli.command.CmdRegistry.register(new Control());
    }

    // ────────────────────────── 接管（切「指令模式」任务） ──────────────────────────

    /**
     * ★ 进入受控 = 把她换到「指令模式」任务（{@code maid_cli:command}）。
     *
     * <p><b>为什么不再用\"反射清 brain 行为盒子\"（原因见下：清盒子会被 setSchedule 触发的 refreshBrain 冲掉）</b>：
     * <pre>
     * 清盒子的实现里：clearBuckets() → setSchedule(ALL)
     *   EntityMaid.setSchedule() 内部【无条件】refreshBrain()（没有值相等判断）
     *   refreshBrain() = copyWithoutBehaviors() + registerBrainGoals()
     * ⇒ 刚清掉的盒子被全量重装，清了个寂寞 ⇒ 她照样随机游走
     * 而且为了防这件事还得加"每 20 tick 重清"的守护 —— 复杂且版本敏感（反射读 Brain 私有字段）。
     * </pre>
     * 「指令模式」任务则在 TLM 源码层面天然解决：
     * <pre>
     * WORK activity 的随机游走 predicate = {@code e.getTask().enableLookAndRandomWalk(e)}
     * TaskCommand.enableLookAndRandomWalk() 返回 false  ⇒ 每 tick 求值都是 false  ⇒ 她不动
     * （IDLE/REST activity 的 predicate 是无条件 true，所以必须配 schedule=ALL 让她待在 WORK）
     * </pre>
     * ⇒ 无反射、无顺序依赖、{@code refreshBrain} 反而是帮手（重建出来的就是正确的一套）。
     *
     * <p>⚠️ <b>不动她的「跟随 / 留守」设定</b>（设计原因：受控不应改动用户原有的跟随 / 留守选择）。
     * 留守时的范围限制由 {@code ops/FreeRoam} 用"锚点跟着她走"化解。
     */
    public static Map<String, Object> takeControl(EntityMaid maid, MaidHandle h) {
        IMaidTask cmd = TaskManager.findTask(com.xiaofeng.maidcli.task.TaskCommand.UID).orElse(null);
        if (cmd == null) {
            throw new IllegalStateException(
                    "指令模式任务没注册上（找不到 " + com.xiaofeng.maidcli.task.TaskCommand.UID
                            + "）—— 检查 MaidCliExtension.addMaidTask 与 lang 文件");
        }
        StringBuilder sb = new StringBuilder();
        // ① 起身（坐着的时候 brain 里的走位不会跑）
        if (maid.isMaidInSittingPose()) {
            maid.setInSittingPose(false);
            sb.append("站起 ");
        }
        // ② 解冻（从 freeze 状态过来时 AI 全停，连寻路都不跑）
        if (maid.isNoAi()) {
            maid.setNoAi(false);
            sb.append("解除定身 ");
        }
        // ③ 记下原任务（退出受控时还原 —— 旧版没有这一步，因为旧版没有 control 开关）
        if (!h.controlled && h.savedTaskId == null) {
            h.savedTaskId = maid.getTask() == null ? null : maid.getTask().getUid().toString();
        }
        // ④ ★ 切指令模式（这一步就是"受控"本身）
        if (maid.getTask() != cmd) {
            maid.setTask(cmd);
            sb.append("切指令模式 ");
        }
        // ⑤ 日程全天（★ 必须带这个 if 守卫：setSchedule 内部无条件 refreshBrain）
        if (maid.getSchedule() != MaidSchedule.ALL) {
            maid.setSchedule(MaidSchedule.ALL);
            sb.append("日程全天 ");
        }
        // ⑥ 关自动拾取（还有一条不走 brain 的拾取路径，任务管不到它）
        if (maid.isPickup()) {
            maid.setPickup(false);
            sb.append("关自动拾取 ");
        }
        // ⑦ 清掉残留的走位 / 看 / 寻路意图（免得她一进来就朝老目标走）
        WalkOps.stopWalking(maid);
        try {
            maid.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
            maid.getBrain().eraseMemory(MemoryModuleType.PATH);
        } catch (Throwable ignored) {
        }
        maid.getNavigation().stop();

        h.controlledTaskId = cmd.getUid().toString();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("controlled", true);
        out.put("mode", cmd.getUid().toString());
        out.put("saved_task", h.savedTaskId);
        out.put("follow", !maid.isHomeModeEnable());
        out.put("message", sb.isEmpty()
                ? "她已经在受控状态（指令模式）"
                : "已进入受控：" + sb.toString().trim());
        return out;
    }

    /**
     * ★ 退出受控：把原任务还给她（{@code setTask} 内部 refreshBrain ⇒ 自主行为自动恢复原样）。
     *
     * <p>⚠️ 不碰「跟随 / 留守」设定 —— 受控期间本来就没改过它。
     */
    public static Map<String, Object> releaseControl(EntityMaid maid, MaidHandle h) {
        IMaidTask back = null;
        if (h.savedTaskId != null) {
            try {
                back = TaskManager.findTask(ResourceLocation.parse(h.savedTaskId)).orElse(null);
            } catch (Throwable ignored) {
            }
        }
        if (back == null) {
            back = TaskManager.getIdleTask();
        }
        maid.setTask(back);
        // ★ 旧 restoreTakeover 的收尾：原来是【跟随】且离主人 >16 格 ⇒ 立刻传送归队
 // （跟随模式下 TLM 自己也会在 10 格时拽回，这里只是"立刻"—— 旧版就是这么写的）
        try {
            if (!maid.isHomeModeEnable()) {
                var owner = maid.getOwner();
                if (owner != null && maid.distanceToSqr(owner) > 16.0 * 16.0) {
                    maid.teleportToOwner(owner);
                }
            }
        } catch (Throwable ignored) {
        }
        maid.setPickup(true);
        h.controlledTaskId = null;
        h.savedTaskId = null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("controlled", false);
        out.put("mode", back.getUid().toString());
        out.put("message", "已退出受控（任务还原为「" + back.getName().getString() + "」，自主行为已恢复）");
        return out;
    }

    // ────────────────────────── stop ──────────────────────────

    /** {@code stop} —— 立即生效：清队列 + 停当前动作 + 关会话（鼠标槽的东西退回背包） */
    static final class Stop implements MaidCmd {
        @Override
        public String name() {
            return "stop";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "打断：清队列 + 停当前动作 + 关会话（鼠标槽的东西退回背包），立即生效";
        }

        @Override
        public Map<String, Param> params() {
            return Map.of();
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            MaidHandle h = ctx.handle();
            int wasQueued = h.queueLen();
            h.reset("stop");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("stopped", true);
            out.put("cleared", wasQueued);
            out.put("message", "已清空（原待办 " + wasQueued + " 条）");
            return out;
        }
    }

    // ────────────────────────── mode ──────────────────────────

    /** {@code mode <模式名> [target=<实体id>]} —— 换她的工作模式（立即生效） */
    static final class Mode implements MaidCmd {
        @Override
        public String name() {
            return "mode";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "换她的工作模式（idle / farm / milk / attack …）；可选值用 help mode 查";
        }

        @Override
        public SubSpec sub() {
            return new SubSpec("task", true, "工作模式名（idle/farm/milk/attack…，用 help mode 查）");
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("task", Param.reqStr("task", "模式名（可省 shouhou_little_maid: 前缀）"));
            p.put("target", Param.optInt("target", 0, "attack 类模式的目标（实体 id）"));
            return p;
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            EntityMaid maid = ctx.maid();
            String raw = ctx.str("task", "");
            if (raw == null || raw.isBlank()) {
                throw CmdException.badArg("要给模式名（idle / farm / milk / attack …；用 help mode 查）");
            }
            ResourceLocation id = raw.contains(":")
                    ? ResourceLocation.parse(raw)
                    : ResourceLocation.fromNamespaceAndPath("touhou_little_maid", raw);
            java.util.Optional<IMaidTask> found = TaskManager.findTask(id);
            if (found.isEmpty()) {
                throw new CmdException(CmdException.BAD_ARG,
                        "没有叫「" + raw + "」的工作模式", "用 help what=mode 看清单");
            }
            IMaidTask task = found.get();
 // ★ 受控状态下换模式 = 自动退出受控（"受控"的实体就是"任务是指令模式"这一事实）。
 // 理由：受控的实体就是"任务 == 指令模式"这一个事实，换任务必然打破它；
 // 与其偷偷维持一个假的 controlled 标志，不如明说"这一下就不受控了"。
            MaidHandle h = ctx.handle();
            boolean wasControlled = h.controlled;
            if (wasControlled) {
                h.controlled = false;
                h.controlledTaskId = null;
                h.savedTaskId = null;    // 原任务不再还原：用户已经明确指定了新任务
            }
            maid.setTask(task);
            int target = ctx.in("target", 0);
            if (target > 0) {
                com.xiaofeng.maidcli.ops.EntityOps.setAttackTarget(maid, target);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("mode", task.getUid().toString());
            out.put("name", task.getName().getString());
            out.put("controlled", false);
            out.put("message", (wasControlled ? "（已自动退出受控）已切到 " : "已切到 ")
                    + task.getName().getString());
            return out;
        }
    }

    // ────────────────────────── control ──────────────────────────

    /** {@code control [off=true]} —— 受控开关（立即生效 + 原子操作） */
    static final class Control implements MaidCmd {
        @Override
        public String name() {
            return "control";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "受控开关：control = 她不再自主行动只听指令；control off=true = 把自主还给她";
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("off", Param.flag("off", "true = 退出受控"));
            return p;
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            EntityMaid maid = ctx.maid();
            MaidHandle h = ctx.handle();
            boolean off = ctx.flag("off", false);
            Map<String, Object> out;
            if (off) {
                out = releaseControl(maid, ctx.handle());
                h.controlled = false;
            } else {
                out = takeControl(maid, ctx.handle());
                h.controlled = true;
            }
            MaidLoader.touch(h);
            return out;
        }
    }
}
