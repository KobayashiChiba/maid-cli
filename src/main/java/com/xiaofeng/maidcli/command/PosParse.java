package com.xiaofeng.maidcli.command;

import com.xiaofeng.maidcli.transport.CmdException;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 坐标参数解析 —— COMMANDS.md §2.1「坐标值格式」（🌐 全局通用）。
 *
 * <pre>
 * x,y,z                     单点（整数=那个方块；小数=空间精确点，瞄准用）
 * x1,y1,z1:x2,y2,z2         立方体（两对角，闭区间）
 * x,y,z:r                   球形（中心 + 半径 r）
 * a;b;c                     参数内批量（一个参数收多组）
 * </pre>
 * 分隔符一律不留空格：{@code ,} 分量 · {@code :} 范围 · {@code ;} 多组
 *
 * <p>为什么单独一个文件：坐标是**唯一**会出现在几乎所有指令里的复杂参数形态，
 * 解析规则必须只有一份（旧实现里 pos 有 3 套写法，是 bug 高发区）。
 */
public final class PosParse {

    /** 一个坐标点（保留小数 —— 精确点用于瞄准） */
    public record P(double x, double y, double z) {
        /** 指向那个方块（向下取整，和原版 BlockPos 语义一致） */
        public BlockPos block() {
            return new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        }

        public List<Double> asList() {
            return List.of(x, y, z);
        }
    }

    /** 一个区域（单点 / 立方体 / 球形） */
    public interface Area {
        /** 区域"代表点"（单点它自己；方区给第一个角；球给中心）—— 用于 go/place 这种只要一个点的地方 */
        P anchor();

        /** 展开成方块坐标（受 limit 限制，超了报 bad_arg） */
        List<BlockPos> blocks(int limit);
    }

    /** 单点 */
    public record Single(P p) implements Area {
        @Override
        public P anchor() {
            return p;
        }

        @Override
        public List<BlockPos> blocks(int limit) {
            return List.of(p.block());
        }
    }

    /** 立方体（两对角，闭区间） */
    public record Box(P a, P b) implements Area {
        @Override
        public P anchor() {
            return a;
        }

        @Override
        public List<BlockPos> blocks(int limit) {
            int x1 = (int) Math.floor(Math.min(a.x, b.x)), x2 = (int) Math.floor(Math.max(a.x, b.x));
            int y1 = (int) Math.floor(Math.min(a.y, b.y)), y2 = (int) Math.floor(Math.max(a.y, b.y));
            int z1 = (int) Math.floor(Math.min(a.z, b.z)), z2 = (int) Math.floor(Math.max(a.z, b.z));
            long n = (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
            if (n > limit) {
                throw new CmdException(CmdException.BAD_ARG,
                        "这个区域有 " + n + " 格，超过上限 " + limit,
                        "分批下发，或改用更小的区域");
            }
            List<BlockPos> out = new ArrayList<>((int) n);
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    for (int x = x1; x <= x2; x++) {
                        out.add(new BlockPos(x, y, z));
                    }
                }
            }
            return out;
        }
    }

    /** 球形（中心 + 半径） */
    public record Sphere(P c, double r) implements Area {
        @Override
        public P anchor() {
            return c;
        }

        @Override
        public List<BlockPos> blocks(int limit) {
            int ir = (int) Math.floor(r);
            int cx = (int) Math.floor(c.x), cy = (int) Math.floor(c.y), cz = (int) Math.floor(c.z);
            List<BlockPos> out = new ArrayList<>();
            double r2 = r * r;
            for (int y = -ir; y <= ir; y++) {
                for (int z = -ir; z <= ir; z++) {
                    for (int x = -ir; x <= ir; x++) {
                        if (x * x + y * y + z * z > r2) {
                            continue;
                        }
                        if (out.size() >= limit) {
                            throw new CmdException(CmdException.BAD_ARG,
                                    "球形区域超过上限 " + limit + " 格",
                                    "把半径改小，或分批下发");
                        }
                        out.add(new BlockPos(cx + x, cy + y, cz + z));
                    }
                }
            }
            return out;
        }
    }

    private PosParse() {
    }

    // ────────────────────────── 解析入口 ──────────────────────────

    /**
     * 解析"参数内批量"：用 {@code ;} 拆成多组，每组一个 {@link Area}。
     * <p>例：{@code "-34,134,-36;-40,130,-40:-20,150,-20"} ⇒ [单点, 方区]
     */
    public static List<Area> parseAreas(Object raw) {
        List<String> chunks = splitGroups(raw);
        List<Area> out = new ArrayList<>(chunks.size());
        for (String c : chunks) {
            out.add(parseArea(c));
        }
        return out;
    }

    /** 解析单个区域（不含 {@code ;}） */
    public static Area parseArea(String s) {
        if (s == null || s.isBlank()) {
            throw new CmdException(CmdException.BAD_ARG, "坐标是空的");
        }
        String t = s.trim();
        int colon = t.indexOf(':');
        if (colon < 0) {
            return new Single(parsePoint(t));
        }
        String left = t.substring(0, colon);
        String right = t.substring(colon + 1).trim();
        List<String> parts = splitComma(right);
        if (parts.size() == 1) {
            // x,y,z:r —— 球形
            double r = parseNum(parts.get(0), "球形半径");
            return new Sphere(parsePoint(left), r);
        }
        // x1,y1,z1:x2,y2,z2 —— 立方体
        return new Box(parsePoint(left), parsePoint(right));
    }

    /** 解析出一个点（必须 3 个分量） */
    public static P parsePoint(String s) {
        List<String> parts = splitComma(s);
        if (parts.size() != 3) {
            throw new CmdException(CmdException.BAD_ARG,
                    "坐标要 3 个数字（收到 " + s + "）",
                    "写法：x,y,z");
        }
        return new P(parseNum(parts.get(0), "x"), parseNum(parts.get(1), "y"), parseNum(parts.get(2), "z"));
    }

    /**
     * 取"批量点"列表 —— 用于 go / place / break 这类点位操作。
     * <p>方区/球只取 {@link Area#anchor()}（不展开成几千格）—— 要逐格请用 blocks 类查询或分批。
     */
    public static List<P> parsePoints(Object raw) {
        List<P> out = new ArrayList<>();
        for (Area a : parseAreas(raw)) {
            out.add(a.anchor());
        }
        return out;
    }

    // ────────────────────────── 小工具 ──────────────────────────

    /** 值可能是 String（query string / body 里的字符串）或 List（body 里的真数组） */
    private static List<String> splitGroups(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                out.add(String.valueOf(o));
            }
            return out;
        }
        String s = String.valueOf(raw).trim();
        List<String> out = new ArrayList<>();
        for (String c : s.split(";")) {
            if (!c.isBlank()) {
                out.add(c.trim());
            }
        }
        if (out.isEmpty()) {
            throw new CmdException(CmdException.BAD_ARG, "坐标是空的");
        }
        return out;
    }

    private static List<String> splitComma(String s) {
        List<String> out = new ArrayList<>();
        for (String c : s.split(",")) {
            if (!c.isBlank()) {
                out.add(c.trim());
            }
        }
        return out;
    }

    private static double parseNum(String s, String what) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            throw new CmdException(CmdException.BAD_ARG, what + " 不是数字（收到 " + s + "）");
        }
    }
}
