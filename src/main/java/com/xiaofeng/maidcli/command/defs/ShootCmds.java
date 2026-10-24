package com.xiaofeng.maidcli.command.defs;

import com.xiaofeng.maidcli.command.MaidCmd;
import com.xiaofeng.maidcli.command.PosParse;
import com.xiaofeng.maidcli.engine.Job;
import com.xiaofeng.maidcli.engine.MaidHandle;
import com.xiaofeng.maidcli.engine.actions.ShootAction;
import com.xiaofeng.maidcli.ops.EntityOps;
import com.xiaofeng.maidcli.transport.CmdException;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

/**
 * {@code macro shoot} —— 射箭（COMMANDS.md §6.4）。
 *
 * <pre>
 * macro shoot pos=<精确点> [timeout=10]     打一个点（整数=方块中心，小数=精确点）
 * macro shoot entity=<实体id> [timeout=10]  打实体（自动取它的位置）
 * macro shoot owner=true                    打主人
 * </pre>
 *
 * <p>行为：拿弓 → <b>弹道解算</b>（模拟 + 迭代补偿瞄点）→ 蓄力 → 发射 → 追踪箭的落点。
 * <p>做完写进历史：{@code {op:"macro", name:"shoot", ok, hit, landed, offset}}
 * <p>初速度 / 蓄力时长是 <b>mod config</b>（不让调用方估）。
 *
 * <p>★ 本类<b>不再单独注册指令</b>：它是 {@code macro} 的一个子命令，
 * 由 {@link MacroCmds} 的 {@code Macro} 分发进来（注册统一、实现单独）。
 */
public final class ShootCmds {

    private ShootCmds() {
    }

    /** 子命令 {@code macro shoot} 的前置校验（由 {@code MacroCmds.Macro.precheck} 调） */
    static void precheckShoot(Map<String, Object> args) {
        boolean hasPos = args.get("pos") != null;
        boolean hasEntity = args.get("entity") != null;
        int n = (hasPos ? 1 : 0) + (hasEntity ? 1 : 0);
        if (n != 1) {
            throw CmdException.badArg(
                    "macro shoot 要给 pos=<坐标> 或 entity=<id|owner> 之一（且只给一个）");
        }
    }

    /** 子命令 {@code macro shoot} 的执行（由 {@code MacroCmds.Macro.run} 调） */
    static Map<String, Object> runShoot(MaidCmd.Ctx ctx) {
        MaidHandle h = ctx.handle();
        JobUtil.requireNoSession(h, "macro shoot");
        Vec3 target = resolveTarget(ctx);
        Job job = JobUtil.job(ctx, "macro", "shoot");
        job.timeoutTicks = (long) (ctx.dbl("timeout", 10) * 20);
        // ★ 目标是实体 ⇒ 把实体 id 一起传下去（开移动靶预判；旧 ShootOps 的 predict 路径）
        int eid = ctx.entityIsOwner() ? 0 : Math.max(0, ctx.entityId());
        job.todo.addLast(new ShootAction(target, eid));
        h.enqueue(job);                      // ★ 别忘了入队！（漏了就是"受理了但永远不会执行"）
        Map<String, Object> out = JobUtil.accepted(job, h);
        out.put("target", List.of(target.x, target.y, target.z));
        return out;
    }

    /**
     * 目标解析。
     * <p>⚠️ 坐标语义：<b>整数 = 打那个方块的中心（+0.5）；小数 = 打这个精确点</b>。
     * 调用方想"指哪打哪"就给小数。
     */
    private static Vec3 resolveTarget(MaidCmd.Ctx ctx) {
        if (ctx.entityIsOwner()) {
            ServerPlayer owner = ctx.server().getPlayerList().getPlayer(ctx.maid().getOwnerUUID());
            if (owner == null) {
                throw CmdException.badState("她主人不在线（射主人要他在）", "改用 macro shoot pos=");
            }
            return owner.getEyePosition();
        }
        int eid = ctx.entityId();
        if (eid > 0) {
            Entity e = EntityOps.findById(ctx.maid().level(), ctx.maid(), eid);
            if (!(e instanceof LivingEntity le) || !le.isAlive()) {
                throw new CmdException(CmdException.NOT_FOUND,
                        "找不到实体 id=" + eid + "（32 格内）", "先用 sense entities 看看附近有什么");
            }
            // 打身体中心（比脚底准）
            return le.position().add(0, le.getBbHeight() * 0.5, 0);
        }
        Object raw = ctx.args().get("pos");
        if (raw == null) {
            throw CmdException.badArg("macro shoot 要给 pos=<坐标> / entity=<id> / owner=true 之一");
        }
        List<PosParse.P> pts = PosParse.parsePoints(raw);
        if (pts.isEmpty()) {
            throw CmdException.badArg("坐标解析不出来");
        }
        PosParse.P p = pts.get(0);
        boolean allInt = p.x() == Math.floor(p.x()) && p.y() == Math.floor(p.y()) && p.z() == Math.floor(p.z());
        return allInt
                ? new Vec3(p.x() + 0.5, p.y() + 0.5, p.z() + 0.5)   // 整数 = 方块中心
                : new Vec3(p.x(), p.y(), p.z());                     // 小数 = 精确点
    }
}
