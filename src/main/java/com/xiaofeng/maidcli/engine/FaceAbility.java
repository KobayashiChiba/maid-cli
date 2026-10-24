package com.xiaofeng.maidcli.engine;

import java.util.EnumMap;
import java.util.Map;

/**
 * 每种界面的【能力声明】（class-design §6）。
 *
 * <p>为什么需要：如果 Session 只是通用转发，调用者给"不支持 select 的界面"调 select
 * ⇒ 只能等 MC 那侧报错才知道 ✗。所以我们自己维护一张表，
 * 用它做<b>校验（先查表，不支持直接报 bad_state）</b> + <b>自省（LLM 知道这个界面能干什么）</b>。
 */
public record FaceAbility(
        boolean slots,
        boolean hasSelect,
        boolean hasInput,
        boolean autoCollect,
        boolean progress,
        String hint
) {

    private static final Map<FaceKind, FaceAbility> TABLE = new EnumMap<>(FaceKind.class);

    private static void put(FaceKind k, boolean slots, boolean select, boolean input,
                            boolean auto, boolean prog, String hint) {
        TABLE.put(k, new FaceAbility(slots, select, input, auto, prog, hint));
    }

    static {
        // ── 纯容器：只有槽位 ──
        put(FaceKind.CHEST, true, false, false, false, false, "把东西搬进来 / 搬出去（双箱 54 格）");
        put(FaceKind.SHULKER, true, false, false, false, false, "27 格的随身箱子");
        put(FaceKind.HOPPER, true, false, false, false, false, "5 格；上面进、侧面烧、下面出");
        put(FaceKind.DISPENSER, true, false, false, false, false, "9 格，投掷器 / 发射器共用");
        put(FaceKind.CRAFTER, true, false, false, false, false, "合成器，9 格可编程");
        put(FaceKind.ENDER_CHEST, true, false, false, false, false, "主人名下，27 格；他离线也能读写");

        // ── 有进度 ──
        put(FaceKind.FURNACE, true, false, false, true, true, "上格烧、下格燃料、右格出；有熔炼进度");
        put(FaceKind.BLAST, true, false, false, true, true, "高炉：只烧矿石 / 盔甲 / 工具，快一倍");
        put(FaceKind.SMOKER, true, false, false, true, true, "烟熏炉：只烧食物，快一倍");
        put(FaceKind.BREWING, true, false, false, true, true, "酿造台：下面 3 瓶 + 上面 1 料 + 燃料；有酿造进度");

        // ── 摆料 → 取产物 ──
        put(FaceKind.CRAFTING, true, false, false, true, false, "工作台 3×3；摆好料产物出现在结果格");
        put(FaceKind.GRINDSTONE, true, false, false, true, false, "砂轮：去附魔 / 修耐久，产物自动出");
        put(FaceKind.SMITHING, true, false, false, true, false, "锻造台：升级装备（模板 + 材料）");
        put(FaceKind.CARTOGRAPHY, true, false, false, true, false, "制图台：复制 / 放大 / 锁定地图");
        put(FaceKind.LOOM, true, false, false, true, false, "织布机：给旗帜做图案（有选项）");

        // ── 有选项 / 按钮 ──
        put(FaceKind.STONECUTTER, true, true, false, true, false, "切石机：先 select 选产物，结果自动出");
        put(FaceKind.ENCHANT, true, true, false, true, false, "附魔台：select 选档位（三档每次重算 ⇒ 必须一次调用内选完）");
        put(FaceKind.MERCHANT, true, true, false, false, false, "村民：select 选交易项，再 select 那个索引成交");
        put(FaceKind.BEACON, true, true, false, false, false, "信标：select 选效果");
        put(FaceKind.LECTERN, true, true, false, false, false, "讲台：select 翻页");

        // ── 特殊 ──
        put(FaceKind.ANVIL, true, false, true, true, false, "铁砧：两格材料 + input 改名字（不消耗经验）");
        put(FaceKind.HORSE, true, false, false, false, false, "马 / 驴：鞍、护甲、箱子");

        put(FaceKind.INV, true, false, false, true, false, "她自己的背包（含 2×2 合成格）");
        put(FaceKind.UNKNOWN, true, false, false, false, false, "不认识的界面：只能用通用槽位操作");
    }

    /** 查表（永不为 null） */
    public static FaceAbility of(FaceKind k) {
        FaceAbility a = TABLE.get(k);
        return a == null ? TABLE.get(FaceKind.UNKNOWN) : a;
    }

    /** select 可用性检查 */
    public void requireSelect(FaceKind k) {
        if (!hasSelect) {
            throw new com.xiaofeng.maidcli.transport.CmdException(
                    com.xiaofeng.maidcli.transport.CmdException.BAD_STATE,
                    "这个界面没有可选项 / 按钮（" + k.wire + "）",
                    hint);
        }
    }

    /** input 可用性检查 */
    public void requireInput(FaceKind k) {
        if (!hasInput) {
            throw new com.xiaofeng.maidcli.transport.CmdException(
                    com.xiaofeng.maidcli.transport.CmdException.BAD_STATE,
                    "这个界面不支持文本输入（" + k.wire + "）",
                    hint);
        }
    }

    /** 槽位能力检查 */
    public void requireSlots(FaceKind k) {
        if (!slots) {
            throw new com.xiaofeng.maidcli.transport.CmdException(
                    com.xiaofeng.maidcli.transport.CmdException.BAD_STATE,
                    "这个界面没有槽位（" + k.wire + "）",
                    hint);
        }
    }
}
