package com.xiaofeng.maidcli.command.defs;

import com.xiaofeng.maidcli.command.CmdRegistry;
import com.xiaofeng.maidcli.command.MaidCmd;
import com.xiaofeng.maidcli.command.Param;
import com.xiaofeng.maidcli.engine.Job;
import com.xiaofeng.maidcli.engine.MaidHandle;
import com.xiaofeng.maidcli.engine.actions.SlotAction;
import com.xiaofeng.maidcli.ops.SlotOps;
import com.xiaofeng.maidcli.transport.CmdException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 界面操作 6 条（COMMANDS.md §6.2）—— 前置状态：<b>界面必须开着</b>。
 *
 * <pre>
 * take · put · move · select · input · close
 * </pre>
 * > 这里的槽位引用既指<b>界面上的槽</b>、也指<b>她身上那四个区</b>；
 * > {@code take}/{@code put} 操作的是<b>鼠标槽</b>（界面里的"临时手上"），<b>不是主手</b>。
 */
public final class SessionCmds {

    private SessionCmds() {
    }

    public static void registerAll() {
        CmdRegistry.register(new Take());
        CmdRegistry.register(new Put());
        CmdRegistry.register(new Move());
        CmdRegistry.register(new Select());
        CmdRegistry.register(new Input());
        CmdRegistry.register(new Close());
    }

    /** 会话门禁 + 入队（6 条共用） */
    private static Map<String, Object> submit(MaidCmd.Ctx ctx, SlotAction.Op op,
                                               String a, String b, int count) {
        return submit(ctx, op, a, b, count, false);
    }

    private static Map<String, Object> submit(MaidCmd.Ctx ctx, SlotAction.Op op,
                                               String a, String b, int count, boolean auto) {
        MaidHandle h = ctx.handle();
        if (h.session == null && op != SlotAction.Op.CLOSE) {
            throw CmdException.badState("界面没开 —— 这个操作要先把界面打开",
                    "open pos=<坐标> / open（开自己背包）");
        }
        Job job = JobUtil.job(ctx, opName(op), null);
        job.todo.addLast(new SlotAction(h, op, a, b, count, auto));
        h.enqueue(job);
        return JobUtil.accepted(job, h);
    }

    private static String opName(SlotAction.Op op) {
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

    // ────────────────────────── take ──────────────────────────

    /** {@code take from=<槽位> [count=N]} —— 拿起（= 左键点槽位），进鼠标槽 */
    static final class Take implements MaidCmd {
        @Override
        public String name() {
            return "take";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "拿起（= 左键点槽位）→ 进「鼠标槽」（不是主手）；鼠标槽已有别的会被拒";
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("from", Param.reqStr("from", "槽位：inv:N / hand:main / hand:off / box:N"));
            p.put("count", Param.optInt("count", 0, "拿几个（不给 = 整堆）"));
            return p;
        }

        @Override
        public void precheck(Map<String, Object> args) {
            SlotOps.checkSyntax(String.valueOf(args.get("from")), "from");
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            return submit(ctx, SlotAction.Op.TAKE, ctx.opt("from"), null, ctx.in("count", 0));
        }
    }

    // ────────────────────────── put ──────────────────────────

    /** {@code put to=<槽位> [count=N]} —— 放下（空:放入 / 同类:合并 / 异类:交换 / ground:扔外面） */
    static final class Put implements MaidCmd {
        @Override
        public String name() {
            return "put";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "放下（= 左键点槽位）：目标空→放入 · 同类→合并（超上限留在手上）· 异类→交换 · to=ground→扔外面";
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("to", Param.reqStr("to", "槽位 inv:N / box:N / hand:main，或 ground"));
            p.put("count", Param.optInt("count", 0, "放几个（不给 = 全部）"));
            return p;
        }

        @Override
        public void precheck(Map<String, Object> args) {
            SlotOps.checkSyntax(String.valueOf(args.get("to")), "to");
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            return submit(ctx, SlotAction.Op.PUT, ctx.opt("to"), null, ctx.in("count", 0));
        }
    }

    // ────────────────────────── move ──────────────────────────

    /** {@code move from=<槽位>} —— Shift 点击（自动在"对面"找位） */
    static final class Move implements MaidCmd {
        @Override
        public String name() {
            return "move";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "Shift 点击（搬运）：只给 from，自动在「对面」找位（先合并同类、再找空格）——不接受 to";
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("from", Param.reqStr("from", "源槽位 inv:N / box:N"));
            p.put("to", Param.optStr("to", null, "（不接受：要精确放哪格请用 take + put）"));
            return p;
        }

        @Override
        public void precheck(Map<String, Object> args) {
 // ★ 语法错误要【先于】会话门禁报出来（例：给 move 传 to 时先报语法错，而不是"界面没开"）
            if (args.get("to") != null) {
                throw CmdException.badArg("move 不接受 to —— 它就是「自动在对面找位」",
                        "要精确指定放到哪一格，请用 take + put");
            }
            SlotOps.checkSyntax(String.valueOf(args.get("from")), "from");
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            return submit(ctx, SlotAction.Op.MOVE, ctx.opt("from"), null, 0);
        }
    }

    // ────────────────────────── select ──────────────────────────

    static final class Select implements MaidCmd {
        @Override
        public String name() {
            return "select";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "点按钮 / 取产物：有按钮的工位按 index 选；无按钮的工位（工作台/铁砧/砂轮/锻造台/制图台）直接确认取产物";
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("index", Param.optInt("index", 0, "选项 / 按钮的下标（从 0 起；无按钮工位忽略）"));
            p.put("times", Param.optInt("times", 1, "点几次（切石机/工作台一次做一组，如 64）"));
            p.put("auto", Param.bool("auto", false, "产物直接进背包（模拟 Shift 点击），塞不下掉地上"));
            return p;
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            return submit(ctx, SlotAction.Op.SELECT, String.valueOf(ctx.in("index", 0)), null,
                    ctx.in("times", 1), ctx.flag("auto", false));
        }
    }

    // ────────────────────────── input ──────────────────────────

    static final class Input implements MaidCmd {
        @Override
        public String name() {
            return "input";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "文本框输入（铁砧改名 / 告示牌 / 命令方块）—— 铁砧是「暂存 → confirm 才生效」";
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("text", Param.reqStr("text", "要输入的文本"));
            return p;
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            return submit(ctx, SlotAction.Op.INPUT, ctx.opt("text"), null, 0);
        }
    }

    // ────────────────────────── close ──────────────────────────

    static final class Close implements MaidCmd {
        @Override
        public String name() {
            return "close";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "关界面（余料自动退回背包：退鼠标槽 + 退工位余料 + 合盖）—— 幂等";
        }

        @Override
        public Map<String, Param> params() {
            return Map.of();
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            return submit(ctx, SlotAction.Op.CLOSE, null, null, 0);
        }
    }
}
