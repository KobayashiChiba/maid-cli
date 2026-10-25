package com.xiaofeng.maidcli.ops;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 射箭的底层实现（★ 只给 {@code ShootAction.tick()} 调）—— <b>弹道解算 + 瞄准</b>。
 *
 * <p>移植自旧实现 早期实现（实测通过），把所有<b>纯算法</b>
 * 摘出来。状态机（蓄力 / 发射 / 落点追踪）在 {@code engine.actions.ShootAction} 里。
 *
 * <p><b>⚠️ 为什么用「模拟」而不是抛物线解析式</b>
 * MC 的箭<b>有空气阻力</b>（每 tick ×0.99）⇒ 抛物线公式只是近似。
 * 直接按游戏物理规则逐 tick 模拟，落点才算得准。物理常数（源码对齐）：
 * 重力 {@code GRAVITY=0.05}、阻力 {@code DRAG=0.99}、满蓄力初速 3.0。
 *
 * <p><b>⚠️⚠️ 判据 = 「点到线段」最近距离</b>
 * <ul>
 *   <li>原判据「箭最后停在哪」✗ —— 箭撞不到方块就一直飞（空中目标尤其），停在远处 ⇒
 *       误差巨大（实测：5 箭精准落在<b>同一个</b>偏离点 2.6 格）。</li>
 *   <li>改成「采样点到目标的距离」也不对 ✗ —— 轨迹真的穿过目标时，两侧采样点各差 0.8 格
 *       ⇒ <b>假缺口</b> ⇒ 拿假缺口迭代 ⇒ <b>越补越偏</b>。</li>
 *   <li><b>正解</b>：轨迹是<b>折线</b>，算「点到线段」的最近点 {@link #closestOnSegment}。</li>
 * </ul>
 * <p><b>误差完全一致 = 系统性错误</b> —— 该查判据本身，不该调参数。
 *
 * <p><b>迭代瞄准</b>
 * 模拟与真实飞行有 ~0.8 格系统性偏差（模型近似）⇒ 把「这条弹道离目标的缺口」反向补偿到
 * 瞄点上，4 轮内收敛到 0.02 格量级（{@link #aimIteratively}）。
 *
 * <p>⚠️ 不能抄 TLM 的 {@code clamp(距离/10, 1.6, 3.2)} 速度（下限太慢 ⇒ 重力作用久 ⇒ 偏 5 格）
 * —— 我们用满蓄力速度（config {@code shoot_speed}，默认 3.0），速度是<b>定值</b>，只解仰角。
 */
public final class ShootOps {

    /** 重力（和 MC 一致；实测有偏差改这里） */
    public static final double GRAVITY = 0.05;
    /** 空气阻力（每 tick ×0.99） */
    public static final double DRAG = 0.99;
    /** 模拟弹道最多推演的 tick 数 */
    public static final int MAX_SIM_TICKS = 60;
    /** 追踪箭落点最多等多少 tick（3 秒够飞很远） */
    public static final int MAX_FLIGHT_TICKS = 100;
    /** 落点/轨迹离目标在此范围内才算「射得中」
     *  ⚠️ 0.5 而不是 2.0：2.0 太宽 ⇒ 平射弹道从目标下方 0.66 格擦过也算"可解"
     *  ⇒ solvePitchWithSpeed 返回 pitch=0（平射）⇒ 箭直接飞过目标 128 格（2026-10-26 实测） */
    public static final double HIT_TOLERANCE = 0.5;

    private ShootOps() {
    }

    /** 她眼睛的位置（瞄准起点，和原版一致）。 */
    public static Vec3 eyeOf(EntityMaid maid) {
        return new Vec3(maid.getX(), maid.getY() + maid.getEyeHeight(), maid.getZ());
    }

    /**
     * 从她身上【原子地取出【一支箭】】—— 扣物品 = 物品守恒。
     *
     * <p>支持 ARROW / SPECTRAL_ARROW / TIPPED_ARROW。找不到返回 {@link ItemStack#EMPTY}。
     */
    public static ItemStack takeOneArrow(EntityMaid maid) {
        var bag = maid.getAvailableInv(true);
        for (int i = 0; i < bag.getSlots(); i++) {
            ItemStack s = bag.getStackInSlot(i);
            if (s.is(Items.ARROW) || s.is(Items.SPECTRAL_ARROW) || s.is(Items.TIPPED_ARROW)) {
                ItemStack one = bag.extractItem(i, 1, false);
                if (!one.isEmpty()) {
                    return one;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /** 让她朝目标看（仰角自动算）。 */
    public static void aimAt(EntityMaid maid, Vec3 target) {
        aimAt(maid, target, Float.NaN);
    }

    /**
     * 让她朝目标看，可用 {@code pitchOverride} 覆盖仰角（放箭时用解出来的 pitch）。
     *
     * <p>⚠️ 写 {@code setYRot/setYBodyRot/setYHeadRot}（和 break/place 一致），
     * <p><b>不用</b> {@code getLookControl().setLookAt}（那是 AI 的临时注视，会自动复位 ⇒ 甩头）。
     */
    public static void aimAt(EntityMaid maid, Vec3 target, float pitchOverride) {
        Vec3 from = eyeOf(maid);
        double dx = target.x - from.x;
        double dz = target.z - from.z;
        double dy = target.y - from.y;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        maid.setYRot(yaw);
        maid.setYBodyRot(yaw);
        maid.setYHeadRot(yaw);
        float pitch = Float.isNaN(pitchOverride)
                ? (float) (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))))
                : pitchOverride;
        maid.setXRot(pitch);
    }

    /**
     * 模拟箭的飞行，返回【整条轨迹中离目标最近的距离】（命中判据）。
     *
     * @return 轨迹与目标的最小距离（越小越准）
     */
    public static double closestApproach(Level level, Vec3 from, Vec3 vel, Vec3 target) {
        return closestPoint(level, from, vel, target).distanceTo(target);
    }

    /**
     * 轨迹上【离目标最近的那个点】（迭代瞄准要用它算「差了多少」）。
     *
     * <p>⚠️ 轨迹是折线 ⇒ 算「点到线段」最近点，不能只比离散采样点（会造出假缺口）。
     */
    public static Vec3 closestPoint(Level level, Vec3 from, Vec3 vel, Vec3 target) {
        Vec3 pos = from;
        Vec3 v = vel;
        Vec3 bestPos = pos;
        double best = pos.distanceTo(target);
        for (int i = 0; i < MAX_SIM_TICKS; i++) {
            Vec3 next = pos.add(v);
            boolean blocked = !level.isEmptyBlock(net.minecraft.core.BlockPos.containing(next));
            Vec3 onSeg = closestOnSegment(pos, next, target);
            double d = onSeg.distanceTo(target);
            if (d < best) {
                best = d;
                bestPos = onSeg;
            }
            if (blocked) {
                return bestPos;
            }
            pos = next;
            v = v.scale(DRAG).subtract(0, GRAVITY, 0);
        }
        return bestPos;
    }

    /** 线段 a→b 上离点 p 最近的点（{@code Mth.clamp(dot/len2,0,1)}）。 */
    public static Vec3 closestOnSegment(Vec3 a, Vec3 b, Vec3 p) {
        Vec3 ab = b.subtract(a);
        double len2 = ab.lengthSqr();
        if (len2 < 1.0e-9) {
            return a;
        }
        double tt = Mth.clamp(p.subtract(a).dot(ab) / len2, 0.0, 1.0);
        return a.add(ab.scale(tt));
    }

    /**
     * 用指定速度解仰角 —— 从 -85° 到 +85° 每 0.25° 扫，取轨迹误差最小的。
     *
     * <p>误差 &gt; {@link #HIT_TOLERANCE} 视为无解 ⇒ 返回 {@code NaN}（<b>不硬射</b>）。
     * 误差相同取【先扫到的】（= 更小的仰角 ⇒ 平射优先）。
     */
    public static float solvePitchWithSpeed(Level level, Vec3 from, Vec3 target, double speed) {
        double dx = target.x - from.x;
        double dz = target.z - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < 1.0e-3) {
            return Float.NaN;
        }
        double ux = dx / horiz;
        double uz = dz / horiz;
        float best = Float.NaN;
        double bestErr = Double.MAX_VALUE;
        for (float pitch = -85f; pitch <= 85f; pitch += 0.25f) {
            double rad = Math.toRadians(pitch);
            Vec3 vel = new Vec3(ux * Math.cos(rad) * speed, -Math.sin(rad) * speed,
                    uz * Math.cos(rad) * speed);
            double err = closestApproach(level, from, vel, target);
            if (err < bestErr - 1.0e-6) {
                bestErr = err;
                best = pitch;
            }
        }
        if (bestErr > HIT_TOLERANCE) {
            // 解不出仰角时留一条现场：起点 / 目标 / 速度 / 最小误差 / 误差最小的那个仰角
            // （下一步排查"明明看得见却判打不到"时，先看这条日志里的 bestErr）
            Vec3 cp = closestPoint(level, from,
                    velOf(from, target, best, speed), target);
            com.xiaofeng.maidcli.MaidCliMod.LOG.warn(
                    "[MaidCLI] 弹道无解 from={} target={} speed={} bestErr={} bestPitch={} closest={}",
                    String.format("%.2f,%.2f,%.2f", from.x, from.y, from.z),
                    String.format("%.2f,%.2f,%.2f", target.x, target.y, target.z),
                    speed, String.format("%.3f", bestErr), best,
                    String.format("%.2f,%.2f,%.2f", cp.x, cp.y, cp.z));
        }
        return (bestErr <= HIT_TOLERANCE) ? best : Float.NaN;
    }

    /**
     * ★ 迭代瞄准：把「这条弹道离目标的缺口」反向补偿到瞄点上，几轮收敛。
     *
     * <p>为什么需要：模拟和真实飞行有 ~0.8 格系统性差别（模型近似），靶方块半径只有 0.5 格
     * ⇒ 差这点就从边上擦过去了 ✗ ⇒ 用同样的缺口反推瞄点，缺口被抵消（不改物理模型）。
     *
     * @return {@code [pitch (Float), 最终瞄点 (Vec3), 缺口 (Double)]}；解不出来返回 {@code null}
     */
    public static Object[] aimIteratively(Level level, Vec3 from, Vec3 target, double speed) {
        Vec3 aimAt = target;
        float pitch = Float.NaN;
        double gap = Double.MAX_VALUE;
        for (int iter = 0; iter < 4; iter++) {
            float p = solvePitchWithSpeed(level, from, aimAt, speed);
            if (Float.isNaN(p)) {
                return null;
            }
            pitch = p;
            Vec3 closest = closestPoint(level, from, velOf(from, aimAt, p, speed), target);
            Vec3 delta = target.subtract(closest);
            gap = delta.length();
            if (gap < 0.15) {
                break;
            }
            aimAt = aimAt.add(delta);              // ★ 反向补偿
        }
        if (Float.isNaN(pitch)) {
            return null;
        }
        return new Object[]{pitch, aimAt, gap};
    }

    /** 由「仰角 + 瞄点」构出初速度向量。 */
    public static Vec3 velOf(Vec3 from, Vec3 aimAt, float pitch, double speed) {
        double dx = aimAt.x - from.x;
        double dz = aimAt.z - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < 1.0e-3) {
            return Vec3.ZERO;
        }
        double rad = Math.toRadians(pitch);
        return new Vec3(dx / horiz * Math.cos(rad) * speed, -Math.sin(rad) * speed,
                dz / horiz * Math.cos(rad) * speed);
    }
}
