package com.xiaofeng.maidcli.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 作业 —— 队列里的单位（core-concepts §4 · execution-model §3 ①）。
 *
 * <p><b>纯数据，没有任何方法</b>（不给它执行能力 —— 执行全在 {@link MaidHandle#tick}）。
 * 一条指令 = 一个作业。
 *
 * <p>双队列代替"列表 + index"（execution-model §3 ②）：
 * <ul>
 *   <li>{@link #todo} 未完成（队头 = 下一步）—— 插走位就是 {@code addFirst}，不用管下标</li>
 *   <li>{@link #done} 已完成（每个动作自带成功/失败）—— 顺便就是记账</li>
 * </ul>
 *
 * <p>⚠️ {@link #pendingWalk} 是"防重复插走位"的闸（execution-model §3 ③）：
 * 不给这个标记的话，每 tick 都会往队头插一个走位 ⇒ 列表炸掉。
 */
public final class Job {

    /** 作业 id（受理时分配 = 响应里的 id） */
    public final long id;
    /** 指令名（go / place / craft…） */
    public final String kind;
    /** 宏的话，宏名（否则 null） */
    public final String macroName;
    /** 待办（未完成） */
    public final Deque<Action> todo = new ArrayDeque<>();
    /** 已完成 */
    public final List<Action> done = new ArrayList<>();
    /** ★ 防重复插走位 */
    public boolean pendingWalk;
    /** 结束报告（受理时建好，收口时填） */
    public final JobReport report;
    /** 受理时刻（tick） */
    public final long acceptedAtTick;
    /** 超时（tick 数；0 = 不限）—— 宏用它（COMMANDS.md §6.4 "宏超时"） */
    public long timeoutTicks;
    /** 被中断的标记（reset 时置上，收口走 aborted） */
    public boolean aborted;

    public Job(long id, String kind, String macroName, long acceptedAtTick) {
        this.id = id;
        this.kind = kind;
        this.macroName = macroName;
        this.acceptedAtTick = acceptedAtTick;
        this.report = new JobReport(id, kind, macroName);
        this.report.startedAtTick = acceptedAtTick;
    }

    /** 已处理的动作总数（进度用） */
    public int processed() {
        return done.size();
    }

    public int remaining() {
        return todo.size();
    }
}
