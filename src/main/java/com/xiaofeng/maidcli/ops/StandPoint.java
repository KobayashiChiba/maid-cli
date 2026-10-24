package com.xiaofeng.maidcli.ops;

import com.xiaofeng.maidcli.MaidCliMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ★★ 安全落脚点选择（旧版 {@code StandPoint.java} 原样搬回，）。
 *
 * <p>它是「够不到就找个地方站」这条链路的最后一环 ——
 * {@link ReachOps#findStandPoint} 调它。
 *
 * <p><b>为什么需要它（实测踩到的回归）</b>：v2 重写时把这一步砍了，
 * 「够不着」时直接把<b>目标格本身</b>当走位目标 ⇒ 她精确走到目标格旁 0.6 格处，
 * 碰撞箱擦着目标格 ⇒ 原版 {@code isUnobstructed} 拒绝 ⇒ {@code place} 报
 * "原版判定放不上去（可能被她自己占着）"。
 *
 * <p><b>历史教训</b>：她曾被 tp 到营火上烧死、也曾被送到平台下面 —— 所以判据很严。
 */
public final class StandPoint {

    private StandPoint() {
    }

    /**
     * ★★ 交互时的"够得到"判定半径 = 7（比手长 8 缩 1 格）—— 设计原因：
     * 留 1 格余量，免得"刚好贴着 8 格边"时因为实体体积/朝向差一点够不着。
     */
    public static final double REACH_INTERACT = 7.0;

    /** 这些方块脚下/身上都不行 —— 会烧死、扎死、淹死（她就是这么被营火烧死的）。 */
    public static boolean isDangerous(BlockState s) {
        return s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE)
                || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE)
                || s.is(Blocks.LAVA) || s.is(Blocks.MAGMA_BLOCK)
                || s.is(Blocks.CACTUS) || s.is(Blocks.SWEET_BERRY_BUSH)
                || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.WATER)
                || s.is(Blocks.POINTED_DRIPSTONE) || s.is(Blocks.WITHER_ROSE)
                || s.is(Blocks.SCULK_SHRIEKER) || s.is(Blocks.SCULK_SENSOR);
    }

    /**
     * ★★ 选一个【安全】的落脚点去够 {@code target}（硬条件）。
     *
     * <p>必须同时满足：
     * <ol>
     *   <li>是空气 —— 且<b>上方一格也空气</b>（她身高 2 格）</li>
     *   <li>脚下是<b>能站</b>的方块（{@code isFaceSturdy}，含楼梯/半砖），且不是危险方块</li>
     *   <li><b>不与目标格重叠</b>（也不能站在目标格<b>下面那格</b> —— 女仆 2 格高，头会占住目标格）</li>
     *   <li>到目标格<b>球面距离 ≤ 7</b>（够得到）</li>
     *   <li>落差用<b>排序权重</b>处理（同层优先），不当硬约束</li>
     * </ol>
     *
     * @param preferNear 越靠近它越优先（少跑路），可为 null
     * @return null = 附近没有安全落脚点（调用方直接判失败，<b>不要兜底</b>）
     */
    public static BlockPos safe(ServerLevel level, BlockPos target, BlockPos preferNear) {
        // ★★ 枚举形状 = 以目标为球心、半径 REACH_INTERACT 的【球】
 // 旧版遇到过的坑：只用"水平环 + dy ±1"覆盖不到"目标下方的地面"⇒ 挖树顶/屋檐时找不到落脚点。
        final int R = (int) REACH_INTERACT;              // 7
        final int R2 = R * R;
        List<BlockPos> all = new ArrayList<>();
        for (int dx = -R; dx <= R; dx++) {
            for (int dy = -R; dy <= R; dy++) {
                for (int dz = -R; dz <= R; dz++) {
                    if (dx * dx + dy * dy + dz * dz > R2) {
                        continue;                            // 球外 ⇒ 跳过 ✓
                    }
                    BlockPos p = target.offset(dx, dy, dz);
                    if (isSafeStand(level, p, target)) {
                        all.add(p);
                    }
                }
            }
        }
        if (all.isEmpty()) {
            return null;
        }
        final BlockPos tg = target;
        final BlockPos near = preferNear;
 // ★ 排序 = 自然度分数 → X 坐标 → Z 坐标（设计原因：
 // 三条判断都打平的最后用 X、Z 强行区分；别用"x*0.01 + z*0.001"塞进分数里 —— 量级会乱套 ✗）
        all.sort(Comparator.comparingDouble((BlockPos p) -> score(level, p, tg, near))
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));
        BlockPos best = all.get(0);
        MaidCliMod.LOG.info("[MaidCLI] 落脚点决策 target={} 候选={} 选中={} 距目标={} 分={}",
                tg.toShortString(), all.size(), best.toShortString(),
                String.format("%.2f", Math.sqrt(best.distSqr(tg))),
                String.format("%.1f", score(level, best, tg, near)));
        return best;
    }

    /**
     * 单个候选点是否合格（硬条件，全部要过）。
     */
    private static boolean isSafeStand(ServerLevel level, BlockPos p, BlockPos target) {
        if (!level.isLoaded(p)) {
            return false;                                    // 区块没加载 → 别去
        }
        // 排除【目标格】和【目标格下面那一格】（女仆 2 格高：站下面那格时头正好占住目标格 ✗）
        if (p.equals(target) || p.equals(target.below())) {
            return false;
        }
 // ★ 排除范围就这两格（设计范围）：
 // 只要求【走位目的地不与交互目标重合】，没有别的水平距离限制。
 // ⚠️ 早期实现曾多加过"水平 ≥2 格"的保险，但它在结构密集处（唯一落点就在旁边）会误杀
 // ⇒ 报"走位后仍够不到" ✗；精确到达已做到（内部走位 closeEnough=0）⇒ 前提不成立 ⇒ 撤掉 ✓
        if (Math.sqrt(p.distSqr(target)) > REACH_INTERACT) {
            return false;                                    // ④ 够得到（7 格）
        }
        BlockState feet = level.getBlockState(p);
        BlockState head = level.getBlockState(p.above());
        BlockState ground = level.getBlockState(p.below());
        if (!feet.isAir() || !head.isAir()) {
            return false;                                    // ① 两格都要空气
        }
        // ★ 落脚判定必须用 isFaceSturdy（"上表面能不能站"）——
 // 别用 isSolidRender！楼梯/半砖/台阶/栅栏的 isSolidRender 都是 false，
 // 但它们明明能站 ⇒ 会把大片地方误判成"没落脚点"（用户看图抓出来）
        if (!ground.isFaceSturdy(level, p.below(), Direction.UP)) {
            return false;                                    // ② 脚下要站得住
        }
        return !isDangerous(ground) && !isDangerous(feet) && !isDangerous(head);  // ②安全
    }

    /**
     * ★★ 候选落脚点的【自然度】打分（越低越自然）。
     *
     * <p>⚠️ 注意：这个打分管的是【动作自不自然】，
     * <p><b>跟"能不能成功"毫无关系</b> —— 成败已经在 {@link #isSafeStand} 的硬条件里筛完了。
     * 三项只是"站哪儿更像人"：
     * <ol>
     *   <li>离目标 2 格最自然（太贴像怼上去、太远像伸手够）</li>
     *   <li>视野通畅（不通畅不硬排除，只排后面）</li>
     *   <li>离当前位置近（少走点路）</li>
     * </ol>
     * 打平的点由调用方用 X、Z 坐标强行区分。
     */
    private static double score(ServerLevel level, BlockPos p, BlockPos target, BlockPos near) {
        double dist = Math.sqrt(p.distSqr(target));
        double s = Math.abs(Math.round(dist) - 2.0) * 1000.0;         // ① 离目标 2 格最自然
        if (!hasLineOfSight(level, p, target)) {
            s += 300.0;                                              // ② 视野通畅（只降序，不排除）
        }
        if (near != null) {
            s += p.distSqr(near) * 0.1;                              // ③ 离当前位置近（少走路）
        }
        if (isContainerOrStation(level, p)) {
            s += 200.0;                                              // 别站容器/工位上（会挡住开箱）
        }
        return s;
    }

    /** 脚下那格是不是容器/工位（站上去会挡住开箱） */
    private static boolean isContainerOrStation(ServerLevel level, BlockPos p) {
        BlockState g = level.getBlockState(p.below());
        return g.getBlock() instanceof net.minecraft.world.level.block.BaseEntityBlock
                || g.is(Blocks.CRAFTING_TABLE)
                || g.is(Blocks.ANVIL)
                || g.is(Blocks.STONECUTTER)
                || g.is(Blocks.GRINDSTONE)
                || g.is(Blocks.LECTERN);
    }

    /**
     * 眼睛（落脚点上 1.6 格）到目标格中心 之间有没有实心方块挡着。
     * <p>自己的体素射线，不用 {@code ClipContext}（这套映射里 world.level / world.phys
     * 各有一个同名类，编译器报"引用不明确"）。每格取 4 个采样点，够用了。
     */
    private static boolean hasLineOfSight(ServerLevel level, BlockPos from, BlockPos target) {
        double ax = from.getX() + 0.5, ay = from.getY() + 1.6, az = from.getZ() + 0.5;
        double bx = target.getX() + 0.5, by = target.getY() + 0.5, bz = target.getZ() + 0.5;
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-4) {
            return true;
        }
        int steps = (int) Math.ceil(len * 4.0);
        for (int i = 1; i < steps; i++) {
            double tt = (double) i / steps;
            BlockPos q = BlockPos.containing(ax + dx * tt, ay + dy * tt, az + dz * tt);
            if (q.equals(target) || q.equals(from)) {
                continue;                                  // 起点/终点本身不算遮挡
            }
            if (level.getBlockState(q).canOcclude()) {
                return false;                              // 被实心方块挡住
            }
        }
        return true;
    }
}
