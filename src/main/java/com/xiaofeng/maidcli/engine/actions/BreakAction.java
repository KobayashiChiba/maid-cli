package com.xiaofeng.maidcli.engine.actions;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.engine.Action;
import com.xiaofeng.maidcli.infra.MaidFakePlayer;
import com.xiaofeng.maidcli.ops.BlockOps;
import com.xiaofeng.maidcli.ops.ItemOps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 挖方块（COMMANDS.md §6.1 {@code break}）。
 *
 * <p>每 tick 走一步：<b>预期校验 → 换最优工具 → 累加原版破坏进度 → 满 1.0 就破</b>。
 * 进度用 {@code state.getDestroyProgress(假玩家, level, pos)} —— 一行拿到原版公式
 * （含工具材质 + 效率附魔 + 急迫/疲劳 + 水下/空中）。
 *
 * <p>⚠️ 破坏必须走 {@code maid.destroyBlock}（保住 TLM 的权限校验），别直接 setBlock(air)。
 * <p>⚠️ {@code drops=ground} 的"掉地上"要三段式（见 {@link BlockOps#destroy}）。
 */
public final class BreakAction extends Action {

    private final BlockPos target;
    private final String expect;
    private final boolean toGround;
    private float prog;

    public BreakAction(BlockPos target, String expect, boolean toGround) {
        this.target = target;
        this.expect = expect;
        this.toGround = toGround;
    }

    @Override
    public Phase tick(ServerLevel lv, EntityMaid maid) {
        BlockState state = lv.getBlockState(target);
        // ① 预期校验（给了就校验 —— 防挖坏别的东西）
        if (expect != null && !expect.isBlank()) {
            String actual = BlockOps.id(state);
            if (!expect.equals(actual) && !actual.endsWith(":" + expect)) {
                return fail("目标不是 " + expect + "（实际是 " + actual + "）—— 按 expect 跳过");
            }
        }
        // ② 硬度
        float hardness = state.getDestroySpeed(lv, target);
        if (hardness < 0) {
            return fail("这个方块挖不动（硬度 " + hardness + "，比如基岩）");
        }
        if (hardness == 0) {
            return doBreak(lv, maid, state);          // 零硬度瞬破
        }
        // ③ 换最优工具 + 累进度
        ItemOps.equipBestTool(maid, state);
        float delta = state.getDestroyProgress(MaidFakePlayer.of(maid), lv, target);
        if (delta <= 0) {
            return fail("原版算出来破坏进度是 0（工具不对？被 TLM 拦了？）");
        }
        maid.swing(InteractionHand.MAIN_HAND);
        prog += delta;
        if (prog >= 1.0f) {
            return doBreak(lv, maid, state);
        }
        return Phase.RUNNING;
    }

    private Phase doBreak(ServerLevel lv, EntityMaid maid, BlockState state) {
        // 转身看向它（放完不复位 —— 用 setYRot 系列，不用 LookControl）
        com.xiaofeng.maidcli.ops.WalkOps.faceTarget(maid, Vec3.atCenterOf(target));
        ItemStack tool = maid.getMainHandItem();
        // 耐久（每次【破除】扣 1，不是每 tick）
        if (!tool.isEmpty() && tool.isDamageableItem()) {
            tool.hurtAndBreak(1, MaidFakePlayer.of(maid), EquipmentSlot.MAINHAND);
        }
        BlockOps.breakEffect(lv, target);
        boolean ok = BlockOps.destroy(maid, target, tool, toGround);
        if (!ok) {
            return fail("挖不动（被 TLM 的权限校验拦下）");
        }
        prog = 0f;
        return Phase.DONE;
    }

    @Override
    public String op() {
        return "break";
    }

    @Override
    public float progress() {
        // 封顶 0.95 —— 留 0.05 表示"还差最后一击"（照旧实现的语义）
        return Math.min(0.95f, prog);
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
