package com.xiaofeng.maidcli.engine.actions;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.engine.Action;
import com.xiaofeng.maidcli.ops.EntityOps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * 骑上载具（COMMANDS.md §6.1 {@code ride}）—— 让女仆【坐上去】（船 / 矿车 / 马 / 猪…）。
 *
 * <p><b>为什么不能用 {@code use entity=} 代替</b>：原版「右键船 / 矿车」的判定是
 * {@code player.startRiding(boat)} —— 那个 "player" 是我们的<b>假玩家</b> ⇒
 * 结果会变成"假玩家骑上去了，她还在旁边站着" ✗
 * 所以必须直接调她自己的骑乘接口：{@code maid.startRiding(target, true)}。
 *
 * <p>⚠️ <b>船 / 矿车不是 {@code LivingEntity}</b>，它们是普通 {@link Entity}
 * ⇒ 用 {@code LivingEntity} 找目标会正好漏掉这两个最需要的。这里按 {@code Entity} 找。
 *
 * <p>⚠️ <b>判断"她有没有在骑"别信 NBT</b>（{@code data get entity <maid> Vehicle} 经常读不出来）
 * ⇒ 用 {@code maid.isPassenger()} / {@code maid.getVehicle()}。
 */
public final class RideAction extends Action {

    /** 目标类型（{@code minecraft:boat} / {@code minecraft:minecart}）；null = 按 id 找 */
    private final String targetType;
    /** 目标实体 id（{@code > 0} 优先，忽略 targetType） */
    private final int entityId;

    /** 解析出来的目标（解析成功后缓存，避免每 tick 重找、也能判断是否已解析过） */
    private Entity target;
    /** 成功文案（骑上后填） */
    private String rideMessage;

    public RideAction(String targetType, int entityId) {
        this.targetType = targetType;
        this.entityId = entityId;
    }

    @Override
    public Phase tick(ServerLevel lv, EntityMaid maid) {
        // ① 解析目标（id 优先，否则按类型找最近的 Entity —— 船/矿车不是 LivingEntity）
        if (target == null) {
            target = resolve(lv, maid);
            if (target == null) {
                return fail("附近找不到要骑的目标"
                        + "（用 target=minecraft:boat / minecraft:minecart / entity_id=<id>）");
            }
            // ② 一堆前置校验（照抄旧实现的报错话术）
            if (maid.isPassenger()) {
                Entity cur = maid.getVehicle();
                return fail("她已经骑在 "
                        + (cur == null ? "别的东西" : cur.getName().getString()) + " 上了（先 dismount）");
            }
            if (target == maid) {
                return fail("不能骑自己");
            }
            if (target.isPassenger() || !target.getPassengers().isEmpty()) {
                return fail("那个 " + target.getName().getString() + " 上已经有人了");
            }
            double d = maid.distanceTo(target);
            if (d > 8.0) {
                return fail("她离目标 " + Math.round(d) + " 格（超过 8 格手长）—— 先用 go 走过去");
            }
            // ③ 直接调她自己的骑乘接口（绝不能走假玩家的 use）
            boolean ok = maid.startRiding(target, true);
            if (!ok) {
                return fail("没骑上去（原版拒绝了：目标不是可骑乘的实体？）");
            }
            rideMessage = "已骑上 " + target.getName().getString();
        }
        return Phase.DONE;
    }

    /** 找"要骑的目标"：id 优先，否则按类型找最近的 {@link Entity}（搜索半径 24 格） */
    private Entity resolve(ServerLevel lv, EntityMaid maid) {
        if (entityId > 0) {
            return lv.getEntity(entityId);
        }
        if (targetType == null || targetType.isBlank()) {
            return null;
        }
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : lv.getEntitiesOfClass(Entity.class, maid.getBoundingBox().inflate(24.0))) {
            if (e == maid) {
                continue;
            }
            if (!EntityOps.entityTypeId(e).equals(targetType)) {
                continue;
            }
            double d = maid.distanceToSqr(e);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    /** 成功文案（供报告用；调度器若接了 result() 就能读它） */
    public String message() {
        return rideMessage;
    }

    @Override
    public String op() {
        return "ride";
    }

    @Override
    public BlockPos targetPos() {
        return target == null ? null : target.blockPosition();
    }
}
