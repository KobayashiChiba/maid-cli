package com.xiaofeng.maidcli.engine.actions;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.engine.Action;
import com.xiaofeng.maidcli.engine.MaidHandle;
import com.xiaofeng.maidcli.engine.Session;
import com.xiaofeng.maidcli.ops.ContainerOps;
import com.xiaofeng.maidcli.ops.WalkOps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 打开一个界面（COMMANDS.md §6.1 {@code open}）—— <b>界面操作的入口</b>。
 *
 * <p>{@code open pos=<坐标>} 方块容器 / {@code open entity=<实体id>} 实体界面 /
 * {@code open}（对空气）= 打开自己的背包。
 *
 * <p>⚠️ <b>和 {@code use} 是同一个"右键"，但分工明确</b>：
 * {@code use} = 只交互（门/拉杆/按钮/打火石/放方块/用物品）；
 * {@code open} = 打开界面。{@code open} <b>不需要 item</b>。
 *
 * <p>⚠️ <b>开盖绝对不能调 {@code startOpen/stopOpen}</b> —— 它们会改和真玩家共享的
 * {@code openerCount} ⇒ 客户端事件数和界面状态对不上（表现为双方各自开关一次，客户端状态就反转一次）。
 * 正解：只发 {@code level.blockEvent(pos, block, 1, 0/1)}（幂等、和玩家彻底隔离）。
 */
public final class OpenAction extends Action {

    private final MaidHandle handle;
    private final BlockPos pos;
    private final int entityId;
    private final boolean selfInv;

    public OpenAction(MaidHandle handle, BlockPos pos, int entityId, boolean selfInv) {
        this.handle = handle;
        this.pos = pos;
        this.entityId = entityId;
        this.selfInv = selfInv;
    }

    @Override
    public Phase tick(ServerLevel lv, EntityMaid maid) {
        if (handle.session != null) {
            return fail("界面已经开着（" + handle.session.kind.wire + "）—— 先 close");
        }
        Session s;
        try {
            if (selfInv) {
                s = ContainerOps.openSelfInventory(maid);
            } else if (entityId > 0) {
                s = ContainerOps.openEntityMenu(maid, entityId);
            } else {
                s = ContainerOps.openContainer(maid, pos);
            }
        } catch (com.xiaofeng.maidcli.transport.CmdException e) {
            return fail(e.getMessage());
        } catch (Throwable t) {
            return fail("开界面失败：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
        }
        if (pos != null) {
            WalkOps.faceTarget(maid, net.minecraft.world.phys.Vec3.atCenterOf(pos));
        }
        s.maidRef = handle;
        s.openedAtTick = lv.getServer().getTickCount();
        handle.session = s;
        return Phase.DONE;
    }

    @Override
    public String op() {
        return "open";
    }

    @Override
    public boolean needsReach() {
        return pos != null;
    }

    @Override
    public BlockPos reachTarget() {
        return pos;
    }

    @Override
    public BlockPos targetPos() {
        return pos;
    }
}
