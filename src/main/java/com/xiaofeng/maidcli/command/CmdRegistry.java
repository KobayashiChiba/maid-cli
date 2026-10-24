package com.xiaofeng.maidcli.command;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.MaidCliMod;
import com.xiaofeng.maidcli.engine.MaidHandle;
import com.xiaofeng.maidcli.transport.CmdException;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指令注册表 —— 注册 · 分发 · 自省（COMMANDS.md §1）。
 *
 * <p>三件事：
 * <ul>
 *   <li><b>注册</b>：{@code register(new PlaceCmd())} —— 加一条指令就是这一行 + 一个实现类</li>
 *   <li><b>分发</b>：按名字查表 → 参数校验/归一化 → 调 {@link MaidCmd#run}</li>
 *   <li><b>自省</b>：{@code help} 的数据源（内嵌智能体据此生成"工具定义"，加新指令它立刻会用）</li>
 * </ul>
 *
 * <p>⚠️ 指令实现在<b>服务端主线程</b>被调用（传输层统一走 {@code onMain}）。
 */
public final class CmdRegistry {

    private static final Map<String, MaidCmd> BY_NAME = new LinkedHashMap<>();

    private CmdRegistry() {
    }

    public static void register(MaidCmd cmd) {
        MaidCmd old = BY_NAME.put(cmd.name(), cmd);
        if (old != null) {
            MaidCliMod.LOG.warn("[MaidCLI] 指令 {} 被重复注册（覆盖）", cmd.name());
        }
    }

    /** 服务端启动时判：静态字段随进程重启重置，所以每次启动注册一轮 */
    public static boolean isEmpty() {
        return BY_NAME.isEmpty();
    }

    public static MaidCmd get(String name) {
        return BY_NAME.get(name);
    }

    public static java.util.Collection<MaidCmd> all() {
        return BY_NAME.values();
    }

    /**
     * 分发一条指令。
     *
     * @param name   指令名
     * @param maid   已解析的女仆实体（世界类指令为 null）
     * @param handle 她的句柄（世界类指令为 null）
     * @param args   原始参数（含未声明的，会被忽略）
     */
    public static Map<String, Object> dispatch(String name, EntityMaid maid, MaidHandle handle,
                                              Map<String, Object> args, MaidCmd.Kind kind,
                                              java.util.UUID player) {
        MaidCmd cmd = BY_NAME.get(name);
        if (cmd == null) {
            throw new CmdException(CmdException.BAD_ARG,
                    "没有这条指令：" + name,
                    "用 help 看有哪些指令");
        }
        if (cmd.kind() != kind) {
            throw new CmdException(CmdException.BAD_ARG,
                    "指令 " + name + " 不是" + (kind == MaidCmd.Kind.QUERY ? "查询类" : "操作类"),
                    kind == MaidCmd.Kind.QUERY
                            ? "它是操作类，要用 POST /cmd 发"
                            : "它是查询类，要用 GET /q 发");
        }
        // 参数校验 + 归一化（没声明的参数一律忽略）
        Map<String, Object> norm = new LinkedHashMap<>();
        for (Param p : cmd.params().values()) {
            norm.put(p.name, p.coerce(args.get(p.name)));
        }
        cmd.precheck(norm);
        try {
            return cmd.run(new CtxImpl(maid, handle, player, norm, args));
        } catch (CmdException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // 指令实现里抛的受检异常统一归成 internal（避免每个调用方都要声明 throws）
            throw CmdException.internal(e);
        }
    }

    /** 只做参数校验（不发指令）—— 传输层判"是查询还是操作"之前用 */
    public static void validate(String name, Map<String, Object> args) {
        MaidCmd cmd = BY_NAME.get(name);
        if (cmd == null) {
            throw new CmdException(CmdException.BAD_ARG, "没有这条指令：" + name, "用 help 看有哪些指令");
        }
        for (Param p : cmd.params().values()) {
            p.coerce(args.get(p.name));
        }
    }

    // ────────────────────────── 自省（help） ──────────────────────────

    /** 全部指令的声明（{@code help} 的数据源） */
    public static Map<String, Object> help(String what) {
        if (what != null && !what.isBlank()) {
            return helpTopic(what.trim());
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (MaidCmd c : BY_NAME.values()) {
            list.add(describe(c));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cmds", list);
        out.put("note", "参数写法：键=值；坐标 x,y,z（小数=精确点）/ x1,y1,z1:x2,y2,z2（方区）/ x,y,z:r（球）"
                + " / a;b;c（批量）");
        return out;
    }

    private static Map<String, Object> describe(MaidCmd c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", c.name());
        m.put("kind", c.kind() == MaidCmd.Kind.QUERY ? "query" : "command");
        // 子命令 / 变长参数也进自省（CLI 和 LLM 据此生成正确的调用形式）
        if (c.sub() != null) {
            Map<String, Object> sub = new LinkedHashMap<>();
            sub.put("name", c.sub().argName());
            sub.put("required", c.sub().required());
            sub.put("desc", c.sub().desc());
            m.put("sub", sub);
        }
        if (c.variadic() != null) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("name", c.variadic().argName());
            v.put("mode", c.variadic().mode().name().toLowerCase());
            m.put("variadic", v);
        }
        if (!c.desc().isEmpty()) {
            m.put("desc", c.desc());
        }
        List<Map<String, Object>> ps = new ArrayList<>();
        for (Param p : c.params().values()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", p.name);
            one.put("type", p.type.name().toLowerCase());
            one.put("required", p.required);
            if (p.def != null) {
                one.put("default", p.def);
            }
            if (!p.values.isEmpty()) {
                one.put("values", p.values);
            }
            if (!p.desc.isEmpty()) {
                one.put("desc", p.desc);
            }
            ps.add(one);
        }
        m.put("params", ps);
        return m;
    }

    /** 公开资料（可选值清单 —— 不依赖女仆） */
    private static Map<String, Object> helpTopic(String what) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (what) {
            case "mode", "tasks" -> {
                List<Map<String, Object>> tasks = new ArrayList<>();
                for (var t : com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager.getTaskIndex()) {
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("id", t.getUid().toString());
                    one.put("name", t.getName().getString());
                    tasks.add(one);
                }
                out.put("tasks", tasks);
            }
            case "errors" -> {
                List<Map<String, Object>> errs = new ArrayList<>();
                addError(errs, CmdException.UNAUTHORIZED, "没 key / key 不认识 / 过期");
                addError(errs, CmdException.NOT_FOUND, "指不到这个女仆 / 方块 / 容器 / 实体");
                addError(errs, CmdException.NOT_YOURS, "她不是这个玩家的女仆");
                addError(errs, CmdException.BAD_ARG, "参数不合法（缺失 / 类型不符 / 越界）");
                addError(errs, CmdException.BAD_STATE, "界面状态不匹配（该开没开 / 该关没关）");
                addError(errs, CmdException.BUSY, "队列满");
                addError(errs, CmdException.TIMEOUT, "动作 / 宏超时");
                addError(errs, CmdException.TOO_FAR, "够不着（超出 8 格手长）");
                addError(errs, CmdException.NOT_AWAKE, "她没被唤醒");
                addError(errs, CmdException.NOT_LOADED, "目标区块没加载");
                addError(errs, CmdException.NO_SPACE, "空间不足（背包满 / 容纳不下她）");
                addError(errs, CmdException.NO_ITEM, "身上没这件物品");
                addError(errs, CmdException.BLOCKED, "被 TLM / 原版判定拦下");
                addError(errs, CmdException.INTERNAL, "未预期的异常（实现 bug）");
                out.put("errors", errs);
            }
            default -> throw new CmdException(CmdException.BAD_ARG,
                    "没有这个主题：" + what,
                    "可用：mode（工作模式清单）/ errors（错误码表）");
        }
        return out;
    }

    private static void addError(List<Map<String, Object>> list, String code, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("desc", desc);
        list.add(m);
    }

    // ────────────────────────── Ctx 实现 ──────────────────────────

    private record CtxImpl(EntityMaid maid, MaidHandle handle, java.util.UUID player,
                           Map<String, Object> args,
                           Map<String, Object> rawArgs) implements MaidCmd.Ctx {

        @Override
        public MinecraftServer server() {
            return MaidCliMod.server();
        }

        @Override
        public BlockPos pos() {
            List<BlockPos> l = posList();
            return l.isEmpty() ? null : l.get(0);
        }

        @Override
        public List<BlockPos> posList() {
            Object raw = args.get("pos");
            if (raw == null) {
                return List.of();
            }
            List<BlockPos> out = new ArrayList<>();
            for (PosParse.P p : PosParse.parsePoints(raw)) {
                out.add(p.block());
            }
            return out;
        }

        @Override
        public List<BlockPos> areaBlocks(int limit) {
            Object raw = args.get("pos");
            if (raw == null) {
                return List.of();
            }
            List<BlockPos> out = new ArrayList<>();
            for (PosParse.Area a : PosParse.parseAreas(raw)) {
                out.addAll(a.blocks(limit));
            }
            return out;
        }

        @Override
        public String opt(String key) {
            Object v = args.get(key);
            return v == null ? null : String.valueOf(v);
        }

        @Override
        public String str(String key, String def) {
            Object v = args.get(key);
            return v == null ? def : String.valueOf(v);
        }

        @Override
        public int in(String key, int def) {
            Object v = args.get(key);
            return v instanceof Number n ? n.intValue() : def;
        }

        @Override
        public double dbl(String key, double def) {
            Object v = args.get(key);
            return v instanceof Number n ? n.doubleValue() : def;
        }

        @Override
        public boolean flag(String key, boolean def) {
            Object v = args.get(key);
            return v instanceof Boolean b ? b : def;
        }

        @Override
        public Object raw(String key) {
            return rawArgs.get(key);
        }
    }
}
