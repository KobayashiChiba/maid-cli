package com.xiaofeng.maidcli.engine;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 动作 —— 作业内部的「一步」（core-concepts §5 · execution-model §1 ④）。
 *
 * <p><b>Action 只会"做"，Job 会"怎么办"。</b>
 * Action = 一个动作（抬手、迈步）；Job = 一个差事（门锁了会绕路，找不到人回来报告）。
 *
 * <p>两条约定（class-design §3.3）：
 * <ol>
 *   <li>{@link #tick} 只做"这一步" —— 能做就做完，做不完就返回 {@link Phase#RUNNING}</li>
 *   <li>跨 tick 的状态放自己的字段，<b>不要放静态变量</b></li>
 *   <li>{@link #abort} 要负责"把借的东西还回去"（手上拿着的物品 / 工位余料）</li>
 * </ol>
 *
 * <p>⚠️ <b>为什么不 sealed</b>：设计稿写的是 {@code sealed abstract class Action}，靠编译期检查
 * "有没有漏实现"。但我们的编译走 classpath（unnamed module）——
 * <p><b>Java 规定 unnamed module 里 sealed 的子类必须和父类同一个包</b>，
 * 而 Action 子类按设计要住在 {@code engine.actions} 子包 ⇒ 编译不过。
 * ⇒ 用普通抽象类（代价只是少了编译期穷举检查，换来文件结构不被绑死）。
 */
public abstract class Action {

    public enum Phase {
        RUNNING,
        DONE,
        FAILED
    }

    public Phase phase = Phase.RUNNING;
    /** 失败原因（进报告） */
    public String failReason;
    /** 是否已经调过 {@link #start}（调度器保证只调一次） */
    public boolean started;
    /**
     * 是不是已经为它插过一次走位了（旧实现 {@code MaidQueue.Event.walkTried}）。
     *
     * <p>⚠️ 防死循环：插走位 → 走完还是够不到 → 又插 → … 必须"一个动作最多走一次"，
     * 第二次仍够不到就直接判失败（旧版行为）。
     */
    public boolean walkTried;

    /** 开始（可选覆写）—— 第一次 tick 之前调一次 */
    public void start(ServerLevel lv, EntityMaid maid) {
    }

    /** ★ 每 tick 走一步 */
    public abstract Phase tick(ServerLevel lv, EntityMaid maid);

    /** 0..1 —— {@code sense state} 的 {@code action.progress} 读它 */
    public float progress() {
        return 0f;
    }

    /** 这一步在干什么（sense state 的 action.op） */
    public String op() {
        return "?";
    }

    /**
     * 需不需要"够得着"（调度器据此决定要不要插走位）。
     * <p>默认 false —— 只有真正要去摸某个方块的（break / place / use / open）才声明 true。
     */
    public boolean needsReach() {
        return false;
    }

    /** 要够着的那个方块（{@code needsReach()} 为 true 时必须给出） */
    public BlockPos reachTarget() {
        return null;
    }

    /** 目标点（报告里用；可为 null） */
    public BlockPos targetPos() {
        return null;
    }

    /** 收尾：把借的东西还回去（物品 / 工位余料 / 解除接管标记） */
    public void abort() {
    }

    /** 失败并说明原因 */
    protected Phase fail(String reason) {
        this.phase = Phase.FAILED;
        this.failReason = reason;
        return Phase.FAILED;
    }
}
