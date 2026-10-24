package com.xiaofeng.maidcli.loading;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.world.data.MaidInfo;
import com.github.tartaricacid.touhoulittlemaid.world.data.MaidWorldData;
import com.xiaofeng.maidcli.MaidCliMod;
import com.xiaofeng.maidcli.engine.MaidData;
import com.xiaofeng.maidcli.engine.MaidHandle;
import com.xiaofeng.maidcli.engine.MaidIdentity;
import com.xiaofeng.maidcli.engine.MaidRegistry;
import com.xiaofeng.maidcli.infra.MaidCliConfig;
import com.xiaofeng.maidcli.transport.CmdException;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.world.chunk.TicketController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 女仆加载 / 「唤醒」（设计见 {@code 内部文档}）。
 *
 * <p><b>核心概念：唤醒 = 加载</b>
 * <pre>
 *   未唤醒 → 一张票都不挂（她真的会从世界里消失，maids 都查不到）
 *   wake   → ① 挂两级半径的票  ② 接管（control 那套）  ③ 起 300 秒无指令超时
 *   释放   → ① stop（清队列 + 关会话）  ② 还原任务 / 锚点  ③ 【最后】撤票
 * </pre>
 *
 * <p><b>两级半径</b>（区块，中心 = 她的区块）：
 * <pre>
 *   d ≤ sim_radius(4)          → 每块 forceChunk(forceLoad=true) ⇒ 实体真 tick
 *   sim &lt; d ≤ view_radius(8)  → 每块一张「只加载」票            ⇒ 不 tick
 * </pre>
 *
 * <p>★★ <b>让它真 tick 的关键：NeoForge 官方 API + forceLoad</b> ——
 * NeoForge 给原版 {@code addRegionTicket} 加了第 5 个参数 {@code forceLoad}，
 * {@code true} 才用 {@code BLOCK_TICKING} 票（实体真 tick）。vanilla 的 3 参重载拿不到这个开关
 * ⇒ 这就是"怎么挂票都不 tick、而 {@code /forceload} 却行"的原因。
 *
 * <p>自举（她不在世界里怎么回来）：TLM 的 {@code MaidWorldData.MaidInfos} 是"卸载瞬间写"的索引
 * ⇒ 按 uuid 精确匹配 → 同步加载区块把她捞回来 ⇒ 交给正常流程。
 * ⚠️ 索引会留<b>幽灵记录</b>（uuid 换过就永远留着）⇒ 必须按 uuid 核对。
 */
public final class MaidLoader {

    /** 我们的票据类型（运行时票据，不落盘；不碰原版 FORCED，免得和 /forceload 打架） */
    private static final TicketType<ChunkPos> TICKET =
            TicketType.create("maid_cli_wake", Comparator.comparingLong(ChunkPos::toLong));

    /** ★ 官方「按 mod 分租户的常加载」控制器 —— 票按 controller id 存，不污染玩家的 /forceload */
    public static final TicketController WAKE_CONTROLLER =
            new TicketController(ResourceLocation.fromNamespaceAndPath("maid_cli", "wake"));

    /** 外圈只加载票的等级（= ChunkLevel.FULL） */
    private static final int LOAD_LEVEL = 33;

    /** 两阶段唤醒的升级延迟（见 {@link #UPGRADE_DELAY} 说明） */
    private static final int UPGRADE_DELAY = 2;

    /** 票账本：标签 → (哪个世界 + 中心区块 + 已加过的区块)。释放时照账本精确撤，绝不留残迹 */
    private record Marked(ServerLevel level, long center, Set<Long> chunks) {
    }

    private static final Map<String, Marked> MARKED = new ConcurrentHashMap<>();

    private MaidLoader() {
    }

    private static MaidCliConfig.Data cfg(MinecraftServer server) {
        return MaidCliConfig.get(server);
    }

    // ────────────────────────── 唤醒 / 取消唤醒 ──────────────────────────

    /**
     * 唤醒 / 取消唤醒（{@code wake} 指令）。
     *
     * <p>★ <b>一次只唤醒一只</b>：想同时控制多只就分别 wake，避免"一个玩家一堆女仆全加载"卡服。
     * <p>★ 必须在"她<b>不在世界里</b>"时也能用 ⇒ 目标可以是标签 / uuid / 名字。
     */
    public static Map<String, Object> wake(MinecraftServer server, String target, UUID playerUUID, boolean off) {
        if (playerUUID == null) {
            throw new CmdException(CmdException.UNAUTHORIZED, "唤醒必须带身份（先 login）");
        }
        // ① 先在世界里找
        EntityMaid maid = findAnywhere(server, target);
        if (maid != null) {
            checkOwner(maid, playerUUID);
            return off ? doRelease(server, maid) : doWake(server, maid, playerUUID);
        }
        // ② 不在世界里 ⇒ 查 TLM 的未加载索引（按 uuid / 名字，必须是这个玩家名下的）
        MaidInfo info = findInIndex(server, target, playerUUID);
        if (info != null) {
            UUID id = info.getEntityId();
            if (off) {
                releaseById(id);
                return out("已取消唤醒（她本来就不在世界里，票已撤）", id, null, false);
            }
            ServerLevel level = levelOf(server, info.getDimension());
            if (level == null) {
                throw CmdException.notFound("她的记录在维度「" + info.getDimension()
                        + "」里，但那个维度现在不在", null);
            }
            ChunkPos cp = new ChunkPos(info.getChunkPos());
            level.getChunk(cp.x, cp.z);                 // ★ 同步加载（阻塞到加载完）把她捞出来
            EntityMaid back = findMaid(server, id);
            if (back != null) {
                checkOwner(back, playerUUID);
                MaidCliMod.LOG.info("[MaidCLI] wake(自举)：按索引 {} {} 同步加载捞回 {}",
                        info.getDimension(), cp.x + "," + cp.z, info.getName().getString());
                return doWake(server, back, playerUUID);
            }
            throw CmdException.notFound("按索引加载「" + info.getName().getString()
                    + "」没成功（幽灵记录？）", "用 maids 看她是否真的还在");
        }
        // ③ 兜底：我们自己的账本（"最后已知位置"）
        for (MaidIdentity rec : new ArrayList<>(MaidData.get(server).all())) {
            boolean hit = target.equalsIgnoreCase(rec.tag)
                    || (rec.uuid != null && rec.uuid.toString().startsWith(target.toLowerCase()))
                    || target.equals(rec.name);
            if (!hit || rec.lastPos == null || rec.lastDim.isEmpty()) {
                continue;
            }
            ServerLevel lv = levelOf(server, rec.lastDim);
            if (lv == null) {
                continue;
            }
            if (off) {
                releaseById(rec.uuid);
                return out("已取消唤醒（她不在世界里，票已撤）", rec.uuid, rec.name, false);
            }
            ChunkPos cp = new ChunkPos(rec.lastPos);
            lv.getChunk(cp.x, cp.z);
            EntityMaid back = rec.uuid == null ? null : findMaid(server, rec.uuid);
            if (back == null) {
                back = findAnywhere(server, rec.tag);
            }
            if (back != null) {
                checkOwner(back, playerUUID);
                MaidCliMod.LOG.info("[MaidCLI] wake(账本兜底)：{} 按最后已知位置 {} {} 捞回",
                        rec.tag, rec.lastDim, cp.x + "," + cp.z);
                return doWake(server, back, playerUUID);
            }
        }
        throw CmdException.notFound("指不到「" + target + "」：世界里没有，未加载记录里也没有",
                "用 maids 看有哪些女仆（含未加载的）");
    }

    private static void checkOwner(EntityMaid maid, UUID playerUUID) {
        UUID owner = maid.getOwnerUUID();
        if (owner != null && !owner.equals(playerUUID)) {
            throw new CmdException(CmdException.NOT_YOURS,
                    "「" + maid.getName().getString() + "」不是你的女仆（她主人是别人）",
                    "只能唤醒属于你自己的女仆");
        }
    }

    private static Map<String, Object> doWake(MinecraftServer server, EntityMaid maid, UUID playerUUID) {
        MaidHandle h = MaidRegistry.handleFor(server, maid);
        h.awake = true;
        h.awakeBy = playerUUID;
        h.lastCommandTick = server.getTickCount();
        diff(server, maid);                     // 立刻挂票（不等下一个 tick）
        takeOver(maid, h);                      // 接管：清盒子 + 日程全天 + 关自动拾取
        MaidCliMod.LOG.info("[MaidCLI] wake：{} [{}] 已唤醒（加载 {} / 实体tick {} 圈）",
                maid.getName().getString(), h.identity.tag,
                cfg(server).view_radius, cfg(server).sim_radius);
        return out("已唤醒", maid.getUUID(), maid.getName().getString(), true);
    }

    private static Map<String, Object> doRelease(MinecraftServer server, EntityMaid maid) {
        MaidHandle h = MaidRegistry.handleFor(server, maid);
        release(server, maid, h, "wake off");
        return out("已取消唤醒（队列已清、状态已还原、加载票已撤）",
                maid.getUUID(), maid.getName().getString(), false);
    }

    /**
     * ★ 取消唤醒的收尾（<b>顺序不能反</b>）：
     * <pre>
     *   ① stop       清我们的动作队列 + 停当前 + 关会话（鼠标槽退料）
     *   ② restore    还原任务 / 锚点 / 拾取设置
     *   ③ 撤票       ★ 必须放最后 —— 先撤票的话她所在区块可能卸载，而①②还在操作她
     * </pre>
     */
    public static void release(MinecraftServer server, EntityMaid maid, MaidHandle h, String reason) {
        h.reset(reason);                    // ①
        if (maid != null) {
            releaseTakeOver(maid, h);       // ②
        }
        releaseByTag(h.identity.tag);       // ③
        h.awake = false;
        h.awakeBy = null;
    }

    private static Map<String, Object> out(String message, UUID id, String name, boolean awake) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", message);
        if (id != null) {
            m.put("maid_uuid", id.toString());
        }
        if (name != null) {
            m.put("maid_name", name);
        }
        m.put("awake", awake);
        return m;
    }

    // ────────────────────────── 门禁 ──────────────────────────

    public static boolean isAwake(MaidHandle h) {
        return h != null && h.awake;
    }

    /** 这次女仆指令是在醒着状态下发的吗 —— 是就刷新计时（超时靠它） */
    public static void touch(MaidHandle h) {
        if (h != null && !h.isIdle()) {
            // 有活儿在跑 = 有活动，续期（宏跑的时候自然续期）
        }
        if (h != null) {
            MinecraftServer s = MaidCliMod.server();
            if (s != null) {
                h.lastCommandTick = s.getTickCount();
            }
        }
    }

    /**
     * 门禁：未唤醒 ⇒ 拒绝。
     * <p>归属也要对：这只女仆必须是<b>当初 wake 它的那个玩家</b>的。
     */
    public static void requireAwake(EntityMaid maid, MaidHandle h, UUID playerUUID) {
        if (h == null || !h.awake) {
            throw new CmdException(CmdException.NOT_AWAKE,
                    "「" + maid.getName().getString() + "」还没被唤醒 —— 女仆指令都要先唤醒",
                    "先 wake（唤醒后 300 秒没指令会自动取消）");
        }
        if (playerUUID != null && h.awakeBy != null && !playerUUID.equals(h.awakeBy)) {
            throw new CmdException(CmdException.NOT_YOURS,
                    "「" + maid.getName().getString() + "」是被别人唤醒的，你不能操作",
                    "只有唤醒它的那个玩家能继续调这只女仆");
        }
    }

    // ────────────────────────── 接管（清盒子） ──────────────────────────

    /**
     * 进入受控（{@code control} 的语义）：切到「指令模式」任务 + 日程全天 + 关自动拾取。
     * <p>唤醒时自动调用；幂等（已在受控则直接返回）。
     */
    public static void takeOver(EntityMaid maid, MaidHandle h) {
        if (h.controlled) {
            return;
        }
        try {
            h.savedTaskId = String.valueOf(maid.getTask().getUid());
        } catch (Throwable t) {
            h.savedTaskId = null;
        }
        try {
            com.xiaofeng.maidcli.command.defs.ControlCmds.takeControl(maid, h);
            h.controlled = true;
        } catch (Throwable t) {
            MaidCliMod.LOG.warn("[MaidCLI] 接管失败（继续唤醒流程）: {}", t.toString());
        }
    }

    /** 退出受控：把自主还给她 */
    public static void releaseTakeOver(EntityMaid maid, MaidHandle h) {
        if (maid == null || !h.controlled) {
            return;
        }
        try {
            com.xiaofeng.maidcli.command.defs.ControlCmds.releaseControl(maid, h);
        } catch (Throwable t) {
            MaidCliMod.LOG.warn("[MaidCLI] 退出接管失败: {}", t.toString());
        }
        h.controlled = false;
    }

    /**
     * 任务被外部改了（玩家 GUI / TLM 内置 AI agent / 网络包）⇒ 退出受控，把控制权还回去。
     *
     * <p>⚠️ <b>只清标志，不调 {@code releaseControl}</b> ——
     * {@code EntityMaid.setTask()} 内部已经 {@code refreshBrain()} 把行为盒子装好了，
     * 再重装一遍是多余的。
     */
    public static void notePlayerTaskChange(EntityMaid maid, MaidHandle h) {
        if (h == null || !h.controlled) {
            return;
        }
        h.controlled = false;
        h.controlledTaskId = null;
        MaidCliMod.LOG.info("[MaidCLI] 任务被外部改动（{} 现在的任务是 {}）⇒ 退出受控",
                maid.getName().getString(),
                maid.getTask() == null ? "null" : maid.getTask().getUid());
    }

    // ────────────────────────── 查未加载的 ──────────────────────────

    /** 列"当前不在世界里"的女仆 / 墓碑（数据来自 TLM 的 MaidWorldData 索引） */
    public static Map<String, Object> whereabouts(MinecraftServer server, UUID ownerUUID) {
        if (ownerUUID == null) {
            throw new CmdException(CmdException.UNAUTHORIZED,
                    "要先 login 才能查（查的是「你自己名下的」未加载记录）");
        }
        MaidWorldData data = worldData(server);
        if (data == null) {
            throw new IllegalStateException("读不到 TLM 的世界数据（MaidWorldData）");
        }
        Set<UUID> loaded = new HashSet<>();
        for (EntityMaid m : MaidRegistry.allLoaded(server)) {
            loaded.add(m.getUUID());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("maids", listOf(data.getInfos(ownerUUID), loaded));
        out.put("tombstones", listOf(data.getTombstones(ownerUUID), loaded));
        out.put("note", "TLM 记录的「当前不在世界里」的对象；loaded=true 说明索引是幽灵记录（她其实在）");
        return out;
    }

    private static List<Map<String, Object>> listOf(List<MaidInfo> infos, Set<UUID> loaded) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (infos == null) {
            return out;
        }
        for (MaidInfo i : infos) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", i.getName() == null ? null : i.getName().getString());
            m.put("uuid", i.getEntityId().toString());
            m.put("dimension", i.getDimension());
            BlockPos p = i.getChunkPos();
            m.put("pos", List.of(p.getX(), p.getY(), p.getZ()));
            m.put("when", i.getTimestamp());
            m.put("loaded", loaded.contains(i.getEntityId()));
            m.put("awake", false);
            out.add(m);
        }
        return out;
    }

    private static MaidInfo findInIndex(MinecraftServer server, String target, UUID ownerUUID) {
        MaidWorldData data = worldData(server);
        if (data == null || target == null) {
            return null;
        }
        List<MaidInfo> all = new ArrayList<>();
        if (data.getInfos(ownerUUID) != null) {
            all.addAll(data.getInfos(ownerUUID));
        }
        if (data.getTombstones(ownerUUID) != null) {
            all.addAll(data.getTombstones(ownerUUID));
        }
        String t = target.trim().toLowerCase();
        List<MaidInfo> hits = new ArrayList<>();
        for (MaidInfo i : all) {
            String uuid = i.getEntityId().toString().toLowerCase();
            String name = i.getName() == null ? "" : i.getName().getString();
            if (uuid.equals(t)
                    || (t.length() >= 4 && t.matches("^[0-9a-f-]+$") && uuid.startsWith(t))
                    || name.equals(target.trim())) {
                hits.add(i);
            }
        }
        return hits.size() == 1 ? hits.get(0) : null;
    }

    private static MaidWorldData worldData(MinecraftServer server) {
        if (server == null || server.overworld() == null) {
            return null;
        }
        return MaidWorldData.get(server.overworld());
    }

    private static ServerLevel levelOf(MinecraftServer server, String dimension) {
        for (ServerLevel l : server.getAllLevels()) {
            if (l.dimension().location().toString().equals(dimension)) {
                return l;
            }
        }
        return null;
    }

    // ────────────────────────── 每 tick：差分挂票 + 超时 ──────────────────────────

    /** 由 {@code ServerTicker} 每 tick 调（全局只有一个 tick 循环） */
    public static void tickTimeouts(MinecraftServer server) {
        for (MaidHandle h : MaidRegistry.all()) {
            if (!h.awake) {
                continue;
            }
            EntityMaid maid = h.maid();
            if (maid != null) {
                diff(server, maid);
                // ★ T3：任务被外部改了？（玩家 GUI / TLM 内置 agent / 网络包）
 // setTask() 内部会 refreshBrain ⇒ 她其实已经恢复自主了，我们的受控标志必须让位
                if (h.controlled) {
                    String nowTask = maid.getTask() == null ? null : maid.getTask().getUid().toString();
                    if (nowTask != null && h.controlledTaskId != null
                            && !nowTask.equals(h.controlledTaskId)) {
                        notePlayerTaskChange(maid, h);
                    }
                }
                long idle = server.getTickCount() - h.lastCommandTick;
                long limit = cfg(server).wake_timeout_seconds * 20L;
                if (idle > limit && h.isIdle()) {
                    MaidCliMod.LOG.info("[MaidCLI] 唤醒超时（{} 秒没指令）⇒ 自动取消唤醒：{}",
                            cfg(server).wake_timeout_seconds, maid.getName().getString());
                    release(server, maid, h, "唤醒超时");
                }
            } else if (server.getTickCount() - h.lastCommandTick > cfg(server).wake_timeout_seconds * 20L) {
                // 她不在世界里 ⇒ 故意不动票（票正是"把她按在加载区"的手段），只处理超时
                MaidCliMod.LOG.info("[MaidCLI] 唤醒超时且她不在世界里 ⇒ 撤票：{}", h.identity.tag);
                releaseById(h.identity.uuid);
                h.awake = false;
                h.awakeBy = null;
            }
        }
    }

    /** 关服时全部释放 */
    public static void releaseAll() {
        releaseEverything();
    }

    /** 关服：撤掉所有票（照账本） */
    public static void releaseEverything() {
        List<String> tags = new ArrayList<>(MARKED.keySet());
        for (String tag : tags) {
            Marked m = MARKED.get(tag);
            if (m == null) {
                continue;
            }
            for (long c : m.chunks()) {
                remove(m.level(), c);
            }
            MARKED.remove(tag);
        }
        MaidCliMod.LOG.info("[MaidCLI] 关服：释放了 {} 只女仆的加载票", tags.size());
    }

    // ────────────────────────── 挂票 / 撤票 ──────────────────────────

    /**
     * 她跨区块了才重建票（两级半径都按"距中心的 Chebyshev 距离"算，中心一变就得重算）。
     *
     * <p>★ 两阶段：<b>先全圈按只加载票挂上 → 过 {@link #UPGRADE_DELAY} tick 再把内圈降级为 tick 票</b>。
     * 原因：实体进 tick 列表只在"方块 tick 状态发生<b>转移</b>"时被调度；
     * 区块"从无到有直接挂 tick 票"⇒ 没有转移 ⇒ 实体永远不 tick ✗（实测 Age 恒 0）。
     */
    private static void diff(MinecraftServer server, EntityMaid maid) {
        MaidHandle h = MaidRegistry.byTag(com.xiaofeng.maidcli.infra.MaidCliTags.read(maid));
        if (h == null || !(maid.level() instanceof ServerLevel level)) {
            return;
        }
        String tag = h.identity.tag;
        ChunkPos now = maid.chunkPosition();
        Marked m = MARKED.get(tag);
        long upgradeAt = h.wakeUpgradeAtTick;
        boolean sameCenter = m != null && m.level() == level && m.center() == now.toLong();
        if (sameCenter && (upgradeAt <= 0 || server.getTickCount() < upgradeAt)) {
            return;
        }
        if (m != null) {
            for (long c : m.chunks()) {
                remove(m.level(), c);
            }
        }
        boolean upgraded = upgradeAt > 0 && server.getTickCount() >= upgradeAt;
        Set<Long> added = addRing(level, now, maid.getUUID(), upgraded);
        MARKED.put(tag, new Marked(level, now.toLong(), added));
        h.wakeUpgradeAtTick = upgraded ? 0 : server.getTickCount() + UPGRADE_DELAY;
        // ★ 顺手刷新"最后已知位置"（账本兜底要用；SavedData 只是标脏，不会每 tick 写盘）
        h.identity.lastPos = maid.blockPosition();
        h.identity.lastDim = level.dimension().location().toString();
        h.identity.uuid = maid.getUUID();
        h.identity.name = maid.getName().getString();
        h.identity.lastSeenTick = server.getTickCount();
        try {
            MaidIdentity rec = MaidData.get(server).getOrCreate(tag);
            rec.lastPos = h.identity.lastPos;
            rec.lastDim = h.identity.lastDim;
            rec.uuid = h.identity.uuid;
            rec.name = h.identity.name;
            rec.lastSeenTick = h.identity.lastSeenTick;
            MaidData.get(server).markDirty();
        } catch (Throwable ignored) {
        }
    }

    /** 按两级半径挂一圈票，返回挂上的区块集合（撤的时候要照账本算，所以必须存中心） */
    private static Set<Long> addRing(ServerLevel level, ChunkPos center, UUID owner, boolean upgraded) {
        MinecraftServer server = level.getServer();
        int sim = Math.max(0, cfg(server).sim_radius);
        int view = Math.max(sim, cfg(server).view_radius);
        Set<Long> out = new HashSet<>();
        for (int dx = -view; dx <= view; dx++) {
            for (int dz = -view; dz <= view; dz++) {
                int d = Math.max(Math.abs(dx), Math.abs(dz));
                ChunkPos p = new ChunkPos(center.x + dx, center.z + dz);
                if (upgraded && d <= sim) {
                    // 内圈（要实体 tick）：官方 API，forceLoad=true ⇒ BLOCK_TICKING 票
                    WAKE_CONTROLLER.forceChunk(level, owner, p.x, p.z, true, true);
                } else {
                    // 外圈（只加载）：vanilla 票，等级 33 ⇒ 精确只覆盖自己那一格
                    ticket(level, p, LOAD_LEVEL, true);
                }
                out.add(p.toLong());
            }
        }
        return out;
    }

    /**
     * 撤票 —— ★ 必须和加票用【完全一样的票类型 + 等级】，否则撤不掉（会漏票）。
     *
     * <p>⚠️ 遇到过的两个坑（合起来导致"取消唤醒后她 90 秒还留在世界里"）：
     * <ol>
     *   <li>加票换了一种票类型，撤票还用旧的 ⇒ 一张都没撤掉 ✗</li>
     *   <li>两阶段唤醒下同一区块先后用过两个等级 ⇒ 只按一种规则撤会漏一张 ✗</li>
     * </ol>
     * ⇒ 现在：<b>两种都撤一遍</b>（撤一张不存在的票是无害的 no-op）。
     */
    private static void remove(ServerLevel level, long chunkLong) {
        ChunkPos p = new ChunkPos(chunkLong);
        WAKE_CONTROLLER.forceChunk(level, java.util.UUID.randomUUID(), p.x, p.z, false, true);
        WAKE_CONTROLLER.forceChunk(level, java.util.UUID.randomUUID(), p.x, p.z, false, false);
    }

    /** 撤票（带 owner 的精确版） */
    private static void remove(ServerLevel level, long chunkLong, UUID owner) {
        ChunkPos p = new ChunkPos(chunkLong);
        WAKE_CONTROLLER.forceChunk(level, owner, p.x, p.z, false, true);
        WAKE_CONTROLLER.forceChunk(level, owner, p.x, p.z, false, false);
        level.getChunkSource().removeRegionTicket(TicketType.PLAYER, p, 0, p);
        level.getChunkSource().removeRegionTicket(TicketType.PLAYER, p,
                33 - tickLevel(level.getServer()), p);
    }

    /** 撤掉一只的全部票（她不在世界里时也要能撤）—— 按 uuid 找标签再撤 */
    public static void releaseById(UUID id) {
        if (id == null) {
            return;
        }
        String tag = null;
        for (MaidHandle h : MaidRegistry.all()) {
            if (id.equals(h.identity.uuid)) {
                tag = h.identity.tag;
                break;
            }
        }
        if (tag == null) {
            // 兜底：账本里只有一条时直接用
            for (MaidIdentity rec : MaidData.get(MaidCliMod.server()).all()) {
                if (id.equals(rec.uuid)) {
                    tag = rec.tag;
                    break;
                }
            }
        }
        if (tag != null) {
            releaseByTag(tag);
        }
    }

    /** 按标签撤票（推荐入口） */
    public static void releaseByTag(String tag) {
        Marked m = MARKED.remove(tag);
        if (m == null) {
            return;
        }
        for (long c : m.chunks()) {
            remove(m.level(), c);
        }
        MaidCliMod.LOG.info("[MaidCLI] 释放 {} 的 {} 个加载票", tag, m.chunks().size());
    }

    private static int tickLevel(MinecraftServer server) {
        return Math.max(0, 31 - Math.max(0, cfg(server).sim_radius));
    }

    /**
     * 挂票 —— ★ 照抄【玩家的加载机制】：{@code TicketType.PLAYER}。
     * 等级换算成 {@code addRegionTicket} 的 distance：{@code distance = 33 - 等级}
     * （等级 33 → distance 0，只覆盖自己那一格）。
     */
    private static void ticket(ServerLevel level, ChunkPos pos, int ticketLevel, boolean add) {
        int distance = 33 - ticketLevel;
        if (add) {
            level.getChunkSource().addRegionTicket(TicketType.PLAYER, pos, distance, pos);
        } else {
            level.getChunkSource().removeRegionTicket(TicketType.PLAYER, pos, distance, pos);
        }
    }

    // ────────────────────────── 小工具 ──────────────────────────

    private static EntityMaid findMaid(MinecraftServer server, UUID id) {
        if (id == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e instanceof EntityMaid maid) {
                return maid;
            }
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (e instanceof EntityMaid maid && maid.getUUID().equals(id)) {
                    return maid;
                }
            }
        }
        return null;
    }

    /** 世界里找（标签 / uuid / 名字）—— 不会抛异常，找不到返回 null */
    private static EntityMaid findAnywhere(MinecraftServer server, String target) {
        try {
            return MaidRegistry.resolve(server, target);
        } catch (CmdException e) {
            return null;
        }
    }
}
