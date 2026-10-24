package com.xiaofeng.maidcli.engine.actions;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.engine.Action;
import com.xiaofeng.maidcli.MaidCliMod;
import com.xiaofeng.maidcli.infra.MaidCliConfig;
import com.xiaofeng.maidcli.ops.ItemOps;
import com.xiaofeng.maidcli.ops.ShootOps;
import com.xiaofeng.maidcli.transport.CmdException;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 射箭 {@code shoot}（COMMANDS.md §6.4 · 世界宏 · {@code use} 的糖）。
 *
 * <p>拿弓 → 瞄准（弹道解算）→ 蓄力 → 发射 → 追踪落点。内部状态机：
 * {@code PREPARE → CHARGE → RELEASE → TRACK}。
 *
 * <p>调用方给一个<b>精确点</b>（{@link Vec3}，整数 = 方块中心的语义由调用方在解析时处理）。
 * 算法全在 {@link ShootOps}（详见那边的注意事项）。
 *
 * <p><b>⚠️ 关键约束（别走回头路）</b>
 * <ul>
 *   <li><b>必须真蓄力</b>：{@code startUsingItem(MAIN_HAND)}，蓄够 config 的
 *       {@code shoot_charge_ticks}（默认 20 tick）才放。</li>
 *   <li>⛔ <b>不用 {@code BowItem.releaseUsing()}</b> —— 那是给玩家写的，对女仆静默失败。
 *       <b>自己造箭 + 自己扣一支</b>（{@link ShootOps#takeOneArrow} +
 *       {@code ProjectileUtil.getMobArrow}）。</li>
 *   <li>⛔ <b>不用 TLM 的 clamp 速度</b> ⇒ 用满蓄力速度（config {@code shoot_speed}，默认 3.0）。</li>
 *   <li>落点追踪报告 {@code {hit, landed, offset}}（{@link #result}）。</li>
 * </ul>
 */
public final class ShootAction extends Action {

    private enum St {
        PREPARE, CHARGE, RELEASE, TRACK
    }

    /** 目标精确点（Vec3，不是 BlockPos）—— 打实体时这只是"最初瞄点"，会随预判更新 */
    private final Vec3 target;
    /** 打实体时的实体 id（0 = 打固定坐标，不预判） */
    private final int targetEntityId;
    /** 实际用的瞄点（预判时会从 target 往前推提前量，旧 ShootOps.aimPoint） */
    private Vec3 aimTarget;
    /** ★ 蓄力期间采到的目标轨迹（旧 ShootOps.Job.track）—— 每一 tick 一个点 */
    private final java.util.List<Vec3> trackTicks = new java.util.ArrayList<>();

    private St st = St.PREPARE;
    private EntityMaid maidRef;

    /** 蓄力已过的 tick 数 */
    private int ticks;
    /** config：蓄力时长（tick） */
    private int chargeTicks = 20;
    /** config：满蓄力初速 */
    private double speed = 3.0;

    private int arrowId = -1;
    private int flightTicks;

    /** 轨迹离目标最近距离（命中判据 + 报告 offset） */
    private double bestApproach = -1;
    /** 箭的落点（追踪不到为 null） */
    private Vec3 landed;
    private boolean hit;
    private String message = "";

    /** 换弓时备份的原主手物品（收尾 / 中断时还回） */
    private ItemStack backup;
    private boolean swapped;

    public ShootAction(Vec3 target) {
        this(target, 0);
    }

    /** @param targetEntityId 打实体时给实体 id（&gt;0 才开移动靶预判） */
    public ShootAction(Vec3 target, int targetEntityId) {
        this.target = target;
        this.aimTarget = target;
        this.targetEntityId = targetEntityId;
    }

    // ────────────────────────── 元信息 ──────────────────────────

    @Override
    public String op() {
        return "shoot";
    }

    /** 要走近才射得准 ⇒ 声明需要走位 */
    @Override
    public boolean needsReach() {
        return false;   // ★ 射箭【站着射】—— 不要走到手长内（那是放置/挖掘的规矩）
    }

    @Override
    public BlockPos reachTarget() {
        return BlockPos.containing(target);
    }

    @Override
    public BlockPos targetPos() {
        return BlockPos.containing(target);
    }

    @Override
    public float progress() {
        return switch (st) {
            case PREPARE -> 0f;
            case CHARGE -> Math.min(0.9f, 0.1f + 0.8f * ticks / Math.max(1f, (float) chargeTicks));
            case RELEASE -> 0.92f;
            case TRACK -> 0.95f;
        };
    }

    // ────────────────────────── 主循环 ──────────────────────────

    @Override
    public Phase tick(ServerLevel lv, EntityMaid maid) {
        maidRef = maid;
        try {
            return switch (st) {
                case PREPARE -> prepare(lv, maid);
                case CHARGE -> charge(lv, maid);
                case RELEASE -> release(lv, maid);
                case TRACK -> track(lv, maid);
            };
        } catch (CmdException e) {
            return fail(e.getMessage());
        } catch (Throwable t) {
            return fail("射箭失败：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
        }
    }

    // ────────────────────────── ① 准备：拿弓 + 起蓄力 ──────────────────────────

    private Phase prepare(ServerLevel lv, EntityMaid maid) {
        // 主手不是弓 ⇒ 从背包换过来（拿弓）。找不到会抛 CmdException（在下面拦成友好话）。
        if (!(maid.getMainHandItem().getItem() instanceof BowItem)) {
            try {
                backup = ItemOps.takeToMainHand(maid, "minecraft:bow", 1);
            } catch (CmdException e) {
                return fail("她身上没有弓（先把弓给她，或放到背包里）");
            }
            swapped = backup != null;
        }
        if (!(maid.getMainHandItem().getItem() instanceof BowItem)) {
            return fail("她身上没有弓（没找到可用的弓）");
        }
        // 速度 / 蓄力时间来自 mod config（不让调用方估）
        MaidCliConfig.Data cfg = MaidCliConfig.get(lv.getServer());
        speed = cfg.shoot_speed;
        chargeTicks = Math.max(1, cfg.shoot_charge_ticks);

        // ★ 真蓄力（有过程感）+ 起手瞄准
        maid.startUsingItem(InteractionHand.MAIN_HAND);
        aimTarget = target;
        ShootOps.aimAt(maid, aimTarget);
        ticks = 0;
        st = St.CHARGE;
        return Phase.RUNNING;
    }

    // ────────────────────────── ② 蓄力：全程保持瞄准 ──────────────────────────

    private Phase charge(ServerLevel lv, EntityMaid maid) {
        // ★ 移动靶预判（旧 ShootOps：蓄力期间采样轨迹，放箭前算提前量）
        sampleTrack(lv);
        ShootOps.aimAt(maid, aimTarget);
        ticks++;
        if (ticks < chargeTicks) {
            return Phase.RUNNING;
        }
        st = St.RELEASE;
        return release(lv, maid);
    }

    // ────────────────────────── ③ 放箭：解仰角 + 造箭 + 射出 ──────────────────────────

    private Phase release(ServerLevel lv, EntityMaid maid) {
        maid.stopUsingItem();
        // ★ 旧版：蓄力结束 ⇒ 用采到的轨迹算提前量（迭代 2 次收敛），把瞄点前推
        applyLead();
        ItemStack bow = maid.getMainHandItem();
        if (!(bow.getItem() instanceof BowItem)) {
            return fail("射不出来：弓不在手上了");
        }
        ItemStack arrowStack = ShootOps.takeOneArrow(maid);
        if (arrowStack.isEmpty()) {
            return fail("射不出来：她身上没有箭了");
        }
        Vec3 from = ShootOps.eyeOf(maid);

        // ★ 迭代瞄准：解仰角 + 把「模拟与真实的缺口」反向补偿（停在 0.02 格量级）
        Object[] aim = ShootOps.aimIteratively(lv, from, aimTarget, speed);
        if (aim == null) {
            return fail("射不中：按配置的速度（" + speed + "）算，这个距离/高度打不到");
        }
        float pitch = (Float) aim[0];
        Vec3 finalAim = (Vec3) aim[1];
        // ⚠️ dx/dy/dz 必须基于【修正后】的瞄点重算（顺序错了会射偏）
        ShootOps.aimAt(maid, finalAim, pitch);

        double dx = finalAim.x - from.x;
        double dy = finalAim.y - from.y;
        double dz = finalAim.z - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < 1.0e-3) {
            return fail("射不出来：目标和她在同一个位置");
        }

        // 照 TLM：getMobArrow 造箭（伤害/药水都处理好）
        AbstractArrow arrow = ProjectileUtil.getMobArrow(maid, arrowStack, 1.0f, bow);
        arrow.setPos(from.x, from.y - 0.1, from.z);
        double rad = Math.toRadians(pitch);
        double vx = dx / horiz * Math.cos(rad);
        double vy = -Math.sin(rad);
        double vz = dz / horiz * Math.cos(rad);
        arrow.shoot(vx, vy, vz, (float) speed, 0.0f);
        arrow.setOwner(maid);
        lv.addFreshEntity(arrow);
        bow.hurtAndBreak(1, maid, EquipmentSlot.MAINHAND);

        arrowId = arrow.getId();
        // 命中判据：这条弹道离目标的最近距离（点到线段，见 ShootOps）
        bestApproach = ShootOps.closestApproach(lv, from, new Vec3(vx, vy, vz).scale(speed), aimTarget);
        flightTicks = 0;
        st = St.TRACK;
        return Phase.RUNNING;
    }

    // ────────────────────────── 移动靶预判（旧 ShootOps 搬回） ──────────────────────────

    /** 实体中心点（旧 {@code aimPointOf}） */
    private static Vec3 centerOf(Entity e) {
        return e.position().add(0, e.getBbHeight() * 0.5, 0);
    }

    /** 蓄力期间每 tick 采一次目标位置（实体没了就不采） */
    private void sampleTrack(ServerLevel lv) {
        if (targetEntityId <= 0) {
            return;
        }
        Entity e = lv.getEntity(targetEntityId);
        if (e == null || !e.isAlive()) {
            return;
        }
        Vec3 p = centerOf(e);
        trackTicks.add(p);
        aimTarget = p;                       // 目标一直在动 ⇒ 瞄准跟着更新（旧行为）
    }

    /** 用轨迹估速度 + 迭代算提前量（旧 {@code estimateVelocity} + 两轮迭代） */
    private void applyLead() {
        if (targetEntityId <= 0 || trackTicks.size() < 2) {
            return;
        }
        int n = trackTicks.size();
        Vec3 first = trackTicks.get(0);
        Vec3 last = trackTicks.get(n - 1);
        Vec3 vel = last.subtract(first).scale(1.0 / (n - 1));   // 每 tick 位移
        Vec3 from = maidRef == null ? last : ShootOps.eyeOf(maidRef);
        double flight = last.distanceTo(from) / Math.max(0.5, speed);
        for (int i = 0; i < 2; i++) {                            // 迭代两次收敛（旧实现）
            Vec3 guess = last.add(vel.scale(flight));
            flight = guess.distanceTo(from) / Math.max(0.5, speed);
        }
        aimTarget = last.add(vel.scale(flight));
        MaidCliMod.LOG.info("[MaidCLI] shoot 预判：采样{}点 速度={} 提前量={}",
                n, vel, aimTarget.subtract(last));
    }

    // ────────────────────────── ④ 追踪落点 ──────────────────────────

    private Phase track(ServerLevel lv, EntityMaid maid) {
        Entity arrow = arrowId < 0 ? null : lv.getEntity(arrowId);
        flightTicks++;
        boolean stopped = arrow == null || arrow.isRemoved() || arrow.onGround()
                || flightTicks > ShootOps.MAX_FLIGHT_TICKS;
        if (!stopped) {
            return Phase.RUNNING;
        }
        landed = arrow == null ? null : arrow.position();
        hit = bestApproach >= 0 && bestApproach <= ShootOps.HIT_TOLERANCE;

        StringBuilder sb = new StringBuilder();
        if (landed != null) {
            sb.append("箭落在 ").append(BlockPos.containing(landed).toShortString());
        } else {
            sb.append("箭已消失（落点没追踪到）");
        }
        if (bestApproach >= 0) {
            sb.append("，轨迹最近处离目标 ").append(round2(bestApproach)).append(" 格");
            sb.append(hit ? " ✓ 命中" : "（差 " + Math.round(bestApproach) + " 格）");
        }
        message = sb.toString();
        return Phase.DONE;
    }

    // ────────────────────────── 结果 / 收尾 ──────────────────────────

    /**
     * 这一步的结果（进 history 的 {@code extra}）：
     * {@code { hit: true|false, landed: [x,y,z], offset: 0.02, target: [x,y,z] }}。
     */
    public Map<String, Object> result() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hit", hit);
        if (landed != null) {
            m.put("landed", List.of(round2(landed.x), round2(landed.y), round2(landed.z)));
        }
        if (bestApproach >= 0) {
            m.put("offset", round2(bestApproach));
        }
        m.put("target", List.of(round2(target.x), round2(target.y), round2(target.z)));
        if (!message.isEmpty()) {
            m.put("message", message);
        }
        return m;
    }

    @Override
    public void abort() {
        // 收弓（别让她一直卡在蓄力姿势）
        if (maidRef != null) {
            try {
                if (maidRef.isUsingItem()) {
                    maidRef.stopUsingItem();
                }
            } catch (Throwable ignored) {
            }
        }
        // 我们是换过弓的 ⇒ 把原主手物品还回去（物品守恒，绝不吞）
        if (swapped && maidRef != null) {
            try {
                ItemOps.restoreMainHand(maidRef, backup);
            } catch (Throwable ignored) {
            }
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
