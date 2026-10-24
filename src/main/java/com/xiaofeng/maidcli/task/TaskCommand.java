package com.xiaofeng.maidcli.task;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 「指令模式」—— 和空闲 / 攻击并列的一个独立工作模式，专门给外部（调用方）驱动用。
 *
 * <p><b>设计目标</b>：<b>能接收具体指令，但自己不主动做任何事</b>。
 * 主要用在完全受控的前提下自动执行复杂任务（自主挖矿 / 砍树 / 盖房子 …）。
 *
 * <p><b>为什么需要这个模式</b>：原来的做法是「站起来 + 留守 + 切空闲」，但
 * TLM 的空闲/工作任务都会挂 {@code MaidBrain.getLookAndRandomWalk()}（内含原版
 * {@code RandomStroll} 随机游走），而"留守"只管跟不跟主人，根本锁不住游走
 * → 她照样到处走（实测抓到的 bug：32 秒漂了 3 格）。
 *
 * <p><b>⚠️ 这个开关只在 WORK activity 生效</b>：{@code enableLookAndRandomWalk} 仅在
 * {@code MaidBrain.registerWorkGoals()} 里被读，IDLE / REST activity 的 predicate
 * 压根不看任务（源码：{@code MaidBrain.java:120} / {@code :155} 都是无条件 true）。
 * 所以切到本模式时<b>必须同时把日程设为 ALL</b>，保证她始终处于 WORK activity ——
 * 否则非工作时间她又会游走。
 *
 * <p><b>★ 重新启用</b>：v2 重写时改成"反射清 brain 行为盒子"，结果
 * {@code control} 里紧跟的 {@code setSchedule(ALL)} 会把盒子全量重装（ * {@code EntityMaid.setSchedule} 无条件 {@code refreshBrain}）⇒ 清了个寂寞，
 * 实测她照样随机游走。旧做法（本类 + 切任务）是 TLM 官方扩展点、无反射、
 * 无顺序依赖、{@code refreshBrain} 反而帮他重建出正确的一套 ⇒ 回到旧做法。
 */
public class TaskCommand implements IMaidTask {

    public static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath("maid_cli", "command");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    /** 界面左下角「当前任务」显示的图标 */
    @Override
    public ItemStack getIcon() {
        return Items.COMMAND_BLOCK.getDefaultInstance();
    }

    @Nullable
    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) {
        return null;   // 指令模式不发环境音（她在等指令）
    }

    /** ★ 关键一：不注册任何主动行为 —— 她自己什么都不会去做 */
    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        return List.of();
    }

    /** ★ 关键二：关掉「四处张望 + 随机走动」（那个随机走动就是 bug 的元凶） */
    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) {
        return false;
    }

    /** 挨打也不乱跑 —— 她只负责上报，由调用方决定要不要切攻击模式 */
    @Override
    public boolean enablePanic(EntityMaid maid) {
        return false;
    }

    /** 不主动去偷吃 */
    @Override
    public boolean enableEating(EntityMaid maid) {
        return false;
    }

    /** 不出现在玩家的任务切换列表里（但左下角"当前任务"仍会显示它） */
    @Override
    public boolean isHidden(EntityMaid maid) {
        return true;
    }

    @Override
    public String getMaidActionSummary() {
        return "指令模式：等待外部指令，自己不主动做任何事";
    }
}
