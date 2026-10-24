package com.xiaofeng.maidcli.transport;

import java.util.Map;

/**
 * 统一错误 —— 命令失败时抛它。
 *
 * <p>传输层会把异常统一包成（COMMANDS.md §2.5）：
 * <pre>
 * { ok: false, cmd: "place", code: "too_far", message: "…", hint: "…" }
 * </pre>
 *
 * <p><b>为什么要 code</b>：CLI 能按 code 决定行为 —— 比如 {@code too_far} 自动走过去重试、
 * {@code not_found} 提示先 wake。错误码表见 COMMANDS.md §2.5。
 */
public class CmdException extends RuntimeException {

    /** 没 key / key 不认识 / 过期（先 login） */
    public static final String UNAUTHORIZED = "unauthorized";
    /** 指不到这个女仆 / 方块 / 容器 / 实体 */
    public static final String NOT_FOUND = "not_found";
    /** 她不是这个玩家的女仆 */
    public static final String NOT_YOURS = "not_yours";
    /** 参数不合法（缺失 / 类型不符 / 越界） */
    public static final String BAD_ARG = "bad_arg";
    /** 界面状态不匹配（该开没开 / 该关没关） */
    public static final String BAD_STATE = "bad_state";
    /** 队列满（待办 ≥ 256 条） */
    public static final String BUSY = "busy";
    /** 动作 / 宏超过限定时间 */
    public static final String TIMEOUT = "timeout";
    /** 够不着（超出 8 格手长）→ CLI 会自动走过去重试 */
    public static final String TOO_FAR = "too_far";
    /** 她没被唤醒 */
    public static final String NOT_AWAKE = "not_awake";
    /** 目标区块没加载 */
    public static final String NOT_LOADED = "not_loaded";
    /** 空间不足（容纳不下她 / 背包满） */
    public static final String NO_SPACE = "no_space";
    /** 身上没有这件物品 */
    public static final String NO_ITEM = "no_item";
    /** 被 TLM 或原版判定拦下 */
    public static final String BLOCKED = "blocked";
    /** 未预期的异常（实现里的 bug） */
    public static final String INTERNAL = "internal";

    public final String code;
    /** 给人看的"怎么办"（可空） */
    public final String hint;
    /** 结构化补充数据（可空），比如 {"distance":12.4,"pos":[x,y,z]} */
    public final Map<String, Object> data;
    /** 出错的指令名 —— 由传输层在 {@code exec()} 里填，返回给调用方定位（COMMANDS.md §2.5） */
    public String cmd;

    public CmdException(String code, String message) {
        this(code, message, null, null);
    }

    public CmdException(String code, String message, String hint) {
        this(code, message, hint, null);
    }

    public CmdException(String code, String message, String hint, Map<String, Object> data) {
        super(message);
        this.code = code;
        this.hint = hint;
        this.data = data;
    }

    public static CmdException badArg(String message) {
        return new CmdException(BAD_ARG, message);
    }

    /** ★ 两参数版（message + hint）—— 别只记得单参数那个（写错过） */
    public static CmdException badArg(String message, String hint) {
        return new CmdException(BAD_ARG, message, hint);
    }

    public static CmdException notFound(String message, String hint) {
        return new CmdException(NOT_FOUND, message, hint);
    }

    public static CmdException badState(String message) {
        return new CmdException(BAD_STATE, message);
    }

    public static CmdException badState(String message, String hint) {
        return new CmdException(BAD_STATE, message, hint);
    }

    public static CmdException tooFar(double distance) {
        return new CmdException(TOO_FAR, "够不着（距离 " + String.format("%.1f", distance) + " 格，手长 8 格）",
                "先 go 过去；走 CLI 的话会自动走过去重试");
    }

    public static CmdException internal(Throwable t) {
        String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        return new CmdException(INTERNAL, m);
    }
}
