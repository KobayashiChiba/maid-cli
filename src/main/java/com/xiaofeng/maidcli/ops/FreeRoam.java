package com.xiaofeng.maidcli.ops;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.task.TaskCommand;
import net.minecraft.core.BlockPos;

/**
 * 「指令模式 = 自由行动」—— 解除位置限制（旧版 {@code FreeRoam.java} 原样搬回）。
 *
 * <p><b>背景（读 TLM 源码确认，两套机制是一对跷跷板）</b>：
 * <pre>
 * homeMode = true （留守） → hasRestriction() == isHomeModeEnable() == true
 *                            → SchedulePos.tick() 每 40 tick 按 activity 重设范围（工作时段 12 格）
 *                            → 她超出「半径 + 4」格就 teleport() 直接传送回锚点
 * homeMode = false（跟随） → 没有范围限制，但 MaidFollowOwnerTask 会把她拽向主人
 *                            （离主人 &gt; 半径-2 走回去，&gt; 半径+4 直接传送到主人身边）
 * </pre>
 *
 * <p>所以「让她能去任何地方」既不能纯留守（会被范围限制 + 拉回），也不能纯跟随（会被拽回主人）。
 * <p><b>不打补丁、不引 mixin 的唯一正解：让留守的锚点跟着她走</b>：
 * <pre>
 *   锚点 ≡ 她当前位置  →  她离锚点永远 0 格  →  isWithinRestriction() 恒真
 *   →  SchedulePos.tick() 里 restrictTo 后的判定直接 return
 *   →  范围重设不生效、超范围传送不触发、强制走回不发生
 *   ⇒  等价于"把这套限制关掉了"，而 TLM 一行代码都没改
 * </pre>
 * <p><b>半径刻意保持 12 不变</b>：{@code restrictRadius} 同时是<b>实体搜索范围</b>
 * （{@code MaidPickupEntitiesSensor} / {@code IMaidTask.searchDimension} 都拿它 inflate AABB）——
 * 放大半径 = 每 30 tick 扫巨大体积 = 卡服。锚点跟着走就够了，不需要放大半径。
 *
 * <p><b>开关条件：她当前任务 == {@code maid_cli:command}
 * （指令模式）且【处于留守】时才自由。</b>
 * <p><b>★ 跟随状态不干预</b> —— 旧-旧实现无条件
 * {@code setHomeModeEnable(true)}（把她从跟随强行改成留守）是<b>擅自改用户设定</b>，
 * 而且"自由行动"这个需求本来就只在留守时有意义。跟随时就该让她正常跟着主人走。
 * <p>⚠️ 连带事实：跟随状态下<b>不能远程派她干活</b> —— 被 tp/go 到远处会被
 * {@code MaidFollowOwnerTask} 拽回主人身边。要远程使唤先 {@code set follow=false}（切留守）。
 *
 * <p>⚠️ 顺带解决一个坑：{@code SchedulePos.setHomeModeEnable(maid, pos)} 里包着
 * {@code if (!configured)}，玩家用指南针配过工作点之后它就不改 pos 了 ——
 * 所以这里<b>直接调 {@code setWorkPos/setIdlePos/setSleepPos}</b>，绕开那道门槛。
 *
 * <p>驱动：由 {@code engine/ServerTicker} 每 8 tick 调一次（保持"全局唯一 tick 入口"）。
 */
public final class FreeRoam {

    /** 每 8 tick（0.4 秒）检查一次就够 —— 判定窗口是 40 tick，她走不出 16 格 */
    public static final int CHECK_INTERVAL = 8;

    private FreeRoam() {
    }

    /** 她是不是处于「指令模式」（我们自己的任务 {@code maid_cli:command}）。 */
    public static boolean isFreeMode(EntityMaid maid) {
        var task = maid.getTask();
        return task != null && TaskCommand.UID.equals(task.getUid());
    }

    /** 每 8 tick 调：指令模式 + 留守 ⇒ 把三个锚点挪到她脚下 */
    public static void tick(EntityMaid maid) {
        if (maid == null || !isFreeMode(maid)) {
            return;
        }
        if (!maid.isHomeModeEnable()) {
            return;          // 跟随 = 用户要的行为（她跟着主人走），我们无权干预
        }
        snapAnchor(maid);
    }

    /**
     * 把三个锚点（工作/空闲/睡觉）立刻设成她当前所在的位置。
     *
     * <p>⚠️ 传送之后<b>必须立刻调一次</b> —— 不然她的锚点还留在传送前的位置，
     * 下一次 {@code SchedulePos.tick()} 就会发现"她离锚点 &gt; 半径+4"然后把她<b>原样传送回去</b>，
     * 等于白传（否则会被 {@code SchedulePos.tick} 判定"离锚点过远"而传送回去）。
     */
    public static void snapAnchor(EntityMaid maid) {
        BlockPos pos = maid.blockPosition();
        // ⚠️ 不走 setHomeModeEnable(maid, pos)：它有 if(!configured) 的门槛，玩家配过锚点后就改不动了
        maid.getSchedulePos().setWorkPos(pos);
        maid.getSchedulePos().setIdlePos(pos);
        maid.getSchedulePos().setSleepPos(pos);
    }
}
