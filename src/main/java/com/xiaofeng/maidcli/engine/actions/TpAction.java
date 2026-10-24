package com.xiaofeng.maidcli.engine.actions;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.engine.Action;
import com.xiaofeng.maidcli.ops.FreeRoam;
import com.xiaofeng.maidcli.ops.WalkOps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 传送（{@code tp} 指令 / {@code go} 走不到时的兜底）。
 *
 * <p>★ <b>指哪去哪，绝不改坐标</b>（旧 {@code MaidEventRunner.safeTeleport} 原文）。
 *
 * <p><b>只做两件"不改坐标"的检查</b>：
 * <pre>
 * ① 距离 ≤ 上限（防手滑传到几万格以外）
 * ② 目标区块已加载（不加载根本传不过去）
 * </pre>
 * 然后直接 {@code randomTeleport(精确坐标)}，原版自带的碰撞箱判定会挡住"塞不下人"的位置。
 *
 * <p>❌ <b>已删除（早期试过"自动修正落点"，反而制造问题，已删除）</b>：找落点 / 往下搜 / 危险方块检查 / 落点修正 ——
 * 那套"安全保障"会擅自把坐标改掉，反而制造一堆问题（把要放方块的那格当落点、往下搜到地底）。
 *
 * <p>⚠️⚠️ <b> 实测抓到的回归</b>：v2 重写时把 {@code safeLanding()} 加了回来，
 * 而且扫描循环从 {@code dx = -r} 开始 ⇒ 第一个通过曼哈顿过滤的点恒为 {@code (-3, -1)}
 * ⇒ <b>每次 tp 都恒定偏 (-2.5, -0.5) 格</b>（RCON 读真身三次验证）。
 * 这就是"你删掉的东西又被加回来"的典型 —— 不要再加回来。
 *
 * <p>⚠️ 传送后必须 {@link FreeRoam#snapAnchor}（留守时锚点跟过来），
 * 否则下一次 {@code SchedulePos.tick()} 会把她原样传送回去。
 */
public final class TpAction extends Action {

    private final BlockPos target;
    /** 距离上限（0 = 不限）；默认 64 */
    private final double maxDistance;

    public TpAction(BlockPos target, double maxDistance) {
        this.target = target;
        this.maxDistance = maxDistance;
    }

    @Override
    public Phase tick(ServerLevel lv, EntityMaid maid) {
        // ① 距离上限
        if (maxDistance > 0) {
            double d = Math.sqrt(maid.distanceToSqr(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5));
            if (d > maxDistance) {
                return fail("太远了（" + String.format("%.0f", d) + " 格，上限 "
                        + String.format("%.0f", maxDistance) + " 格）—— 用 max_distance 放宽，或分几段");
            }
        }
        // ② 区块已加载（传送不会自动加载区块）
        if (!lv.isLoaded(target)) {
            return fail("目标区块没加载（传送不会自动加载区块）—— 先过去一趟，或 forceload add");
        }
        // ③ 指哪去哪 —— 坐标一个字都不改
        boolean ok = maid.randomTeleport(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, true);
        if (!ok) {
            return fail("原版判定传不过去（那块地方塞不下她的碰撞箱）");
        }
        // ④ 清掉残留的走位 / 看 / 寻路意图（免得她一到就自己走开）
        WalkOps.stopWalking(maid);
        try {
            maid.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.LOOK_TARGET);
            maid.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.PATH);
        } catch (Throwable ignored) {
        }
        maid.getNavigation().stop();
        // ⑤ 留守时锚点必须跟过来（否则 SchedulePos 会把她拽回原地）
        FreeRoam.snapAnchor(maid);
        return Phase.DONE;
    }

    @Override
    public String op() {
        return "tp";
    }

    @Override
    public BlockPos targetPos() {
        return target;
    }

    @Override
    public boolean needsReach() {
        return false;
    }
}
