package com.xiaofeng.maidcli.command;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.engine.MaidHandle;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Map;

/**
 * 一条「指令」—— 指令层的最小单元（COMMANDS.md §1）。
 *
 * <p><b>一套指令定义，三个入口</b>：CLI（HTTP）· mod 内嵌智能体（直接调）· 测试脚本。
 * 加一条指令 = 写一个实现类 + 在 {@link CmdRegistry} 注册一行。
 *
 * <p>指令实现在<b>服务端主线程</b>被调用，所以可以直接碰世界、直接调 TLM / 原版 API。
 *
 * <p><b>职责边界</b>（execution-model §1）：
 * <ul>
 *   <li>查询类（QUERY）：直接读、直接返回，<b>不入队</b></li>
 *   <li>操作类（COMMAND）：校验 → <b>拆解成动作</b> → 装 {@link com.xiaofeng.maidcli.engine.Job} → 入队 → 受理即回</li>
 * </ul>
 */
public interface MaidCmd {

    /** 指令类别 —— 决定它挂在哪条路径、以及"会不会动世界" */
    enum Kind {
        /** 只读查询（{@code GET /q}）—— 随便调不会出事 */
        QUERY,
        /** 命令（{@code POST /cmd}）—— 会改变世界或她的状态 */
        COMMAND
    }

    /** 指令名（小写下划线） */
    String name();

    /** 属于哪一类 */
    Kind kind();

    /**
     * 参数声明 —— 校验、默认值、自省都靠它。
     * <p>没声明的参数一律忽略；声明为必填的缺了就报 {@code bad_arg}。
     */
    Map<String, Param> params();

    /**
     * 子命令规格（可选）。
     *
     * <p>声明后：命令行里<b>第一个不含 {@code =} 的 token</b> 会被当成子命令，
     * 装进 {@code argName} 这个参数（指令的 {@code run()} 照旧用 {@code ctx.str(argName,…)} 读）。
     *
     * <pre>
     * sense self        → args{domain: "self"}      （sense 声明 sub("domain")）
     * mode attack       → args{task: "attack"}      （mode 声明 sub("task")）
     * macro shoot pos=… → args{macro: "shoot", pos: "…"}
     * help mode         → args{what: "mode"}
     * </pre>
     *
     * <p>⚠️ 子命令是<b>声明制</b>：没声明 sub 的指令，裸 token 不会被当成子命令
     * （避免 {@code get mode follow} 被误解）。
     */
    default SubSpec sub() {
        return null;
    }

    /**
     * 变长参数规格（可选）—— 给 {@code get} / {@code set} 这种「一个指令多个键」用。
     *
     * <ul>
     *   <li>{@link Mode#WORDS}：裸词模式 — {@code get mode follow} → {@code keys:["mode","follow"]}</li>
     *   <li>{@link Mode#PAIRS}：键值对模式 — {@code set follow=true sit=false} → {@code keys:{follow:"true",sit:"false"}}</li>
     * </ul>
     *
     * <p>声明了 variadic 的指令，其余 token 全部收进 {@code argName}，不再支持平级参数。
     */
    default Variadic variadic() {
        return null;
    }

    /** 子命令规格 —— argName 是内部参数名（不对外出现） */
    record SubSpec(String argName, boolean required, String desc) {
    }

    /** 变长参数规格 */
    record Variadic(String argName, Mode mode) {
        public enum Mode {
            /** 裸词：get mode follow */
            WORDS,
            /** 键值对：set follow=true sit=false */
            PAIRS
        }
    }

    /** 执行（已在服务端主线程）。返回值会放进统一返回信封的 {@code data} 字段。 */
    Map<String, Object> run(Ctx ctx) throws Exception;

    /** 一句话说明（自省输出 / CLI 帮助文本） */
    default String desc() {
        return "";
    }

    /**
     * 可选：参数之外的【前置语法检查】—— 在"参数校验之后、会话门禁之前"跑。
     * <p>用途：语法错误（比如给 move 传了 to）永远比"先 open"先报出来。
     */
    default void precheck(Map<String, Object> args) {
    }

    /**
     * ★ 这条只读指令能不能【不依赖女仆】执行（世界类查询）。
     * <p>默认 false —— 只有真正"给坐标就能读"的查询才声明 true。
     */
    default boolean worldOk() {
        return false;
    }

    /**
     * 这条指令需不需要"她已经唤醒"这道门禁。
     * <p>默认：只要用到女仆（非 worldOk）就要；登录类 / 世界类 / 帮助类覆写成 false。
     */
    default boolean needsAwake() {
        return !worldOk();
    }

    /** 这条指令需不需要 key（只有 login 不需要） */
    default boolean needsKey() {
        return true;
    }

    /** 这条指令需不需要指定女仆（世界类 / 登录类 / 帮助类不需要） */
    default boolean needsMaid() {
        return !worldOk();
    }

    // ────────────────────────── 运行上下文 ──────────────────────────

    /**
     * 运行上下文 —— 指令实现从这里取参数，不用自己碰 Map 和类型转换。
     * <p>参数已由 {@link Param} 校验 + 归一化（GSON 把 JSON 数字都解析成 Double，
     * 这里统一收敛成 int / double，免得每条指令各写一遍 {@code instanceof Number}）。
     */
    interface Ctx {

        /** 指定的女仆实体（世界类指令为 null） */
        EntityMaid maid();

        /** 她对应的常驻句柄（队列 / 会话 / 历史都在这） */
        MaidHandle handle();

        /** 这次请求是谁发的（login 发的那个 key 的玩家）—— wake 的归属校验要用 */
        java.util.UUID player();

        MinecraftServer server();

        /** 归一化之后的完整参数表（要冷门参数时用） */
        Map<String, Object> args();

        /** 单个坐标（统一名 {@code pos}）；没给返回 null */
        BlockPos pos();

        /** 坐标数组（**数组即批量**）：只给一个坐标时会包成 1 个元素的数组 */
        List<BlockPos> posList();

        /** 区域列表（方区 / 球展开；受 limit 限制） */
        List<BlockPos> areaBlocks(int limit);

        /** 取可选字符串，没有返回 null */
        String opt(String key);

        String str(String key, String def);

        int in(String key, int def);

        double dbl(String key, double def);

        boolean flag(String key, boolean def);

        /** 原始参数值（没归一化的），拿到就是别人传进来的样子 */
        Object raw(String key);

        /**
         * ★ 把 {@code entity=} 解析成实际实体 id —— 它是全仓统一的"目标实体"参数。
         *
         * <p>值可以是：
         * <ul>
         *   <li>数字 → 实体 id</li>
         *   <li>{@code owner} → 主人的实体 id（主人必须在线）</li>
         * </ul>
         * <p>没给 / 给 0 → 返回 0。主人不在线 → {@code bad_state}。
         */
        default int entityId() {
            Object o = args().get("entity");
            if (o == null) {
                return 0;
            }
            if (o instanceof Number n) {
                return n.intValue();
            }
            String s = String.valueOf(o).trim();
            if (s.isEmpty()) {
                return 0;
            }
            if (s.equalsIgnoreCase("owner")) {
                net.minecraft.server.level.ServerPlayer owner =
                        server().getPlayerList().getPlayer(maid().getOwnerUUID());
                if (owner == null) {
                    throw com.xiaofeng.maidcli.transport.CmdException.badState(
                            "她主人不在线（entity=owner 要他 online）",
                            "改用具体的 entity=<数字 id>");
                }
                return owner.getId();
            }
            throw com.xiaofeng.maidcli.transport.CmdException.badArg(
                    "entity= 要给实体 id 或 owner（收到 " + o + "）");
        }

        /** {@code entity=owner}？ */
        default boolean entityIsOwner() {
            Object o = args().get("entity");
            return o != null && "owner".equalsIgnoreCase(String.valueOf(o).trim());
        }
    }
}
