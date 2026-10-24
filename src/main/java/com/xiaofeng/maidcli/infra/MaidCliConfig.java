package com.xiaofeng.maidcli.infra;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xiaofeng.maidcli.MaidCliMod;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * maid_cli 自己的配置（约定）。
 *
 * <p>为什么要有：射箭的"初速度""蓄力时间"这类**不该让调用方（尤其 LLM）去估** ——
 * 做成服务端配置，一次设好、持久保存。
 *
 * <p>存放：{@code <服务端目录>/config/maid_cli.json}（我们自己的文件，不碰 TLM 的配置）
 * <p>⚠️ 遇到过的坑：原本写在存档目录里（{@code getWorldPath(ROOT)}）—— 那是存档内部，
 * 和玩家熟悉的 {@code config/} 不是一回事，实测找不到 ✗ ⇒ 改到服务端工作目录下。
 */
public final class MaidCliConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 配置项（字段名就是 json 的 key） */
    public static final class Data {
        // ── 射箭 ──
        /** 箭的初速度（格/tick）。MC 里满蓄力的弓是 3.0 */
        public double shoot_speed = 3.0;
        /** 蓄力时间（tick）。20 tick = 1 秒 */
        public int shoot_charge_ticks = 20;

        // ── 唤醒 / 加载 ──
        /** 【实体 tick】半径（区块）—— 她能在里面动、干活 */
        public int sim_radius = 4;
        /** 【加载】半径（区块）—— 只加载不 tick，给"感知"用 */
        public int view_radius = 8;
        /** 唤醒后多久没收到女仆指令就自动取消唤醒（秒） */
        public int wake_timeout_seconds = 300;
        /** login 发的 key 有效小时数 */
        public int key_ttl_hours = 12;

        // ── 执行模型 ──
        /** 手长（格）—— 够得着的判定半径 */
        public double reach = 8.0;
        /** 走位的到达容差（格）—— ⚠️ 不能用 reach 当到达判定（旧坑） */
        public double walk_arrive_radius = 3.0;
        /** 走位超时（秒）—— 到点没到就 FAILED（防"插走位死循环"） */
        public int walk_timeout_seconds = 15;
        /** 一条指令最多拆出多少个动作（超了报 bad_arg，提示分批） */
        public int max_actions_per_job = 256;
        /** 待办队列上限（超了报 busy） */
        public int queue_capacity = 256;

        // ── 会话 ──
        /**
         * 走远超过这个距离自动关会话（格）。
         * <p>★ 取 <b>8</b>（= 手长 {@code reach}）：超过手长就"够不着了"，
         * 界面还开着没有意义 —— 旧版就是 {@code d > REACH} 判定（{@code MaidEventRunner.sweepSessions}）。
         * 之前写 16 会让 8~16 格之间出现"界面开着但拿不到东西"的怪状态。
         */
        public int session_close_distance = 8;
        /** 会话最长开着多久（秒）—— 兜底（旧版 60 秒） */
        public int session_timeout_seconds = 60;

        // ── 网络 ──
        /** HTTP 监听端口（多个服同时跑时必须各不相同） */
        public int http_port = 8644;
    }

    private static Data CACHE;

    private MaidCliConfig() {
    }

    private static Path path(MinecraftServer server) {
        return server.getServerDirectory().resolve("config").resolve("maid_cli.json");
    }

    /** 读配置（带缓存；文件不存在就写一份默认的） */
    public static synchronized Data get(MinecraftServer server) {
        if (CACHE != null) {
            return CACHE;
        }
        Path p = path(server);
        try {
            if (Files.exists(p)) {
                Data d = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), Data.class);
                if (d != null) {
                    CACHE = d;
                    return CACHE;
                }
            }
        } catch (Exception e) {
            MaidCliMod.LOG.warn("[MaidCLI] 读配置失败，用默认值: {}", e.toString());
        }
        CACHE = new Data();
        save(server);
        return CACHE;
    }

    /** 服务重启时清缓存 */
    public static synchronized void invalidate() {
        CACHE = null;
    }

    public static synchronized void save(MinecraftServer server) {
        if (CACHE == null) {
            return;
        }
        Path p = path(server);
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, GSON.toJson(CACHE), StandardCharsets.UTF_8);
        } catch (IOException e) {
            MaidCliMod.LOG.warn("[MaidCLI] 写配置失败: {}", e.toString());
        }
    }

    /** 改配置 */
    public static synchronized Map<String, Object> set(MinecraftServer server, Map<String, Object> set) {
        Data d = get(server);
        java.util.List<String> changed = new java.util.ArrayList<>();
        for (Map.Entry<String, Object> e : set.entrySet()) {
            String k = String.valueOf(e.getKey());
            Object v = e.getValue();
            if (!(v instanceof Number n) && !(v instanceof Boolean) && !(v instanceof String)) {
                throw new IllegalArgumentException("配置项 " + k + " 的值类型不支持");
            }
            switch (k) {
                case "shoot_speed" -> d.shoot_speed = num(v).doubleValue();
                case "shoot_charge_ticks" -> d.shoot_charge_ticks = num(v).intValue();
                case "sim_radius" -> d.sim_radius = num(v).intValue();
                case "view_radius" -> d.view_radius = num(v).intValue();
                case "wake_timeout_seconds" -> d.wake_timeout_seconds = num(v).intValue();
                case "key_ttl_hours" -> d.key_ttl_hours = num(v).intValue();
                case "reach" -> d.reach = num(v).doubleValue();
                case "walk_arrive_radius" -> d.walk_arrive_radius = num(v).doubleValue();
                case "walk_timeout_seconds" -> d.walk_timeout_seconds = num(v).intValue();
                case "max_actions_per_job" -> d.max_actions_per_job = num(v).intValue();
                case "queue_capacity" -> d.queue_capacity = num(v).intValue();
                case "session_close_distance" -> d.session_close_distance = num(v).intValue();
                case "session_timeout_seconds" -> d.session_timeout_seconds = num(v).intValue();
                case "http_port" -> d.http_port = num(v).intValue();
                default -> throw new IllegalArgumentException("未知配置项 " + k + "（可用："
                        + String.join(" / ", KEYS) + "）");
            }
            changed.add(k);
        }
        save(server);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("changed", changed);
        out.put("config", asMap(server));
        return out;
    }

    private static final java.util.List<String> KEYS = java.util.List.of(
            "shoot_speed", "shoot_charge_ticks", "sim_radius", "view_radius", "wake_timeout_seconds",
            "key_ttl_hours", "reach", "walk_arrive_radius", "walk_timeout_seconds", "max_actions_per_job",
            "queue_capacity", "session_close_distance", "session_timeout_seconds", "http_port");

    private static Number num(Object v) {
        if (v instanceof Number n) {
            return n;
        }
        return Double.parseDouble(String.valueOf(v));
    }

    public static synchronized Map<String, Object> asMap(MinecraftServer server) {
        Data d = get(server);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("shoot_speed", d.shoot_speed);
        m.put("shoot_charge_ticks", d.shoot_charge_ticks);
        m.put("sim_radius", d.sim_radius);
        m.put("view_radius", d.view_radius);
        m.put("wake_timeout_seconds", d.wake_timeout_seconds);
        m.put("key_ttl_hours", d.key_ttl_hours);
        m.put("reach", d.reach);
        m.put("walk_arrive_radius", d.walk_arrive_radius);
        m.put("walk_timeout_seconds", d.walk_timeout_seconds);
        m.put("max_actions_per_job", d.max_actions_per_job);
        m.put("queue_capacity", d.queue_capacity);
        m.put("session_close_distance", d.session_close_distance);
        m.put("session_timeout_seconds", d.session_timeout_seconds);
        m.put("http_port", d.http_port);
        return m;
    }
}
