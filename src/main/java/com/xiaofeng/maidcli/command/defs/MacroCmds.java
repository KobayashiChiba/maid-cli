package com.xiaofeng.maidcli.command.defs;

import com.xiaofeng.maidcli.command.CmdRegistry;
import com.xiaofeng.maidcli.command.MaidCmd;
import com.xiaofeng.maidcli.command.Param;
import com.xiaofeng.maidcli.engine.Job;
import com.xiaofeng.maidcli.engine.MaidHandle;
import com.xiaofeng.maidcli.engine.actions.MacroAction;
import com.xiaofeng.maidcli.ops.SlotOps;
import com.xiaofeng.maidcli.transport.CmdException;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 宏 ×9（COMMANDS.md §6.4）—— 语法糖垃圾桶。
 *
 * <p>分工：<b>世界宏</b>（前置：界面没开）· <b>界面宏</b>（前置：界面开着）。
 *
 * <p>⚠️ 宏和原语一样"只受理"：进队列、做完写历史；<b>宏超时 = 写一条失败历史</b>。
 */
public final class MacroCmds {

    private MacroCmds() {
    }

    public static void registerAll() {
        // ★ 对外只有一条 macro 指令；9 个宏是它的子命令（COMMANDS.md §6.4）
        CmdRegistry.register(new Macro());
    }

    /** 子命令 → 实现 */
    private static final Map<String, String> MACRO_DESC = new LinkedHashMap<>();

    static {
        MACRO_DESC.put("shoot", "射箭（瞄准解算 + 蓄力 + 发射 + 报落点）");
        MACRO_DESC.put("swap", "换主副手（= 原版按 F）");
        MACRO_DESC.put("hold", "把某格搬到主手");
        MACRO_DESC.put("drop", "丢【主手】的东西（= 原版按 Q）");
        MACRO_DESC.put("look", "看向某处（只转头）");
        MACRO_DESC.put("dismount", "从载具上下来");
        MACRO_DESC.put("give", "把东西交给主人（要他 online）");
        MACRO_DESC.put("sort", "整理当前打开的界面");
        MACRO_DESC.put("craft", "合成（要 open 工作台或背包）");
    }

    // ────────────────────────── macro（唯一入口） ──────────────────────────

    static final class Macro implements MaidCmd {

        @Override
        public String name() {
            return "macro";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "宏入口：macro <" + String.join("|", MACRO_DESC.keySet()) + "> [参数]";
        }

        @Override
        public SubSpec sub() {
            return new SubSpec("macro", true, String.join("|", MACRO_DESC.keySet()));
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("macro", Param.reqStr("macro", "宏名：" + String.join(" / ", MACRO_DESC.keySet())));
            // 下面是所有宏参数的并集（全部可选；必填校验在 precheck 里按子命令做）
            p.put("pos", Param.position(false, "坐标（look / shoot）"));
            p.put("entity", Param.optEntity("entity", "实体 id 或 owner（look / shoot）"));
            p.put("from", Param.optStr("from", null, "槽位（hold）"));
            p.put("item", Param.optStr("item", null, "物品（give / craft）"));
            p.put("count", Param.optInt("count", 0, "数量（drop / give）"));
            p.put("times", Param.optInt("times", 1, "份数（craft）"));
            p.put("timeout", Param.optDbl("timeout", 10, "最多跑多少秒"));
            return p;
        }

        @Override
        public void precheck(Map<String, Object> args) {
            String which = String.valueOf(args.get("macro"));
            switch (which) {
                case "swap", "drop", "dismount", "give", "sort" -> {
                }
                case "hold" -> {
                    Object from = args.get("from");
                    if (from == null || String.valueOf(from).isBlank()) {
                        throw CmdException.badArg("macro hold 要给 from=<槽位>",
                                "例：macro hold from=inv:3");
                    }
                    SlotOps.checkSyntax(String.valueOf(from), "from");
                }
                case "look" -> {
                    int n = (args.get("pos") != null ? 1 : 0)
                            + (args.get("entity") != null ? 1 : 0);
                    if (n != 1) {
                        throw CmdException.badArg("macro look 要给 pos= 或 entity=<id|owner> 之一（且只给一个）");
                    }
                }
                case "craft" -> {
                    Object item = args.get("item");
                    if (item == null || String.valueOf(item).isBlank()) {
                        throw CmdException.badArg("macro craft 要给 item=<产物>",
                                "例：macro craft item=minecraft:cherry_planks times=4");
                    }
                }
                case "shoot" -> ShootCmds.precheckShoot(args);
                default -> throw CmdException.badArg("没有这个宏：" + which,
                        "可用：" + String.join(" / ", MACRO_DESC.keySet()));
            }
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            String which = ctx.str("macro", "");
            return switch (which) {
                case "shoot" -> ShootCmds.runShoot(ctx);
                case "swap" -> new Swap().run(ctx);
                case "hold" -> new Hold().run(ctx);
                case "drop" -> new Drop().run(ctx);
                case "look" -> new Look().run(ctx);
                case "dismount" -> new Dismount().run(ctx);
                case "give" -> new Give().run(ctx);
                case "sort" -> new Sort().run(ctx);
                case "craft" -> new Craft().run(ctx);
                default -> throw CmdException.badArg("没有这个宏：" + which,
                        "可用：" + String.join(" / ", MACRO_DESC.keySet()));
            };
        }
    }

    /** 世界宏 / 界面宏的公共提交（含前置状态检查 + 可选 timeout） */
    private static Map<String, Object> submit(MaidCmd.Ctx ctx, MacroAction.Op op, String a, int count,
                                              boolean interfaceMacro, double timeoutSec) {
        MaidHandle h = ctx.handle();
        if (interfaceMacro) {
            if (h.session == null) {
                throw CmdException.badState("这条宏要先把界面打开（它是界面宏）",
                        "open（开自己背包）或 open pos=<坐标>");
            }
        } else {
            JobUtil.requireNoSession(h, opName(op));
        }
        Job job = JobUtil.job(ctx, "macro", opName(op));
        if (timeoutSec > 0) {
            job.timeoutTicks = (long) (timeoutSec * 20);
        }
        job.todo.addLast(new MacroAction(h, op, a, count));
        h.enqueue(job);
        return JobUtil.accepted(job, h);
    }

    private static String opName(MacroAction.Op op) {
        return switch (op) {
            case SWAP -> "swap";
            case HOLD -> "hold";
            case DROP -> "drop";
            case LOOK -> "look";
            case DISMOUNT -> "dismount";
            case GIVE -> "give";
            case SORT -> "sort";
            case CRAFT -> "craft";
        };
    }

    // ────────────────────────── swap ──────────────────────────

    static final class Swap implements MaidCmd {
        @Override
        public String name() {
            return "swap";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "换主副手（= 原版按 F）";
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("timeout", Param.optDbl("timeout", 2, "最多跑多少秒"));
            return p;
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            return submit(ctx, MacroAction.Op.SWAP, null, 0, false, ctx.dbl("timeout", 2));
        }
    }

    // ────────────────────────── hold ──────────────────────────

    static final class Hold implements MaidCmd {
        @Override
        public String name() {
            return "hold";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "把某格搬到主手（原来主手的回背包）——from=<槽位>，对应原版快捷键选格";
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("from", Param.reqStr("from", "槽位 inv:N / hand:off"));
            p.put("timeout", Param.optDbl("timeout", 3, "最多跑多少秒"));
            return p;
        }

        @Override
        public void precheck(Map<String, Object> args) {
            SlotOps.checkSyntax(String.valueOf(args.get("from")), "from");
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            return submit(ctx, MacroAction.Op.HOLD, ctx.opt("from"), 0, false, ctx.dbl("timeout", 3));
        }
    }

    // ────────────────────────── drop ──────────────────────────

    static final class Drop implements MaidCmd {
        @Override
        public String name() {
            return "drop";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "丢【主手】的东西（= 原版按 Q）；count=N 丢几个，不给 = 整堆";
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("count", Param.optInt("count", 0, "丢几个（不给 = 整堆）"));
            p.put("timeout", Param.optDbl("timeout", 2, "最多跑多少秒"));
            return p;
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            return submit(ctx, MacroAction.Op.DROP, null, ctx.in("count", 0), false, ctx.dbl("timeout", 2));
        }
    }

    // ────────────────────────── look ──────────────────────────

    static final class Look implements MaidCmd {
        @Override
        public String name() {
            return "look";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "看向某处（只转头，不转身不移动 —— 录像里制造「她在看你」的互动感）";
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("pos", Param.position(false, "看向哪"));
            p.put("entity", Param.optInt("entity", 0, "看向某个实体"));
            p.put("owner", Param.flag("owner", "看向主人"));
            return p;
        }

        @Override
        public void precheck(Map<String, Object> args) {
            boolean hasPos = args.get("pos") != null;
            boolean hasEnt = args.get("entity") instanceof Number n && n.intValue() > 0;
            boolean hasOwner = Boolean.TRUE.equals(args.get("owner"));
            int n = (hasPos ? 1 : 0) + (hasEnt ? 1 : 0) + (hasOwner ? 1 : 0);
            if (n != 1) {
                throw CmdException.badArg("look 要给 pos=<坐标> / entity=<id> / owner=true 之一（且只给一个）");
            }
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            String target = null;
            if (ctx.entityIsOwner()) {
                ServerPlayer owner = ctx.server().getPlayerList().getPlayer(ctx.maid().getOwnerUUID());
                if (owner == null) {
                    throw CmdException.badState("她主人不在线（看向主人要他在）", "改用 macro look pos=");
                }
                Vec3 p = owner.position();
                target = p.x + "," + p.y + "," + p.z;
            } else if (ctx.entityId() > 0) {
                target = "entity:" + ctx.entityId();
            } else {
                var bp = ctx.pos();
                if (bp != null) {
                    target = bp.getX() + "," + bp.getY() + "," + bp.getZ();
                }
            }
            return submit(ctx, MacroAction.Op.LOOK, target, 0, false, ctx.dbl("timeout", 3));
        }
    }

    // ────────────────────────── dismount ──────────────────────────

    static final class Dismount implements MaidCmd {
        @Override
        public String name() {
            return "dismount";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "从载具上下来，并找安全落脚点（免得下来就摔进岩浆/悬崖）";
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("timeout", Param.optDbl("timeout", 3, "最多跑多少秒"));
            return p;
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            return submit(ctx, MacroAction.Op.DISMOUNT, null, 0, false, ctx.dbl("timeout", 3));
        }
    }

    // ────────────────────────── give ──────────────────────────

    static final class Give implements MaidCmd {
        @Override
        public String name() {
            return "give";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "把东西交给主人（★ 需要主人在线）；不给参数 = 能给的都给他，给了 item= 就只给那个";
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("item", Param.optStr("item", null, "只给这种（不给 = 全部）"));
            p.put("count", Param.optInt("count", 0, "给几个（不给 = 不限）"));
            p.put("timeout", Param.optDbl("timeout", 10, "最多跑多少秒"));
            return p;
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            return submit(ctx, MacroAction.Op.GIVE, ctx.opt("item"), ctx.in("count", 0),
                    false, ctx.dbl("timeout", 10));
        }
    }

    // ────────────────────────── sort ──────────────────────────

    static final class Sort implements MaidCmd {
        @Override
        public String name() {
            return "sort";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "整理当前打开的界面（合并同类 + 按物品名排序）—— 没开界面就报错";
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("timeout", Param.optDbl("timeout", 5, "最多跑多少秒"));
            return p;
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            // ★ 无参数：整理【当前打开的界面】—— 开的是她背包就整理背包，开的是容器就整理容器
            return submit(ctx, MacroAction.Op.SORT, "current", 0, true, ctx.dbl("timeout", 5));
        }
    }

    // ────────────────────────── craft ──────────────────────────

    static final class Craft implements MaidCmd {
        @Override
        public String name() {
            return "craft";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "合成（界面宏：要先 open 工作台或背包）；item=<产物> [times=N]";
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("item", Param.reqStr("item", "要造的产物（可只给子串，如 cherry_stairs）"));
            p.put("times", Param.optInt("times", 1, "造几份"));
            p.put("timeout", Param.optDbl("timeout", 30, "最多跑多少秒"));
            return p;
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            return submit(ctx, MacroAction.Op.CRAFT, ctx.opt("item"), ctx.in("times", 1),
                    true, ctx.dbl("timeout", 30));
        }
    }
}
