package com.xiaofeng.maidcli.command.defs;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidConfigManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.PickType;
import com.xiaofeng.maidcli.command.CmdRegistry;
import com.xiaofeng.maidcli.command.MaidCmd;
import com.xiaofeng.maidcli.command.Param;
import com.xiaofeng.maidcli.engine.MaidHandle;
import com.xiaofeng.maidcli.infra.MaidCliTags;
import com.xiaofeng.maidcli.transport.CmdException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设置类（COMMANDS.md §7）—— {@code get}（读）/ {@code set}（写），键完全相同。
 *
 * <p>跟 {@code sense} 对称：不贴合原版，她身上所有"能配"的都收进来。
 * <p>⚠️ <b>{@code mode} / {@code control} / {@code target} 是只读的</b> —— 要改得走控制类指令，
 * （切模式 / 进出受控会"重装整套行为盒子"，不是改一个字段）。
 */
public final class ConfigCmds {

    /** 只读键（COMMANDS.md §7.1） */
    static final List<String> READ_ONLY = List.of("mode", "control", "target");

    /** 可写键（18 项） */
    static final List<String> WRITABLE = List.of(
            "follow", "home", "sit", "schedule", "pickup", "rideable",
            "name", "tag", "model", "sound_pack",
            "pickup_type", "open_door", "open_fence_gate", "active_climbing", "sound_freq",
            "backpack_show", "back_item_show", "chat_bubble_show");

    static final List<String> ALL_KEYS;

    static {
        List<String> all = new ArrayList<>(WRITABLE);
        all.addAll(READ_ONLY);
        ALL_KEYS = List.copyOf(all);
    }

    private ConfigCmds() {
    }

    public static void registerAll() {
        CmdRegistry.register(new Get());
        CmdRegistry.register(new Set());
    }

    // ────────────────────────── 读 ──────────────────────────

    static Object read(MaidCmd.Ctx ctx, String key) {
        EntityMaid maid = ctx.maid();
        MaidHandle h = ctx.handle();
        MaidConfigManager c = maid.getConfigManager();
        return switch (key) {
            // ── 只读三键 ──
            case "mode" -> maid.getTask() == null ? null : maid.getTask().getUid().toString();
            case "control" -> h.controlled;
            case "target" -> attackTargetId(maid);
            // ── 行为 ──
            case "follow" -> !maid.isHomeModeEnable();
            case "home" -> homeOf(maid);
            case "sit" -> maid.isMaidInSittingPose();
            case "schedule" -> maid.getSchedule().name();
            case "pickup" -> maid.isPickup();
            case "rideable" -> maid.isRideable();
            // ── 身份 ──
            case "name" -> maid.getName().getString();
            case "tag" -> MaidCliTags.read(maid);
            case "model" -> maid.getModelId();
            case "sound_pack" -> maid.getSoundPackId();
            // ── 交互偏好 ──
            case "pickup_type" -> switch (c.getPickupType()) {
                case ONLY_ITEM -> "item";
                case ONLY_XP -> "xp";
                case ALL -> "all";
            };
            case "open_door" -> c.isOpenDoor();
            case "open_fence_gate" -> c.isOpenFenceGate();
            case "active_climbing" -> c.isActiveClimbing();
            case "sound_freq" -> c.getSoundFreq();
            case "backpack_show" -> c.isShowBackpack();
            case "back_item_show" -> c.isShowBackItem();
            case "chat_bubble_show" -> c.isChatBubbleShow();
            default -> throw CmdException.badArg("没有这个键：" + key,
                    "可用：" + String.join(" / ", ALL_KEYS));
        };
    }

    private static Object attackTargetId(EntityMaid maid) {
        try {
            return maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET)
                    .map(LivingEntity::getId).orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object homeOf(EntityMaid maid) {
        try {
            BlockPos p = maid.getSchedulePos().getWorkPos();
            return p == null ? null : List.of(p.getX(), p.getY(), p.getZ());
        } catch (Throwable t) {
            return null;
        }
    }

    // ────────────────────────── 写 ──────────────────────────

    static void write(MaidCmd.Ctx ctx, String key, Object value) {
        EntityMaid maid = ctx.maid();
        MaidConfigManager c = maid.getConfigManager();
        if (READ_ONLY.contains(key)) {
            throw new CmdException(CmdException.BAD_ARG,
                    key + " 是只读的（它一改就要「重装行为盒子」，不是改一个字段）",
                    key.equals("mode") || key.equals("target")
                            ? "用控制类指令：mode <模式名> / mode attack target=<实体id>"
                            : "用控制类指令：control / control off=true");
        }
        switch (key) {
            case "follow" -> maid.setHomeModeEnable(!asBool(key, value));
            case "home" -> {
                BlockPos p = asPos(key, value);
                // ⚠️ 别用 SchedulePos.setHomeModeEnable（里面有 if(!configured) 门槛会挡掉）
                maid.getSchedulePos().setWorkPos(p);
                maid.getSchedulePos().setIdlePos(p);
                maid.getSchedulePos().setSleepPos(p);
            }
            case "sit" -> maid.setInSittingPose(asBool(key, value));
            case "schedule" -> {
                String s = String.valueOf(value).toUpperCase();
                try {
                    maid.setSchedule(MaidSchedule.valueOf(s));
                } catch (IllegalArgumentException e) {
                    throw CmdException.badArg("schedule 只能是 ALL / DAY / NIGHT（收到 " + value + "）");
                }
            }
            case "pickup" -> maid.setPickup(asBool(key, value));
            case "rideable" -> maid.setRideable(asBool(key, value));
            case "name" -> maid.setCustomName(Component.literal(String.valueOf(value)));
            case "tag" -> {
                String t = String.valueOf(value).trim().toUpperCase();
                if (!MaidCliTags.valid(t)) {
                    throw CmdException.badArg("标签要 6 位（字母表去掉了 I/O/0/1），收到 " + value);
                }
                MaidCliTags.write(maid, t);
                MaidHandle h = ctx.handle();
                h.identity.tag = t;      // ⚠️ 句柄的主键跟着换（不然下次按旧标签找不到她）
            }
            case "model" -> maid.setModelId(String.valueOf(value));
            case "sound_pack" -> maid.setSoundPackId(String.valueOf(value));
            case "pickup_type" -> c.setPickupType(pickTypeOf(value));
            case "open_door" -> c.setOpenDoor(asBool(key, value));
            case "open_fence_gate" -> c.setOpenFenceGate(asBool(key, value));
            case "active_climbing" -> c.setActiveClimbing(asBool(key, value));
            case "sound_freq" -> c.setSoundFreq((float) asDouble(key, value));
            case "backpack_show" -> c.setShowBackpack(asBool(key, value));
            case "back_item_show" -> c.setShowBackItem(asBool(key, value));
            case "chat_bubble_show" -> c.setChatBubbleShow(asBool(key, value));
            default -> throw CmdException.badArg("没有这个键：" + key,
                    "可写：" + String.join(" / ", WRITABLE));
        }
    }

    private static boolean asBool(String key, Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim();
        if (s.equalsIgnoreCase("true")) {
            return true;
        }
        if (s.equalsIgnoreCase("false")) {
            return false;
        }
        throw CmdException.badArg(key + " 只能是 true / false（收到 " + v + "）");
    }

    private static double asDouble(String key, Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw CmdException.badArg(key + " 要数字（收到 " + v + "）");
        }
    }

    private static BlockPos asPos(String key, Object v) {
        var pts = com.xiaofeng.maidcli.command.PosParse.parsePoints(v);
        if (pts.isEmpty()) {
            throw CmdException.badArg(key + " 要一个坐标（x,y,z）");
        }
        return pts.get(0).block();
    }

    private static PickType pickTypeOf(Object o) {
        String s = String.valueOf(o).trim();
        try {
            int i = Integer.parseInt(s);
            return switch (i) {
                case 0 -> PickType.ONLY_ITEM;
                case 1 -> PickType.ONLY_XP;
                case 2 -> PickType.ALL;
                default -> throw CmdException.badArg("pickup_type 只能是 none / item / xp / all（收到 " + o + "）");
            };
        } catch (NumberFormatException ignored) {
            // 不是数字 ⇒ 按名字
        }
        return switch (s.toLowerCase()) {
            case "item" -> PickType.ONLY_ITEM;
            case "xp" -> PickType.ONLY_XP;
            case "all" -> PickType.ALL;
            default -> throw CmdException.badArg("pickup_type 只能是 item / xp / all（收到 " + o + "）",
                    "想\"都不捡\"用 set pickup=false");
        };
    }

    // ────────────────────────── 两条指令 ──────────────────────────

    /** {@code get [键 ...]} —— 不填键 = 读全部 */
    static final class Get implements MaidCmd {

        @Override
        public String name() {
            return "get";
        }

        @Override
        public Kind kind() {
            return Kind.QUERY;
        }

        @Override
        public String desc() {
            return "读她的配置（21 个键）；不给键 = 读全部，例：get mode follow";
        }

        @Override
        public Variadic variadic() {
            return new Variadic("keys", Variadic.Mode.WORDS);
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("keys", Param.opt("keys", Param.Type.STRING_OR_LIST, null, "要读的键（空格分隔）"));
            return p;
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            List<String> keys = splitKeys(ctx.raw("keys"));
            if (keys.isEmpty()) {
                keys = ALL_KEYS;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (String k : keys) {
                out.put(k, read(ctx, k));
            }
            return out;
        }
    }

    /** {@code set follow=true sit=false ...} —— 一次可改多个，立即生效 */
    static final class Set implements MaidCmd {

        @Override
        public String name() {
            return "set";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "写她的配置（一次可改多个，立即生效），例：set follow=true sit=false";
        }

        @Override
        public Variadic variadic() {
            return new Variadic("keys", Variadic.Mode.PAIRS);
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("keys", Param.opt("keys", Param.Type.RAW, null,
                    "键=值（空格分隔，一次多个）"));
            return p;
        }

        @Override
        public boolean needsAwake() {
            return true;
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            Map<String, Object> pairs = splitPairs(ctx.raw("keys"));
            if (pairs.isEmpty()) {
                throw CmdException.badArg("set 要给 键=值（如 set keys=follow=true;sit=false）");
            }
            List<String> changed = new ArrayList<>();
            for (Map.Entry<String, Object> e : pairs.entrySet()) {
                write(ctx, e.getKey(), e.getValue());
                changed.add(e.getKey());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("changed", changed);
            out.put("message", "改了 " + changed.size() + " 项：" + String.join(", ", changed));
            return out;
        }
    }

    // ────────────────────────── 参数解析小工具 ──────────────────────────

    @SuppressWarnings("unchecked")
    static List<String> splitKeys(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                out.addAll(splitKeys(String.valueOf(o)));
            }
            return out;
        }
        for (String s : String.valueOf(raw).split("[,\\s;]+")) {
            if (!s.isBlank()) {
                out.add(s.trim().toLowerCase());
            }
        }
        return out;
    }

    /** 从 {@code "follow=true;sit=false"} / {@code {"follow":"true",...}} / {@code ["follow=true"]} 里取键值对 */
    static Map<String, Object> splitPairs(Object raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        if (raw instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()).toLowerCase(), e.getValue());
            }
            return out;
        }
        List<String> chunks = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                chunks.add(String.valueOf(o));
            }
        } else {
            chunks.add(String.valueOf(raw));
        }
        for (String chunk : chunks) {
            // 空格 / 分号 / 逗号都当分隔（逗号只在不是坐标时拆 —— 简化为：先按 ; 和空格拆，逗号留着）
            for (String part : chunk.split("[;\\s]+")) {
                if (part.isBlank()) {
                    continue;
                }
                int eq = part.indexOf('=');
                if (eq <= 0) {
                    throw CmdException.badArg("set 的参数要写成 键=值（收到 " + part + "）");
                }
                out.put(part.substring(0, eq).trim().toLowerCase(), part.substring(eq + 1).trim());
            }
        }
        return out;
    }
}
