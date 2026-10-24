package com.xiaofeng.maidcli.ops;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.MaidCliMod;
import com.xiaofeng.maidcli.engine.FaceKind;
import com.xiaofeng.maidcli.engine.Session;
import com.xiaofeng.maidcli.infra.MaidFakePlayer;
import com.xiaofeng.maidcli.transport.CmdException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 村民交易 —— <b>「虚拟界面」形式</b>（）。
 *
 * <p>★ 设计原因：村民交易不能照搬"把村民坐标当工位 pos"的做法，
 * 要按<b>【虚拟界面】</b>来做 —— 也就是<b>跟工位（{@link StationOps}）同一套路</b>：
 * <pre>
 *   open entity=&lt;村民id&gt;  →  建一个原版 {@link MerchantMenu}  →  用【虚拟槽】承载「支付格 / 结果格」
 *                          →  复用现有的 take/put/move 和 select 完成交易
 * </pre>
 *
 * <p><b>与工位（StationOps）的对应关系</b>
 * <ul>
 *   <li><b>入口</b>：工位 {@code open pos=} → {@code openStation(maid,pos,kind)}；
 *       村民 {@code open entity=} → {@link #openMerchant(EntityMaid,int)}</li>
 *   <li><b>界面种类</b>：{@code FaceKind.CRAFTING / ANVIL / …} ⇔ {@code FaceKind.MERCHANT}</li>
 *   <li><b>建 Menu</b>：{@code new AnvilMenu / StonecutterMenu / …} ⇔ {@code new MerchantMenu(0, inv, villager)}</li>
 *   <li><b>虚拟槽</b>：都用 {@code Session.virtual}（按格号）；村民固定 0=支付A、1=支付B、2=结果</li>
 *   <li><b>槽位视图</b>：包一个 {@code IItemHandlerModifiable}（村民见 {@link #handler}）⇒ take/put/move 自动支持</li>
 *   <li><b>成交</b>：工位 {@code select index=N} → {@code clickMenuButton} → {@code pullResult}；
 *       村民 {@code select index=N} → {@link #select}（MerchantMenu 没有按钮，见下）</li>
 *   <li><b>收口</b>：工位 {@code pullResult}（读结果 + 消耗输入）；
 *       村民 {@link #select}（扣支付物 + {@code notifyTrade} + 结果进结果格）</li>
 * </ul>
 *
 * <p><b>★ 为什么成交不能走 {@code clickMenuButton}</b>
 * 工位（切石机/附魔台）靠 {@code menu.clickMenuButton(fake, idx)} 选档。村民不行：
 * <p><b>原版 {@link MerchantMenu} 并不覆盖 {@code clickMenuButton}</b>，基类
 * {@code AbstractContainerMenu.clickMenuButton} 的实现是 {@code return false}（已用 javap 验字节码：
 * {@code iconst_0; ireturn}）⇒ 走那条路必被 {@code SlotAction.select} 当成「菜单自己拒了」报错。
 * 所以 {@code SlotAction.select} 对 {@code MERCHANT} 单独分流到 {@link #select}。
 * <p>（{@link MerchantMenu} 的原生交互其实是「把支付物放进槽 0/1 → 点结果槽 2 → onTake 结算」，
 * 那要求真的走槽位点击的事件链；我们按「虚拟界面」直接把结算收口到 {@code select}，更省也更稳。）
 *
 * <p><b>★★ 交易机制的几个注意点</b>
 * <ol>
 *   <li>{@code Merchant.getOffers()} → {@link MerchantOffers}（交易列表）；</li>
 *   <li>{@link MerchantOffer}：{@code baseCostA}（她给的）+ {@code costB}（可选第二样）+ {@code result}（换到的）+ uses/maxUses；</li>
 *   <li>成交三件套：{@code satisfiedBy(a,b)} 检查 / {@code take(a,b)} 扣物品 / {@code increaseUses()} 记次；</li>
 *   <li>★ {@code MerchantOffer.take(a,b)} 只做「检查 + shrink 扣物品」，<b>【不增加 uses】</b>
 *       （javap 验过：take 里只有 satisfiedBy + shrink，没有 increaseUses）；</li>
 *   <li>★ {@code AbstractVillager.notifyTrade(offer)} 内部<b>【自己就调 increaseUses()】</b>
 *       （javap 验过：notifyTrade 第一句就是 {@code offer.increaseUses()}）
 *       ⇒ 调用方<b>【不要】</b>再自己 increaseUses()，否则 uses 变双份
 *       （实测现象：做 1 笔交易，4/16 → 6/16，村民经验也翻倍）
 *       ⇒ <b>正解</b>：交给 notifyTrade 加；只有它抛异常（没加成）时才自己兜底加一次 ——
 *          见 {@link #select} 里的 {@code boolean recorded}。</li>
 *   <li>产物要用 recipe/assemble 的口径拿（带附魔等）—— {@code MerchantOffer.assemble()} 就是
 *       {@code result.copy()}（javap 验过），所以直接 {@code getResult().copy()} 即可；</li>
 *   <li>交易会涨村民经验 ⇒ 他会升级并自动解锁新交易（正常现象，别当 bug）。</li>
 * </ol>
 *
 * <p>⚠️ 虚拟槽默认走内存（{@link Session#virtual}）—— 守恒绝对安全：成交后支付物已扣、
 * 成品落在结果格；关会话时 {@code ContainerOps.close} 会遍历 {@code session.virtual}
 * 把【结果 + 支付余料】全部退回背包（塞不下掉地上）。<b>绝不吞、绝不凭空造</b>。
 */
public final class MerchantOps {

    private MerchantOps() {
    }

    // ────────────────────────── 槽号约定（对齐 MerchantMenu 的 0/1/2）──────────────────────────

    /** 支付格 A（她必须给的第一样）。 */
    public static final int PAY_A = 0;
    /** 支付格 B（有些交易要的第二样，可空）。 */
    public static final int PAY_B = 1;
    /** 结果格（成交后换到的东西）。 */
    public static final int RESULT = 2;
    /** 虚拟槽总数。 */
    public static final int SLOT_COUNT = 3;

    /** 这个界面种类是不是村民交易界面。 */
    public static boolean isMerchant(FaceKind k) {
        return k == FaceKind.MERCHANT;
    }

    // ────────────────────────── 打开村民界面 ──────────────────────────

    /**
     * 打开一个村民的交易界面（虚拟槽 + 建 {@link MerchantMenu}）。
     *
     * @throws CmdException 实体 id 找不到 / 不是村民 / 没有交易
     */
    public static Session openMerchant(EntityMaid maid, int entityId) {
        AbstractVillager villager = villagerOf(maid, entityId);
        Session s = new Session();
        s.kind = FaceKind.MERCHANT;
        s.entityId = entityId;          // 村民是实体 ⇒ 下次 select 靠它现找（不存悬空引用）
        s.pos = null;                   // ★ 村民不是方块，没有 pos（这正是旧代码要改掉的地方）
        // 建一个原版 MerchantMenu，挂到 session —— 和工位「凭空建 Menu」完全一致。
 // ⚠️ MerchantMenu 构造只读 villager + 假玩家背包，没有 setTradingPlayer 之类的副作用（javap 验过）。
        AbstractContainerMenu menu = null;
        try {
            menu = new MerchantMenu(0, MaidFakePlayer.of(maid).getInventory(), villager);
        } catch (Throwable t) {
            MaidCliMod.LOG.warn("[MaidCLI] 建村民 MerchantMenu 失败：{}", t.toString());
        }
        s.menu = menu;
        // 一个 IItemHandler 视图 ⇒ take/put/move 全自动支持（和工位一样）
        s.container = handler(s);
        MaidCliMod.LOG.info("[MaidCLI] 打开村民交易界面 #{}（{}，{} 笔交易；menu={}）",
                entityId, villager.getName().getString(),
                villager.getOffers() == null ? 0 : villager.getOffers().size(),
                menu == null ? "无" : menu.getClass().getSimpleName());
        return s;
    }

    /** 按实体 id 现找一个村民（她那一侧不存实体引用）。 */
    private static AbstractVillager villagerOf(EntityMaid maid, int entityId) {
        if (entityId <= 0) {
            throw CmdException.notFound("open entity= 要给村民的实体 id",
                    "open entity=<村民实体id>");
        }
        Entity e = maid.level().getEntity(entityId);
        if (e == null) {
            throw CmdException.notFound("找不到实体 #" + entityId + "（区块没加载 / 已经没了？）",
                    "先 sense nearby 看看村民的实体 id");
        }
        if (!(e instanceof AbstractVillager v)) {
            throw CmdException.badArg("实体 #" + entityId + " 不是村民（是 "
                    + e.getClass().getSimpleName() + "）—— open entity= 目前只认村民",
                    "村民交易：open entity=<村民实体id>");
        }
        if (v.getOffers() == null || v.getOffers().isEmpty()) {
            throw new CmdException(CmdException.BAD_STATE,
                    "这个村民没有交易（" + v.getName().getString() + "）",
                    "小村民 / 没职业 / 没上班的村民没有交易表");
        }
        return v;
    }

    // ────────────────────────── 成交（= 工位的 pullResult 收口）──────────────────────────

    /**
     * {@code select index=N} 的村民实现 —— <b>成交收口</b>。
     *
     * <p>步骤（对齐旧 {@code StationExecute.trade} 的机制，只是组织成虚拟界面）：
     * <ol>
     *   <li>取这笔 offer，查越界 / 用完；</li>
     *   <li>{@code satisfiedBy(支付A, 支付B)} 检查（先不动东西）；</li>
     *   <li>{@code offer.take(a,b)} 从虚拟支付格扣料（take 自己会 shrink）；</li>
     *   <li>{@code villager.notifyTrade(offer)} 记次 + 涨经验（★ 内部已 increaseUses，别再自己加）；</li>
     *   <li>成品 {@code offer.getResult().copy()} 落进【结果格 box:2】（关会话时退回背包）。</li>
     * </ol>
     *
     * @return 给 history 的结果（含 got / paid / uses / message）
     */
    public static Map<String, Object> select(EntityMaid maid, Session s, int idx) {
        if (s == null || s.kind != FaceKind.MERCHANT) {
            throw new CmdException(CmdException.BAD_STATE,
                    "当前界面不是村民交易界面", "先 open entity=<村民实体id>");
        }
        AbstractVillager villager = villagerOf(maid, s.entityId);
        MerchantOffers offers = villager.getOffers();
        if (idx < 0 || idx >= offers.size()) {
            throw CmdException.badArg("select index=" + idx + " 越界（这个村民有 "
                            + offers.size() + " 笔交易，范围 0~" + (offers.size() - 1) + "）",
                    "用 trade_list 看每笔「给什么 / 换什么」");
        }
        MerchantOffer offer = offers.get(idx);
        if (offer.isOutOfStock()) {
            throw new CmdException(CmdException.BAD_STATE,
                    "第 " + idx + " 笔交易已经用完了（需要村民补货 / 上班）",
                    "换一笔，或等村民补货");
        }
        // ① 支付物【从虚拟支付格拿】（和工位「摆什么看得见」一致）
        ItemStack a = orEmpty(s.virtual.get(PAY_A));
        ItemStack b = orEmpty(s.virtual.get(PAY_B));
        // ② 先检查够不够（用副本，不动东西）—— 原版 satisfiedBy 不改物品
        if (!offer.satisfiedBy(a.isEmpty() ? a : a.copy(), b.isEmpty() ? b : b.copy())) {
            throw new CmdException(CmdException.BAD_STATE,
                    "支付格上的东西不够这笔交易（它要 " + describeCost(offer) + "）",
                    "把要给的放进 box:0（第二样放 box:1），再 select index=" + idx);
        }
        // ③ 扣支付物：offer.take 只「检查 + shrink」，【不加 uses】（照抄旧代码）
        boolean ok = offer.take(a, b);
        if (!ok) {
            throw new CmdException(CmdException.INTERNAL,
                    "扣支付物失败（支付物检查过了却没扣动，异常）");
        }
        s.virtual.put(PAY_A, a.isEmpty() ? ItemStack.EMPTY : a);
        s.virtual.put(PAY_B, b.isEmpty() ? ItemStack.EMPTY : b);
        // 把支付格同步进 menu（让「界面上的槽」和虚拟槽一致；setSelectionHint 顺手重算结果槽）
        syncMenu(s, idx);
        // ④ 记次 + 通知：★ notifyTrade 内部【自己就 increaseUses()】⇒ 别重复加，
 // 只有它抛异常（没加成）时才自己兜底加一次（防 uses 记漏）。
        boolean recorded = false;
        try {
            villager.notifyTrade(offer);
            recorded = true;
        } catch (Throwable t) {
            MaidCliMod.LOG.warn("[MaidCLI] notifyTrade 失败（将兜底 increaseUses 一次）：{}", t.toString());
        }
        if (!recorded) {
            offer.increaseUses();
        }
        // ⑤ 成品 → 结果格（assemble 口径 = result.copy()，带附魔等）
        ItemStack got = offer.getResult().copy();
        s.virtual.put(RESULT, got);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("selected", idx);
        out.put("paid", describeCost(offer));
        out.put("got", desc(got));
        out.put("uses", offer.getUses() + "/" + offer.getMaxUses());
        out.put("message", "村民交易：用 " + describeCost(offer) + " 换了 " + desc(got)
                + "（成品在 box:" + RESULT + "；支付物已从支付格扣掉）");
        MaidCliMod.LOG.info("[MaidCLI] 村民交易：{}", out.get("message"));
        return out;
    }

    /** 把虚拟支付格同步进 menu 的槽 0/1，并让 menu 选到这一笔（重算结果槽）。 */
    private static void syncMenu(Session s, int idx) {
        if (!(s.menu instanceof MerchantMenu mm)) {
            return;
        }
        try {
            mm.getSlot(PAY_A).set(orEmpty(s.virtual.get(PAY_A)).copy());
            mm.getSlot(PAY_B).set(orEmpty(s.virtual.get(PAY_B)).copy());
            mm.setSelectionHint(idx);
        } catch (Throwable t) {
            MaidCliMod.LOG.debug("[MaidCLI] 同步村民 menu 失败: {}", t.toString());
        }
    }

    // ────────────────────────── 只读：交易表 ──────────────────────────

    /**
     * 列出村民的交易表（只读，给 {@code trade_list} 用）——
     * 每笔「给什么 / 换什么 / 用过几次 / 还做不做得起」。
     */
    public static Map<String, Object> list(EntityMaid maid, Session s) {
        if (s == null || s.kind != FaceKind.MERCHANT) {
            throw new CmdException(CmdException.BAD_STATE,
                    "trade_list 只对村民交易界面有效", "先 open entity=<村民实体id>");
        }
        AbstractVillager villager = villagerOf(maid, s.entityId);
        MerchantOffers offers = villager.getOffers();
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < offers.size(); i++) {
            MerchantOffer o = offers.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", i);
            m.put("give", describeCost(o));
            m.put("get", desc(o.getResult()));
            m.put("uses", o.getUses() + "/" + o.getMaxUses());
            m.put("can", !o.isOutOfStock());
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("villager", villager.getName().getString());
        out.put("offers", list);
        out.put("message", "村民（" + villager.getName().getString() + "）有 " + offers.size()
                + " 笔交易 —— select index=N 成交");
        return out;
    }

    // ────────────────────────── IItemHandler 视图（同工位套路）──────────────────────────

    /**
     * ★ 把村民的虚拟槽包成 {@link IItemHandlerModifiable} ——
     * {@code SlotOps.take/put/move} 只认这个接口 ⇒ 村民界面<b>自动支持全部槽位操作</b>。
     * <p>读返回 {@link Session#virtual} 里的<b>活引用</b>（take 的 split 会原地改它，和真容器一致）；
     * 写立刻落进 virtual。
     */
    private static IItemHandlerModifiable handler(Session s) {
        final int n = SLOT_COUNT;
        return new IItemHandlerModifiable() {
            @Override
            public void setStackInSlot(int slot, ItemStack stack) {
                if (slot < 0 || slot >= n) {
                    throw new IllegalArgumentException("村民槽 " + slot + " 越界（只有 " + n + " 格）");
                }
                s.virtual.put(slot, stack == null ? ItemStack.EMPTY : stack);
            }

            @Override
            public int getSlots() {
                return n;
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

            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return true;
            }
        };
    }

    // ────────────────────────── 小工具 ──────────────────────────

    private static ItemStack orEmpty(ItemStack s) {
        return s == null ? ItemStack.EMPTY : s;
    }

    /** 交易要付什么（ItemCost → "24×paper" / "1×emerald + 1×book"）。 */
    static String describeCost(MerchantOffer o) {
        StringBuilder sb = new StringBuilder();
        sb.append(o.getCostA().getCount()).append("×").append(path(o.getCostA()));
        ItemStack cb = o.getCostB();
        if (cb != null && !cb.isEmpty()) {
            sb.append(" + ").append(cb.getCount()).append("×").append(path(cb));
        }
        return sb.toString();
    }

    private static String path(ItemStack s) {
        return s == null || s.isEmpty() ? "空"
                : BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    private static String desc(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return "空";
        }
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath() + "×" + s.getCount();
    }
}
