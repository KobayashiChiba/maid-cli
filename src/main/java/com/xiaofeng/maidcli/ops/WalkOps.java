package com.xiaofeng.maidcli.ops;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.MaidCliMod;
import com.xiaofeng.maidcli.infra.MaidCliConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 走位的实现（★ 只给 {@code Action.tick()} 调，见 execution-model §3 ⑤）。
 *
 * <p>⚠️⚠️ <b>走位必须写 {@code WALK_TARGET} 记忆，不能调 {@code navigation.moveTo()}</b>
 * —— 后者会被她自己的 AI <b>每 tick 覆盖</b>，她一步不动（旧实现遇到过）。
 *
 * <p>⚠️ <b>到达判定不能用"手长"</b> —— {@code go} 用 8 格判定会让她"原地就算到了"。
 * 走路要单独一个到达半径（默认 3 格），手长只用于方块交互。
 */
public final class WalkOps {

    /**
     * ★ 内部走位的到达判定 = <b>1.0</b>（旧实现 {@code MaidEventRunner.ARRIVE_STANDPOINT}，
     * 早期实现的关键修正）。
     *
     * <p>为什么必须比 {@code go} 严：落脚点是按几何算出来的（"站这儿就不压目标格"），
     * 她 0.6 宽 —— 站到那一格中心时碰撞箱跨不出本格 ⇒ 一定不压邻格。
     * 实测 closeEnough=2 时她会停在落脚点 2 格多之外 ⇒ "不重叠"的结论全废 ⇒ 放置被自己挡住。
     * <p>手动 {@code go} 用 config 的 {@code walk_arrive_radius}（默认 3，宽松点没关系）。
     */
    public static final double ARRIVE_INTERNAL = 1.0;

    private WalkOps() {
    }

    /**
     * 写走位记忆（每 tick 都要重写 —— 她的 AI 会覆盖）。
     *
     * <p>{@code WalkTarget} 的 {@code closeEnough} 是 &lt;b&gt;int&lt;/b&gt;（原版寻路的"够近"判定，单位=格）。
     * 取值照抄旧实现 {@code MaidEventRunner.driveWalk}：
     * <ul>
     *   <li><b>内部走位</b>（够不着时插的那种，{@code closeEnough ≤ 1}）⇒ <b>0</b>：走到格子中心，
     *       这样"落脚点不压目标格"的几何结论才成立</li>
     *   <li><b>手动 go</b>（{@code closeEnough = 3}）⇒ <b>2</b>：宽松点，免得在窄处反复横跳</li>
     * </ul>
     */
    public static void setWalkTarget(EntityMaid maid, BlockPos target, double closeEnough) {
        int ce = closeEnough > 0 && closeEnough <= 1.0 ? 0 : 2;
        maid.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(target, 0.6f, ce));
    }

    /** 停住（清掉走位记忆） */
    public static void stopWalking(EntityMaid maid) {
        try {
            maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 到达了吗。
     *
     * <p>★ 判据按旧实现（{@code MaidEventRunner.java:716-720}）：
     * <p><b>水平距离 ≤ r 且 |dy| ≤ 1.0</b>。
     * <ul>
     *   <li>用<b>水平</b>距离，不是 3D 距离 —— 3D 距离会把"同一格但差半层"算成没到</li>
     *   <li>高度差 ≤ 1.0 —— 防"柱顶上被误判已到达"（实测发现）</li>
     * </ul>
     * ⚠️ 别再往松里改：v2 重写时写成 {@code d <= r + 1.0 && dy <= 2.0}，
     * 叠加 config 的 3.0 之后实际容差 = <b>4 格</b>，她停在目标 4 格外就算"到了"，
     * 视觉上就是"没走到位"（实测抓到）。
     */
    public static boolean arrived(EntityMaid maid, BlockPos target, double radius) {
        double r = radius > 0 ? radius : MaidCliConfig.get(maid.getServer()).walk_arrive_radius;
        double dx = maid.getX() - (target.getX() + 0.5);
        double dz = maid.getZ() - (target.getZ() + 0.5);
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double dy = Math.abs(maid.getY() - target.getY());
        return horiz <= r && dy <= 1.0;
    }

    /**
     * 找"能站的落脚点"（tp / dismount 的兜底用）。
     *
     * <p>⚠️⚠️ <b>注意：她曾被 tp 到空中 / 悬崖边摔死过两次</b> ——
     * 只看了"下方有没有实体"、<b>没看落差</b> ⇒ 落到下方 28 格才是地面 = 站在悬崖边 = 自由落体。
     * ⇒ 合格条件 = 该格空气 + 上方两格空气 + <b>下方一路向下找到的落差 ≤ 3</b>，
     * 且下方不是危险方块。
     */
    public static BlockPos findStandable(Level level, BlockPos around, int horizRange, int maxDrop) {
        // 先在水平范围内、同层优先找
        for (int dy = 0; dy >= -maxDrop; dy--) {
            for (int dx = -horizRange; dx <= horizRange; dx++) {
                for (int dz = -horizRange; dz <= horizRange; dz++) {
                    BlockPos p = around.offset(dx, dy, dz);
                    if (isStandable(level, p) && dropBelow(level, p, maxDrop) >= 0) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    /** 该格能不能站人：空气 + 上面也空气 + 下面实心且不危险 */
    public static boolean isStandable(Level level, BlockPos p) {
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                && !level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty()
                && !isDangerous(level, p.below())
                && !isDangerous(level, p);
    }

    /** 从 p 往下找实地，返回落差（0 = 脚下就是实地）；超过 maxDrop 返回 -1 = 悬崖 */
    public static int dropBelow(Level level, BlockPos p, int maxDrop) {
        for (int i = 0; i <= maxDrop; i++) {
            BlockPos q = p.below(i);
            if (!level.getBlockState(q).getCollisionShape(level, q).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** 危险方块（站上去会受伤 / 掉进去的） */
    public static boolean isDangerous(Level level, BlockPos p) {
        var s = level.getBlockState(p);
        var b = s.getBlock();
        String id = b.toString();
        return id.contains("campfire") || id.contains("fire") || id.contains("lava")
                || id.contains("cactus") || id.contains("sweet_berry") || id.contains("powder_snow")
                || id.contains("magma") || id.contains("pointed_dripstone") || id.contains("wither_rose")
                || id.contains("sculk") || s.getFluidState().isEmpty() == false && id.contains("water");
    }

    /** 调试用：把坐标写成 "x,y,z" */
    public static String fmt(BlockPos p) {
        return p == null ? "null" : p.getX() + "," + p.getY() + "," + p.getZ();
    }

    // ────────────────────────── 转向 ──────────────────────────

    /** 转身阈值（差 <15° 不转，防抖 —— 同一批朝一个方向放置时不会甩来甩去） */
    private static final float TURN_THRESHOLD = 15.0f;

    /**
     * 看向某个点（<b>身体水平转 + 头只上下调，放完【保持不复位】</b>）。
     *
     * <p>⛔ <b>千万别用 {@code getLookControl().setLookAt(...)}</b> —— 那是 AI 的"临时注视"，
     * 有"看一会儿自动复位"的机制 ⇒ 表现成"转过去→立刻弹回来"地甩头，非常假（用户指出过）。
     */
    public static void faceTarget(EntityMaid maid, Vec3 target) {
        double dx = target.x - maid.getX();
        double dz = target.z - maid.getZ();
        double dy = target.y - (maid.getY() + maid.getEyeHeight());
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float wantYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float delta = Math.abs(((wantYaw - maid.getYRot()) % 360f + 540f) % 360f - 180f);
        if (delta > TURN_THRESHOLD) {
            maid.setYRot(wantYaw);
            maid.setYBodyRot(wantYaw);
            maid.setYHeadRot(wantYaw);
        }
        maid.setXRot((float) (-Math.toDegrees(Math.atan2(dy, horiz))));
    }

    /** 看向某个实体（取它身体中心偏上一点） */
    public static void faceEntity(EntityMaid maid, net.minecraft.world.entity.Entity ent) {
        if (ent == null) {
            return;
        }
        faceTarget(maid, ent.position().add(0, ent.getBbHeight() * 0.5, 0));
    }

    static {
        // 让 MaidCliMod 的 LOG 在静态分析里不被当成未使用（走位失败时要用）
        assert MaidCliMod.MOD_ID != null;
    }
}
