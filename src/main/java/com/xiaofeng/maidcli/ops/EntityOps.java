package com.xiaofeng.maidcli.ops;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * 对实体的操作实现（★ 只给 {@code Action.tick()} 调）。
 *
 * <p>⚠️ <b>原版顺序：实体先，物品后</b>（读字节码确认，别猜）：
 * <pre>
 * Player.interactOn(entity, hand):
 *   ① entity.interact(player, hand)          ← 先【实体自己的】（喂食 / 剪毛 / 挤奶 / 驯服 / 装鞍）
 *   ② stack.interactLivingEntity(...)        ← 仅当①没消费这次交互
 * </pre>
 */
public final class EntityOps {

    private EntityOps() {
    }

    /** 按实体 id 找活体实体（搜索半径 32 格） */
    public static Entity findById(Level level, EntityMaid maid, int entityId) {
        AABB box = maid.getBoundingBox().inflate(32);
        for (Entity e : level.getEntities(maid, box)) {
            if (e.getId() == entityId) {
                return e;
            }
        }
        return null;
    }

    /** ⚠️ EntityType.toString() 返回 "entity.minecraft.pig" ⇒ 查注册表拿 "minecraft:pig" */
    public static String entityTypeId(Entity e) {
        try {
            return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .getKey(e.getType()).toString();
        } catch (Throwable t) {
            return e.getType().toString();
        }
    }

    /** 按实体类型找最近的（搜索半径 24 格 —— 射箭那边的 resolveTarget 同口径） */
    public static Entity findNearestByType(Level level, EntityMaid maid, String typeId, double radius) {
        AABB box = maid.getBoundingBox().inflate(radius);
        Entity best = null;
        double bd = Double.MAX_VALUE;
        for (Entity e : level.getEntities(maid, box)) {
            if (e == maid || entityTypeId(e).equals(typeId) || entityTypeId(e).endsWith(":" + typeId)) {
                double d = maid.distanceToSqr(e);
                if (entityTypeId(e).equals(typeId) || entityTypeId(e).endsWith(":" + typeId) && d < bd) {
                    bd = d;
                    best = e;
                }
            }
        }
        return best;
    }

    /** 附近某类型的所有实体 */
    public static List<Entity> findAllByType(Level level, EntityMaid maid, String typeId, double radius) {
        AABB box = maid.getBoundingBox().inflate(radius);
        return level.getEntities(maid, box).stream()
                .filter(e -> entityTypeId(e).equals(typeId) || entityTypeId(e).endsWith(":" + typeId))
                .toList();
    }

    /**
     * 指定攻击目标（{@code mode attack target=} 用）。
     * <p>★ 外部塞进 {@code ATTACK_TARGET} 的目标<b>不会被"目标合法性"过滤掉</b>
     * （{@code StopAttackingIfTargetInvalid} 只看"有没有武器 + 离得远不远"）
     * ⇒ 这让 CLI 能指挥她打任何东西（自动索敌才守 {@code canAttack} 规矩）。
     */
    public static void setAttackTarget(EntityMaid maid, int entityId) {
        Entity e = findById(maid.level(), maid, entityId);
        if (!(e instanceof LivingEntity le)) {
            throw new com.xiaofeng.maidcli.transport.CmdException(
                    com.xiaofeng.maidcli.transport.CmdException.NOT_FOUND,
                    "找不到实体 id=" + entityId + "（32 格内）", "先用 sense entities 看看附近有什么");
        }
        maid.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, le);
    }

    /** 清攻击目标 */
    public static void clearAttackTarget(EntityMaid maid) {
        maid.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
    }
}
