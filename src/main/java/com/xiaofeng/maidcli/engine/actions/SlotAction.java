package com.xiaofeng.maidcli.engine.actions;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.item.ItemStack;
import com.xiaofeng.maidcli.engine.Action;
import com.xiaofeng.maidcli.engine.MaidHandle;
import com.xiaofeng.maidcli.engine.Session;
import com.xiaofeng.maidcli.ops.ContainerOps;
import com.xiaofeng.maidcli.ops.SlotOps;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.Map;

/**
 * 界面操作（COMMANDS.md §6.2）—— take / put / move / select / input / close。
 *
 * <p>⚠️ <b>为什么这 6 个合成一个类、而不是 6 个子类</b>（设计稿写的是"每种一个"）：
 * 它们<b>同质</b>（都是一瞬间完成的一步，不像 break 有进度、walk 有到达判定），
 * 又都只调 {@link SlotOps} / {@link ContainerOps} 的现成实现。
 * 合成一个 `op` 字段区分的类，同样满足设计的核心意图（<b>加新操作不用改调度器</b>），
 * 但省下 5 个近乎空壳的文件。
 */
public final class SlotAction extends Action {

    public enum Op {TAKE, PUT, MOVE, SELECT, INPUT, CLOSE, LIST}

    private final MaidHandle handle;
    private final Op op;
    private final String a;
    private final String b;
    private final int count;
    /** select 用：产物直接进背包（模拟原版的 Shift 点击），塞不下掉地上不丢 */
    private final boolean auto;

    public SlotAction(MaidHandle handle, Op op, String a, String b, int count) {
        this(handle, op, a, b, count, false);
    }

    public SlotAction(MaidHandle handle, Op op, String a, String b, int count, boolean auto) {
        this.handle = handle;
        this.op = op;
        this.a = a;
        this.b = b;
        this.count = count;
        this.auto = auto;
    }

    @Override
    public Phase tick(ServerLevel lv, EntityMaid maid) {
        try {
            Map<String, Object> r = switch (op) {
                case TAKE -> SlotOps.take(maid, handle, a, count);
                case PUT -> SlotOps.put(maid, handle, a, count);
                case MOVE -> SlotOps.move(maid, handle, a);
                case SELECT -> select(maid, lv);
                case INPUT -> input(maid);
                case CLOSE -> close(maid);
                case LIST -> list(maid);
            };
            lastResult = r;
 // ★ 每次变动后立刻写 NBT（补）：崩溃时工位余料 / 鼠标槽里已拿起的物品不丢
 // 正常 close 会把它们退回背包并清空快照（见 ContainerOps.close）
            if (handle.session != null && op != Op.CLOSE) {
                try {
                    com.xiaofeng.maidcli.ops.StationOps.persistSession(handle.session);
                } catch (Throwable ignored) {
                }
            }
            // ★ 铁砧 / 砂轮这类【没有按钮】的工位：结果是"放完料自动算"的 ——
 // 它们既不 input 也不 select，所以取产出必须挂在【放料之后】（move/put）。
 // ⚠️ 只能挑字段拿，不能 putAll（pullResult 在"料还不全"时会返回 ok:false，
 // putAll 会把整个放置作业标成失败 —— 同 input 那个坑）
            if ((op == Op.MOVE || op == Op.PUT) && handle.session != null) {
                var sk = handle.session.kind;
                if (sk == com.xiaofeng.maidcli.engine.FaceKind.ANVIL
                        || sk == com.xiaofeng.maidcli.engine.FaceKind.GRINDSTONE) {
                    try {
                        java.util.Map<String, Object> rr =
                                com.xiaofeng.maidcli.ops.StationOps.pullResult(handle.session);
                        if (rr != null) {
                            Object got = rr.get("got");
                            if (got != null) {
                                r.put("got", got);
                            }
                            Object sm = rr.get("message");
                            if (sm != null) {
                                r.put("station_message", sm);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            return Phase.DONE;
        } catch (com.xiaofeng.maidcli.transport.CmdException e) {
            return fail(e.getMessage());
        } catch (Throwable t) {
            return fail("界面操作失败：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
        }
    }

    /** 这一步的结果（进 history 的 extra） */
    private Map<String, Object> lastResult;

    public Map<String, Object> result() {
        return lastResult;
    }

    // ────────────────────────── select / input ──────────────────────────

    /**
     * 点按钮 / 选选项。统一走 {@code AbstractContainerMenu.clickMenuButton(Player, int)}。
     * <p>⚠️ 附魔台的三档每次 {@code slotsChanged} 都重算 ⇒ 必须<b>一次调用内完成"算档 → 选档"</b>。
     */
    /**
     * 点按钮 / 取产物。
     *
     * <p>{@code times=N}：一次调用点 N 次（切石机切一组、工作台连做）。料不够 / 菜单拒了
     * ⇒ 停下来并按实际次数报告（第一次就失败仍当整条指令失败）。
     * <p>{@code auto=true}：产物直接进背包（模拟原版 Shift 点击），塞不下掉地上。
     */
    private Map<String, Object> select(EntityMaid maid, ServerLevel lv) {
        int times = count <= 0 ? 1 : count;
        int done = 0;
        Map<String, Object> last = null;
        String stop = null;
        for (int t = 0; t < times; t++) {
            try {
                last = selectOnce(maid, lv);
                done++;
            } catch (com.xiaofeng.maidcli.transport.CmdException e) {
                if (t == 0) {
                    throw e;                 // 第一次就失败 ⇒ 整条指令失败（保持原语义）
                }
                stop = "第 " + (t + 1) + " 次：" + e.getMessage();
                break;
            }
        }
        Map<String, Object> agg = new java.util.LinkedHashMap<>();
        if (last != null) {
            agg.putAll(last);
        }
        if (times > 1) {
            agg.put("times_requested", times);
            agg.put("times_done", done);
        }
        if (stop != null) {
            agg.put("stopped", stop);
            agg.put("message", "做了 " + done + " 次就停了 —— " + stop);
        }
        return agg;
    }

    /** 点一次（select 的单次实现；循环外壳见上） */
    private Map<String, Object> selectOnce(EntityMaid maid, ServerLevel lv) {
        Session s = handle.session;
        if (s == null) {
            throw com.xiaofeng.maidcli.transport.CmdException.badState(
                    "界面没开 —— 先 open",
                    "open（开自己背包）/ open pos=<坐标>");
        }
        // 随身 2×2 没有 Menu（虚拟合成格），不能要求 menu != null
        boolean is2x2 = (s.kind == com.xiaofeng.maidcli.engine.FaceKind.INV);
        if (s.menu == null && !is2x2) {
            throw com.xiaofeng.maidcli.transport.CmdException.badState(
                    "当前界面没有可点的按钮 / 选项",
                    "先 open 一个带选项的界面（切石机 / 附魔台 / 村民 / 工作台）");
        }
        // ★ 无按钮工位（工作台 / 铁砧 / 砂轮 / 锻造台 / 制图台）：原版没有按钮
 // （clickMenuButton 继承基类恒 false），select 在它们身上是"确认取产物"。
        boolean noButton = com.xiaofeng.maidcli.ops.StationOps.isStation(s.kind)
                && !com.xiaofeng.maidcli.ops.StationOps.hasButton(s.kind);
        if (!noButton && !is2x2) {
            s.ability().requireSelect(s.kind);
        }
        int idx;
        try {
            idx = Integer.parseInt(a);
        } catch (NumberFormatException e) {
            throw com.xiaofeng.maidcli.transport.CmdException.badArg("select 要给 index=<数字>");
        }
        // ★ 村民交易：走【虚拟界面】收口 —— 不能走下面的 clickMenuButton。
 // 原版 MerchantMenu 不覆盖 clickMenuButton，基类默认 return false（javap 验过）
 // ⇒ 走那条路必被下面 "菜单自己拒了" 误报。所以这里直接交给 MerchantOps.select
 // （它做：satisfiedBy 检查 → take 扣支付物 → notifyTrade 记次(内部自带 increaseUses) → 结果进结果格）。
        if (s.kind == com.xiaofeng.maidcli.engine.FaceKind.MERCHANT) {
            return com.xiaofeng.maidcli.ops.MerchantOps.select(maid, s, idx);
        }
        // ★ 随身 2×2：没有 Menu，直接结算（与工位"select = 确认取产物"同一套语义）
        if (is2x2) {
            java.util.Map<String, Object> out2 = new java.util.LinkedHashMap<>();
            out2.put("selected", idx);
            java.util.Map<String, Object> r2 = com.xiaofeng.maidcli.ops.Craft2x2Ops.settle(maid, s);
            if (r2 != null) {
                out2.putAll(r2);
            }
            if (auto) {
                ItemStack product = s.virtual.get(com.xiaofeng.maidcli.ops.Craft2x2Ops.RESULT_SLOT);
                if (product != null && !product.isEmpty()) {
                    s.virtual.put(com.xiaofeng.maidcli.ops.Craft2x2Ops.RESULT_SLOT, ItemStack.EMPTY);
                    com.xiaofeng.maidcli.ops.ItemOps.stash(maid, product);
                    out2.put("auto_stashed", true);
                }
            }
            out2.put("message", auto ? "取了产物（已直接进背包）" : "取了产物（在 box:4，等你 take）");
            return out2;
        }
        var fake = com.xiaofeng.maidcli.infra.MaidFakePlayer.of(maid);
        // ★ 旧实现：附魔台路径把假玩家设为创造模式（见 StationOps.buildMenu 同款注释）
        try {
            if (s.kind == com.xiaofeng.maidcli.engine.FaceKind.ENCHANT) {
                fake.getAbilities().instabuild = true;
            }
        } catch (Throwable ignored) {
        }
        // ★★ 照抄旧 StationExecute（实测通过的老代码）—— 【点按钮前必须做两件事】：
 // ① 手动 slotsChanged：原版靠 GUI 事件触发档位重算，我们【凭空建 Menu】没有那个事件
 // ⇒ 不调的话 costs/enchantClue 全是初始值（附魔台 costs 全 0 ⇒ clickMenuButton 里
 // `if (cost <= 0) return false` 直接拒，实测就是"这个界面不接受 index=0"）
 // ② 附魔台还要把【假玩家经验】设到 ≥ 该档要求（原版 clickMenuButton 会 giveExperienceLevels(-cost)）
        // ★★ 附魔台：必须先走旧代码那套 ensureTiers（新建 menu + 设种子 + 档位缓存），
 // 光调 slotsChanged 会被原版"物品没变就跳过"拦住 ⇒ costs 全 0 ⇒ 菜单拒（实测）
        if (s.kind == com.xiaofeng.maidcli.engine.FaceKind.ENCHANT
                && s.menu instanceof net.minecraft.world.inventory.EnchantmentMenu em0
                && s.pos != null) {
            ItemStack in0 = s.virtual.get(0);
            if (in0 != null && !in0.isEmpty()) {
                try {
                    var emNew = com.xiaofeng.maidcli.ops.StationOps.ensureTiers(
                            em0, fake, in0, s.pos, maid,
                            com.xiaofeng.maidcli.infra.MaidCliTags.enchantCount(maid), 0, lv);
                    if (emNew != null) {
                        s.menu = emNew;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            var c0 = s.menu.getSlot(0).container;
            if (c0 != null) {
                s.menu.slotsChanged(c0);
            }
        } catch (Throwable ignored) {
        }
        if (s.menu instanceof net.minecraft.world.inventory.EnchantmentMenu em
                && idx >= 0 && idx < em.costs.length && em.costs[idx] > 0) {
            try {
                fake.setExperienceLevels(Math.max(em.costs[idx], 1));
            } catch (Throwable ignored) {
            }
        }
        // ★★★ 把 virtual 的每一格写进 menu 的对应格（附魔台槽 1 要青金石才重算档位）
        //
 // ⚠️ 复查时修了两处：
 // ① 原来用【未映射】的 vi 直接当 menu 槽号 —— CraftingMenu 的顺序是
 // `menu[0]=结果, menu[1..9]=材料`，于是 virtual[0]（材料）被写进 menu[0]（结果槽），
 // 把算好的产物当场冲掉 ⇒ 工作台 select 永远"结果槽空"（日志实证：
 // 0.3 秒前 menu[0]=furnace，pullResult 时变 air）
 // ② 结果格是"算出来的"，绝不能被 virtual 覆盖 —— 直接跳过
        try {
            if (s.menu != null && s.menu.slots.size() > 0) {
                int resultIdx = com.xiaofeng.maidcli.ops.StationOps.resultSlot(s.kind);
                for (var vEnt : s.virtual.entrySet()) {
                    int vi = vEnt.getKey();
                    if (vi == resultIdx) {
                        continue;                                  // ★ 不碰结果格
                    }
                    int mi = com.xiaofeng.maidcli.ops.StationOps.menuIndexOf(s.kind, vi);
                    if (mi >= 0 && mi < s.menu.slots.size()) {
                        ItemStack want = vEnt.getValue();
                        s.menu.getSlot(mi).set(want == null ? ItemStack.EMPTY : want);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // ★ 无按钮工位：跳过点按钮，直接走下面的 pullResult
        if (!noButton) {
            boolean ok = s.menu.clickMenuButton(fake, idx);
            if (!ok) {
                throw new com.xiaofeng.maidcli.transport.CmdException(
                        com.xiaofeng.maidcli.transport.CmdException.BAD_ARG,
                        "这个界面不接受 index=" + idx + "（菜单自己拒了）",
                        "用 sense session 看 options");
            }
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("selected", idx);
        // ★ 工位：取产物 —— 读 Menu 结果格 → 写进虚拟槽 + 输入各扣 1
 // （不做这一步，切石机/附魔台会"选了却没产物"；无按钮工位更是唯一出路）
        if (com.xiaofeng.maidcli.ops.StationOps.isStation(s.kind)) {
            try {
                java.util.Map<String, Object> r2 = com.xiaofeng.maidcli.ops.StationOps.pullResult(s);
                if (r2 != null) {
                    out.putAll(r2);
                }
                // ★ auto=true：产物直接进背包（模拟原版 Shift 点击结果格），
 // 塞不下就掉地上（ItemOps.stash 兜底），绝不丢
                if (auto) {
                    int r = com.xiaofeng.maidcli.ops.StationOps.resultSlot(s.kind);
                    if (r >= 0) {
                        ItemStack product = s.virtual.get(r);
                        if (product != null && !product.isEmpty()) {
                            s.virtual.put(r, ItemStack.EMPTY);
                            com.xiaofeng.maidcli.ops.ItemOps.stash(maid, product);
                            out.put("auto_stashed", true);
                        }
                    }
                }
            } catch (Throwable t) {
                out.put("result_error", String.valueOf(t.getMessage()));
            }
        }
        // ★ 附魔台：真附上了才 +1（旧 MaidOps.bumpEnchantCount）—— 它决定下一批档位的种子基数
        if (s.kind == com.xiaofeng.maidcli.engine.FaceKind.ENCHANT
                && Boolean.TRUE.equals(out.get("ok"))) {
            out.put("enchant_count", com.xiaofeng.maidcli.infra.MaidCliTags.bumpEnchantCount(maid));
        }
        out.put("message", noButton
                ? (auto ? "取了产物（已直接进背包）" : "取了产物（在结果格，等你 take）")
                : ("点了第 " + idx + " 项"));
        return out;
    }

    /**
     * 文本框输入。
     * <p>⚠️ 铁砧改名是「<b>暂存 → confirm 才生效</b>」：这里先记进会话，等下一次 {@code select} 时写进去。
     */
    private Map<String, Object> input(EntityMaid maid) {
        Session s = handle.session;
        if (s == null) {
            throw com.xiaofeng.maidcli.transport.CmdException.badState("界面没开");
        }
        s.ability().requireInput(s.kind);
        s.pendingInput = a == null ? "" : a;
        if (s.menu instanceof net.minecraft.world.inventory.AnvilMenu am) {
            am.setItemName(s.pendingInput);
            // ★ 铁砧的结果槽是【自动算】的（原版没有 select 可点）—— 设完名字必须主动触发一次，
 // 否则结果槽永远是空的 ⇒ "改了名却取不到成品"（验证抓到）
            try {
                am.createResult();
            } catch (Throwable ignored) {
            }
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("input", s.pendingInput);
        out.put("pending", true);
        // ★ 没有 select 的工位（铁砧 / 砂轮）：input 之后【直接取产出】。
 // 它们的结果槽是自动算的，没有按钮可点 —— 不调 pullResult 就"改了名却拿不到成品"。
        if (s.kind == com.xiaofeng.maidcli.engine.FaceKind.ANVIL
                || s.kind == com.xiaofeng.maidcli.engine.FaceKind.GRINDSTONE) {
            try {
                java.util.Map<String, Object> r2 =
                        com.xiaofeng.maidcli.ops.StationOps.pullResult(s);
                // ⚠️ 只能【挑字段】拿，绝不能 putAll —— pullResult 在"料还不全"时会返回
 // {ok:false, "算不出结果"}，putAll 会把那个 ok:false 带进 input 的结果里，
 // 导致整个 input 作业被标成 failed（实测：报 job_failed:完成）
                if (r2 != null) {
                    Object got = r2.get("got");
                    if (got != null) {
                        out.put("got", got);
                    }
                    Object sm = r2.get("message");
                    if (sm != null) {
                        out.put("station_message", sm);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        out.put("message", "已填「" + s.pendingInput + "」（大多数界面要再 select/取产物才生效）");
        return out;
    }

    /** 只读：列出当前村民交易界面的交易表（不成交、不改动任何东西） */
    private Map<String, Object> list(EntityMaid maid) {
        Session s = handle.session;
        if (s == null) {
            throw com.xiaofeng.maidcli.transport.CmdException.badState("界面没开");
        }
        return com.xiaofeng.maidcli.ops.MerchantOps.list(maid, s);
    }

    /** 关界面：余料自动退回背包（退鼠标槽 + 退工位余料 + 合盖）—— 幂等 */
    private Map<String, Object> close(EntityMaid maid) {
        Session s = handle.session;
        if (s == null) {
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("closed", false);
            out.put("message", "界面本来就没开");
            return out;
        }
        handle.closeSession("close 指令");
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("closed", true);
        out.put("message", "已关界面（鼠标槽 / 工位余料都退回背包了）");
        return out;
    }

    @Override
    public String op() {
        return switch (op) {
            case TAKE -> "take";
            case PUT -> "put";
            case MOVE -> "move";
            case SELECT -> "select";
            case INPUT -> "input";
            case CLOSE -> "close";
            case LIST -> "trade_list";
        };
    }

    /** 界面操作要不要走位？—— close 不要，其余都要在方块旁边 */
    @Override
    public boolean needsReach() {
        return false;      // 会话建立时已经确保她在旁边了；走远会被自动关会话
    }

    @Override
    public BlockPos targetPos() {
        return handle.session == null ? null : handle.session.pos;
    }
}
