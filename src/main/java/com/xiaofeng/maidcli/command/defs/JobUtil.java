package com.xiaofeng.maidcli.command.defs;

import com.xiaofeng.maidcli.command.MaidCmd;
import com.xiaofeng.maidcli.engine.Job;
import com.xiaofeng.maidcli.engine.MaidHandle;
import com.xiaofeng.maidcli.engine.Session;
import com.xiaofeng.maidcli.transport.CmdException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 指令 handler 的公共小工具 —— 「装 Job → 入队 → 受理即回」这段每个操作类指令都要写。
 *
 * <p>集中在这里，免得 20 多条指令各写一遍（也就不会各错一遍）。
 */
final class JobUtil {

    private JobUtil() {
    }

    /** 建一个作业（id 由 handle 分配） */
    static Job job(MaidCmd.Ctx ctx, String kind, String macroName) {
        return new Job(ctx.handle().newJobId(), kind, macroName, ctx.server().getTickCount());
    }

    /**
     * ★ 世界操作的前置检查：<b>界面必须没开</b>（COMMANDS.md §6 的分类依据）。
     * <p>界面 / 世界的分界就一个判据：Session 是不是 null。
     */
    static void requireNoSession(MaidHandle h, String what) {
        Session s = h.session;
        if (s != null) {
            throw new CmdException(CmdException.BAD_STATE,
                    what + " 不能在界面开着的时候做（现在是 " + s.kind.wire + "）",
                    "先 close 关掉界面");
        }
    }

    /** 界面操作的前置检查：<b>界面必须开着</b> */
    static Session requireSession(MaidHandle h, String what) {
        Session s = h.session;
        if (s == null) {
            throw new CmdException(CmdException.BAD_STATE,
                    what + " 需要先把界面打开（现在没开）",
                    "先 open（方块 / 实体 / 空手开背包）");
        }
        return s;
    }

    /** 受理响应：{ accepted, id, queue_len } */
    static Map<String, Object> accepted(Job job, MaidHandle h) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", true);
        out.put("id", job.id);
        out.put("queue_len", h.queueLen());
        out.put("cmd", job.kind);
        return out;
    }

    /** 便捷：一条指令别一次拆太多（execution-model §3 ⑦c） */
    static void checkActionBudget(int count, int limit) {
        if (count > limit) {
            throw new CmdException(CmdException.BAD_ARG,
                    "这一条要拆出 " + count + " 个动作，超过上限 " + limit,
                    "分批下发（CLI 侧可以自动分批）");
        }
    }
}
