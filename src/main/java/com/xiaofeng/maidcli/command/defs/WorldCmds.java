package com.xiaofeng.maidcli.command.defs;

import com.xiaofeng.maidcli.command.CmdRegistry;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.command.MaidCmd;
import com.xiaofeng.maidcli.command.Param;
import com.xiaofeng.maidcli.engine.Job;
import com.xiaofeng.maidcli.engine.MaidHandle;
import com.xiaofeng.maidcli.engine.actions.BreakAction;
import com.xiaofeng.maidcli.engine.actions.HitAction;
import com.xiaofeng.maidcli.engine.actions.OpenAction;
import com.xiaofeng.maidcli.engine.actions.PickupAction;
import com.xiaofeng.maidcli.engine.actions.PlaceAction;
import com.xiaofeng.maidcli.engine.actions.RideAction;
import com.xiaofeng.maidcli.engine.actions.TpAction;
import com.xiaofeng.maidcli.engine.actions.UseAction;
import com.xiaofeng.maidcli.engine.actions.WalkAction;
import com.xiaofeng.maidcli.infra.MaidCliConfig;
import com.xiaofeng.maidcli.transport.CmdException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界操作 8 条（COMMANDS.md §6.1）—— 前置状态：<b>界面必须没开</b>。
 *
 * <pre>
 * go · tp · hit · use · break · place · pickup · open
 * </pre>
 * 每条 = <b>校验 → 拆成 N 个 Action → 装 Job → 入队 → 受理即回</b>。
 * 走位由调度器动态插入（{@code needsReach} 的动作够不着时）。
 */
public final class WorldCmds {

    private WorldCmds() {
    }

    public static void registerAll() {
        CmdRegistry.register(new Go());
        CmdRegistry.register(new Tp());
        CmdRegistry.register(new Hit());
        CmdRegistry.register(new Use());
        CmdRegistry.register(new Break());
        CmdRegistry.register(new Place());
        CmdRegistry.register(new Pickup());
        CmdRegistry.register(new Open());
        CmdRegistry.register(new Ride());
    }

    // ────────────────────────── go ──────────────────────────

    static final class Go implements MaidCmd {
        @Override
        public String name() {
            return "go";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "走过去（支持多组坐标：依次走）；走不到默认传送兜底";
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("pos", Param.positions(true, "目标点（支持 a;b;c 批量）"));
            p.put("no_tp", Param.flag("no_tp", "禁止\"走不到就传送\"的兜底"));
            return p;
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            MaidHandle h = ctx.handle();
            JobUtil.requireNoSession(h, "go");
            List<BlockPos> pts = ctx.posList();
            if (pts.isEmpty()) {
                throw CmdException.badArg("go 要给 pos=<坐标>");
            }
            JobUtil.checkActionBudget(pts.size(), MaidCliConfig.get(ctx.server()).max_actions_per_job);
            boolean noTp = ctx.flag("no_tp", false);
            Job job = JobUtil.job(ctx, "go", null);
            for (BlockPos p : pts) {
                job.todo.addLast(new WalkAction(p, 0, !noTp));
            }
            h.enqueue(job);
            return JobUtil.accepted(job, h);
        }
    }

    // ────────────────────────── tp ──────────────────────────

    static final class Tp implements MaidCmd {
        @Override
        public String name() {
            return "tp";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "直接传送（不走寻路）；tp pos=x,y,z / tp entity=<id|owner>";
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("pos", Param.position(false, "目标点"));
            p.put("entity", Param.optEntity("entity", "传送到某个实体处：数字 id 或 owner"));
            p.put("max_distance", Param.optInt("max_distance", 64, "距离上限；0 = 不限（默认 64）"));
            return p;
        }

        @Override
        public void precheck(Map<String, Object> args) {
            boolean hasPos = args.get("pos") != null;
            boolean hasEntity = args.get("entity") != null;
            if (!hasPos && !hasEntity) {
                throw CmdException.badArg("tp 要给 pos=<坐标> 或 entity=<id|owner>");
            }
            if (hasPos && hasEntity) {
                throw CmdException.badArg("tp 的 pos 和 entity 只能给一个");
            }
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            MaidHandle h = ctx.handle();
            JobUtil.requireNoSession(h, "tp");
            BlockPos target;
            if (ctx.args().get("entity") != null) {
                int eid = ctx.entityId();
                net.minecraft.world.entity.Entity e = com.xiaofeng.maidcli.ops.EntityOps
                        .findById(ctx.maid().level(), ctx.maid(), eid);
                if (e == null) {
                    throw new CmdException(CmdException.NOT_FOUND,
                            "找不到实体 id=" + eid, "先用 sense entities 看看附近有什么");
                }
                target = e.blockPosition();
            } else {
                target = ctx.pos();
                if (target == null) {
                    throw CmdException.badArg("tp 要给 pos=<坐标> 或 entity=<id|owner>");
                }
            }
            Job job = JobUtil.job(ctx, "tp", null);
            // ★ 旧版：正数上限 512（防手滑输个 99999），≤0 = 不限
            int maxD = ctx.in("max_distance", 64);
            if (maxD > 512) {
                maxD = 512;
            }
            job.todo.addLast(new TpAction(target, maxD));
            h.enqueue(job);
            return JobUtil.accepted(job, h);
        }
    }

    // ────────────────────────── hit ──────────────────────────

    /** {@code hit entity=<id>} / {@code hit pos=<坐标>} / {@code hit} —— 左键短按 */
    static final class Hit implements MaidCmd {
        @Override
        public String name() {
            return "hit";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "左键短按：对实体 = 攻击（伤害+击退）；对方块 / 空气 = 挥手（空实现）";
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("entity", Param.optEntity("entity", "实体 id 或 owner（打它）"));
            p.put("pos", Param.position(false, "目标方块（只挥手）"));
            return p;
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            MaidHandle h = ctx.handle();
            JobUtil.requireNoSession(h, "hit");
            Job job = JobUtil.job(ctx, "hit", null);
            job.todo.addLast(new HitAction(ctx.entityId(), ctx.pos()));
            h.enqueue(job);
            return JobUtil.accepted(job, h);
        }
    }

    // ────────────────────────── use ──────────────────────────

    /** {@code use ...} —— 右键（方块 / 实体 / 空气 三选一） */
    static final class Use implements MaidCmd {
        @Override
        public String name() {
            return "use";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "右键：对方块（放/开门/拉杆/骨粉）· 对实体（挤奶/剪毛/喂食/上船）· 对空气（吃/喝/蓄力）";
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("pos", Param.positions(false, "目标方块（可批量）"));
            p.put("entity", Param.optInt("entity", 0, "目标实体 id"));
            p.put("face", Param.optStr("face", null, "贴哪一面 up/down/north/south/east/west"));
            p.put("item", Param.optStr("item", null, "用哪个物品（会自动换手）"));
            p.put("duration", Param.optInt("duration", -1, "持续多少 tick（只对蓄力类有意义；不给 = 自然完成）"));
            p.put("aim", Param.optStr("aim", null, "单位向量 \"x,y,z\"（只对空气有效）"));
            return p;
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            MaidHandle h = ctx.handle();
            JobUtil.requireNoSession(h, "use");
            int entity = ctx.in("entity", 0);
            List<BlockPos> pts = ctx.posList();
            if (entity <= 0 && pts.isEmpty()) {
                // 对空气
                Job job = JobUtil.job(ctx, "use", null);
                job.todo.addLast(new UseAction(null, 0, ctx.opt("item"), null,
                        ctx.in("duration", -1), ctx.opt("aim")));
                h.enqueue(job);
                return JobUtil.accepted(job, h);
            }
            if (entity > 0 && !pts.isEmpty()) {
                throw CmdException.badArg("use 的 entity 和 pos 只能给一个");
            }
            Job job = JobUtil.job(ctx, "use", null);
            if (entity > 0) {
                job.todo.addLast(new UseAction(null, entity, ctx.opt("item"), null, -1, null));
            } else {
                JobUtil.checkActionBudget(pts.size(), MaidCliConfig.get(ctx.server()).max_actions_per_job);
                for (BlockPos p : pts) {
                    job.todo.addLast(new UseAction(p, 0, ctx.opt("item"), ctx.opt("face"), -1, null));
                }
            }
            h.enqueue(job);
            return JobUtil.accepted(job, h);
        }
    }

    // ────────────────────────── break ──────────────────────────

    static final class Break implements MaidCmd {
        @Override
        public String name() {
            return "break";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "挖方块（自动换最优工具）；expect 给了就校验（不符跳过）；drops 默认进背包";
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("pos", Param.positions(true, "目标方块（支持 a;b;c 批量）"));
            p.put("expect", Param.optStr("expect", null, "\"挖到的东西应该是这个\"（不符跳过）"));
            p.put("drops", Param.enums("drops", false, "inv", "inv 进背包 / ground 留在地上", "inv", "ground"));
            return p;
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            MaidHandle h = ctx.handle();
            JobUtil.requireNoSession(h, "break");
            List<BlockPos> pts = ctx.posList();
            if (pts.isEmpty()) {
                throw CmdException.badArg("break 要给 pos=<坐标>");
            }
            JobUtil.checkActionBudget(pts.size(), MaidCliConfig.get(ctx.server()).max_actions_per_job);
            boolean ground = "ground".equals(ctx.str("drops", "inv"));
            Job job = JobUtil.job(ctx, "break", null);
            for (BlockPos p : pts) {
                job.todo.addLast(new BreakAction(p, ctx.opt("expect"), ground));
            }
            h.enqueue(job);
            return JobUtil.accepted(job, h);
        }
    }

    // ────────────────────────── place ──────────────────────────

    static final class Place implements MaidCmd {
        @Override
        public String name() {
            return "place";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "放方块（自动取方块换手）；朝向/半砖/开关写在 item 的 blockstate 里";
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("pos", Param.positions(true, "目标位置（支持 a;b;c 批量）"));
            p.put("item", Param.optStr("item", null, "放什么（可带 blockstate，如 oak_stairs[facing=north]）"));
            p.put("interval", Param.optInt("interval", 8,
                    "放置间隔（tick）—— 默认 8 = 0.4 秒（旧版默认），别\"秒放\"，一眼假"));
            return p;
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            MaidHandle h = ctx.handle();
            JobUtil.requireNoSession(h, "place");
            List<BlockPos> pts = ctx.posList();
            if (pts.isEmpty()) {
                throw CmdException.badArg("place 要给 pos=<坐标>");
            }
            JobUtil.checkActionBudget(pts.size(), MaidCliConfig.get(ctx.server()).max_actions_per_job);
            Job job = JobUtil.job(ctx, "place", null);
            int interval = Math.max(2, ctx.in("interval", 8));
            for (BlockPos p : pts) {
                job.todo.addLast(new PlaceAction(p, ctx.opt("item"), interval));
            }
            h.enqueue(job);
            return JobUtil.accepted(job, h);
        }
    }

    // ────────────────────────── pickup ──────────────────────────

    static final class Pickup implements MaidCmd {
        @Override
        public String name() {
            return "pickup";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "定向捡起来（一次捡一个）；entity=<实体id> 或 item=<物品id> 或都不给=捡最近的";
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("entity", Param.optInt("entity", 0, "掉落物实体 id"));
            p.put("item", Param.optStr("item", null, "物品 id（捡这种）"));
            return p;
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            MaidHandle h = ctx.handle();
            JobUtil.requireNoSession(h, "pickup");
            Job job = JobUtil.job(ctx, "pickup", null);
            job.todo.addLast(new PickupAction(ctx.in("entity", 0), ctx.opt("item")));
            h.enqueue(job);
            return JobUtil.accepted(job, h);
        }
    }

    // ────────────────────────── open ──────────────────────────

    static final class Open implements MaidCmd {
        @Override
        public String name() {
            return "open";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "打开界面（界面操作的入口）：open pos= 方块 · open entity= 实体 · open 自己背包";
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("pos", Param.position(false, "方块坐标（容器 / 工位）"));
            p.put("entity", Param.optInt("entity", 0, "实体 id（村民 / 马）"));
            return p;
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            MaidHandle h = ctx.handle();
            JobUtil.requireNoSession(h, "open");
            int entity = ctx.in("entity", 0);
            BlockPos pos = ctx.pos();
            if (entity > 0 && pos != null) {
                throw CmdException.badArg("open 的 pos 和 entity 只能给一个");
            }
            boolean selfInv = entity <= 0 && pos == null;
            Job job = JobUtil.job(ctx, "open", null);
            job.todo.addLast(new OpenAction(h, pos, entity, selfInv));
            h.enqueue(job);
            return JobUtil.accepted(job, h);
        }
    }

    // ────────────────────────── ride ──────────────────────────

    /** {@code ride target=minecraft:boat} / {@code ride entity_id=<id>} —— 让她自己骑上去 */
    static final class Ride implements MaidCmd {
        @Override
        public String name() {
            return "ride";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "骑上载具（船 / 矿车 / 马…）：target=类型 或 entity=<id>；她自己坐上去，不是假玩家";
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("target", Param.optStr("target", null, "目标类型（minecraft:boat / minecraft:minecart …）"));
            p.put("entity", Param.optInt("entity", 0, "目标实体 id（优先于 target）"));
            return p;
        }

        @Override
        public void precheck(Map<String, Object> args) {
            boolean hasTarget = args.get("target") != null;
            Object id = args.get("entity");
            boolean hasId = id instanceof Number n && n.intValue() > 0;
            if (!hasTarget && !hasId) {
                throw CmdException.badArg("ride 要给 target=<类型> 或 entity=<id>");
            }
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            MaidHandle h = ctx.handle();
            JobUtil.requireNoSession(h, "ride");
            Job job = JobUtil.job(ctx, "ride", null);
            job.todo.addLast(new RideAction(ctx.opt("target"), ctx.in("entity", 0)));
            h.enqueue(job);
            return JobUtil.accepted(job, h);
        }
    }

}
