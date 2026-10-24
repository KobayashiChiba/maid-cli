package com.xiaofeng.maidcli.engine.actions;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.engine.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * 定向捡起来（COMMANDS.md §6.1 {@code pickup}）—— <b>一次捡一个</b>。
 *
 * <p>和自动拾取的分工：{@code set pickup=false} ⇒ 物品不自动捡，要哪件用 {@code pickup} 点名。
 *
 * <p>★ 走 TLM 原生 {@code maid.pickupItem(it, false)}（背包满/能否捡等判定都跟原生一致）。
 * <p>⚠️ TLM 的 {@code pickupItem} <b>只接受 {@code ItemEntity}</b> —— 经验球要单独走 {@code orb.playerTouch(假玩家)}。
 * <p>⚠️ 它会<b>绕过 {@code PickupType}</b>（主动捡不受配置限制）—— 这是有意的。
 * <p>⚠️ 捡的东西可能落进【展示栏】（TLM 从前往后找空槽，展示栏排在背包前面）⇒ 视觉上"背在身上"。
 */
public final class PickupAction extends Action {

    private static final double SEARCH_RADIUS = 8.0;

    private final int entityId;
    private final String itemId;

    /** @param entityId 指定掉落物实体；@param itemId 指定物品 id；都不给 = 捡最近的 </summary> */
    public PickupAction(int entityId, String itemId) {
        this.entityId = entityId;
        this.itemId = itemId;
    }

    @Override
    public Phase tick(ServerLevel lv, EntityMaid maid) {
        AABB box = maid.getBoundingBox().inflate(SEARCH_RADIUS);

        if (entityId > 0) {
            Entity e = lv.getEntity(entityId);
            if (e == null || e.isRemoved()) {
                return fail("那件掉落物已经不在了（id=" + entityId + "）");
            }
            if (e instanceof ItemEntity it) {
                return maid.pickupItem(it, false) ? Phase.DONE : fail("捡不了（背包满了 / 她的拾取类型不允许）");
            }
            if (e instanceof ExperienceOrb orb) {
                orb.playerTouch(com.xiaofeng.maidcli.infra.MaidFakePlayer.of(maid));
                return Phase.DONE;
            }
            return fail("那个实体不是掉落物（type=" + e.getType() + "）");
        }

        // 按物品 id 找（或捡最近的）
        List<ItemEntity> items = lv.getEntitiesOfClass(ItemEntity.class, box);
        for (ItemEntity it : items) {
            if (it.isRemoved()) {
                continue;
            }
            if (itemId != null && !itemId.isBlank()) {
                String id = BuiltInRegistries.ITEM.getKey(it.getItem().getItem()).toString();
                if (!id.equals(itemId) && !id.endsWith(":" + itemId)) {
                    continue;
                }
            }
            return maid.pickupItem(it, false) ? Phase.DONE : fail("捡不了（背包满了 / 她的拾取类型不允许）");
        }
        if (itemId == null || itemId.isBlank()) {
            // 退一步：附近没物品就试试经验球
            List<ExperienceOrb> orbs = lv.getEntitiesOfClass(ExperienceOrb.class, box);
            if (!orbs.isEmpty()) {
                orbs.get(0).playerTouch(com.xiaofeng.maidcli.infra.MaidFakePlayer.of(maid));
                return Phase.DONE;
            }
        }
        return fail(itemId == null ? "附近没有可捡的东西（" + (int) SEARCH_RADIUS + " 格内）"
                : "附近没有 " + itemId);
    }

    @Override
    public String op() {
        return "pickup";
    }

    @Override
    public boolean needsReach() {
        return true;
    }

    @Override
    public BlockPos reachTarget() {
        // 掉落物的坐标在构造时已经解析过了（没解析就用她脚下 —— 反正半径 8 格内）
        return null;    // 让它用"不需要特定方块"的路径：靠 radius 找
    }

    @Override
    public BlockPos targetPos() {
        return null;
    }
}
