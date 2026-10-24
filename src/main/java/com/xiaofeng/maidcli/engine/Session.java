package com.xiaofeng.maidcli.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话 —— 界面开着这个状态（core-concepts §6 · class-design §3.6）。
 *
 * <p>四个核心概念里唯一<b>跨作业存活</b>的：{@code open} 一个作业、{@code take} 另一个作业、
 * {@code close} 又一个 —— 但它们共享同一个会话。
 *
 * <p>生命周期是嵌套的 ⇒ 会话必须挂在"<b>比她短、比作业长</b>"的那一层：
 * <pre>
 * 女仆实体 > MaidHandle > 【Session】 > Job > Action
 * </pre>
 * 挂在 MaidHandle 里（一个字段，{@code null} = 界面没开）。
 *
 * <p>⚠️ <b>Session 只是数据 + 原版句柄，不带操作</b> ——
 * "关会话"的实现只有一处：{@code ContainerOps.close(session)}（退料 + 关盖）。
 */
public final class Session {

    /** ★ 归属的句柄（关会话时要靠它找回实体做退料；-- 批 0 加，设计里隐含在"挂在 MaidHandle 里"） */
    public MaidHandle maidRef;
    /** 我们的界面分类 */
    public FaceKind kind = FaceKind.UNKNOWN;
    /** 开的方块（开自己背包时 = null） */
    public BlockPos pos;
    /** 开的实体（村民 / 马）时非 0 */
    public int entityId;
    /** ★ 原版菜单对象（有选项 / 按钮的界面要走它） */
    public AbstractContainerMenu menu;
    /** 槽位视图（容器 / 工位统一；双箱 = 合并后的 54 格） */
    public IItemHandler container;
    /** 鼠标槽 —— 拿起的东西（只在界面里存在） */
    public ItemStack cursor = ItemStack.EMPTY;
    /** 用于 60 秒超时 */
    public long openedAtTick;
    /** input 指令暂存的文本（铁砧改名是"暂存 → confirm 才生效"） */
    public String pendingInput;
    /** 容器方块实体（关盖 / 读进度用；可为 null） */
    public Object blockEntity;

    // ────────────────────────── 末影箱（内容属于【主人】，可能来自离线存档） ──────────────────────────
    /** 需要写回末影箱存档（= 主人离线、从 playerdata 读出的实例；在线时为 false）。 */
    public boolean enderChestNeedsSave;
    /** 末影箱所属玩家 uuid（主人离线、需要写回时非 null）。 */
    public java.util.UUID enderChestOwner;
    /** 主人离线时读出来的末影箱实例（关会话时要写回它的内容；在线时为 null）。 */
    public PlayerEnderChestContainer enderChestOfflineInv;

    /**
     * 工位虚拟槽（铁砧 / 切石机 / 附魔台 / 织布机… 这些方块<b>没有真实槽位</b>）。
     * <p>按格号存 ItemStack —— 关会话时余料自动退回背包（"东西绝不丢"）。
     */
    public final Map<Integer, ItemStack> virtual = new LinkedHashMap<>();

    /** 该界面的能力声明 */
    public FaceAbility ability() {
        return FaceAbility.of(kind);
    }

    /** 是不是"开着自己的背包" */
    public boolean isInv() {
        return kind == FaceKind.INV;
    }

    @Override
    public String toString() {
        return "Session[" + kind.wire + (pos == null ? "" : "@" + pos.toShortString())
                + (entityId != 0 ? "#" + entityId : "") + "]";
    }
}
