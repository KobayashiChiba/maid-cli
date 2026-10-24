package com.xiaofeng.maidcli.ops;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.MaidCliMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 物品 / 手上的实现（★ 只给 {@code Action.tick()} 调）。
 *
 * <p>⚠️⚠️ <b>这里最要命的一条：物品守恒</b> ——
 * <pre>
 * 玩家："拿在手上" = hotbar 里选中某一格 → 物品没动过
 * 女仆："拿在手上" = 真的把物品【搬进】主手槽 → 背包里就没了
 * ⇒ 对女仆，"搬手上"是一次【移动】操作，必须按移动来写
 * </pre>
 * 正解是「<b>真交换</b>」：物品任何时刻都只有一份、都在某个槽里 ⇒ 结构上不可能丢。
 * （早期几版"复制+销毁""失败后按差异补"都没兜住，别重走。）
 */
public final class ItemOps {

    private ItemOps() {
    }

    /**
     * 把一份物品塞回她的背包。
     * <p>⚠️⚠️ <b>必须用 {@code getAvailableBackpackInv()}（只背包）</b>，
     * <p><b>绝不能用 {@code getAvailableInv(false)}</b> ——
     * 后者"手在末尾 2 格"，找空槽会落到手槽，紧跟的 {@code setItemInHand} 会<b>覆盖丢工具</b>。
     */
    public static void stash(EntityMaid maid, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        var left = net.neoforged.neoforge.items.ItemHandlerHelper
                .insertItemStacked(maid.getAvailableBackpackInv(), stack.copy(), false);
        if (!left.isEmpty()) {
            maid.spawnAtLocation(left);      // 塞不下就掉地上，绝不销毁
        }
    }

    /** 在"手在前"的完整可及背包里找某件物品，返回槽号（-1 = 没有） */
    public static int findSlot(EntityMaid maid, String itemId) {
        var inv = maid.getAvailableInv(true);
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            if (id.equals(itemId) || id.endsWith(":" + itemId)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * ★ 把背包里某件物品搬到主手（真交换），返回<b>原来的主手物品</b>（收尾时还回去）。
     *
     * @param itemId null / 空 = 不换（用现有的主手）
     * @param count  搬几个
     * @return 原主手物品（null = 本来就是空手）
     */
    public static ItemStack takeToMainHand(EntityMaid maid, String itemId, int count) {
        if (itemId == null || itemId.isBlank()) {
            return null;                       // 不指定 ⇒ 用主手现有的
        }
        int slot = findSlot(maid, itemId);
        if (slot < 0) {
            throw new com.xiaofeng.maidcli.transport.CmdException(
                    com.xiaofeng.maidcli.transport.CmdException.NO_ITEM,
                    "她身上没有 " + itemId,
                    "先 宏 give 反过来给她，或者用 sense inventory 看家底");
        }
        StringBuilder id = new StringBuilder();
        ItemStack backup = null;
        var inv = maid.getAvailableInv(true);
        if (slot == 0) {
            // 本来就在主手 ⇒ 不用换
            return null;
        }
        if (slot == 1) {
            // 目标在副手：主手 ↔ 副手 互换
            ItemStack off = inv.getStackInSlot(1);
            ItemStack main = maid.getMainHandItem();
            maid.setItemInHand(InteractionHand.OFF_HAND, main.copy());
            maid.setItemInHand(InteractionHand.MAIN_HAND, off.copy());
            return null;
        }
        // ① 备份并清空主手（物品进背包，不销毁）
        ItemStack cur = maid.getMainHandItem();
        if (!cur.isEmpty()) {
            backup = cur.copy();
            stash(maid, cur);
        }
        maid.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        // ② 从背包【原子地】取出目标（split/extractItem 是原子的，不存在中间态）
        ItemStack want = inv.extractItem(slot, count, false);
        if (want.isEmpty()) {
            // 理论上不会发生（刚找到的），兜底把备份还回去
            if (backup != null) {
                maid.setItemInHand(InteractionHand.MAIN_HAND, backup);
            }
            throw new com.xiaofeng.maidcli.transport.CmdException(
                    com.xiaofeng.maidcli.transport.CmdException.NO_ITEM, "从背包取 " + itemId + " 失败");
        }
        maid.setItemInHand(InteractionHand.MAIN_HAND, want);
        if (id.length() == 0) {
            MaidCliMod.LOG.debug("[MaidCLI] 换手：{}", itemId);
        }
        return backup;
    }

    /** 收尾：把备份的主手物品还回去（原来是空手就还原成空手） */
    public static void restoreMainHand(EntityMaid maid, ItemStack backup) {
        if (backup == null) {
            return;
        }
        ItemStack now = maid.getMainHandItem();
        if (!now.isEmpty()) {
            stash(maid, now);
        }
        maid.setItemInHand(InteractionHand.MAIN_HAND, backup);
    }

    /** 工具打分（break 用：能正确掉落 +1000，再比挖掘速度） */
    public static float toolScore(ItemStack stack, BlockState state) {
        if (stack == null || stack.isEmpty()) {
            return 1.0f;              // ★ 旧实现：空手 = 基础挖掘速度 1（不是 0）
        }
        return (stack.isCorrectToolForDrops(state) ? 1000f : 0f) + stack.getDestroySpeed(state);
    }

    /**
     * 自动换最优工具（<b>只用于 break</b>）。
     * <p>⚠️ 只在背包里找（{@code getAvailableBackpackInv}），别把主手那把也算候选。
     * <p>⚠️ 严格更高才换（平手不换，免得来回倒腾）。
     */
    public static void equipBestTool(EntityMaid maid, BlockState state) {
        var inv = maid.getAvailableBackpackInv();
        float cur = toolScore(maid.getMainHandItem(), state);
        int bestSlot = -1;
        float best = cur;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            float sc = toolScore(s, state);
            if (sc > best) {
                best = sc;
                bestSlot = i;
            }
        }
        if (bestSlot < 0) {
            return;
        }
        ItemStack tool = inv.extractItem(bestSlot, Integer.MAX_VALUE, false);
        if (tool.isEmpty()) {
            return;
        }
        ItemStack old = maid.getMainHandItem();
        maid.setItemInHand(InteractionHand.MAIN_HAND, tool);
        if (!old.isEmpty()) {
            ItemStack left = inv.insertItem(bestSlot, old, false);
            if (!left.isEmpty()) {
                maid.spawnAtLocation(left);   // 放不回原槽就掉地上（绝不吞）
            }
        }
    }

    /** 丢主手（原版按 Q） */
    public static ItemStack dropMainHand(EntityMaid maid, int count) {
        ItemStack hand = maid.getMainHandItem();
        if (hand.isEmpty()) {
            throw new com.xiaofeng.maidcli.transport.CmdException(
                    com.xiaofeng.maidcli.transport.CmdException.NO_ITEM, "她主手是空的，没得丢");
        }
        int n = count <= 0 ? hand.getCount() : Math.min(count, hand.getCount());
        ItemStack out = hand.split(n);
        if (hand.isEmpty()) {
            maid.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        maid.spawnAtLocation(out);
        return out;
    }

    /** 主手 ↔ 副手 互换（= 原版按 F） */
    public static void swapHands(EntityMaid maid) {
        ItemStack main = maid.getMainHandItem().copy();
        ItemStack off = maid.getOffhandItem().copy();
        maid.setItemInHand(InteractionHand.MAIN_HAND, off);
        maid.setItemInHand(InteractionHand.OFF_HAND, main);
    }

    /** 物品 id（给报告用） */
    public static String idOf(ItemStack s) {
        return s == null || s.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }
}
