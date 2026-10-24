package com.xiaofeng.maidcli.engine.actions;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.engine.Action;
import com.xiaofeng.maidcli.infra.MaidFakePlayer;
import com.xiaofeng.maidcli.ops.BlockOps;
import com.xiaofeng.maidcli.ops.ItemOps;
import com.xiaofeng.maidcli.ops.WalkOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 放方块（COMMANDS.md §6.1 {@code place}）—— 放置是问题最多的一条链路。
 *
 * <p>两条路：
 * <ol>
 *   <li><b>{@code item} 带完整 blockstate</b>（{@code oak_stairs[facing=north,half=bottom]}）⇒ 直接 {@code setBlock}</li>
 *   <li><b>不带属性</b> ⇒ 走原版 {@code BlockItem.useOn}（朝向/可替换/草被顶掉都是原版行为）</li>
 * </ol>
 *
 * <p>⚠️⚠️ <b>{@code clicked = 目标格【本身】}</b>，不是"下面那格"。
 * 早期写成下面那格 ⇒ 那格是空气时原版 {@code replaceClicked} 会把方块放到那儿 ⇒
 * 表现成"方块塌下去 / 留空的门窗被上方砌墙填掉"。改成目标格本身后<b>完全不依赖支撑</b>。
 *
 * <p>⚠️⚠️ <b>物品守恒靠"真交换"</b>（见 {@link ItemOps#takeToMainHand}）：
 * 背包永不清空、手上放副本、成功才 {@code shrink(1)}。
 *
 * <p>⚠️ 她自己站的那格放不上（原版 {@code isUnobstructed} 检查玩家碰撞箱）——
 * 报失败并提示挪开，<b>不自动避让</b>（挪位置属编排层职责）。
 */
public final class PlaceAction extends Action {

    private final BlockPos target;
    private final String itemSpec;      // 可能带 blockstate
    private final int interval;         // 放置间隔（tick）

    private int waitAfter = -1;
    private boolean handSwapped;
    private ItemStack backup;
    private boolean placed;

    public PlaceAction(BlockPos target, String itemSpec, int interval) {
        this.target = target;
        this.itemSpec = itemSpec;
        this.interval = Math.max(2, interval);
    }

    @Override
    public Phase tick(ServerLevel lv, EntityMaid maid) {
        // 放置间隔：放过一块之后，等 interval tick 再收工（别"秒放"，一眼假）
        if (waitAfter >= 0) {
            if (waitAfter > 0) {
                waitAfter--;
                return Phase.RUNNING;
            }
            return placed ? Phase.DONE : Phase.FAILED;
        }

        BlockState now = lv.getBlockState(target);
        boolean withState = itemSpec != null && itemSpec.contains("[");

        // ① 带完整 blockstate ⇒ 直接 setBlock（但必须先校验目标格）
        if (withState) {
            BlockState want;
            try {
                want = net.minecraft.commands.arguments.blocks.BlockStateParser
                        .parseForBlock(lv.holderLookup(Registries.BLOCK), itemSpec, false)
                        .blockState();
            } catch (Throwable t) {
                return fail("item 的 blockstate 解析不了：" + itemSpec + "（" + t.getMessage() + "）");
            }
            if (now.equals(want)) {
                placed = true;
                waitAfter = interval;
                return Phase.RUNNING;                 // 已经是了 ⇒ 成功跳过
            }
            if (!now.isAir() && !now.canBeReplaced()) {
                return fail("目标格不空（" + BlockOps.id(now) + "）—— 强制放置不覆盖，请先挖掉再放");
            }
            swapIfNeeded(maid, lv, now);
            ItemStack hand = maid.getMainHandItem();
            if (hand.isEmpty()) {
                return fail("手上没有方块可放");
            }
            hand.shrink(1);
            lv.setBlock(target, want, Block.UPDATE_ALL);
            WalkOps.faceTarget(maid, Vec3.atCenterOf(target));
            maid.swing(InteractionHand.MAIN_HAND);
            placed = true;
            waitAfter = interval;
            return Phase.RUNNING;
        }

        // ② 不带属性 ⇒ 走原版 useOn
        ItemStack toPlace = resolveItem(maid);
        if (toPlace.isEmpty()) {
            return fail("她身上没有可放的方块" + (itemSpec == null ? "（手上也是空的）" : "：" + itemSpec));
        }
        if (!(toPlace.getItem() instanceof BlockItem blockItem)) {
            return fail(itemSpec + " 不是方块类物品");
        }
        if (now.equals(blockItem.getBlock().defaultBlockState())) {
            placed = true;
            waitAfter = interval;
            return Phase.RUNNING;
        }
        if (!now.isAir() && !now.canBeReplaced()) {
            return fail("那里不是空地（" + BlockOps.id(now) + "）");
        }
        if (BlockOps.selfBlocking(maid, target)) {
            return fail("她自己站在这格上（她有体积，站方块交界处会占 2×2）—— 先 go 让她挪开");
        }

        swapIfNeeded(maid, lv, now);
        ItemStack inHand = maid.getMainHandItem();
        if (!(inHand.getItem() instanceof BlockItem)) {
            return fail("换手后主手不是方块类物品（内部错误）");
        }
        MaidFakePlayer fake = MaidFakePlayer.of(maid);
        Direction face = BlockOps.resolveFace(maid, target, null);
        BlockPos clicked = target;                       // ★ 被点击格 = 目标格本身
        Vec3 hitVec = Vec3.atCenterOf(clicked);
        BlockHitResult hit = new BlockHitResult(hitVec, face, clicked, false);
        WalkOps.faceTarget(maid, hitVec);

        ItemStack probe = inHand.copyWithCount(1);       // 用副本探（不真扣）
        BlockPlaceContext ctx = new BlockPlaceContext(fake, InteractionHand.MAIN_HAND, probe, hit);
        InteractionResult res;
        try {
            res = ((BlockItem) inHand.getItem()).useOn(ctx);
        } catch (Throwable t) {
            return fail("原版放置抛异常：" + t.getMessage());
        }
        boolean okAtTarget = !lv.getBlockState(target).isAir();
        boolean okAtClicked = lv.getBlockState(clicked).getBlock() == blockItem.getBlock();
        if (res.consumesAction() && (okAtTarget || okAtClicked)) {
            inHand.shrink(1);                            // 成功才扣
            maid.swing(InteractionHand.MAIN_HAND);
            placed = true;
            waitAfter = interval;
            return Phase.RUNNING;
        }
        return fail("原版判定放不上去（可能被她自己占着 —— 她有体积）");
    }

    private ItemStack resolveItem(EntityMaid maid) {
        if (itemSpec == null || itemSpec.isBlank()) {
            return maid.getMainHandItem().copy();
        }
        int slot = ItemOps.findSlot(maid, itemSpec);
        if (slot < 0) {
            return ItemStack.EMPTY;
        }
        return maid.getAvailableInv(true).getStackInSlot(slot).copy();
    }

    private void swapIfNeeded(EntityMaid maid, ServerLevel lv, BlockState now) {
        if (handSwapped || itemSpec == null || itemSpec.isBlank()) {
            return;
        }
        handSwapped = true;
        // blockstate 那种写法只取 `[` 前的物品 id
        String id = itemSpec.contains("[") ? itemSpec.substring(0, itemSpec.indexOf('[')) : itemSpec;
        backup = ItemOps.takeToMainHand(maid, id, 1);
    }

    @Override
    public String op() {
        return "place";
    }

    @Override
    public float progress() {
        return placed ? 0.9f : 0.5f;
    }

    @Override
    public boolean needsReach() {
        return true;
    }

    @Override
    public BlockPos reachTarget() {
        return target;
    }

    @Override
    public BlockPos targetPos() {
        return target;
    }
}
