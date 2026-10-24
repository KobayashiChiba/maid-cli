package com.xiaofeng.maidcli.engine;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 作业的结束报告（execution-model §3 ⑥）。
 *
 * <p><b>四态 + 结构化</b>：
 * <pre>
 * result:  done（全成）/ partial（部分成）/ failed（全败）/ aborted（被中断）
 * counts:  { total, ok, failed, skipped }
 * failed:  [ { pos, reason } ]      ← 只存【失败明细】
 * message: "放了 2 块，第 3 块失败"  ← 给人看
 * </pre>
 *
 * <p>⚠️ <b>机器一律读 {@code result} / {@code counts} / {@code failed[]}，绝不解析 {@code message}</b>
 * —— 旧实现就栽在这：{@code message} 只写摘要"完成（跳过 1 个）"，失败原因在 skipped 列表里，
 * 读 message 判断就错。
 */
public final class JobReport {

    public enum Result {
        /** 全部成功 */
        DONE("done"),
        /** 部分成功 */
        PARTIAL("partial"),
        /** 全部失败 */
        FAILED("failed"),
        /** 被中断（stop / 她消失 / 超时） */
        ABORTED("aborted");

        public final String wire;

        Result(String wire) {
            this.wire = wire;
        }
    }

    /** 一条失败明细 */
    public record Failure(String what, String reason) {
    }

    /** 作业 id（受理时分配 = 响应里的 id） */
    public final long id;
    /** 作业类型（go / place / macro…） */
    public final String op;
    /** 宏的话，宏名（否则 null） */
    public final String name;
    public Result result = Result.DONE;
    public int total;
    public int ok;
    public int failed;
    public int skipped;
    public final java.util.List<Failure> failures = new java.util.ArrayList<>();
    public String message = "";
    /** 附加数据（宏自己塞，如 shoot 的 hit/landed） */
    public final Map<String, Object> extra = new LinkedHashMap<>();
    public long startedAtTick;
    public long finishedAtTick;

    public JobReport(long id, String op, String name) {
        this.id = id;
        this.op = op;
        this.name = name;
    }

    public void addFailure(String what, String reason) {
        failures.add(new Failure(what, reason));
    }

    /** 按计数自动定 result（调用方也可以在收口时手改，如 aborted） */
    public void resolve() {
        if (total == 0) {
            result = Result.DONE;
            return;
        }
        if (ok == 0) {
            result = Result.FAILED;
        } else if (failed > 0 || skipped > 0) {
            result = Result.PARTIAL;
        } else {
            result = Result.DONE;
        }
    }

    /** 序列化成 history 里的一条（COMMANDS.md §5.2.2 的形状） */
    public Map<String, Object> toHistoryEntry() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("op", op);
        if (name != null) {
            m.put("name", name);
        }
        m.put("ok", result == Result.DONE || result == Result.PARTIAL);
        m.put("result", result.wire);
        m.put("total", total);
        m.put("ok_count", ok);
        m.put("failed", failed);
        m.put("skipped", skipped);
        if (!failures.isEmpty()) {
            java.util.List<Map<String, Object>> fs = new java.util.ArrayList<>();
            // 只保留前 20 条明细（1000 格的活儿不能把 history 撑爆）
            for (int i = 0; i < Math.min(20, failures.size()); i++) {
                Failure f = failures.get(i);
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("what", f.what());
                fm.put("reason", f.reason());
                fs.add(fm);
            }
            if (failures.size() > 20) {
                fs.add(Map.of("what", "…", "reason", "还有 " + (failures.size() - 20) + " 条"));
            }
            m.put("failures", fs);
        }
        m.putAll(extra);
        if (!message.isEmpty()) {
            m.put("message", message);
        }
        return m;
    }

    @Override
    public String toString() {
        return "[job#" + id + " " + op + " " + result.wire + " ok=" + ok + "/" + total + "]";
    }

    // ────────────────────────── 小工具（动作实现常用） ──────────────────────────

    /** 方块坐标 → JSON 友好的 list */
    public static java.util.List<Object> p(net.minecraft.core.BlockPos pos) {
        return java.util.List.of(pos.getX(), pos.getY(), pos.getZ());
    }

    /** 精确点 → JSON 友好的 list */
    public static java.util.List<Object> p(double x, double y, double z) {
        return java.util.List.of(x, y, z);
    }

    /** 物品栈 → { item, count } 或 null */
    public static Map<String, Object> stack(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item", s.getItem().toString());
        m.put("count", s.getCount());
        return m;
    }

    /** 实体 → 简短描述 */
    public static String describe(Entity e) {
        if (e == null) {
            return null;
        }
        if (e instanceof ServerPlayer p) {
            return p.getGameProfile().getName();
        }
        return e.getType().toString();
    }

    /** 维度 id */
    public static String dim(ServerLevel lv) {
        return lv == null ? null : lv.dimension().location().toString();
    }
}
