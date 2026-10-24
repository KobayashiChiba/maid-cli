package com.xiaofeng.maidcli.ops;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.engine.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * 「够不着就先去站好」这条链路的两个函数（旧版 {@code MaidEventRunner} 的
 * {@code canReach()} / {@code findStandPoint()} 搬回来，）。
 *
 * <p><b>为什么必须分开成两个函数</b>（v2 重写时把它们内联成一句 {@code d <= reach} 就丢了语义）：
 * <ol>
 *   <li>{@link #canReach} —— <b>要不要走位</b>：除了距离，还要判 {@code place} 的
 *       "她自己占着目标格"（她 0.9 宽的碰撞箱压住目标格时，原版 {@code isUnobstructed}
 *       一定拒绝，光"距离够"是假阳性）</li>
 *   <li>{@link #findStandPoint} —— <b>走到哪</b>：绝不是"走到目标格本身"，
 *       而是 {@link StandPoint#safe} 在球内按自然度挑一个点（离目标 2 格最优、
 *       不重叠目标格/目标下方格、避危险方块）</li>
 * </ol>
 *
 * <p>⚠️ 实测教训：v2 里走位目标直接用了 {@code reachTarget()} ⇒ 她精确走到
 * 目标格旁 0.6 格处、碰撞箱擦着目标格 ⇒ {@code place} 全部报
 * "原版判定放不上去（可能被她自己占着）"。
 */
public final class ReachOps {

    private ReachOps() {
    }

    /**
     * 她够得到这个动作的目标吗（手长 8 格；{@code place} 收 1 格余量 + 自占判定）。
     *
     * <p>照旧版 {@code MaidEventRunner.canReach}：
     * <pre>
     * reach    = place ? (手长 - 1) : 手长      ← 放方块要留 1 格余量，免得贴着边缘手抖
     * occupied = place && selfBlocking(...)      ← 目标格在她自己身上 ⇒ 不管多远都算够不到
     * </pre>
     */
    public static boolean canReach(EntityMaid maid, Action a, double reachCfg) {
        if (!a.needsReach()) {
            return true;
        }
        BlockPos pos = a.reachTarget();
        if (pos == null) {
            return true;                 // 没有具体目标（如 pickup 靠半径找）⇒ 不需要走位
        }
        boolean isPlace = "place".equals(a.op());
        double reach = isPlace ? reachCfg - 1.0 : reachCfg;
        boolean occupied = isPlace && selfBlocking(maid, pos);
        double dist = Math.sqrt(maid.distanceToSqr(Vec3.atCenterOf(pos)));
        return !occupied && dist <= reach;
    }

    /**
     * 给"够不到的动作"选一个能站的落脚点。
     *
     * <p>交互类用 {@link StandPoint#safe} 的完整判定（两格空气 / 脚下能站 / 不危险 /
     * 不挡着目标 / 够得到 ≤7）。
     *
     * @return {@code null} = 附近找不到安全落脚点 ⇒ 调用方直接判失败，
     *         <b>这里不该有任何兜底</b>（兜底会把"要交互的那一格"当落点，顺着链传下去就出事）
     */
    public static BlockPos findStandPoint(EntityMaid maid, Action a) {
        BlockPos pos = a.reachTarget();
        if (pos == null || !(maid.level() instanceof ServerLevel sl)) {
            return null;
        }
        return StandPoint.safe(sl, pos, maid.blockPosition());
    }

    /**
     * 目标格是不是被她自己占着（旧版 {@code MaidEventRunner.selfBlocking} 原样）。
     *
     * <p>用她碰撞箱的半宽 ±0.45（她 sized 0.6 宽，留一点余量）扫水平 2×2，高度扫脚下两格。
     * 只要目标格落在其中就算"占着"—— 原版放方块时 {@code isUnobstructed} 会因此拒绝。
     */
    public static boolean selfBlocking(EntityMaid maid, BlockPos pos) {
        double fx = maid.getX();
        double fz = maid.getZ();
        int y0 = maid.blockPosition().getY();
        int x0 = (int) Math.floor(fx - 0.45);
        int x1 = (int) Math.floor(fx + 0.45);
        int z0 = (int) Math.floor(fz - 0.45);
        int z1 = (int) Math.floor(fz + 0.45);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y0 + 1; y++) {
                    if (pos.getX() == x && pos.getY() == y && pos.getZ() == z) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
