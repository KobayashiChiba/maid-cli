package com.xiaofeng.maidcli.command;

import com.xiaofeng.maidcli.transport.CmdException;

import java.util.List;

/**
 * 参数声明 + 校验 + 归一化（COMMANDS.md §2.1）。
 *
 * <p>每个 {@link MaidCmd} 用它说明"自己有哪些参数"，于是：
 * <ol>
 *   <li><b>校验集中</b>：缺必填、类型不符、枚举越界 → 统一报 {@code bad_arg}</li>
 *   <li><b>类型归一化</b>：GSON 把 JSON 数字都读成 {@code Double}，这里统一收敛成
 *       {@code Integer}/{@code Double}（此前遇到过的坑）</li>
 *   <li><b>自省</b>：{@code help} 直接输出这些声明，CLI / 内嵌智能体据此生成工具定义</li>
 * </ol>
 *
 * <p>⚠️ 没声明的参数一律<b>忽略</b>（不报错）。
 */
public final class Param {

    public enum Type {
        STRING,
        /** 字符串或字符串数组（两种都原样收）—— "单个=对全体生效 / 数组=逐个对应" */
        STRING_OR_LIST,
        /** 原样收下任何东西（Map / List / 字符串都行）—— 透传结构体 */
        RAW,
        INT,
        DOUBLE,
        BOOL,
        /** 单个坐标 {@code x,y,z} */
        POS,
        /** 坐标（可批量 {@code a;b;c}）—— 数组即批量 */
        POS_LIST,
        /** 枚举：值必须是 {@link #values} 里的一个 */
        ENUM,
        /** 实体引用：数字 id 或保留字 {@code owner}（主人） */
        ENTITY
    }

    public final String name;
    public final Type type;
    public final boolean required;
    public final Object def;
    public final String desc;
    public final List<String> values;

    private Param(String name, Type type, boolean required, Object def, String desc, List<String> values) {
        this.name = name;
        this.type = type;
        this.required = required;
        this.def = def;
        this.desc = desc;
        this.values = values;
    }

    // ────────────────────────── 工厂 ──────────────────────────

    /** 必填参数 */
    public static Param req(String name, Type type, String desc) {
        return new Param(name, type, true, null, desc, List.of());
    }

    public static Param reqStr(String name, String desc) {
        return req(name, Type.STRING, desc);
    }

    public static Param reqInt(String name, String desc) {
        return req(name, Type.INT, desc);
    }

    public static Param reqPos(String name, String desc) {
        return req(name, Type.POS, desc);
    }

    /** 可选参数（带默认值） */
    public static Param opt(String name, Type type, Object def, String desc) {
        return new Param(name, type, false, def, desc, List.of());
    }

    public static Param optStr(String name, String def, String desc) {
        return opt(name, Type.STRING, def, desc);
    }

    public static Param optInt(String name, int def, String desc) {
        return opt(name, Type.INT, def, desc);
    }

    /** 实体引用：数字 id 或保留字 {@code owner}（主人） */
    public static Param optEntity(String name, String desc) {
        return new Param(name, Type.ENTITY, false, null, desc, List.of());
    }

    public static Param optDbl(String name, double def, String desc) {
        return opt(name, Type.DOUBLE, def, desc);
    }

    /** 开关参数（布尔，默认 false） */
    public static Param flag(String name, String desc) {
        return new Param(name, Type.BOOL, false, Boolean.FALSE, desc, List.of());
    }

    /** 布尔参数（带默认值） */
    public static Param bool(String name, boolean def, String desc) {
        return new Param(name, Type.BOOL, false, def, desc, List.of());
    }

    /** 枚举参数 */
    public static Param enums(String name, boolean required, String def, String desc, String... values) {
        return new Param(name, Type.ENUM, required, def, desc, List.of(values));
    }

    /** 坐标（可批量），最常用的一种 */
    public static Param positions(boolean required, String desc) {
        return new Param("pos", Type.POS_LIST, required, null, desc, List.of());
    }

    /** 单个坐标 */
    public static Param position(boolean required, String desc) {
        return new Param("pos", Type.POS, required, null, desc, List.of());
    }

    // ────────────────────────── 校验 / 归一化 ──────────────────────────

    /**
     * 把原始值收敛成本声明的类型。
     *
     * @throws CmdException {@code bad_arg}
     */
    public Object coerce(Object raw) {
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            if (required && def == null) {
                throw new CmdException(CmdException.BAD_ARG,
                        "缺参数 " + name + (desc.isEmpty() ? "" : "（" + desc + "）"));
            }
            return def;
        }
        switch (type) {
            case STRING -> {
                return String.valueOf(raw);
            }
            case STRING_OR_LIST, RAW -> {
                // 原样收：字符串（query）/ List / Map（body）都行，由命令自己决定怎么用
                return raw;
            }
            case INT -> {
                if (raw instanceof Number n) {
                    return (int) Math.round(n.doubleValue());
                }
                try {
                    return Integer.parseInt(String.valueOf(raw).trim());
                } catch (NumberFormatException e) {
                    throw new CmdException(CmdException.BAD_ARG, "参数 " + name + " 要整数（收到 " + raw + "）");
                }
            }
            case DOUBLE -> {
                if (raw instanceof Number n) {
                    return n.doubleValue();
                }
                try {
                    return Double.parseDouble(String.valueOf(raw).trim());
                } catch (NumberFormatException e) {
                    throw new CmdException(CmdException.BAD_ARG, "参数 " + name + " 要数字（收到 " + raw + "）");
                }
            }
            case BOOL -> {
                if (raw instanceof Boolean b) {
                    return b;
                }
                String s = String.valueOf(raw).trim();
                // ⚠️ 协议规定只认 true / false（COMMANDS.md §2.1：不接受 on/off）
                if (s.equalsIgnoreCase("true")) {
                    return Boolean.TRUE;
                }
                if (s.equalsIgnoreCase("false")) {
                    return Boolean.FALSE;
                }
                throw new CmdException(CmdException.BAD_ARG,
                        "参数 " + name + " 只能是 true / false（收到 " + raw + "）");
            }
            case POS, POS_LIST -> {
                // ⚠️ /q 走 query string ⇒ 参数到手就是字符串（"x,y,z"）；
 // 只有 POST body 才是真 JSON 数组。两种都得收，交给 Ctx 统一解析。
                if (raw instanceof List<?> || raw instanceof String) {
                    return raw;
                }
                throw new CmdException(CmdException.BAD_ARG,
                        "参数 " + name + " 要坐标（x,y,z 或 x,y,z;x,y,z，收到 " + raw + "）");
            }
            case ENUM -> {
                String s = String.valueOf(raw).trim();
                for (String v : values) {
                    if (v.equalsIgnoreCase(s)) {
                        return v;
                    }
                }
                throw new CmdException(CmdException.BAD_ARG,
                        "参数 " + name + " 只能是 " + String.join(" / ", values) + "（收到 " + raw + "）");
            }
            case ENTITY -> {
                // 数字 id 或保留字 owner —— 具体解析（主人 id）留给 Ctx.entityId()
                if (raw instanceof Number n) {
                    return n.intValue();
                }
                String s = String.valueOf(raw).trim();
                if (s.equalsIgnoreCase("owner")) {
                    return "owner";
                }
                try {
                    return Integer.parseInt(s);
                } catch (NumberFormatException e) {
                    throw new CmdException(CmdException.BAD_ARG,
                            "参数 " + name + " 要给实体 id 或 owner（收到 " + raw + "）");
                }
            }
            default -> {
                return raw;
            }
        }
    }
}
