package com.xiaofeng.maidcli.ops;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.MaidCliMod;
import com.xiaofeng.maidcli.engine.Session;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

import java.util.Map;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 容器 / 工位的实现（★ 只给 {@code Action.tick()} 调）。
 *
 * <p>三个职责：<b>开合</b> · <b>槽位视图</b> · <b>退料</b>（+ 双箱合并）。
 *
 * <p>⚠️ <b>开盖动画不能用 {@code startOpen/stopOpen}</b> —— 那会改服务端的 openerCount，
 * 而它和真玩家<b>共享</b> ⇒ 会和玩家的界面状态打架（实测：双方各开关一次，视觉状态就会反转一次）。
 * 正解：<b>只发 blockEvent</b>，不碰计数：
 * <pre>level.blockEvent(pos, state.getBlock(), 1, opening ? 1 : 0);</pre>
 * （幂等 —— 重复发同一个值无害；完全隔离 —— 不影响玩家的界面操作）
 */
public final class ContainerOps {

    private ContainerOps() {
    }

    /** 发开盖/关盖的"表现层"事件（不碰 openerCount，见类注释） */
    public static void animate(EntityMaid maid, BlockPos pos, boolean opening) {
        try {
            BlockState state = maid.level().getBlockState(pos);
            // ★ 改回旧实现的写法（0.9.2 定案）：开合的【视觉】只由 blockEvent 驱动 —— 只发事件、不碰方块状态。
 // ⚠️ 原来这里多写了一句 setBlock(OPEN)：对「门/活板门/栅栏门」会真的改方块状态 +
 // 触发方块更新 ⇒ 会和玩家的操作打架（跟"开盖别碰 openerCount"是同一类错误）。
 // 箱子/木桶的开合本来就是 BlockEntity.openness（由 blockEvent 驱动），改 state 对它们无效。
            // ★ 旧实现只对这三种 BE 发（别的方块对 event id=1 没反应，发了也没用）
            net.minecraft.world.level.block.entity.BlockEntity be =
                    maid.level().getBlockEntity(pos);
            boolean lid = be instanceof net.minecraft.world.level.block.entity.ChestBlockEntity
                    || be instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity
                    || be instanceof net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
            if (lid) {
                maid.level().blockEvent(pos, state.getBlock(), 1, opening ? 1 : 0);
            }
        } catch (Throwable t) {
            // 不是能开合的方块 —— 无所谓
        }
    }

    /**
     * ★ 关会话（退料 + 关盖）—— <b>唯一的收口</b>，{@code CloseAction} 和 {@code MaidHandle.reset} 都调它。
     *
     * <p>"东西绝不丢"靠这一步：鼠标槽 + 工位虚拟槽里剩的东西全部退回背包
     * （塞不下掉地上，绝不销毁）。
     */
    public static void close(Session s) {
        if (s == null) {
            return;
        }
        EntityMaid maid = currentMaidOf(s);
        // ① 鼠标槽退回背包
        if (maid != null && s.cursor != null && !s.cursor.isEmpty()) {
            giveBack(maid, s.cursor);
            s.cursor = ItemStack.EMPTY;
        }
        // ② 工位虚拟槽的余料退回背包
 // （随身 2×2 的结果格也算实物：select 结算时材料已扣，产物该退；
 // 摆料没 select 就 close ⇒ 结果格本来就是空的，材料原样退回）
        if (maid != null) {
            for (Map.Entry<Integer, ItemStack> e : s.virtual.entrySet()) {
                ItemStack st = e.getValue();
                if (st != null && !st.isEmpty()) {
                    giveBack(maid, st);
                }
            }
        }
        s.virtual.clear();
 // ②b ★ 退料后清掉 NBT 快照：不清的话下次 open 会把已退回背包的料又载进来 = 凭空复制
        if (maid != null) {
            try {
                com.xiaofeng.maidcli.ops.StationOps.clearVirtualNbt(s);
                com.xiaofeng.maidcli.ops.StationOps.saveCursor(maid, ItemStack.EMPTY);
            } catch (Throwable ignored) {
            }
        }
        // ③ 关盖（表现层）
        if (s.pos != null && maid != null) {
            animate(maid, s.pos, false);
        }
 // ⑤ ★ 清掉残留的走位意图（用户拍板 A 方案）
 // MoveToTargetSink 是接管时【保留】的 CORE 零件（我们的 go/tp 靠它消费 WALK_TARGET）。
 // 如果 brain 里残留一条 WALK_TARGET（典型：open 的"走过去"被界面操作打断、没走完），
 // 她会一直朝那个点走 —— 实测：工作台在 -130，她却在摆料途中从 -134 走到 -138，
 // 直接触发"走远"自动关会话。收工时清一次，不管根因是什么都不会再"接着走"。
        if (maid != null) {
            clearWalkIntent(maid);
        }
        // ④ 末影箱：主人离线时读出的实例 → 写回主人存档（否则改动白做）
        if (s.enderChestNeedsSave && s.enderChestOwner != null && s.enderChestOfflineInv != null) {
            MinecraftServer server = maid != null ? maid.level().getServer() : null;
            if (server != null) {
                writeOfflineEnderChest(server, s.enderChestOwner, s.enderChestOfflineInv);
            } else {
                MaidCliMod.LOG.warn("[MaidCLI] 拿不到服务端实例，末影箱没能写回主人存档（改动可能丢失）: {}", s.enderChestOwner);
            }
        }
        s.enderChestNeedsSave = false;
        s.enderChestOwner = null;
        s.enderChestOfflineInv = null;
        s.menu = null;
        s.container = null;
    }

    /**
     * 清掉残留的走位意图（{@code WALK_TARGET} / {@code PATH}）。
     *
     * <p>指令模式的脑里只保留 TLM 的 CORE 行为（含 {@code MoveToTargetSink} —— 我们的
     * go/tp 靠它消费 WALK_TARGET）。代价是：如果 brain 里
     * 残留一条走位目标（典型场景：{@code open} 的"走过去"被界面操作打断、没走完），
     * 她就会一直朝那个点走 —— 实测在工作台旁边摆料时从 -134 走到 -138，触发"走远"关会话。
     *
     * <p>所以在<b>收工</b>时清一次：不管根因是什么，界面关掉后她不会再"接着走"。
     */
    public static void clearWalkIntent(EntityMaid maid) {
        if (maid == null) {
            return;
        }
        try {
            var brain = maid.getBrain();
            brain.eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
            brain.eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.PATH);
        } catch (Throwable ignored) {
        }
    }

    /** 会话没记 maid —— 用会话所属的 handle 找。批 0 简化：调用方通常能给出实体。 */
    private static EntityMaid currentMaidOf(Session s) {
        if (s.maidRef != null) {
            return s.maidRef.maid();
        }
        return null;
    }

    /** 把一份物品还给她的背包（塞不下就掉地上 —— 绝不销毁） */
    public static void giveBack(EntityMaid maid, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        ItemStack left = ItemHandlerHelper.insertItemStacked(maid.getAvailableBackpackInv(), stack.copy(), false);
        if (!left.isEmpty()) {
            maid.spawnAtLocation(left);
        }
    }

    /**
     * 读一个方块的容器视图（统一成 {@code IItemHandler}）。
     *
     * <p>⚠️ <b>优先级：原版 Container 优先，capability 兜底</b> ——
     * capability 对有些方块是<b>按面裁剪</b>的（`Direction.UP` 拿熔炉只给输入格 1 格，应该 3 格）。
     * <p>⚠️ <b>双箱必须走 {@code ChestBlock.getContainer(..., true)}</b> 拿合并后的 54 格，
     * 别自己拼两半。
     */
    public static net.neoforged.neoforge.items.IItemHandler containerAt(EntityMaid maid, BlockPos pos) {
        var level = maid.level();
        BlockState state = level.getBlockState(pos);
        // ⓪ 末影箱：内容属于【主人】，物品【不在方块里】⇒ 三条既有路都不满足，必须单独认方块类型。
 // 判据是【绑定过的 UUID】(getOwnerUUID)，不要求主人在线（主人在线用内存对象，离线读 playerdata）。
 // ⚠️ 这条只负责"取"；open 走的是带写回的那条路（离线读完 close 时要写回存档）。
        if (state.getBlock() instanceof EnderChestBlock) {
            return enderBoxOf(maid).handler();
        }
        // ① 双箱：只有这条路能拿到合并后的 54 格
        if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock chest) {
            Container c = net.minecraft.world.level.block.ChestBlock.getContainer(chest, state, level, pos, true);
            if (c != null) {
                return new net.neoforged.neoforge.items.wrapper.InvWrapper(c);
            }
        }
        // ② 原版容器方块实体本身就是 Container（熔炉 3 格 / 漏斗 5 格 / 酿造台 5 格…）
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof Container container) {
            return new net.neoforged.neoforge.items.wrapper.InvWrapper(container);
        }
        // ③ 兜底：capability（留给模组容器）
        return net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK
                .getCapability(level, pos, state, be, net.minecraft.core.Direction.UP);
    }

    // ────────────────────────── 末影箱（内容属于主人） ──────────────────────────

    /**
     * 末影箱的解析结果。
     *
     * @param handler         可用的物品栏
     * @param ownerToWriteBack 非 null = 这是【主人离线】时读出来的，关闭时要写回该玩家的存档
     * @param offlineInv       那个离线末影箱实例（写回时用的就是它）
     */
    private record EnderBox(InvWrapper handler, java.util.UUID ownerToWriteBack,
                            PlayerEnderChestContainer offlineInv) {
    }

    /**
     * 拿主人的末影箱。
     *
     * <p>判据是【绑定过的 UUID】（{@code getOwnerUUID()}）—— <b>不要求主人在线</b>（设计原因：内容属于主人，只要知道主人是谁就能打开）：
     * <ul>
     *   <li><b>主人在线</b> → 直接用他内存里的末影箱（{@code ServerPlayer.getEnderChestInventory()}），
     *       读写天然同步，<b>不需要写回</b></li>
     *   <li><b>主人离线</b> → 读 {@code <world>/playerdata/<uuid>.dat} 里的 {@code EnderItems}，
     *       包成 {@code PlayerEnderChestContainer} 用；⚠️ 改完必须写回，否则改动白做</li>
     * </ul>
     * <p>⚠️ 类名是 {@code net.minecraft.world.inventory.PlayerEnderChestContainer}（不是 EnderChestInventory）。
     */
    private static EnderBox enderBoxOf(EntityMaid maid) {
        java.util.UUID ownerUuid = maid.getOwnerUUID();
        if (ownerUuid == null) {
            throw new com.xiaofeng.maidcli.transport.CmdException(
                    com.xiaofeng.maidcli.transport.CmdException.BAD_STATE,
                    "她没有绑定主人，打不开末影箱",
                    "末影箱用的是【主人】的箱子 —— 她得先绑定主人");
        }
        MinecraftServer server = maid.level().getServer();
        if (server == null) {
            throw new com.xiaofeng.maidcli.transport.CmdException(
                    com.xiaofeng.maidcli.transport.CmdException.BAD_STATE,
                    "拿不到服务端实例，打不开末影箱", "");
        }
        // ① 主人在线 → 内存对象（读写天然同步，不需要写回）
        ServerPlayer online = server.getPlayerList().getPlayer(ownerUuid);
        if (online != null) {
            return new EnderBox(new InvWrapper(online.getEnderChestInventory()), null, null);
        }
        // ② 主人离线 → 读存档
        PlayerEnderChestContainer offline = readOfflineEnderChest(server, ownerUuid);
        if (offline == null) {
            throw new com.xiaofeng.maidcli.transport.CmdException(
                    com.xiaofeng.maidcli.transport.CmdException.NOT_FOUND,
                    "打不开：她主人（" + ownerUuid + "）的存档里没有末影箱数据（这个档他从没用过末影箱？）",
                    "等主人上线开一次末影箱，存档里就有 EnderItems 了");
        }
        return new EnderBox(new InvWrapper(offline), ownerUuid, offline);
    }

    /** 从玩家存档读末影箱（读不到返回 null）。 */
    private static PlayerEnderChestContainer readOfflineEnderChest(MinecraftServer server, java.util.UUID uuid) {
        try {
            Path file = playerDataFile(server, uuid);
            if (!Files.exists(file)) {
                return null;
            }
            CompoundTag nbt = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            PlayerEnderChestContainer inv = new PlayerEnderChestContainer();
            inv.fromTag(nbt.getList("EnderItems", Tag.TAG_COMPOUND), server.registryAccess());
            return inv;
        } catch (Exception e) {
            MaidCliMod.LOG.warn("[MaidCLI] 读离线末影箱失败: {}", e.toString());
            return null;
        }
    }

    /** 把离线末影箱写回玩家存档。⚠️ 先写临时文件再原子替换，避免写一半崩了坏档。 */
    private static void writeOfflineEnderChest(MinecraftServer server, java.util.UUID uuid, PlayerEnderChestContainer inv) {
        try {
            Path file = playerDataFile(server, uuid);
            if (!Files.exists(file)) {
                return;   // 没有存档就别新建，免得造出坏档
            }
            CompoundTag nbt = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            nbt.put("EnderItems", inv.createTag(server.registryAccess()));
            Path tmp = file.resolveSibling(file.getFileName() + ".maidcli.tmp");
            NbtIo.writeCompressed(nbt, tmp);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            MaidCliMod.LOG.info("[MaidCLI] 已把末影箱写回主人存档: {}", uuid);
        } catch (Exception e) {
            MaidCliMod.LOG.warn("[MaidCLI] 写离线末影箱失败: {}", e.toString());
        }
    }

    private static Path playerDataFile(MinecraftServer server, java.util.UUID uuid) {
        return server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(uuid + ".dat");
    }

    /** 把右半箱归到左半（canonical）—— 免得按两个坐标各查一遍重复报同一份内容 */
    public static BlockPos canonicalChestPos(EntityMaid maid, BlockPos pos) {
        try {
            BlockState state = maid.level().getBlockState(pos);
            if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                    && state.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE)) {
                var type = state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE);
                if (type == net.minecraft.world.level.block.state.properties.ChestType.RIGHT) {
                    var facing = state.getValue(net.minecraft.world.level.block.ChestBlock.FACING);
                    return pos.relative(facing.getCounterClockWise());
                }
            }
        } catch (Throwable t) {
            MaidCliMod.LOG.debug("[MaidCLI] canonicalChestPos 失败: {}", t.toString());
        }
        return pos;
    }

    // ────────────────────────── 开界面（open 指令） ──────────────────────────

    /**
     * 按方块认界面种类（用方块 id 匹配 —— 比 import 一堆 Block 类更稳）。
     * <p>顺序有讲究：先匹配更具体的（{@code smoker} 要在 {@code furnace} 前面判）。
     */
    public static com.xiaofeng.maidcli.engine.FaceKind kindOf(BlockState st) {
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(st.getBlock()).toString();
        if (id.contains("ender_chest")) {
            return com.xiaofeng.maidcli.engine.FaceKind.ENDER_CHEST;
        }
        if (id.contains("shulker_box")) {
            return com.xiaofeng.maidcli.engine.FaceKind.SHULKER;
        }
        if (id.contains("chest") || id.contains("barrel")) {
            return com.xiaofeng.maidcli.engine.FaceKind.CHEST;
        }
        if (id.contains("hopper")) {
            return com.xiaofeng.maidcli.engine.FaceKind.HOPPER;
        }
        if (id.contains("dispenser") || id.contains("dropper")) {
            return com.xiaofeng.maidcli.engine.FaceKind.DISPENSER;
        }
        if (id.contains("crafter")) {
            return com.xiaofeng.maidcli.engine.FaceKind.CRAFTER;
        }
        if (id.contains("blast_furnace")) {
            return com.xiaofeng.maidcli.engine.FaceKind.BLAST;
        }
        if (id.contains("smoker")) {
            return com.xiaofeng.maidcli.engine.FaceKind.SMOKER;
        }
        if (id.contains("furnace")) {
            return com.xiaofeng.maidcli.engine.FaceKind.FURNACE;
        }
        if (id.contains("brewing_stand")) {
            return com.xiaofeng.maidcli.engine.FaceKind.BREWING;
        }
        if (id.contains("crafting_table")) {
            return com.xiaofeng.maidcli.engine.FaceKind.CRAFTING;
        }
        if (id.contains("grindstone")) {
            return com.xiaofeng.maidcli.engine.FaceKind.GRINDSTONE;
        }
        if (id.contains("smithing_table")) {
            return com.xiaofeng.maidcli.engine.FaceKind.SMITHING;
        }
        if (id.contains("cartography_table")) {
            return com.xiaofeng.maidcli.engine.FaceKind.CARTOGRAPHY;
        }
        if (id.contains("loom")) {
            return com.xiaofeng.maidcli.engine.FaceKind.LOOM;
        }
        if (id.contains("stonecutter")) {
            return com.xiaofeng.maidcli.engine.FaceKind.STONECUTTER;
        }
        if (id.contains("enchanting_table")) {
            return com.xiaofeng.maidcli.engine.FaceKind.ENCHANT;
        }
        if (id.contains("beacon")) {
            return com.xiaofeng.maidcli.engine.FaceKind.BEACON;
        }
        if (id.contains("lectern")) {
            return com.xiaofeng.maidcli.engine.FaceKind.LECTERN;
        }
        if (id.contains("anvil")) {
            return com.xiaofeng.maidcli.engine.FaceKind.ANVIL;
        }
        return com.xiaofeng.maidcli.engine.FaceKind.UNKNOWN;
    }

    /** 这个种类有没有"真实槽位"（有 ⇒ 走容器路线；没有 ⇒ 工位，虚拟槽） */
    public static boolean isRealContainer(com.xiaofeng.maidcli.engine.FaceKind k) {
        return switch (k) {
            case CHEST, SHULKER, HOPPER, DISPENSER, CRAFTER, ENDER_CHEST,
                 FURNACE, BLAST, SMOKER, BREWING -> true;
            default -> false;
        };
    }

    /**
     * 打开一个方块界面（容器）。
     * <p>⚠️ 容器必须有<b>真实槽位</b>才走这里；工位（铁砧/切石机/附魔台…）没有真实槽位（批 3 处理）。
     */
    public static Session openContainer(EntityMaid maid, BlockPos pos) {
        BlockPos cpos = canonicalChestPos(maid, pos);
        // ★ 先认种类：工位（铁砧/切石机/附魔台…）没有真实槽位 ⇒ 走虚拟槽 + 建 Menu 那条路
        com.xiaofeng.maidcli.engine.FaceKind kind = kindOf(maid.level().getBlockState(cpos));
        // ★ 信标：本期不支持（选效果走 ServerboundSetBeaconPacket + 要金字塔底座 + 支付矿物）
        if (kind == com.xiaofeng.maidcli.engine.FaceKind.BEACON) {
            throw new com.xiaofeng.maidcli.transport.CmdException(
                    com.xiaofeng.maidcli.transport.CmdException.NOT_FOUND,
                    "信标界面本期不支持（已记为待做）",
                    "信标要金字塔底座 + 支付矿物 + 走网络包选效果，还没实现");
        }
        if (!isRealContainer(kind)) {
            if (StationOps.isStation(kind)) {
                return StationOps.openStation(maid, cpos, kind);
            }
            throw new com.xiaofeng.maidcli.transport.CmdException(
                    com.xiaofeng.maidcli.transport.CmdException.NOT_FOUND,
                    "这个方块不是可打开的界面（" + net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(maid.level().getBlockState(cpos).getBlock()) + "）",
                    "能开的是容器（箱子/熔炉/漏斗…）和工位（工作台/铁砧/切石机/附魔台/砂轮/锻造台/制图台/织布机/信标/讲台）");
        }
        net.neoforged.neoforge.items.IItemHandler h;
        java.util.UUID enderOwner = null;
        PlayerEnderChestContainer enderInv = null;
        // ★ 末影箱单独解析：内容属于【主人】，可能涉及"主人离线"读存档、且关闭时要写回
        if (maid.level().getBlockState(cpos).getBlock() instanceof EnderChestBlock) {
            EnderBox eb = enderBoxOf(maid);
            h = eb.handler();
            enderOwner = eb.ownerToWriteBack();
            enderInv = eb.offlineInv();
        } else {
            h = containerAt(maid, cpos);
        }
        if (h == null) {
            throw new com.xiaofeng.maidcli.transport.CmdException(
                    com.xiaofeng.maidcli.transport.CmdException.NOT_FOUND,
                    "这个方块不是容器（" + net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(maid.level().getBlockState(cpos).getBlock()) + "）",
                    "容器类：箱子 / 木桶 / 熔炉 / 漏斗 / 潜影盒…；工位类（工作台/铁砧/切石机）在批 3 支持");
        }
        animate(maid, cpos, true);
        Session s = new Session();
        s.kind = kindOf(maid.level().getBlockState(cpos));
        s.pos = cpos;
        s.container = h;
        s.enderChestOwner = enderOwner;
        s.enderChestOfflineInv = enderInv;
        s.enderChestNeedsSave = (enderOwner != null && enderInv != null);
        s.blockEntity = maid.level().getBlockEntity(cpos);
        return s;
    }

    /**
     * 打开她自己的背包（= 对空气 open）。
     *
     * <p>★ 统一模型：一个界面 = <b>自身格子 + box 格子</b>。
     * 打开背包时：
     * <ul>
     *   <li>自身侧 = {@code inv:N}（背包 + 手 + 展示栏）· {@code armor:} · {@code bauble:}</li>
     *   <li>box 侧 = <b>随身 2×2 合成格</b>（{@code box:0..3} 材料，{@code box:4} 结果）</li>
     * </ul>
     *
     * <p>以前 box 侧指向"背包本身"，导致 {@code take from=box:0} 和 {@code inv:2} 是同一格；
     * 而且 {@code move from=inv:0} 的"对面"会指回主手自己（实测遇到过：连续 6 次搬主手，
     * 每样都被下一轮覆盖，只剩最后一样）。改成合成格后两个问题都不存在。
     */
    public static Session openSelfInventory(EntityMaid maid) {
        Session s = new Session();
        s.kind = com.xiaofeng.maidcli.engine.FaceKind.INV;
        s.virtual.clear();
        s.container = Craft2x2Ops.view(maid, s);
        s.pos = null;
        return s;
    }

    /**
     * 打开实体界面（村民交易 / 马·驴物品栏）。
     * <p>⚠️ 旧实现没有这条 —— 村民当年是"借坐标当工位"实现的。
     * 新路线要"凭空建 Menu"（{@code MerchantMenu} / {@code HorseInventoryMenu}），批 3 落地。
     */
    public static Session openEntityMenu(EntityMaid maid, int entityId) {
 // ★ 村民交易：走【虚拟界面】形式（）——
 // 建原版 MerchantMenu + 虚拟槽（支付格/结果格）+ 复用 take/put/move/select。
 // 村民是实体 ⇒ 靠实体 id 现找（不存悬空引用），不再「借坐标当工位 pos」（那是旧实现的土办法，已废）。
        net.minecraft.world.entity.Entity e = maid.level().getEntity(entityId);
        if (e instanceof net.minecraft.world.entity.npc.AbstractVillager) {
            return MerchantOps.openMerchant(maid, entityId);
        }
        throw new com.xiaofeng.maidcli.transport.CmdException(
                com.xiaofeng.maidcli.transport.CmdException.NOT_FOUND,
                e == null
                        ? ("找不到实体 #" + entityId + "（区块没加载 / 已经没了？）")
                        : ("open entity= 目前只认村民（实体 #" + entityId + " 是 "
                            + e.getClass().getSimpleName() + "）"),
                "村民交易：open entity=<村民实体id>");
    }
}
