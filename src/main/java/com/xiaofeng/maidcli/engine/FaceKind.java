package com.xiaofeng.maidcli.engine;

/**
 * 界面种类（class-design §6）。
 *
 * <p>用处：<b>校验 + 自省</b> —— 调用者给"不支持 confirm 的界面"调 confirm 时，
 * 我们在**指令层**就报 {@code bad_state}（而不是等 MC 那侧报错）。
 * 真正的操作**仍然调原版**（不重复实现原版逻辑，但也不依赖调用方自律）。
 *
 * <p>{@link #wire} 是给外面看的名字（{@code sense session} 的 {@code kind} 字段）。
 */
public enum FaceKind {

    // ── 纯容器 ──
    CHEST("chest", "箱子 · 陷阱箱 · 木桶（双箱 = 合并后的 54 格）"),
    SHULKER("shulker", "潜影盒"),
    HOPPER("hopper", "漏斗"),
    DISPENSER("dispenser", "投掷器 / 发射器"),
    CRAFTER("crafter", "合成器"),
    ENDER_CHEST("ender_chest", "末影箱（主人的；他离线也能用）"),

    // ── 有进度 ──
    FURNACE("furnace", "熔炉"),
    BLAST("blast_furnace", "高炉"),
    SMOKER("smoker", "烟熏炉"),
    BREWING("brewing", "酿造台"),

    // ── 摆料 → 取产物 ──
    CRAFTING("workbench", "工作台（3×3）"),
    GRINDSTONE("grindstone", "砂轮"),
    SMITHING("smithing", "锻造台"),
    CARTOGRAPHY("cartography", "制图台"),
    LOOM("loom", "织布机"),

    // ── 有选项 / 按钮 ──
    STONECUTTER("stonecutter", "切石机（选项 + 点按钮选产物）"),
    ENCHANT("enchanting", "附魔台（三档位，每次重算 ⇒ 必须一次调用内选完）"),
    MERCHANT("villager", "村民（选交易项 + 成交）"),
    BEACON("beacon", "信标（选效果 + 确认）"),
    LECTERN("lectern", "讲台（翻页）"),

    // ── 特殊 ──
    ANVIL("anvil", "铁砧（要输文本改名）"),
    HORSE("horse", "马 / 驴 / 羊驼（装鞍、箱子）"),

    /** 她自己的背包（2×2 合成格） */
    INV("inv", "她自己的背包"),
    /** 不认识的界面（闸门：只允许通用槽位操作） */
    UNKNOWN("unknown", "未分类的界面（只支持槽位操作）");

    public final String wire;
    public final String desc;

    FaceKind(String wire, String desc) {
        this.wire = wire;
        this.desc = desc;
    }

    /**
     * {@code move} / {@code macro sort} 能不能用在这个界面。
     *
     * <p>只允许<b>格子语义同质</b>的容器 + 她自己的背包：
     * 箱子 / 木桶 / 潜影盒 / 末影箱 / 漏斗 / 投掷器 / 合成器。
     * <p>❌ 熔炉·高炉·烟熏炉·酿造台：格子有语义（输入 / 燃料 / 输出），"自动找位"会把料塞进
     * 燃料格，排序更会把正在烧的东西挪走。
     * <p>❌ 工位（工作台 / 铁砧 / 切石机…）：虚拟槽 + 结果格只读。
     * <p>❌ 村民 · 马：交易格 / 装备格不是储物格。
     */
    public boolean canQuickMove() {
        return switch (this) {
            case CHEST, SHULKER, ENDER_CHEST, HOPPER, DISPENSER, CRAFTER, INV -> true;
            default -> false;
        };
    }

    public static FaceKind fromWire(String s) {
        for (FaceKind k : values()) {
            if (k.wire.equalsIgnoreCase(s)) {
                return k;
            }
        }
        return UNKNOWN;
    }
}
