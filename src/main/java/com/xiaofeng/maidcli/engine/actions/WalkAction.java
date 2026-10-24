package com.xiaofeng.maidcli.engine.actions;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.engine.Action;
import com.xiaofeng.maidcli.infra.MaidCliConfig;
import com.xiaofeng.maidcli.ops.WalkOps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 走位（core-concepts §5 / execution-model §3 ③）。
 *
 * <p>它是<b>独立 Action</b>、被调度器<b>动态插队</b>（在"够不着的那个动作"前面）。
 *
 * <p>⚠️⚠️ <b>必须自带超时</b>（execution-model §3 ③b）—— 不然会死循环：
 * <pre>
 * 执行动作 A → 够不着 → 插走位 → 走位失败 → 下一 tick 又执行 A → 又够不着 → … ✗
 * </pre>
 * （走位失败时由 {@code MaidHandle} 把被挡路的那个动作一并判失败。）
 *
 * <p>⚠️ 插队的走位<b>不带传送兜底</b> —— 传送是 {@code go} 指令的语义，不该偷偷用在别的动作里。
 */
public final class WalkAction extends Action {

    private final BlockPos target;
    private final double arriveRadius;
    private final long timeoutTicks;
    /** ★ true = 走不到就传送过去（只有 `go` 指令会带 —— 插队的走位不带，见类注释） */
    private final boolean teleportFallback;
    private long startTick = -1;
    /** ★ 卡住计数（旧实现：navigation 已停但没到 ⇒ 卡住；累计 >40 就按超时处理） */
    private int stuckTicks;

    /**
     * @param target            走到哪
     * @param arriveRadius      到达容差（0 = 用 config.walk_arrive_radius）
     */
    public WalkAction(BlockPos target, double arriveRadius) {
        this(target, arriveRadius, false);
    }

    public WalkAction(BlockPos target, double arriveRadius, boolean teleportFallback) {
        this.target = target;
        this.arriveRadius = arriveRadius;
        this.timeoutTicks = -1;   // 用 config
        this.teleportFallback = teleportFallback;
    }

    @Override
    public void start(ServerLevel lv, EntityMaid maid) {
        startTick = maid.getServer().getTickCount();
        WalkOps.setWalkTarget(maid, target, arriveRadius);
    }

    @Override
    public Phase tick(ServerLevel lv, EntityMaid maid) {
        if (startTick < 0) {
            start(lv, maid);
        }
 // 每 tick 重写走位记忆 —— 她自己的 AI 会覆盖它（旧实现遇到过的坑）
        WalkOps.setWalkTarget(maid, target, arriveRadius);
        if (WalkOps.arrived(maid, target, arriveRadius)) {
            WalkOps.stopWalking(maid);
            return Phase.DONE;
        }
        // ★ 旧实现的"卡住"判定：寻路已经停了（isDone）却还没到 ⇒ 记一次卡住
 // （40 次 = 2 秒；旧 driveWalk 就是这个阈值，超了走传送兜底/判失败）
        boolean stuck = maid.getNavigation().isDone();
        if (stuck) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        // ★ 超时两档（旧版）：内部走位（够不着时插的那种）= 30 秒；用户手动 go = config 的 15 秒
        long limit = timeoutTicks > 0 ? timeoutTicks
                : (arriveRadius > 0 && arriveRadius <= 1.0
                        ? 30L : MaidCliConfig.get(maid.getServer()).walk_timeout_seconds) * 20L;
        if (stuckTicks > 40 || maid.getServer().getTickCount() - startTick > limit) {
            WalkOps.stopWalking(maid);
            if (teleportFallback) {
                // ★ 旧实现 teleportFallback：走不到/卡住就【传送到算好的落脚点】
 // （内部走位也一样 —— 落脚点本来就是 StandPoint.safe 选出来的可站点，
 // 所以先精确传过去；原版 randomTeleport 自带碰撞箱判定，塞不下会返回 false）
                if (maid.randomTeleport(target.getX() + 0.5, target.getY(),
                        target.getZ() + 0.5, true)) {
                    return Phase.DONE;
                }
                BlockPos landing = WalkOps.findStandable(lv, target, 3, 8);
                if (landing != null) {
                    boolean ok = maid.randomTeleport(landing.getX() + 0.5, landing.getY(),
                            landing.getZ() + 0.5, true);
                    if (ok) {
                        return Phase.DONE;
                    }
                }
                return fail("走不到目标，传送兜底也失败（附近找不到安全落脚点）");
            }
            return fail("走不到目标（超时）");
        }
        return Phase.RUNNING;
    }

    @Override
    public String op() {
        return "walk";
    }

    @Override
    public BlockPos targetPos() {
        return target;
    }

    @Override
    public void abort() {
        // 不用"把借的东西还回去"，但要把走位意图停掉
        startTick = -1;
    }

    /** 离目标多远（进度用） */
    @Override
    public float progress() {
        return 0f;
    }
}
