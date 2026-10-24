package com.xiaofeng.maidcli.command.defs;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.MaidCliMod;
import com.xiaofeng.maidcli.command.CmdRegistry;
import com.xiaofeng.maidcli.command.MaidCmd;
import com.xiaofeng.maidcli.command.Param;
import com.xiaofeng.maidcli.engine.MaidHandle;
import com.xiaofeng.maidcli.engine.MaidRegistry;
import com.xiaofeng.maidcli.engine.Session;
import com.xiaofeng.maidcli.transport.CmdException;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询类（COMMANDS.md §5）—— 世界类 {@code maids} / {@code blocks} · 女仆类 {@code sense <域>}。
 *
 * <p>⚠️ 名字 {@code blocks} 出现两次，是两个不同的东西：
 * <ul>
 *   <li>顶层的 {@code blocks} = 独立查询指令（查<b>世界</b>方块，按你给的坐标，不用唤醒）</li>
 *   <li>{@code sense blocks} = 她<b>身边</b>的方块（以她站的位置为中心，要唤醒）</li>
 * </ul>
 */
public final class QueryCmds {

    private QueryCmds() {
    }

    public static void registerAll() {
        CmdRegistry.register(new Maids());
        CmdRegistry.register(new WorldBlocks());
        CmdRegistry.register(new Sense());
    }

    // ────────────────────────── maids（世界类） ──────────────────────────

    static final class Maids implements MaidCmd {
        @Override
        public String name() {
            return "maids";
        }

        @Override
        public Kind kind() {
            return Kind.QUERY;
        }

        @Override
        public String desc() {
            return "列出所有女仆（含未加载的）—— 唤醒之前用它";
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("filter", Param.optStr("filter", null, "只看匹配的（名字 / 标签 / uuid）"));
            return p;
        }

        @Override
        public boolean worldOk() {
            return true;
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            String filter = ctx.opt("filter");
            List<Map<String, Object>> all = MaidRegistry.listAll(ctx.server());
            if (filter != null && !filter.isBlank()) {
                String f = filter.toLowerCase();
                all.removeIf(o -> {
                    String n = String.valueOf(o.get("name")).toLowerCase();
                    String t = String.valueOf(o.get("tag")).toLowerCase();
                    String u = String.valueOf(o.get("uuid")).toLowerCase();
                    return !n.contains(f) && !t.contains(f) && !u.startsWith(f);
                });
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("maids", all);
            return out;
        }
    }

    // ────────────────────────── blocks（世界类，按坐标） ──────────────────────────

    static final class WorldBlocks implements MaidCmd {
        @Override
        public String name() {
            return "blocks";
        }

        @Override
        public Kind kind() {
            return Kind.QUERY;
        }

        @Override
        public String desc() {
            return "读世界方块（按给定坐标；list 逐格 / count 只统计）";
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("pos", Param.position(true, "单点 / 方区 / 球（§2.1 坐标格式）"));
            p.put("state", Param.bool("state", true, "是否列状态属性"));
            p.put("nbt", Param.bool("nbt", false, "是否列方块实体 NBT"));
            p.put("mode", Param.enums("mode", false, "list", "list 逐格 / count 只统计", "list", "count"));
            return p;
        }

        @Override
        public boolean worldOk() {
            return true;
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            return readBlocks(ctx, null, 32768, ctx.pos());
        }
    }

    // ────────────────────────── sense ──────────────────────────

    /** {@code sense <域>} —— "感知"对她就是一件事（她在看），域是"看哪一方面" */
    static final class Sense implements MaidCmd {

        @Override
        public String name() {
            return "sense";
        }

        @Override
        public Kind kind() {
            return Kind.QUERY;
        }

        @Override
        public String desc() {
            return "女仆感知：sense self|state|inventory|craftable|blocks|entities|session|world";
        }

        @Override
        public SubSpec sub() {
            return new SubSpec("domain", true,
                    "self / state / inventory / craftable / blocks / entities / session / world");
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("domain", Param.reqStr("domain", "self / state / inventory / craftable / blocks / entities / session / world"));
            p.put("part", Param.optStr("part", null, "inventory 用：hands / backpack / armor / baubles"));
            p.put("radius", Param.optInt("radius", 0, "blocks / entities 用（blocks 默认 8，entities 默认 16）"));
            p.put("find", Param.optStr("find", null, "blocks 用：只找某种方块（#logs 亦可）"));
            p.put("below", Param.flag("below", "blocks 用：只看她脚下"));
            p.put("type", Param.optStr("type", null, "entities 用：只要这个实体类型"));
            p.put("what", Param.optStr("what", null, "entities 用：all / mob / item / player / orb"));
            p.put("filter", Param.optStr("filter", null, "craftable 用：产物关键词"));
            p.put("times", Param.optInt("times", 1, "craftable 用：份数"));
            p.put("item", Param.optStr("item", null, "craftable 用：指定产物"));
            p.put("state", Param.bool("state", true, "blocks 用：是否列状态"));
            p.put("nbt", Param.bool("nbt", false, "blocks 用：是否列方块实体 NBT"));
            p.put("mode", Param.enums("mode", false, "list", "blocks 用：list / count", "list", "count"));
            p.put("history", Param.opt("history", Param.Type.INT, null, "state 用：最多返回 N 条未读历史"));
            p.put("since", Param.opt("since", Param.Type.INT, null, "state 用：返回 id 大于 N 的全部历史"));
            return p;
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            String d = ctx.str("domain", "self");
            return switch (d) {
                case "self" -> self(ctx);
                case "state" -> state(ctx);
                case "inventory" -> inventory(ctx);
                case "craftable" -> craftable(ctx);
                case "blocks" -> senseBlocks(ctx);
                case "entities" -> entities(ctx);
                case "session" -> session(ctx);
                case "world" -> world(ctx);
                default -> throw CmdException.badArg("没有这个域：" + d
                        + "（可用 self / state / inventory / craftable / blocks / entities / session / world）");
            };
        }
    }

    // ── sense self ──

    private static Map<String, Object> self(MaidCmd.Ctx ctx) {
        EntityMaid maid = ctx.maid();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("health", Map.of("current", maid.getHealth(), "max", maid.getMaxHealth()));
        // 好感度：value = 点数，level = 等级（TLM 的 FavorabilityManager 直接给）
        Map<String, Object> favor = new LinkedHashMap<>();
        favor.put("value", maid.getFavorability());
        try {
            favor.put("level", maid.getFavorabilityManager().getLevel());
        } catch (Throwable ignored) {
            favor.put("level", null);
        }
        out.put("favor", favor);
        List<Map<String, Object>> effects = new ArrayList<>();
        for (MobEffectInstance e : maid.getActiveEffects()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", effectId(e));
            one.put("amplifier", e.getAmplifier());
            one.put("duration", e.getDuration());
            effects.add(one);
        }
        out.put("effects", effects);
        out.put("pos", posOf(maid));
        out.put("riding", ridingOf(maid));
        out.put("exp", expInfo(maid.getExperience()));
        return out;
    }

    private static String effectId(MobEffectInstance e) {
        try {
            return e.getEffect().unwrapKey().map(k -> k.location().toString()).orElse("?");
        } catch (Throwable t) {
            return "?";
        }
    }

    private static Map<String, Object> posOf(EntityMaid maid) {
        BlockPos p = maid.blockPosition();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("xyz", List.of(p.getX(), p.getY(), p.getZ()));
        m.put("yaw", maid.getYRot());
        m.put("pitch", maid.getXRot());
        m.put("dimension", maid.level().dimension().location().toString());
        m.put("on_ground", maid.onGround());
        return m;
    }

    private static Object ridingOf(EntityMaid maid) {
        Entity v = maid.getVehicle();
        if (v == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", v.getType().toString());
        m.put("entity_id", v.getId());
        return m;
    }

    // ── sense state ──

    private static Map<String, Object> state(MaidCmd.Ctx ctx) {
        MaidHandle h = ctx.handle();
        EntityMaid maid = ctx.maid();
        Map<String, Object> out = new LinkedHashMap<>();
        // action = 正在跑的（带进度）
        if (h.current != null) {
            var todo = h.current.todo;
            if (!todo.isEmpty()) {
                var a = todo.peekFirst();
                Map<String, Object> act = new LinkedHashMap<>();
                act.put("id", h.current.id);
                act.put("op", a.op());
                if (a.targetPos() != null) {
                    act.put("target", List.of(a.targetPos().getX(), a.targetPos().getY(), a.targetPos().getZ()));
                }
                act.put("progress", a.progress());
                act.put("phase", switch (a.phase) {
                    case RUNNING -> "执行中";
                    case DONE -> "已完成";
                    case FAILED -> "失败";
                });
                out.put("action", act);
            }
        } else {
            out.put("action", null);
        }
        // queue = 排队中的
        List<Map<String, Object>> queue = new ArrayList<>();
        for (var j : h.queue) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", j.id);
            one.put("op", j.kind);
            queue.add(one);
        }
        out.put("queue", queue);
        // history —— ★ 用【归一化后】的参数（ctx.raw 拿到的是 query string 的字符串，
 // 按 Number 判会永远失败 ⇒ since 静默退化成"未读的全返回"，还会污染游标）
        Object sinceRaw = ctx.args().get("since");
        List<Map<String, Object>> hist;
        if (sinceRaw instanceof Number n) {
            hist = h.readHistorySince(n.longValue());
        } else {
            Object hRaw = ctx.args().get("history");
            hist = h.readHistory(hRaw instanceof Number n2 ? n2.intValue() : null);
        }
        out.put("history", hist);
 // ★ 骑乘状态（原 ride_status 的字段， 并入 sense state）
 // 为什么不用 NBT Vehicle / RootVehicle：那两个字段经常读不出来（实测）
        out.put("riding", maid != null && maid.isPassenger());
        if (maid != null && maid.isPassenger()) {
            Entity v = maid.getVehicle();
            if (v != null) {
                out.put("vehicle", v.getName().getString());
                out.put("vehicle_type", entityTypeId(v));
                out.put("vehicle_id", v.getId());
            }
        }
        return out;
    }

    // ── sense inventory ──

    private static Map<String, Object> inventory(MaidCmd.Ctx ctx) {
        EntityMaid maid = ctx.maid();
        String part = ctx.opt("part");
        Map<String, Object> out = new LinkedHashMap<>();
        boolean all = part == null || part.isBlank();

        if (all || "hands".equals(part)) {
            Map<String, Object> hands = new LinkedHashMap<>();
            hands.put("main", stackOf(maid.getMainHandItem()));
            hands.put("off", stackOf(maid.getOffhandItem()));
            out.put("hands", hands);
        }
        if (all || "backpack".equals(part)) {
            var inv = maid.getAvailableBackpackInv();
            Map<String, Object> bp = new LinkedHashMap<>();
            try {
                bp.put("type", maid.getMaidBackpackType() == null ? null
                        : maid.getMaidBackpackType().getId().toString());
            } catch (Throwable t) {
                bp.put("type", null);
            }
            bp.put("slots", inv.getSlots());
            out.put("backpack", bp);
            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (s.isEmpty()) {
                    continue;
                }
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("index", i);
                // ★ 顺手给"inv 视图槽号"——`inv:N` 是【手在前】(0=主手 1=副手 2..=背包)，
 // 而这里的 index 是【背包内下标】⇒ 差 2。调用方直接拿 slot 就能用 `inv:slot`，
 // 不用自己 +2（实测遇到过：拿 index 当 inv:N 会搬到错的格）
                one.put("slot", i + 2);
                one.put("item", s.getItem().toString());
                one.put("count", s.getCount());
                items.add(one);
            }
            out.put("backpack_items", items);
            out.put("note", "backpack_items[].slot = 可以直接喂给 inv:N 的槽号（inv 是手在前，差 2）");
        }
        if (all || "armor".equals(part)) {
            Map<String, Object> ar = new LinkedHashMap<>();
            ar.put("head", stackOf(maid.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD), "armor:head"));
            ar.put("chest", stackOf(maid.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST), "armor:chest"));
            ar.put("legs", stackOf(maid.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS), "armor:legs"));
            ar.put("feet", stackOf(maid.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET), "armor:feet"));
            out.put("armor", ar);
        }
        if (all || "baubles".equals(part)) {
            // ★ TLM 饰品栏：BaubleItemHandler extends ItemStackHandler（固定 30 格）
 // 每条都带可直喂 take/put 的 `slot`（bauble:N）
            java.util.List<Map<String, Object>> bs = new ArrayList<>();
            var bh = maid.getMaidBauble();
            for (int i = 0; i < bh.getSlots(); i++) {
                var st = bh.getStackInSlot(i);
                if (st.isEmpty()) {
                    continue;
                }
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("index", i);
                one.put("slot", "bauble:" + i);
                one.put("item", st.getItem().toString());
                one.put("count", st.getCount());
                bs.add(one);
            }
            out.put("baubles", bs);
        }
        return out;
    }

    private static Object stackOf(ItemStack s) {
        return stackOf(s, null);
    }

    private static Object stackOf(ItemStack s, String slotName) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        if (slotName != null) {
            m.put("slot", slotName);
        }
        m.put("item", s.getItem().toString());
        m.put("count", s.getCount());
        return m;
    }

    // ── sense craftable ──

    private static Map<String, Object> craftable(MaidCmd.Ctx ctx) {
        return CraftHelper.list(ctx);
    }

    // ── sense blocks（她身边） ──

    private static Map<String, Object> senseBlocks(MaidCmd.Ctx ctx) {
        int r = ctx.in("radius", 0);
        if (r <= 0) {
            r = 8;
        }
        // ⚠️ 实际半径上限 16（旧实现实测：传 48 也没用）—— 要找更大范围就带她多走几个点分片扫
        r = Math.min(r, 16);
        EntityMaid maid = ctx.maid();
        Map<String, Object> out = readBlocks(ctx, maid, r * r * r * 8, null);
        out.put("radius", r);
        return out;
    }

    // ── sense entities ──

    private static Map<String, Object> entities(MaidCmd.Ctx ctx) {
        EntityMaid maid = ctx.maid();
        double r = ctx.in("radius", 0) > 0 ? ctx.in("radius", 0) : 16;
        String what = ctx.opt("what");
        String type = ctx.opt("type");
        AABB box = maid.getBoundingBox().inflate(r);
        List<Map<String, Object>> mobs = new ArrayList<>();
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> players = new ArrayList<>();
        List<Map<String, Object>> orbs = new ArrayList<>();
        List<Map<String, Object>> vehicles = new ArrayList<>();
        for (Entity e : maid.level().getEntities(maid, box)) {
            if (e == maid) {
                continue;
            }
            if (type != null && !type.isBlank() && !entityTypeId(e).equals(type)
                    && !entityTypeId(e).endsWith(":" + type)) {
                continue;
            }
            double dist = Math.sqrt(maid.distanceToSqr(e));
            if (e instanceof ItemEntity it) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("item", it.getItem().getItem().toString());
                one.put("count", it.getItem().getCount());
                one.put("pos", List.of(it.blockPosition().getX(), it.blockPosition().getY(), it.blockPosition().getZ()));
                one.put("distance", round1(dist));
                items.add(one);
            } else if (e instanceof ExperienceOrb orb) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("value", orb.getValue());
                one.put("pos", List.of(orb.blockPosition().getX(), orb.blockPosition().getY(), orb.blockPosition().getZ()));
                one.put("distance", round1(dist));
                orbs.add(one);
            } else if (e instanceof Player p) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("name", p.getGameProfile().getName());
                one.put("uuid", p.getUUID().toString());
                one.put("pos", List.of(p.blockPosition().getX(), p.blockPosition().getY(), p.blockPosition().getZ()));
                one.put("distance", round1(dist));
                one.put("is_owner", p.getUUID().equals(maid.getOwnerUUID()));
                players.add(one);
            } else if (e instanceof net.minecraft.world.entity.vehicle.AbstractMinecart
                    || e instanceof net.minecraft.world.entity.vehicle.Boat) {
                // 船 / 矿车：可骑，但不是 Mob ⇒ 单独一类
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("type", entityTypeId(e));
                one.put("entity_id", e.getId());
                one.put("pos", List.of(e.blockPosition().getX(), e.blockPosition().getY(), e.blockPosition().getZ()));
                one.put("distance", round1(dist));
                one.put("occupied", !e.getPassengers().isEmpty());
                one.put("rideable", true);
                vehicles.add(one);
            } else if (e instanceof Mob m) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("type", entityTypeId(m));
                one.put("entity_id", m.getId());
                one.put("pos", List.of(m.blockPosition().getX(), m.blockPosition().getY(), m.blockPosition().getZ()));
                one.put("distance", round1(dist));
                one.put("health", m.getHealth());
                one.put("hostile", m instanceof net.minecraft.world.entity.monster.Enemy);
                // 能上鞍的（马/驴/猪/炽足兽…）—— 给 ride 用
                one.put("rideable", m instanceof net.minecraft.world.entity.Saddleable);
                mobs.add(one);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (what == null || what.equals("all") || what.equals("mob")) {
            out.put("mobs", mobs);
        }
        // ★ what=all 时额外给一个【合并列表】叫 entities —— 调用方按 entities 取不再拿到空。
 // （原来 all 只返回 mobs/items 两个【分类】字段，读 entities 的人会以为"附近没实体"，
 // 实测踩到：RCON 查到 5 个实体、sense 读 entities 却是空）
        if (what == null || what.equals("all")) {
            java.util.List<Map<String, Object>> merged = new java.util.ArrayList<>();
            merged.addAll(mobs);
            merged.addAll(items);
            merged.addAll(vehicles);
            out.put("entities", merged);
        }
        if (what == null || what.equals("all") || what.equals("vehicle")) {
            out.put("vehicles", vehicles);
        }
        if (what == null || what.equals("all") || what.equals("item")) {
            out.put("items", items);
        }
        if (what == null || what.equals("all") || what.equals("player")) {
            out.put("players", players);
        }
        if (what == null || what.equals("all") || what.equals("orb")) {
            out.put("orbs", orbs);
        }
        return out;
    }

    /** ⚠️ EntityType.toString() 返回 "entity.minecraft.pig"，不是 "minecraft:pig" ⇒ 要查注册表 */
    private static String entityTypeId(net.minecraft.world.entity.Entity e) {
        try {
            return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .getKey(e.getType()).toString();
        } catch (Throwable t) {
            return e.getType().toString();
        }
    }

    private static double round1(double d) {
        return Math.round(d * 10.0) / 10.0;
    }

    /**
     * 经验点数 → {@code {points, level}}。
     * <p>等级按原版公式换算：0–15 级 {@code 2L+7} / 16–30 级 {@code 5L-38} / 31 级以上 {@code 9L-158}。
     */
    private static Map<String, Object> expInfo(int points) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("points", points);
        int level = 0;
        int remain = points;
        while (level < 1000) {
            int need = xpNeed(level);
            if (remain < need) {
                break;
            }
            remain -= need;
            level++;
        }
        m.put("level", level);
        return m;
    }

    private static int xpNeed(int level) {
        if (level >= 30) {
            return 9 * level - 158;
        }
        if (level >= 15) {
            return 5 * level - 38;
        }
        return 2 * level + 7;
    }

    // ── sense session ──

    private static Map<String, Object> session(MaidCmd.Ctx ctx) {
        Session s = ctx.handle().session;
        Map<String, Object> out = new LinkedHashMap<>();
        if (s == null) {
            out.put("open", false);
            return out;
        }
        out.put("open", true);
        Map<String, Object> what = new LinkedHashMap<>();
        what.put("kind", s.kind.wire);
        if (s.pos != null) {
            what.put("pos", List.of(s.pos.getX(), s.pos.getY(), s.pos.getZ()));
        }
        if (s.entityId != 0) {
            what.put("entity_id", s.entityId);
        }
        out.put("what", what);
        List<Map<String, Object>> slots = new ArrayList<>();
        if (s.container != null) {
            for (int i = 0; i < s.container.getSlots(); i++) {
                ItemStack st = s.container.getStackInSlot(i);
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("index", i);
                if (st.isEmpty()) {
                    one.put("item", null);
                } else {
                    one.put("item", st.getItem().toString());
                    one.put("count", st.getCount());
                }
                slots.add(one);
            }
        }
        out.put("slots", slots);
        if (s.cursor != null && !s.cursor.isEmpty()) {
            out.put("cursor", stackOf(s.cursor));
        }
        out.put("options", optionsOf(ctx.maid(), s));
        Map<String, Object> ab = new LinkedHashMap<>();
        ab.put("slots", s.ability().slots());
        ab.put("select", s.ability().hasSelect());
        ab.put("input", s.ability().hasInput());
        ab.put("progress", s.ability().progress());
        ab.put("hint", s.ability().hint());
        out.put("ability", ab);
        return out;
    }

    /**
     * 界面里「可选项」的统一出口（COMMANDS.md §5.2.7）。
     * <p>村民和附魔台、切石机一样都是界面 ⇒ 选项统一由 {@code sense session} 返回，
     * 不再单开 {@code trade_list}。
     * <p>没有选项的界面（箱子 / 熔炉 / 铁砧…）返回 {@code null}。
     */
    private static Object optionsOf(EntityMaid maid, Session s) {
        try {
            if (s.kind == com.xiaofeng.maidcli.engine.FaceKind.MERCHANT) {
                return com.xiaofeng.maidcli.ops.MerchantOps.list(maid, s).get("offers");
            }
            if (s.kind == com.xiaofeng.maidcli.engine.FaceKind.STONECUTTER
                    || s.kind == com.xiaofeng.maidcli.engine.FaceKind.ENCHANT) {
                return com.xiaofeng.maidcli.ops.StationOps.readOptions(s);
            }
        } catch (Throwable t) {
            return null;      // 读选项失败不该拖垮整个 session 查询
        }
        return null;
    }

    // ── sense world ──

    private static Map<String, Object> world(MaidCmd.Ctx ctx) {
        ServerLevel lv = (ServerLevel) ctx.maid().level();
        Map<String, Object> out = new LinkedHashMap<>();
        long t = lv.getDayTime() % 24000L;
        Map<String, Object> time = new LinkedHashMap<>();
        time.put("tick", t);
        time.put("phase", t < 12000 ? "day" : (t < 13800 ? "sunset" : (t < 22200 ? "night" : "sunrise")));
        out.put("time", time);
        String weather = "clear";
        if (lv.isThundering()) {
            weather = "thunder";
        } else if (lv.isRaining()) {
            weather = "rain";
        }
        out.put("weather", weather);
        return out;
    }

    // ────────────────────────── 方块读取（顶层 blocks 和 sense blocks 共用） ──────────────────────────

    /**
     * 读方块。
     *
     * @param maid  非 null = 以她为中心（sense blocks）；null = 用 ctx.pos()
     * @param limit 体积上限
     * @param single 单点模式（顶层 blocks 只给一个点时用）
     */
    private static Map<String, Object> readBlocks(MaidCmd.Ctx ctx, EntityMaid maid, int limit, BlockPos single) {
        MinecraftServer server = ctx.server();
        List<BlockPos> positions;
        if (maid != null) {
            // ── 她身边：球体扫描 ──
            int r = Math.min(ctx.in("radius", 8) <= 0 ? 8 : ctx.in("radius", 8), 16);
            positions = new ArrayList<>();
            BlockPos c = maid.blockPosition();
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (dx * dx + dy * dy + dz * dz > r * r) {
                            continue;
                        }
                        positions.add(c.offset(dx, dy, dz));
                    }
                }
            }
        } else if (single != null && ctx.in("radius", 0) > 0) {
            // ★ 单点 + radius ⇒ 以它为中心的立方体
 // ⚠️ 原来 radius 参数【压根没人读】—— areaBlocks 只认 pos 的"区域语法"
 // ⇒ 传多少半径都只查 1 格（找灯笼时踩到）
            int r = Math.min(ctx.in("radius", 0), 16);
            positions = new ArrayList<>();
            outer:
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (positions.size() >= limit) {
                            break outer;
                        }
                        positions.add(single.offset(dx, dy, dz));
                    }
                }
            }
        } else {
            // ★ 统一走 areaBlocks —— 它自己会处理「单点 / 方区 / 半径」三种写法。
 // ⚠️ 原来这里先判 single != null 就走单点 ⇒ radius / 方区模式【永远走不到】，
 // 表现是"不管传什么半径都只查 1 格"（实测踩到：找灯笼找不到）
            positions = ctx.areaBlocks(limit);
        }

        boolean withState = ctx.flag("state", true);
        boolean withNbt = ctx.flag("nbt", false);
        boolean count = "count".equals(ctx.str("mode", "list"));
        String find = ctx.opt("find");
        boolean below = ctx.flag("below", false);

        ServerLevel lv = maid != null ? (ServerLevel) maid.level()
                : server.overworld();
        BlockPos center = maid != null ? maid.blockPosition() : single;

        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        int scanned = 0;
        for (BlockPos p : positions) {
            if (below && center != null && !(p.getY() < center.getY())) {
                continue;
            }
            scanned++;
            BlockState st;
            try {
                st = lv.getBlockState(p);
            } catch (Throwable t) {
                continue;
            }
            String id = blockId(st);
            if (find != null && !find.isBlank() && !matchesFind(lv, st, find)) {
                continue;
            }
            counts.merge(id, 1, Integer::sum);
            if (!count && list.size() < 4096) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("pos", List.of(p.getX(), p.getY(), p.getZ()));
                one.put("block", id);
                if (withState && !st.getProperties().isEmpty()) {
                    one.put("state", stateString(st));
                }
                if (withNbt) {
                    BlockEntity be = lv.getBlockEntity(p);
                    if (be != null) {
                        try {
                            one.put("nbt", be.saveWithoutMetadata(lv.registryAccess()).toString());
                        } catch (Throwable ignored) {
                        }
                    }
                }
                list.add(one);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (below) {
            out.put("below_only", true);
        }
        if (count) {
            out.put("counts", counts);
        } else if (single != null && maid == null && positions.size() == 1) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("pos", List.of(single.getX(), single.getY(), single.getZ()));
            one.put("block", blockId(lv.getBlockState(single)));
            if (withState && !lv.getBlockState(single).getProperties().isEmpty()) {
                one.put("state", stateString(lv.getBlockState(single)));
            }
            return one;
        } else {
            out.put("blocks", list);
        }
        out.put("scanned", scanned);
        return out;
    }

    private static String blockId(BlockState st) {
        try {
            return st.getBlock().builtInRegistryHolder().key().location().toString();
        } catch (Throwable t) {
            return st.getBlock().toString();
        }
    }

    private static String stateString(BlockState st) {
        StringBuilder sb = new StringBuilder();
        for (net.minecraft.world.level.block.state.properties.Property<?> p : st.getProperties()) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(p.getName()).append("=").append(val(st, p));
        }
        return sb.toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String val(BlockState st, net.minecraft.world.level.block.state.properties.Property p) {
        // ⚠️ 1.21 里这方法叫 getName(T)（老版本是 valueToString）
        return p.getName(st.getValue(p));
    }

    private static boolean matchesFind(ServerLevel lv, BlockState st, String find) {
        if (find.startsWith("#")) {
            try {
                var tag = net.minecraft.tags.TagKey.create(
                        net.minecraft.core.registries.Registries.BLOCK,
                        net.minecraft.resources.ResourceLocation.parse(find.substring(1)));
                return st.is(tag);
            } catch (Throwable t) {
                return false;
            }
        }
        String id = blockId(st);
        if (id.equals(find)) {
            return true;
        }
        return id.endsWith(":" + find) || id.contains(find);
    }
}
