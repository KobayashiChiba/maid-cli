package com.xiaofeng.maidcli.engine;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.MaidCliMod;
import com.xiaofeng.maidcli.infra.MaidCliTags;
import com.xiaofeng.maidcli.transport.CmdException;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 全局表：标签 ↔ {@link MaidHandle}（core-concepts §1）。
 *
 * <p>旧实现把状态散在 8 个类的静态 Map 里 —— 这里是那堆东西的<b>唯一</b>归口。
 *
 * <p>两层查找（core-concepts §2.2）：
 * <pre>
 * ① 快路径  账本: 标签 → 最后已知 uuid → 按 uuid 在 server 里找实体
 * ② 慢路径  扫所有维度的 EntityMaid → 读她 NBT 里的标签 → 匹配（顺便更新账本 uuid）
 * ③ 都没有  ⇒ 她不在世界里（只有账本那份记录）
 * </pre>
 */
public final class MaidRegistry {

    /** 标签 → 句柄（只增不减） */
    private static final Map<String, MaidHandle> BY_TAG = new LinkedHashMap<>();

    private MaidRegistry() {
    }

    public static synchronized MaidHandle byTag(String tag) {
        return tag == null ? null : BY_TAG.get(tag);
    }

    public static synchronized List<MaidHandle> all() {
        return new ArrayList<>(BY_TAG.values());
    }

    public static synchronized MaidHandle getOrCreate(String tag) {
        return BY_TAG.computeIfAbsent(tag, MaidHandle::new);
    }

    /** 服务重启时清空（运行时层本来也不该续） */
    public static synchronized void clearAll() {
        BY_TAG.clear();
    }

    public static synchronized int size() {
        return BY_TAG.size();
    }

    /**
     * 取一只女仆的句柄 —— 顺手保证"她"在账本里、且贴了标签。
     * <p>贴标签的时机 = <b>第一次见到她</b>。
     */
    public static MaidHandle handleFor(MinecraftServer server, EntityMaid maid) {
        String tag = MaidCliTags.read(maid);
        if (tag == null || !MaidCliTags.valid(tag)) {
            tag = assignTag(server, maid);
        }
        String finalTag = tag;
        MaidHandle h = getOrCreate(tag);
        h.identity.uuid = maid.getUUID();
        h.identity.name = maid.getName().getString();
        h.identity.lastPos = maid.blockPosition();
        if (maid.level() instanceof ServerLevel sl) {
            h.identity.lastDim = sl.dimension().location().toString();
        }
        h.identity.lastSeenTick = server.getTickCount();
        try {
            h.identity.ownerUuid = maid.getOwnerUUID();
        } catch (Throwable ignored) {
        }
        // 账本落盘（只在真的变了时写）
        MaidData data = MaidData.get(server);
        MaidIdentity rec = data.getOrCreate(finalTag);
        rec.uuid = h.identity.uuid;
        rec.name = h.identity.name;
        rec.lastPos = h.identity.lastPos;
        rec.lastDim = h.identity.lastDim;
        rec.ownerUuid = h.identity.ownerUuid;
        rec.lastSeenTick = h.identity.lastSeenTick;
        data.markDirty();
        return h;
    }

    /** 给没标签的她贴一个（避开世界里已用的） */
    private static String assignTag(MinecraftServer server, EntityMaid maid) {
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (ServerLevel lv : server.getAllLevels()) {
            for (Entity e : lv.getAllEntities()) {
                if (e instanceof EntityMaid m && !m.getUUID().equals(maid.getUUID())) {
                    String t = MaidCliTags.read(m);
                    if (t != null) {
                        taken.add(t);
                    }
                }
            }
        }
        synchronized (MaidRegistry.class) {
            taken.addAll(BY_TAG.keySet());
            String tag = MaidCliTags.randomUnique(taken);
            MaidCliTags.write(maid, tag);
            MaidCliMod.LOG.info("[MaidCLI] 给「{}」贴标签 {}", maid.getName().getString(), tag);
            return tag;
        }
    }

    // ────────────────────────── 找实体 ──────────────────────────

    /**
     * 按标签找实体（我们最主力的查找）。
     *
     * @return null = 她不在世界里
     */
    public static EntityMaid maidByTag(MinecraftServer server, String tag) {
        if (tag == null || server == null) {
            return null;
        }
        // ① 快路径：账本里的 uuid
        MaidHandle h = byTag(tag);
        UUID u = h != null ? h.identity.uuid : null;
        if (u == null) {
            MaidIdentity rec = MaidData.get(server).identities.get(tag);
            u = rec != null ? rec.uuid : null;
        }
        if (u != null) {
            Entity e = server.getLevel(net.minecraft.world.level.Level.OVERWORLD) == null ? null : null;
            for (ServerLevel lv : server.getAllLevels()) {
                Entity found = lv.getEntity(u);
                if (found instanceof EntityMaid m) {
                    // 顺手核对标签真的对得上（uuid 会变过 / 有幽灵记录）
                    String t = MaidCliTags.read(m);
                    if (t == null || t.equals(tag)) {
                        return m;
                    }
                }
            }
        }
        // ② 慢路径：扫世界读 NBT
        for (ServerLevel lv : server.getAllLevels()) {
            for (Entity e : lv.getAllEntities()) {
                if (e instanceof EntityMaid m && tag.equals(MaidCliTags.read(m))) {
                    return m;
                }
            }
        }
        return null;
    }

    /**
     * 解析一个"寻址"（COMMANDS.md §2.3）→ 实体。
     * <p>支持：标签（6 位）· uuid 短码 / 全串 · 名字 · {@code 名字#N}
     *
     * @throws CmdException {@code not_found}（含重名时列出候选）
     */
    public static EntityMaid resolve(MinecraftServer server, String key) {
        if (key == null || key.isBlank()) {
            throw CmdException.badArg("没说找哪只女仆（要标签 / uuid / 名字）");
        }
        String k = key.trim();

        // ① 标签
        if (MaidCliTags.valid(k)) {
            EntityMaid m = maidByTag(server, k);
            if (m != null) {
                return m;
            }
            throw CmdException.notFound(
                    "找不到「" + k + "」—— 她现在不在世界里（未唤醒 / 走远了）",
                    "先 wake " + k + "（唤醒会按记录把她加载回来）；不知道她在哪就 maids 列一下");
        }

        // ② uuid（全串或 8 位短码）
        if (k.matches("[0-9a-fA-F]{8}") || k.matches("[0-9a-fA-F-]{32,36}")) {
            List<EntityMaid> hits = new ArrayList<>();
            for (ServerLevel lv : server.getAllLevels()) {
                for (Entity e : lv.getAllEntities()) {
                    if (e instanceof EntityMaid m) {
                        String us = m.getUUID().toString().replace("-", "");
                        if (us.startsWith(k.toLowerCase().replace("-", ""))) {
                            hits.add(m);
                        }
                    }
                }
            }
            if (hits.size() == 1) {
                return hits.get(0);
            }
            if (hits.isEmpty()) {
                throw CmdException.notFound("按 uuid「" + k + "」找不到她（她可能没加载）",
                        "先用 maids 看看有哪些；未加载的用 wake 拉回来");
            }
            throw new CmdException(CmdException.BAD_ARG, "uuid 短码「" + k + "」匹配到多只", "给全串");
        }

        // ③ 名字 / 名字#N
        String name = k;
        int idx = -1;
        int hash = k.lastIndexOf('#');
        if (hash > 0) {
            String tail = k.substring(hash + 1);
            try {
                idx = Integer.parseInt(tail);
                name = k.substring(0, hash);
            } catch (NumberFormatException ignored) {
                // 名字里本来就有 # ⇒ 当完整名字
            }
        }
        List<EntityMaid> matches = new ArrayList<>();
        for (ServerLevel lv : server.getAllLevels()) {
            for (Entity e : lv.getAllEntities()) {
                if (e instanceof EntityMaid m && name.equals(m.getName().getString())) {
                    matches.add(m);
                }
            }
        }
        if (matches.isEmpty()) {
            throw CmdException.notFound("找不到名字叫「" + name + "」的女仆（她可能没加载）",
                    "先 maids 看看有哪些；未加载的用 wake 拉回来");
        }
        // 按标签字典序排（跨重启稳定）
        matches.sort((a, b) -> {
            String ta = MaidCliTags.read(a), tb = MaidCliTags.read(b);
            return String.valueOf(ta).compareTo(String.valueOf(tb));
        });
        if (matches.size() == 1 && idx < 0) {
            return matches.get(0);
        }
        if (idx >= 1 && idx <= matches.size()) {
            return matches.get(idx - 1);
        }
        // 重名 ⇒ 报错 + 候选（COMMANDS.md §2.4）
        List<Map<String, Object>> cand = new ArrayList<>();
        for (EntityMaid m : matches) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", m.getName().getString());
            c.put("tag", MaidCliTags.read(m));
            c.put("uuid", m.getUUID().toString());
            c.put("dimension", m.level().dimension().location().toString());
            c.put("pos", List.of(m.blockPosition().getX(), m.blockPosition().getY(), m.blockPosition().getZ()));
            c.put("loaded", true);
            cand.add(c);
        }
        throw new CmdException(CmdException.BAD_ARG,
                "名字「" + name + "」有 " + matches.size() + " 只在世界里 —— 用标签或 名字#N 指定",
                null, Map.of("candidates", cand));
    }

    /** 扫一遍世界：贴标签 + 更新账本（wake / maids 都会调） */
    public static void scan(MinecraftServer server) {
        for (ServerLevel lv : server.getAllLevels()) {
            for (Entity e : lv.getAllEntities()) {
                if (e instanceof EntityMaid m) {
                    try {
                        handleFor(server, m);
                    } catch (Throwable t) {
                        MaidCliMod.LOG.warn("[MaidCLI] 扫描女仆失败: {}", t.toString());
                    }
                }
            }
        }
    }

    /** 世界类查询用：所有"世界里 + 不在世界里"的女仆条目（COMMANDS.md §5.1.1） */
    public static List<Map<String, Object>> listAll(MinecraftServer server) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        // ① 先放世界里的
        for (ServerLevel lv : server.getAllLevels()) {
            for (Entity e : lv.getAllEntities()) {
                if (!(e instanceof EntityMaid m)) {
                    continue;
                }
                MaidHandle h = handleFor(server, m);
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("name", m.getName().getString());
                o.put("tag", h.identity.tag);
                o.put("uuid", m.getUUID().toString());
                o.put("dimension", lv.dimension().location().toString());
                BlockPos p = m.blockPosition();
                o.put("pos", List.of(p.getX(), p.getY(), p.getZ()));
                o.put("loaded", true);
                o.put("awake", h.awake);
                out.put(h.identity.tag, o);
            }
        }
        // ② 再补账本里"不在世界里"的
        for (MaidHandle h : all()) {
            if (out.containsKey(h.identity.tag)) {
                continue;
            }
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", h.identity.name);
            o.put("tag", h.identity.tag);
            o.put("uuid", h.identity.uuid == null ? null : h.identity.uuid.toString());
            o.put("dimension", h.identity.lastDim);
            o.put("pos", h.identity.lastPos == null ? null
                    : List.of(h.identity.lastPos.getX(), h.identity.lastPos.getY(), h.identity.lastPos.getZ()));
            o.put("loaded", false);
            o.put("awake", false);
            o.put("last_seen", h.identity.lastSeenTick);
            out.put(h.identity.tag, o);
        }
        // ③ 最后补账本（存档）里的幽灵记录
        for (MaidIdentity id : MaidData.get(server).all()) {
            if (out.containsKey(id.tag)) {
                continue;
            }
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", id.name);
            o.put("tag", id.tag);
            o.put("uuid", id.uuid == null ? null : id.uuid.toString());
            o.put("dimension", id.lastDim);
            o.put("pos", id.lastPos == null ? null
                    : List.of(id.lastPos.getX(), id.lastPos.getY(), id.lastPos.getZ()));
            o.put("loaded", false);
            o.put("awake", false);
            o.put("stale", true);
            out.put(id.tag, o);
        }
        return new ArrayList<>(out.values());
    }

    /** 一段不能变的东西：世界里的全部女仆（给 assignTag 这类地方用） */
    public static List<EntityMaid> allLoaded(MinecraftServer server) {
        List<EntityMaid> out = new ArrayList<>();
        for (ServerLevel lv : server.getAllLevels()) {
            for (Entity e : lv.getAllEntities()) {
                if (e instanceof EntityMaid m) {
                    out.add(m);
                }
            }
        }
        return Collections.unmodifiableList(out);
    }
}
