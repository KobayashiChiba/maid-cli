package com.xiaofeng.maidcli.transport;

import com.mojang.authlib.GameProfile;
import com.xiaofeng.maidcli.MaidCliMod;
import com.xiaofeng.maidcli.infra.MaidCliConfig;
import net.minecraft.server.MinecraftServer;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 身份 / key —— {@code login}（COMMANDS.md §4.1）。
 *
 * <pre>
 *   maidcli login 用户            ← 声明"我是用户"
 *     服务端校验：玩家档案（usercache）里有这个名字 ★【不要求在线】
 *     → 发一个 key（默认 12 小时）
 *   maidcli wake &lt;她&gt;            ← 之后所有调用都带这个 key（HTTP 头 X-Maid-Key）
 * </pre>
 *
 * <p><b>CLI 全自动</b>：key 存在 {@code cli/.maidkey.json}，每次请求自动带上；
 * 失效（401）自动重新 login 并重试 ⇒ <b>LLM 全程不接触 key</b>。
 *
 * <p>⚠️ <b>它挡得住什么、挡不住什么</b>（老实说）：
 * <ul>
 *   <li>挡得住：不带身份的裸调、别的程序乱调、误操作（错误码可追溯）</li>
 *   <li>挡不住：能访问本机 127.0.0.1:8644 <b>且</b>知道玩家名的人 —— 他也能 login ✗
 *       （所以它是"声明身份"，不是"防攻击"）</li>
 * </ul>
 */
public final class Auth {

    /** 一次登录会话 */
    public static final class Key {
        public final String value;
        public final String playerName;
        public final UUID playerUUID;
        /** 到期时刻（epoch 毫秒） */
        public final long expireAt;

        Key(String value, String playerName, UUID playerUUID, long expireAt) {
            this.value = value;
            this.playerName = playerName;
            this.playerUUID = playerUUID;
            this.expireAt = expireAt;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", value);
            m.put("player", playerName);
            m.put("uuid", playerUUID == null ? null : playerUUID.toString());
            m.put("expires_at", expireAt);
            m.put("ttl_seconds", Math.max(0, (expireAt - System.currentTimeMillis()) / 1000));
            return m;
        }
    }

    private static final Map<String, Key> KEYS = new ConcurrentHashMap<>();
    /** 一个玩家同时只保留一个 key（COMMANDS.md §4.1） */
    private static final Map<UUID, String> BY_PLAYER = new ConcurrentHashMap<>();
    private static final SecureRandom RAND = new SecureRandom();

    private Auth() {
    }

    /**
     * 登录：校验玩家名 → 发 key。
     *
     * <p>★ <b>不要求在线</b>：否则远程时唤不醒她。判据是"玩家档案里有这个名字"（读 usercache）。
     */
    public static Key login(MinecraftServer server, String playerName) {
        if (playerName == null || playerName.isBlank()) {
            throw CmdException.badArg("player 不能为空（要传玩家的名字，如 player=用户）");
        }
        String name = playerName.trim();
        // ★ 先卡一道：名字必须【已经出现过】在服务器的玩家档案里（usercache.json）。
 // 为什么不能只靠 getProfileCache().get(name)：**离线模式的服务器会给任何名字
 // 现编一个 uuid**（实测：login 一个瞎编的名字照样成功 ✗）⇒ 那样验证等于没有。
        if (!knownPlayer(server, name)) {
            throw CmdException.badArg("服务器里没有「" + name + "」的玩家记录"
                    + "（他从没进过这个服 —— 换个进过服的玩家名）");
        }
        GameProfile profile;
        try {
            profile = server.getProfileCache() == null ? null
                    : server.getProfileCache().get(name).orElse(null);
        } catch (Throwable t) {
            profile = null;
        }
        if (profile == null) {
            throw CmdException.badArg("服务器里没有「" + name + "」这个玩家的档案"
                    + "（他从没进过这个服？换个进过服的名字试试）");
        }
        // 同一玩家只留一个 key：先作废旧的
        String old = BY_PLAYER.get(profile.getId());
        if (old != null) {
            KEYS.remove(old);
        }
        Key k = new Key(newKey(), profile.getName(), profile.getId(), expireAt(server));
        KEYS.put(k.value, k);
        BY_PLAYER.put(k.playerUUID, k.value);
        MaidCliMod.LOG.info("[MaidCLI] login: {} ({}) · key={}… · 有效 {} 小时",
                k.playerName, k.playerUUID, k.value.substring(0, 6),
                MaidCliConfig.get(server).key_ttl_hours);
        return k;
    }

    /** 登出：作废指定 key（{@code login off=true}） */
    public static boolean logout(String key) {
        if (key == null) {
            return false;
        }
        Key k = KEYS.remove(key.trim());
        if (k != null) {
            BY_PLAYER.remove(k.playerUUID);
            return true;
        }
        return false;
    }

    /**
     * 校验一个 key（HTTP 头 {@code X-Maid-Key}）。
     * 没有 / 不认识 / 过期 ⇒ 抛 {@code unauthorized}（CLI 见到它会自动重登重试）。
     */
    public static Key require(String key) {
        if (key == null || key.isBlank()) {
            throw new CmdException(CmdException.UNAUTHORIZED,
                    "没带身份 —— 先登录",
                    "maidcli login <玩家名>（CLI 会自动带上 key，正常情况你看不到这一步）");
        }
        String k = key.trim();
        Key session = KEYS.get(k);
        if (session == null) {
            throw new CmdException(CmdException.UNAUTHORIZED,
                    "这个 key 不认识（服务端重启过？）",
                    "重新 login 一次即可（CLI 会自动做）");
        }
        if (System.currentTimeMillis() > session.expireAt) {
            KEYS.remove(k);
            BY_PLAYER.remove(session.playerUUID);
            throw new CmdException(CmdException.UNAUTHORIZED,
                    "key 过期了",
                    "重新 login 一次即可（CLI 会自动做）");
        }
        return session;
    }

    /** 从请求头里取 key 名（传输层用） */
    public static final String HEADER = "X-Maid-Key";

    /**
     * 名字是否在服务器的玩家档案里（{@code usercache.json}）—— 挡掉"随便编一个名字"。
     * <p>⚠️ 这条**只能挡"从没进过服"的名字**。
     */
    private static boolean knownPlayer(MinecraftServer server, String name) {
        try {
            java.nio.file.Path p = server.getServerDirectory().resolve("usercache.json");
            if (!java.nio.file.Files.exists(p)) {
                MaidCliMod.LOG.warn("[MaidCLI] 找不到 usercache.json，跳过玩家名校验");
                return true;                      // 找不到就放行（别把功能卡死）
            }
            String json = java.nio.file.Files.readString(p, java.nio.charset.StandardCharsets.UTF_8);
            com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(json).getAsJsonArray();
            for (com.google.gson.JsonElement e : arr) {
                com.google.gson.JsonObject o = e.getAsJsonObject();
                if (o.has("name") && name.equalsIgnoreCase(o.get("name").getAsString())) {
                    return true;
                }
            }
        } catch (Throwable t) {
            MaidCliMod.LOG.warn("[MaidCLI] 读 usercache 失败，跳过玩家名校验: {}", t.toString());
            return true;
        }
        return false;
    }

    /** 配置里的有效期（小时）→ 到期时刻 */
    private static long expireAt(MinecraftServer server) {
        int hours = Math.max(1, MaidCliConfig.get(server).key_ttl_hours);
        return System.currentTimeMillis() + hours * 3600_000L;
    }

    /** 随机 key（16 字节十六进制） */
    private static String newKey() {
        byte[] b = new byte[16];
        RAND.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** 服务重启时清掉所有 key（内存里的本来就该失效） */
    public static void clearAll() {
        KEYS.clear();
        BY_PLAYER.clear();
    }
}
