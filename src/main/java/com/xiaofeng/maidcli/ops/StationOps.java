package com.xiaofeng.maidcli.ops;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.MaidCliExtension;
import com.xiaofeng.maidcli.MaidCliMod;
import com.xiaofeng.maidcli.engine.FaceKind;
import com.xiaofeng.maidcli.engine.Session;
import com.xiaofeng.maidcli.infra.MaidFakePlayer;
import com.xiaofeng.maidcli.transport.CmdException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 工位（铁砧 / 切石机 / 附魔台 / 砂轮 / 锻造台 / 制图台 / 织布机 / 信标 / 讲台 / 工作台）
 * 的虚拟槽与菜单构建支持。
 *
 * <p><b>功能</b>：把这些「纯菜单方块」包成一个 {@link IItemHandlerModifiable} 视图交给
 * {@link Session#container}，于是 {@code take / put / move} 无需特殊处理即可用于工位；
 * 产出一律经 {@link #pullResult(Session)} 收口。
 *
 * <p><b>原因</b>：这些方块在游戏里没有真实容器（不像箱子有 BlockEntity 存物品），槽位只存在于菜单中。
 * 因此这里凭空 {@code new} 一个原版 Menu，喂料、算结果、读结果槽，槽位数据放在
 * {@link Session#virtual}（按格号，{@code 0..8} = 输入格，最后一格 = 结果）。
 *
 * <p><b>三条约束</b>：
 * <ol>
 *   <li><b>只暴露一个视图</b>：{@code take/put/move} 只认 {@link Session#container}；</li>
 *   <li><b>余料绝不丢</b>：写操作立刻落进 {@link Session#virtual}，
 *       {@code ContainerOps.close} 会遍历 virtual 把余料退回背包（塞不下掉地上）；</li>
 *   <li><b>物品守恒</b>：产出在 {@link #pullResult(Session)} 里做「读结果 + 消耗输入」的一次性收口，
 *       未产出前输入原样留在 virtual（关会话原样退回）。</li>
 * </ol>
 *
 * <p><b>接线</b>：{@code ContainerOps.openContainer} 对 {@code !isRealContainer(kind)} 的分支调
 * {@link #openStation}；{@code SlotAction} 的 select / anvil 分支在 {@code clickMenuButton} 之后调
 * {@link #pullResult}。
 *
 * <p><b>持久化</b>：虚拟槽默认只在内存（{@link Session#virtual}），关会话即退料；
 * 需要扛重启 / 扛收进物品时用 {@link #saveVirtual} / {@link #loadVirtual}，
 * 但启用后<b>必须</b>在关会话时调 {@link #clearVirtualNbt}，
 * 否则下次 open 会把已退回背包的材料再载进来（导致复制）。
 */
public final class StationOps {

    private StationOps() {
    }

    /** 纯菜单型工位（没有真实 Container —— 槽位虚拟）。真实容器型（箱子/熔炉/漏斗…）不在此列。 */
    public static final Set<FaceKind> STATIONS = EnumSet.of(
            FaceKind.CRAFTING, FaceKind.GRINDSTONE, FaceKind.SMITHING, FaceKind.CARTOGRAPHY,
            FaceKind.LOOM, FaceKind.STONECUTTER, FaceKind.ENCHANT, FaceKind.ANVIL,
            FaceKind.BEACON, FaceKind.LECTERN);

    /** 虚拟槽最多记几格（对齐旧的 MAX_SLOTS；工作台 3×3 是 9 格，留余量）。 */
    public static final int MAX_SLOTS = 16;

    /** 虚拟槽存进 {@code maid_cli:tag} 那个 CompoundTag 里的字段名（和「标签」t 字段共存）。 */
    private static final String FIELD = "Station";

    // ────────────────────────── 种类识别 ──────────────────────────

    /** 这个界面种类是不是「工位」（纯菜单、虚拟槽）。 */
    public static boolean isStation(FaceKind k) {
        return STATIONS.contains(k);
    }

    /** 这个工位有几个槽（= 暴露给 {@code box:N} 的格数）。 */
    public static int slotCount(FaceKind k) {
        return switch (k) {
            case CRAFTING -> 10;      // 3×3 合成格 + 结果
            case GRINDSTONE -> 3;     // 0,1 输入 → 2 结果
            case CARTOGRAPHY -> 3;    // 0 地图, 1 附加 → 2 结果
            case SMITHING -> 4;       // 0 模板, 1 底材, 2 加成 → 3 结果
            case LOOM -> 4;           // 0 旗帜, 1 染料, 2 图案 → 3 结果
            case STONECUTTER -> 2;    // 0 原料 → 1 结果
            case ANVIL -> 3;          // 0,1 输入 → 2 结果
            case ENCHANT -> 2;        // 0 物品, 1 青金石（原地改槽 0）
            case BEACON -> 1;         // 0 支付
            case LECTERN -> 1;        // 0 书
            default -> 0;
        };
    }

    /** 哪些格是「输入」（喂进 Menu 的格）。 */
    private static int[] inputSlots(FaceKind k) {
        return switch (k) {
            case CRAFTING -> new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8};
            case GRINDSTONE, CARTOGRAPHY, ANVIL, ENCHANT -> new int[]{0, 1};
            case SMITHING, LOOM -> new int[]{0, 1, 2};
            case STONECUTTER, BEACON, LECTERN -> new int[]{0};
            default -> new int[]{};
        };
    }

    /**
     * 这个工位有没有「真按钮」（{@code clickMenuButton} 会被覆写）。
     *
     * <p>⚠️ javap 查过 1.21.1：<b>CraftingMenu / SmithingMenu / CartographyTableMenu /
     * AnvilMenu / GrindstoneMenu 都没有覆写 clickMenuButton</b> ⇒ 基类版本恒返回 false，
     * 对它们调 clickMenuButton 必然「菜单自己拒了」。
     * <p>村民（MerchantMenu）也没覆写，但它走 {@code MerchantOps.select} 专用路径。
     */
    public static boolean hasButton(FaceKind k) {
        return switch (k) {
            case STONECUTTER, ENCHANT, LOOM, LECTERN -> true;
            default -> false;
        };
    }

    /**
     * 我们的虚拟槽号 → 原版 Menu 里的槽号。
     *
     * <p>⚠️ <b>{@code CraftingMenu} 的槽位顺序和所有别的工位都不一样</b>：
     * 它是 {@code slots[0] = 结果槽}、{@code slots[1..9] = 3×3 合成格}；
     * 而我们的虚拟槽是 {@code 0..8 = 合成格}、{@code 9 = 结果}。
     *
     * <p>其它工位（切石机 / 铁砧 / 砂轮 / 锻造台 / 制图台 / 织布机 / 附魔台 / 讲台）
     * 的 Menu 槽位顺序和我们的虚拟槽一致，直接用槽号。
     *
     * <p>⚠️ 复查抓到：不映射的话，工作台会把料喂进结果槽、把材料格当产物读，
     * 而且 {@code slotsChanged(结果槽的 container)} 不触发原版重算 ⇒ 3×3 整个不可用。
     */
    public static int menuIndexOf(FaceKind k, int slot) {
        if (k == FaceKind.CRAFTING) {
            return slot == 9 ? 0 : slot + 1;
        }
        return slot;
    }

    /** 「独立结果格」的虚拟格号（-1 = 没有独立结果格：附着型 / 原地型 / 支付型）。 */
    public static int resultSlot(FaceKind k) {
        return switch (k) {
            case CRAFTING -> 9;
            case GRINDSTONE, CARTOGRAPHY, ANVIL -> 2;
            case SMITHING, LOOM -> 3;
            case STONECUTTER -> 1;
            case ENCHANT -> 0;        // 原地：成品回到槽 0（输入本身被改造）
            case INV -> Craft2x2Ops.RESULT_SLOT;   // ★ 随身 2×2：结果格 = 4（2026-10-20 与工位统一）
            case BEACON, LECTERN -> -1;
            default -> -1;
        };
    }

    /** 每个格是干嘛的（给 sense / 帮助用）。 */
    public static String slotHint(FaceKind k, int slot) {
        return switch (k) {
            case ANVIL -> switch (slot) {
                case 0 -> "要修 / 要附魔的物品";
                case 1 -> "材料（同类物品 或 附魔书）";
                case 2 -> "结果";
                default -> "";
            };
            case GRINDSTONE -> switch (slot) {
                case 0, 1 -> "要磨的物品（放两件同类可合并耐久）";
                case 2 -> "结果";
                default -> "";
            };
            case STONECUTTER -> switch (slot) {
                case 0 -> "原料（石头这类）";
                case 1 -> "结果（select index=N 选产物）";
                default -> "";
            };
            case ENCHANT -> switch (slot) {
                case 0 -> "要附魔的物品（成品原地回此格）";
                case 1 -> "青金石（select index=N 选档位时消耗）";
                default -> "";
            };
            case SMITHING -> switch (slot) {
                case 0 -> "锻造模板";
                case 1 -> "底材（要升级的装备）";
                case 2 -> "加成材料";
                case 3 -> "结果";
                default -> "";
            };
            case CRAFTING -> slot <= 8 ? ("合成格 " + (slot / 3 + 1) + "行" + (slot % 3 + 1) + "列") : "结果";
            case CARTOGRAPHY -> switch (slot) {
                case 0 -> "地图";
                case 1 -> "附加（纸 / 玻璃板 / 空地图）";
                case 2 -> "结果";
                default -> "";
            };
            case LOOM -> switch (slot) {
                case 0 -> "旗帜";
                case 1 -> "染料";
                case 2 -> "图案（旗帜图案）";
                case 3 -> "结果";
                default -> "";
            };
            case BEACON -> slot == 0 ? "支付格（下界合金锭 / 铁金绿钻石 / 海晶等）" : "";
            case LECTERN -> slot == 0 ? "书" : "";
            default -> "";
        };
    }

    // ────────────────────────── 打开工位 ──────────────────────────

    /**
     * 打开一个工位（虚拟槽 + 建 Menu）。
     *
     * @throws CmdException 这个方块不是已知工位
     */
    public static Session openStation(EntityMaid maid, BlockPos pos, FaceKind kind) {
        if (!isStation(kind)) {
            throw new CmdException(CmdException.BAD_STATE,
                    "这个方块不是工位（" + kind.wire + "）",
                    "工位类：工作台 / 铁砧 / 切石机 / 附魔台 / 砂轮 / 锻造台 / 制图台 / 织布机 / 信标 / 讲台");
        }
        Session s = new Session();
        s.kind = kind;
        s.pos = pos;
        // ① 虚拟槽（内存权威；可选从 NBT 恢复）
        Map<Integer, ItemStack> restored = loadVirtual(maid, kind);
        for (Map.Entry<Integer, ItemStack> e : restored.entrySet()) {
            s.virtual.put(e.getKey(), e.getValue());
        }
        // ② 建 Menu 并挂到 session —— select 要靠它（切石机 / 附魔台的选项都在 menu 里）
        AbstractContainerMenu menu = null;
        try {
            menu = buildMenu(maid, pos, kind);
        } catch (Throwable t) {
            MaidCliMod.LOG.warn("[MaidCLI] 建工位 Menu 失败（{}）：{}", kind.wire, t.toString());
        }
        s.menu = menu;
        if (menu != null) {
            pushInputs(s);        // 把恢复的料喂进 menu（这样 select / 读结果格才正确）
        }
        // ③ 一个 IItemHandler 视图 ⇒ take/put/move/sort 全自动支持
        s.container = handler(s);
        s.blockEntity = maid.level().getBlockEntity(pos);
        MaidCliMod.LOG.info("[MaidCLI] 打开工位 {} @{}（虚拟 {} 格，menu={}）",
                kind.wire, pos.toShortString(), slotCount(kind), menu == null ? "无" : menu.getClass().getSimpleName());
        return s;
    }

    /** 凭空造对应的原版 Menu（构造只要求一个 PlayerInventory —— 用假玩家的）。 */
    private static AbstractContainerMenu buildMenu(EntityMaid maid, BlockPos pos, FaceKind kind) {
        var access = ContainerLevelAccess.create(maid.level(), pos);
        MaidFakePlayer fakeForMenu = MaidFakePlayer.of(maid);
        // ★ 附魔台的三档 cost 是按【玩家经验等级】算的（EnchantmentMenu 内部用 playerInv.player.experienceLevel）
 // 假玩家默认 0 级 ⇒ 三档全是 0 ⇒ clickMenuButton 里 `if (cost <= 0) return false` 直接拒
 // ⇒ 先给假玩家一个够高的等级，档位才有值（实测踩到：select index=0 被菜单拒）
        try {
            if (fakeForMenu.experienceLevel < 30) {
                fakeForMenu.setExperienceLevels(30);
            }
            // ★ 旧实现两条附魔路径都设了创造模式（StationOps:572 / StationExecute:457）
 // javap 结论：它不影响档位计算，只影响 clickMenuButton 里「青金石/经验够不够」的守卫
 // ⇒ 设上以后"扣料由原版内部完成"，跟旧实测口径一致
            fakeForMenu.getAbilities().instabuild = true;
        } catch (Throwable ignored) {
        }
        var inv = fakeForMenu.getInventory();
        return switch (kind) {
            case ANVIL -> new AnvilMenu(0, inv, access);
            case GRINDSTONE -> new GrindstoneMenu(0, inv, access);
            case SMITHING -> new SmithingMenu(0, inv, access);
            case STONECUTTER -> new StonecutterMenu(0, inv, access);
            case ENCHANT -> new EnchantmentMenu(0, inv, access);
            case CRAFTING -> new CraftingMenu(0, inv, access);
            case CARTOGRAPHY -> new CartographyTableMenu(0, inv, access);
            case LOOM -> new LoomMenu(0, inv, access);
            case BEACON -> new BeaconMenu(0, new SimpleContainer(1));
            case LECTERN -> new LecternMenu(0);
            default -> null;
        };
    }

    /** 把 virtual 的输入格喂进 Menu，并让 Menu 重算结果。 */
    private static void pushInputs(Session s) {
        AbstractContainerMenu m = s.menu;
        if (m == null) {
            return;
        }
        for (int i : inputSlots(s.kind)) {
            if (i >= m.slots.size()) {
                continue;
            }
            ItemStack v = s.virtual.get(i);
            try {
                m.getSlot(menuIndexOf(s.kind, i)).set(v == null ? ItemStack.EMPTY : v.copy());
            } catch (Throwable t) {
                MaidCliMod.LOG.debug("[MaidCLI] 喂工位槽 {} 失败: {}", i, t.toString());
            }
        }
        recompute(s);
    }

    /** 触发原版「槽位变了 ⇒ 重算结果」。 */
    private static void recompute(Session s) {
        AbstractContainerMenu m = s.menu;
        if (m == null || m.slots.isEmpty()) {
            return;
        }
        try {
            m.slotsChanged(m.getSlot(menuIndexOf(s.kind, 0)).container);
        } catch (Throwable t) {
 // ★：原先是 debug 级别 ⇒ 异常被静默吞掉，工作台"料进去了却算不出产物"查了很久。
 // 重算失败是功能性问题，必须 warn。
            MaidCliMod.LOG.warn("[MaidCLI] 工位重算失败（{}）：{}", s.kind.wire, t.toString(), t);
        }
 // 【临时诊断 】工作台 3×3 结果算不出来，打印 menu 侧真实内容
    }

    // ────────────────────────── 执行 / 取产物（收口） ──────────────────────────

    /**
     * ★ <b>取产物</b>：把 Menu 里的结果读到 virtual，并<b>消耗输入</b>（一次原子收口）。
     *
     * <p>调用时机：{@code select index=N}（{@code clickMenuButton}）成功之后；
     * 或者铁砧这类没有按钮的工位在改名输入后直接调。
     *
     * <p><b>守恒</b>：产出前 virtual 里只有输入（关会话原样退回）；产出后 virtual 里只剩成品
     * （输入各扣 1）。<b>绝不在读结果时残留半成品</b>。
     *
     * @return 结果描述（给 history）
     */
    // ══════════════ 附魔台档位机制（★ 整段照抄旧 StationOps，实测通过）══════════════
    //
    // 为什么不能只"喂料 + slotsChanged"：原版 EnchantmentMenu.slotsChanged 里有
 // 「物品没变就跳过」的检测 —— 在【同一个 menu】上反复换种子会被它拦住，
 // 表现就是 costs 保持初始值 0 ⇒ clickMenuButton 里 `if (cost <= 0) return false` ⇒
 // 报"这个界面不接受 index=0"（实测踩了两轮）。
    // 旧代码的解法：① 每次【新建 menu】再喂料 ② 设附魔种子 ③ 档位缓存（保证"看档/选档"一致）。

    private static final Map<String, int[][]> TIER_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** 刷新 N 次后的种子（确定性链，同一个 refresh 永远算同一个种子）。 */
    private static int seedAfterRefresh(int baseSeed, int refresh) {
        int s = baseSeed;
        for (int i = 0; i < refresh; i++) {
            s = new java.util.Random(s).nextInt();
        }
        return s;
    }

    /** 设种子 + 清空再喂料触发两次重算（照抄）。 */
    private static void seedAndRefill(net.minecraft.world.inventory.EnchantmentMenu menu,
                                      MaidFakePlayer fp, ItemStack input, int baseSeed, int refresh) {
        fp.setEnchantSeed(seedAfterRefresh(baseSeed, refresh));
        menu.getSlot(0).set(ItemStack.EMPTY);
        menu.slotsChanged(menu.getSlot(0).container);
        menu.getSlot(0).set(input);
        menu.slotsChanged(menu.getSlot(0).container);
    }

    /**
     * 取这一档的结果：有缓存就用缓存（写进 menu 的三个数组），没有就算一次然后存下来。
     * <p>⇒「看档位」和「选档位」（甚至多次调用）看到的都是同一批魔咒 ✓
     */
    public static net.minecraft.world.inventory.EnchantmentMenu ensureTiers(
            net.minecraft.world.inventory.EnchantmentMenu menu,
            MaidFakePlayer fp, ItemStack input, BlockPos pos,
            EntityMaid maid, int baseSeed, int refresh, net.minecraft.server.level.ServerLevel level) {
        String key = maid.getUUID() + "|" + net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(input.getItem()) + "|" + input.getComponents().hashCode() + "|"
                + pos.asLong() + "|" + refresh;
        int[][] cached = TIER_CACHE.get(key);
        if (cached == null) {
            // ★ 每次【新建】一个 menu 再喂料（原版有"物品没变就跳过"，同一 menu 反复换种子会被拦）
            menu = new net.minecraft.world.inventory.EnchantmentMenu(0, fp.getInventory(),
                    net.minecraft.world.inventory.ContainerLevelAccess.create(level, pos));
            seedAndRefill(menu, fp, input, baseSeed, refresh);
            int n = menu.costs.length;
            cached = new int[n][3];
            for (int i = 0; i < n; i++) {
                cached[i][0] = menu.costs[i];
                cached[i][1] = menu.enchantClue[i];
                cached[i][2] = menu.levelClue[i];
            }
            TIER_CACHE.put(key, cached);
        } else {
            for (int i = 0; i < cached.length && i < menu.costs.length; i++) {
                menu.costs[i] = cached[i][0];
                menu.enchantClue[i] = cached[i][1];
                menu.levelClue[i] = cached[i][2];
            }
            menu.getSlot(0).set(input);
        }
        return menu;
    }

    /**
     * 读「这个工位界面有哪些可选项」—— {@code sense session} 的 {@code options}。
     *
     * <p>切石机 = 当前输入能切出的产物列表；附魔台 = 三档（cost / clue）。
     * <p>其它工位没有"选项"概念（铁砧 / 砂轮放料自动出结果）⇒ 返回空列表。
     *
     * <p>⚠️ 选项依赖输入：输入槽空着时切石机返回空列表（和玩家看到的一样）。
     */
    /**
     * 魔咒数字 id → 名字（旧 {@code enchantName} 原样）。
     * <p>⚠️ 1.21.1 的附魔是<b>数据驱动</b>的 → 走 {@code RegistryAccess} 的
     * {@code Registries.ENCHANTMENT}，**不是** {@code BuiltInRegistries}（那里没有 ENCHANTMENT 字段）。
     */
    public static String enchantName(int id) {
        if (id < 0) {
            return "-";
        }
        try {
            var srv = MaidCliMod.server();
            if (srv == null) {
                return "#" + id;
            }
            var reg = srv.registryAccess()
                    .registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
            var e = reg.byId(id);
            if (e == null) {
                return "#" + id;
            }
            var key = reg.getKey(e);
            return key == null ? ("#" + id) : key.getPath();
        } catch (Throwable t) {
            return "#" + id;
        }
    }

    public static java.util.List<Map<String, Object>> readOptions(Session s) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (s == null || s.menu == null) {
            return out;
        }
        try {
            if (s.menu instanceof StonecutterMenu sm) {
                net.minecraft.server.MinecraftServer srv = MaidCliMod.server();
                if (srv == null) {
                    return out;
                }
                var recipes = sm.getRecipes();
                for (int i = 0; i < Math.min(recipes.size(), 64); i++) {
                    ItemStack result = recipes.get(i).value().getResultItem(srv.registryAccess());
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("index", i);
                    one.put("item", BuiltInRegistries.ITEM.getKey(result.getItem()).toString());
                    one.put("count", result.getCount());
                    one.put("name", result.getHoverName().getString());
                    out.add(one);
                }
            } else if (s.menu instanceof EnchantmentMenu em) {
                for (int i = 0; i < 3; i++) {
                    int cost = em.costs[i];
                    if (cost <= 0) {
                        continue;      // 0 = 这一档不可用
                    }
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("index", i);
                    one.put("cost", cost);
                    one.put("clue", em.enchantClue[i]);
                    one.put("ench_name", enchantName(em.enchantClue[i]));
                    one.put("level_clue", em.levelClue[i]);
                    out.add(one);
                }
            }
        } catch (Throwable t) {
            MaidCliMod.LOG.debug("[MaidCLI] 读界面选项失败: {}", t.toString());
        }
        return out;
    }

    public static Map<String, Object> pullResult(Session s) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (s == null || s.menu == null) {
            out.put("ok", false);
            out.put("message", "这个工位没有可执行的界面（menu 未建）");
            return out;
        }
        // 铁砧 / 锻造台：结果不是 clickMenuButton 触发的，显式 createResult 一次
        try {
            if (s.menu instanceof AnvilMenu am) {
                am.createResult();
            } else if (s.menu instanceof SmithingMenu sm) {
                sm.createResult();
            }
        } catch (Throwable ignored) {
        }

        // ① 原地型（附魔台）：成品回到槽 0，消耗青金石（槽 1）
        if (s.kind == FaceKind.ENCHANT) {
            ItemStack after = s.menu.getSlot(0).getItem();
            if (after.isEmpty()) {
                out.put("ok", false);
                out.put("message", "附魔没成功（那一档对这个物品无效）—— 料原样留着");
                return out;
            }
            ItemStack product = after.copy();
            s.virtual.put(0, product);
            // ★ 青金石：原版 clickMenuButton 内部【已经】按档位扣过了（第 N 档 shrink(N+1) 个）
 // ⇒ 这里【只同步 virtual 的实际剩余】，绝不能再清空/再扣。
 // ⚠️ 原来直接 put(1, EMPTY) 把整格抹了 —— 实测放 16 个青金石、点一次附魔后【全没了】
 // （原版只该消耗 1~3 个）⇒ 物品守恒 bug（自测抓到）
            ItemStack lapisLeft = s.menu.getSlot(1).getItem();
            s.virtual.put(1, lapisLeft.isEmpty() ? ItemStack.EMPTY : lapisLeft.copy());
            out.put("ok", true);
            out.put("got", desc(product));
            out.put("message", "附魔台：⇒ " + desc(product) + "（成品在 box:0，青金石已消耗）");
            return out;
        }

        int r = resultSlot(s.kind);
        if (r < 0 || r >= s.menu.slots.size()) {
            out.put("ok", false);
            out.put("message", s.kind.wire + " 没有独立结果格（支付 / 翻页型）");
            return out;
        }
        ItemStack res = s.menu.getSlot(menuIndexOf(s.kind, r)).getItem();
        if (res.isEmpty()) {
            MaidCliMod.LOG.debug("[MaidCLI] {} 结果槽（menu[{}]）为空 ⇒ 不产出。virtual={}",
                    s.kind.wire, menuIndexOf(s.kind, r), s.virtual);
            out.put("ok", false);
            out.put("message", "算不出结果（料不匹配 / 没选档位）—— 料原样留着（一样没动）");
            return out;
        }
        ItemStack product = res.copy();
        s.virtual.put(r, product);
        MaidCliMod.LOG.debug("[MaidCLI] {} 产出={} ⇒ virtual[{}]；扣料前 virtual={}",
                s.kind.wire, product, r, s.virtual);
        // 消耗输入（各扣 1）—— 和原版一致
        for (int i : inputSlots(s.kind)) {
            if (i == r) {
                continue;
            }
            ItemStack v = s.virtual.get(i);
            if (v != null && !v.isEmpty()) {
                v.shrink(1);
                if (v.isEmpty()) {
                    s.virtual.put(i, ItemStack.EMPTY);
                }
                int mi = menuIndexOf(s.kind, i);
                if (mi >= 0 && mi < s.menu.slots.size()) {
                    s.menu.getSlot(mi).set(v.isEmpty() ? ItemStack.EMPTY : v.copy());
                }
            }
        }
        recompute(s);
        out.put("ok", true);
        out.put("got", desc(product));
        out.put("message", "产出 " + desc(product) + "（在 box:" + r + "；料已消耗）");
        return out;
    }

    // ────────────────────────── IItemHandler 视图 ──────────────────────────

    /**
     * ★ 把工位的虚拟槽包成 {@link IItemHandlerModifiable} ——
     * {@code SlotOps.take/put/move} 只认这个接口，所以工位<b>自动支持全部槽位操作</b>。
     *
     * <p>读返回 {@link Session#virtual} 里的<b>活引用</b>（{@code take} 的 {@code split} 会原地改它，
     * 就和真容器一致）；写立刻落进 virtual（并可同步进 Menu 重算）。
     */
    private static IItemHandlerModifiable handler(Session s) {
        final int n = slotCount(s.kind);
        return new IItemHandlerModifiable() {
            @Override
            public void setStackInSlot(int slot, ItemStack stack) {
                if (slot < 0 || slot >= n) {
                    throw new IllegalArgumentException("工位槽 " + slot + " 越界（只有 " + n + " 格）");
                }
                s.virtual.put(slot, stack == null ? ItemStack.EMPTY : stack);
                pushInputs(s);
            }

            @Override
            public int getSlots() {
                return n;
            }

            /**
             * ★ 原版规则校验：直接问 Menu 里那个 Slot。
             * <p>结果格用的是原版 ResultSlot ⇒ {@code mayPlace()} 恒 false ⇒ 自动拒绝往结果格塞东西。
             * <p>工位各格（铁砧的材料格、附魔台的书架格…）也是原版 Slot 实现 ⇒ 规则天然一致，
             * 不需要我们维护任何黑白名单。
             */
            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                if (stack == null || stack.isEmpty() || slot < 0 || slot >= n) {
                    return false;
                }
                // ★ 结果格永远只读 —— 用我们自己的索引判断，不依赖 Menu 的槽位顺序
 // （CraftingMenu 的结果槽在 menu[0]，不这样判会误把材料格当结果格）
                if (slot == resultSlot(s.kind)) {
                    return false;
                }
                try {
                    if (s.menu != null) {
                        int mi = menuIndexOf(s.kind, slot);
                        if (mi >= 0 && mi < s.menu.slots.size()) {
                            return s.menu.getSlot(mi).mayPlace(stack);
                        }
                    }
                } catch (Throwable ignored) {
                }
                return true;
            }

            @Override
            public ItemStack getStackInSlot(int slot) {
                if (slot < 0 || slot >= n) {
                    return ItemStack.EMPTY;
                }
                ItemStack v = s.virtual.get(slot);
                return v == null ? ItemStack.EMPTY : v;
            }

            @Override
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                if (slot < 0 || slot >= n || stack == null || stack.isEmpty()) {
                    return stack;
                }
                ItemStack there = getStackInSlot(slot);
                if (there.isEmpty()) {
                    if (!simulate) {
                        setStackInSlot(slot, stack.copy());
                    }
                    return ItemStack.EMPTY;
                }
                if (ItemStack.isSameItemSameComponents(there, stack)) {
                    int can = Math.min(stack.getCount(), there.getMaxStackSize() - there.getCount());
                    if (can <= 0) {
                        return stack;
                    }
                    if (!simulate) {
                        there.grow(can);
                        setStackInSlot(slot, there);
                    }
                    ItemStack rest = stack.copy();
                    rest.shrink(can);
                    return rest;
                }
                return stack;
            }

            @Override
            public ItemStack extractItem(int slot, int amount, boolean simulate) {
                if (slot < 0 || slot >= n || amount <= 0) {
                    return ItemStack.EMPTY;
                }
                ItemStack there = getStackInSlot(slot);
                if (there.isEmpty()) {
                    return ItemStack.EMPTY;
                }
                int take = Math.min(amount, there.getCount());
                ItemStack outStack = there.copyWithCount(take);
                if (!simulate) {
                    there.shrink(take);
                    setStackInSlot(slot, there.isEmpty() ? ItemStack.EMPTY : there);
                }
                return outStack;
            }

            @Override
            public int getSlotLimit(int slot) {
                return 64;
            }
        };
    }

    // ────────────────────────── 虚拟槽的 NBT 持久化（可选） ──────────────────────────

    /**
     * 读某个种类工位的虚拟槽。
     * <p>⚠️ 默认实现里 {@link Session#virtual} 才是权威；这里只是给「想扛重启」的场景备用。
     */
    public static Map<Integer, ItemStack> loadVirtual(EntityMaid maid, FaceKind kind) {
        Map<Integer, ItemStack> out = new LinkedHashMap<>();
        if (MaidCliExtension.TAG == null) {
            return out;
        }
        try {
            CompoundTag data = maid.getData(MaidCliExtension.TAG);
            if (data == null || !data.contains(FIELD)) {
                return out;
            }
            CompoundTag st = data.getCompound(FIELD);
            String key = keyOf(kind);
            if (!st.contains(key)) {
                return out;
            }
            int n = slotCount(kind);
            ListTag list = st.getList(key, Tag.TAG_COMPOUND);
            var reg = MaidCliMod.server() == null ? null : MaidCliMod.server().registryAccess();
            if (reg == null) {
                return out;
            }
            for (int i = 0; i < list.size() && i < n; i++) {
                CompoundTag e = list.getCompound(i);
                if (e.isEmpty()) {
                    continue;
                }
                ItemStack s = ItemStack.parseOptional(reg, e);
                if (!s.isEmpty()) {
                    out.put(i, s);
                }
            }
        } catch (Throwable t) {
            MaidCliMod.LOG.debug("[MaidCLI] 读工位虚拟槽失败: {}", t.toString());
        }
        return out;
    }

    /** 写某个种类工位的虚拟槽进她的 NBT（全空就把记录删掉）。 */
    public static void saveVirtual(EntityMaid maid, FaceKind kind, Map<Integer, ItemStack> v) {
        if (MaidCliExtension.TAG == null) {
            return;
        }
        var server = MaidCliMod.server();
        if (server == null) {
            return;
        }
        try {
            CompoundTag old = maid.getData(MaidCliExtension.TAG);
            CompoundTag data = old == null ? new CompoundTag() : old.copy();
            CompoundTag st = data.contains(FIELD) ? data.getCompound(FIELD) : new CompoundTag();
            String key = keyOf(kind);
            ListTag list = new ListTag();
            boolean any = false;
            int n = slotCount(kind);
            for (int i = 0; i < n; i++) {
                ItemStack s = v.get(i);
                if (s == null || s.isEmpty()) {
                    list.add(new CompoundTag());
                } else {
                    list.add(s.save(server.registryAccess()));
                    any = true;
                }
            }
            if (any) {
                st.put(key, list);
            } else {
                st.remove(key);
            }
            data.put(FIELD, st);
            maid.setData(MaidCliExtension.TAG, data);
        } catch (Throwable t) {
            MaidCliMod.LOG.debug("[MaidCLI] 写工位虚拟槽失败: {}", t.toString());
        }
    }

    /**
     * 清掉某个会话所在工位的 NBT 快照。
     * <p>⚠️ <b>只有在启用了 {@link #saveVirtual} 持久化时才需要在
     * {@code ContainerOps.close} 里调它</b>（否则下次 open 会把已退回背包的料又载进来）。
     */
    public static void clearVirtualNbt(Session s) {
        if (s == null || s.maidRef == null) {
            return;
        }
        EntityMaid maid = s.maidRef.maid();
        if (maid == null) {
            return;
        }
        saveVirtual(maid, s.kind, new LinkedHashMap<>());
    }

    // ────────────────────────── 持久化：虚拟槽 + 鼠标槽 ──────────────────────────

    /** NBT 里鼠标槽的字段名（和 Station 并列，都挂在女仆的 TAG 下） */
    private static final String CURSOR_FIELD = "Cursor";

    /**
     * 把当前会话的<b>虚拟槽 + 鼠标槽</b>写进她 NBT（每次变动后调一次）。
     *
     * <p>★ 补：原来 {@code saveVirtual} 只有"写空"的调用点，
     * 等于虚拟槽从来没被持久化 —— 服务端崩溃时工位上摆的料、以及<b>鼠标槽里已经拿起来的物品</b>
     * 会直接丢（旧版 {@code StationStore} + {@code CursorSlot} 都是每次变化就写 NBT）。
     */
    public static void persistSession(Session s) {
        if (s == null || s.maidRef == null) {
            return;
        }
        EntityMaid maid = s.maidRef.maid();
        if (maid == null) {
            return;
        }
        saveVirtual(maid, s.kind, s.virtual);
        saveCursor(maid, s.cursor);
    }

    /** 写鼠标槽快照（空栈 = 删除字段） */
    public static void saveCursor(EntityMaid maid, ItemStack stack) {
        if (MaidCliExtension.TAG == null) {
            return;
        }
        var server = MaidCliMod.server();
        if (server == null) {
            return;
        }
        try {
            CompoundTag old = maid.getData(MaidCliExtension.TAG);
            CompoundTag data = old == null ? new CompoundTag() : old.copy();
            if (stack == null || stack.isEmpty()) {
                data.remove(CURSOR_FIELD);
            } else {
                data.put(CURSOR_FIELD, stack.save(server.registryAccess()));
            }
            maid.setData(MaidCliExtension.TAG, data);
        } catch (Throwable t) {
            MaidCliMod.LOG.debug("[MaidCLI] 写鼠标槽快照失败: {}", t.toString());
        }
    }

    /**
     * 读鼠标槽快照（崩溃后残留的；正常关会话会清掉，所以正常情况读不到）。
     * @return null / 空 = 没有残留
     */
    public static ItemStack loadCursor(EntityMaid maid) {
        if (MaidCliExtension.TAG == null) {
            return ItemStack.EMPTY;
        }
        try {
            CompoundTag data = maid.getData(MaidCliExtension.TAG);
            if (data == null || !data.contains(CURSOR_FIELD)) {
                return ItemStack.EMPTY;
            }
            var server = MaidCliMod.server();
            if (server == null) {
                return ItemStack.EMPTY;
            }
            return ItemStack.parseOptional(server.registryAccess(), data.getCompound(CURSOR_FIELD));
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * 跨会话清理：把 <b>所有</b> 工位虚拟槽 NBT 残留退回背包并删记录
     * （她每次被加载 / 服务端停止时调，兜「残」）。
     */
    public static int purgeVirtual(EntityMaid maid) {
        if (MaidCliExtension.TAG == null) {
            return 0;
        }
        int n = 0;
        try {
            CompoundTag data = maid.getData(MaidCliExtension.TAG);
            if (data == null || !data.contains(FIELD)) {
                return 0;
            }
            CompoundTag st = data.getCompound(FIELD);
            for (FaceKind k : STATIONS) {
                String key = keyOf(k);
                if (!st.contains(key)) {
                    continue;
                }
                Map<Integer, ItemStack> v = loadVirtual(maid, k);
                for (ItemStack item : v.values()) {
                    if (item != null && !item.isEmpty()) {
                        ContainerOps.giveBack(maid, item);
                        n++;
                    }
                }
                saveVirtual(maid, k, new LinkedHashMap<>());
            }
        } catch (Throwable t) {
            MaidCliMod.LOG.debug("[MaidCLI] 清理工位虚拟槽失败: {}", t.toString());
        }
        return n;
    }

    private static String keyOf(FaceKind k) {
        return "kind:" + k.wire;
    }

    private static String desc(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return "空";
        }
        return BuiltInRegistries.ITEM.getKey(s.getItem()).toString() + " ×" + s.getCount();
    }
}
