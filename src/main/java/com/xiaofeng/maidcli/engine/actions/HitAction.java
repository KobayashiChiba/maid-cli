package com.xiaofeng.maidcli.engine.actions;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.engine.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * 左键短按（COMMANDS.md §6.1 {@code hit}）。
 *
 * <p>对实体 = 攻击（伤害 + 击退）；对方块 / 空气 = 挥手（空实现）。
 *
 * <p>★ <b>照抄 TLM 自己的近战</b>（{@code MaidMeleeAttack.java:35-36}）：
 * <pre>
 * maid.swing(InteractionHand.MAIN_HAND);   // 挥手动画
 * maid.doHurtTarget(target);               // 伤害 + 击退（原版一次攻击，含附魔）
 * </pre>
 *
 * <p>⚠️ <b>不要往 brain 里塞 {@code ATTACK_TARGET}</b> —— 那是 {@code mode attack} 的事
 * （会让她自己追着打，{@code hit} 的"拍一下"语义就没了）。这里直接 {@code doHurtTarget} 一次性。
 */
public final class HitAction extends Action {

    private final int entityId;
    private final BlockPos pos;
    private BlockPos resolvedTarget;

    /** @param entityId 0 = 不打实体；@param pos 目标方块（可空）；都不给 = 对空气挥手 */
    public HitAction(int entityId, BlockPos pos) {
        this.entityId = entityId;
        this.pos = pos;
        this.resolvedTarget = pos;
    }

    @Override
    public Phase tick(ServerLevel lv, EntityMaid maid) {
        if (entityId > 0) {
            Entity e = lv.getEntity(entityId);
            if (!(e instanceof LivingEntity le) || !le.isAlive()) {
                return fail("目标生物已经不在了（id=" + entityId + "）");
            }
            resolvedTarget = le.blockPosition();
            // 攻击要走到近处（原版近战距离），但我们的"够得着"用 8 格手长兜 —— 够不到调度器会插走位
            maid.lookAt(le, 30f, 30f);
            maid.swing(InteractionHand.MAIN_HAND);
            maid.doHurtTarget(le);
            return Phase.DONE;
        }
        // 方块 / 空气 —— 保留挥手接口（空实现）
        maid.swing(InteractionHand.MAIN_HAND);
        return Phase.DONE;
    }

    @Override
    public String op() {
        return "hit";
    }

    @Override
    public boolean needsReach() {
        return entityId > 0 || pos != null;
    }

    @Override
    public BlockPos reachTarget() {
        return resolvedTarget;
    }

    @Override
    public BlockPos targetPos() {
        return resolvedTarget;
    }
}
