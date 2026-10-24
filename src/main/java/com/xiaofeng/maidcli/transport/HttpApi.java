package com.xiaofeng.maidcli.transport;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.sun.net.httpserver.HttpExchange;
import com.xiaofeng.maidcli.MaidCliMod;
import com.xiaofeng.maidcli.command.CmdRegistry;
import com.xiaofeng.maidcli.command.MaidCmd;
import com.xiaofeng.maidcli.engine.MaidHandle;
import com.xiaofeng.maidcli.engine.MaidRegistry;
import com.xiaofeng.maidcli.loading.MaidLoader;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP 适配器 —— 传输层（COMMANDS.md §1）。
 *
 * <p>它<b>不含业务逻辑</b>：鉴权 / JSON 解析 / 序列化 / 找指令 / 门禁，然后交给指令层。
 *
 * <pre>
 * GET  /ping                       健康检查（不要 key）
 * GET  /help [?what=主题]          指令自省 + 公开资料（不要 key）
 * POST /cmd  {"cmd":"sense", …}    发一条指令
 * POST /cmd  {"maid":"K7M2QX","cmds":["sense self","go pos=-40,130,-40"]}   批量
 * GET  /q?cmd=inv&maid=K7M2QX      查询类（只读）
 * </pre>
 */
public final class HttpApi {

    /** 批量上限（COMMANDS.md §2.1）：一批最多 32 条 */
    private static final int MAX_BATCH = 32;
    /** 请求体上限：1MB */
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private HttpApi() {
    }

    public static void handle(HttpExchange ex) throws IOException {
        try {
            route(ex);
        } catch (CmdException e) {
            error(ex, e);
        } catch (Throwable t) {
            MaidCliMod.LOG.error("[MaidCLI] 请求处理失败", t);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("code", CmdException.INTERNAL);
            out.put("message", t.getMessage() == null ? String.valueOf(t) : t.getMessage());
            json(ex, 500, out);
        }
    }

    private static void route(HttpExchange ex) throws Exception {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod().toUpperCase();
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        String rawKey = ex.getRequestHeaders().getFirst(Auth.HEADER);

        if (path.equals("/") || path.equals("/ping")) {
            MinecraftServer s = MaidCliMod.server();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("mod", MaidCliMod.MOD_ID);
            m.put("version", MaidCliMod.VERSION);
            m.put("java", Runtime.version().feature());
            m.put("ticks", s == null ? -1 : s.getTickCount());
            m.put("maids", MaidRegistry.size());
            json(ex, 200, m);
            return;
        }

        // 自省：不需要 key、不需要女仆
        if (path.equals("/help")) {
            String what = q.get("what");
            json(ex, 200, wrap("help", MaidCliMod.onMain(s -> CmdRegistry.help(what), 10)));
            return;
        }

        // 查询类
        if (path.equals("/q")) {
            if (!method.equals("GET") && !method.equals("POST")) {
                json(ex, 405, simpleError("use GET"));
                return;
            }
            Auth.Key k = Auth.require(rawKey);
            // ★ 两种入口：
 // ① line=…（推荐，和 CLI / 批量同一种语法）—— "sense self"
 // ② cmd=…&参数=…（结构化，内部/测试用）
            String line = strOf(q.get("line"));
            Map<String, Object> args;
            String name;
            if (line != null && !line.isBlank()) {
                Map<String, Object> parsed = parseCommandLine(line);
                name = strOf(parsed.remove("__name"));
                args = parsed;
                // ★ line 里没有 maid —— 从 query string 补进来（否则查询类会丢女仆）
                String mq = strOf(q.get("maid"));
                if (mq != null && !mq.isBlank()) {
                    args.put("maid", mq);
                }
            } else {
                args = new LinkedHashMap<>(q);
                name = strOf(args.remove("cmd"));
                if (name == null) {
                    name = strOf(args.remove("what"));
                }
            }
            final String cmdName = name;
            json(ex, 200, wrap(cmdName, MaidCliMod.onMain(s -> exec(cmdName, args, k), 30)));
            return;
        }

        // 命令类（单条 / 批量）
        if (path.equals("/cmd")) {
            if (!method.equals("POST")) {
                json(ex, 405, simpleError("use POST"));
                return;
            }
            Map<String, Object> body = readJson(ex);
            String name = strOf(body.get("cmd"));
            Object cmds = body.get("cmds");

            if (cmds instanceof List<?> list) {
                if (list.size() > MAX_BATCH) {
                    throw new CmdException(CmdException.BAD_ARG,
                            "一批最多 " + MAX_BATCH + " 条（收到 " + list.size() + " 条）",
                            "分批发送");
                }
                // ── 批量：读批打包返回；写批依次执行 ──
                Auth.Key k = Auth.require(rawKey);
                // ★ 请求级 maid 作为默认注入每条命令（命令级 maid= 优先）—— COMMANDS.md §2.3 两级指定
                final String reqMaid = strOf(body.get("maid"));
                List<Map<String, Object>> parsed = new ArrayList<>();
                boolean allQuery = true;
                for (Object o : list) {
                    Map<String, Object> one = parseCommandLine(String.valueOf(o));
                    String cn = strOf(one.remove("__name"));
                    if (reqMaid != null && !one.containsKey("maid")) {
                        one.put("maid", reqMaid);
                    }
                    MaidCmd c = CmdRegistry.get(cn);
                    if (c == null) {
                        throw new CmdException(CmdException.BAD_ARG, "没有这条指令：" + cn, "用 help 看清单");
                    }
                    if (c.kind() != MaidCmd.Kind.QUERY) {
                        allQuery = false;
                    }
                    Map<String, Object> withName = new LinkedHashMap<>(one);
                    withName.put("__name", cn);
                    parsed.add(withName);
                }
                if (allQuery) {
                    // 读批：攒齐一起回
                    Map<String, Object> res = MaidCliMod.onMain(s -> {
                        List<Map<String, Object>> results = new ArrayList<>();
                        for (Map<String, Object> one : parsed) {
                            String cn = strOf(one.remove("__name"));
                            results.add(wrap(cn, exec(cn, one, k)));
                        }
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("results", results);
                        return out;
                    }, 30);
                    json(ex, 200, wrap("batch", res));
                    return;
                }
                // 写批：依次执行
                Map<String, Object> res = MaidCliMod.onMain(s -> {
                    List<Map<String, Object>> results = new ArrayList<>();
                    for (Map<String, Object> one : parsed) {
                        String cn = strOf(one.remove("__name"));
                        results.add(wrap(cn, exec(cn, one, k)));
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("results", results);
                    return out;
                }, 30);
                json(ex, 200, wrap("batch", res));
                return;
            }

            // ── 单条 ──
            Map<String, Object> args = new LinkedHashMap<>(body);
            args.remove("cmd");
            MaidCmd probe = name == null ? null : CmdRegistry.get(name);
            if (probe == null) {
                throw new CmdException(CmdException.BAD_ARG, "没有这条指令：" + name, "用 help 看清单");
            }
            Auth.Key k = probe.needsKey() ? Auth.require(rawKey) : null;
            json(ex, 200, wrap(name, MaidCliMod.onMain(s -> exec(name, args, k), 30)));
            return;
        }

        Map<String, Object> err = new LinkedHashMap<>();
        err.put("ok", false);
        err.put("code", "not_found");
        err.put("message", "没有这个路径：" + path);
        json(ex, 404, err);
    }

    // ────────────────────────── 一条指令的完整流程 ──────────────────────────

    /**
     * 门禁顺序：<b>要不要 key → 要不要女仆（找她 + 必须唤醒）→ 分发</b>。
     */
    private static Map<String, Object> exec(String name, Map<String, Object> args, Auth.Key key) {
        try {
            return exec0(name, args, key);
        } catch (CmdException e) {
            if (e.cmd == null) {
                e.cmd = name;      // ★ 失败信封要带 cmd（COMMANDS.md §2.5）
            }
            throw e;
        }
    }

    private static Map<String, Object> exec0(String name, Map<String, Object> args, Auth.Key key) {
        MaidCmd cmd = CmdRegistry.get(name);
        if (cmd == null) {
            throw new CmdException(CmdException.BAD_ARG, "没有这条指令：" + name, "用 help 看清单");
        }
        if (cmd.needsKey() && key == null) {
            throw new CmdException(CmdException.UNAUTHORIZED, "这条指令要带身份（先 login）");
        }
        if (!cmd.needsMaid()) {
            return cmd.kind() == MaidCmd.Kind.QUERY
                    ? data(CmdRegistry.dispatch(name, null, null, args, MaidCmd.Kind.QUERY,
                    key == null ? null : key.playerUUID))
                    : data(CmdRegistry.dispatch(name, null, null, args, MaidCmd.Kind.COMMAND,
                    key == null ? null : key.playerUUID));
        }
        MinecraftServer server = MaidCliMod.server();
        String mkey = strOf(args.get("maid"));
        if (mkey == null || mkey.isBlank()) {
            throw new CmdException(CmdException.BAD_ARG,
                    "没说找哪只女仆（maid=<标签/uuid/名字>）",
                    "用 maids 看有哪些");
        }
        EntityMaid maid = MaidRegistry.resolve(server, mkey);
        MaidHandle h = MaidRegistry.handleFor(server, maid);
        MaidLoader.requireAwake(maid, h, key == null ? null : key.playerUUID);
        MaidLoader.touch(h);
        return data(CmdRegistry.dispatch(name, maid, h, args, cmd.kind(),
                key == null ? null : key.playerUUID));
    }

    private static Map<String, Object> data(Map<String, Object> d) {
        return d == null ? new LinkedHashMap<>() : d;
    }

    /** 包信封：{ ok, cmd, data } */
    private static Map<String, Object> wrap(String cmd, Map<String, Object> data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        if (cmd != null) {
            out.put("cmd", cmd);
        }
        out.put("data", data == null ? new LinkedHashMap<>() : data);
        return out;
    }

    private static Map<String, Object> simpleError(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("code", CmdException.BAD_ARG);
        m.put("message", message);
        return m;
    }

    private static void error(HttpExchange ex, CmdException e) throws IOException {
        int status = switch (e.code) {
            case CmdException.UNAUTHORIZED -> 401;
            case CmdException.NOT_FOUND -> 404;
            case CmdException.NOT_AWAKE, CmdException.NOT_YOURS -> 403;
            default -> 400;
        };
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        if (e.cmd != null) {
            out.put("cmd", e.cmd);
        }
        out.put("code", e.code);
        out.put("message", e.getMessage() == null ? e.code : e.getMessage());
        if (e.hint != null) {
            out.put("hint", e.hint);
        }
        if (e.data != null) {
            out.putAll(e.data);
        }
        json(ex, status, out);
    }

    // ────────────────────────── 工具 ──────────────────────────

    public static void json(HttpExchange ex, int code, Object obj) throws IOException {
        byte[] body = MaidCliMod.GSON.toJson(obj).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> readJson(HttpExchange ex) throws IOException {
        String raw;
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new CmdException(CmdException.BAD_ARG,
                        "请求体超过 " + (MAX_BODY_BYTES / 1024 / 1024) + "MB 上限",
                        "分批发送");
            }
            raw = new String(bytes, StandardCharsets.UTF_8);
        }
        if (raw.isBlank()) {
            return new LinkedHashMap<>();
        }
        return MaidCliMod.GSON.fromJson(raw, Map.class);
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i < 0) {
                out.put(decode(pair), "");
            } else {
                out.put(decode(pair.substring(0, i)), decode(pair.substring(i + 1)));
            }
        }
        return out;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String strOf(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * 解析"命令行"形式 —— CLI 批量（{@code cmds}）和 {@code run} 用的唯一入口。
     *
     * <p>语法（COMMANDS.md §2.1）：
     * <pre>
     *   &lt;指令&gt; [子命令] [键=值 ...]        ← 子命令只在该指令声明了 sub() 时才认
     *   get 键 键 ...                      ← 变长裸词（variadic=WORDS）
     *   set 键=值 键=值 ...                 ← 变长键值对（variadic=PAIRS）
     * </pre>
     *
     * <p>★ <b>绝不静默丢弃 token</b>：认不出来的 token 一律报 {@code bad_arg}
     * （以前是 {@code continue} 直接扔掉 —— 用户把 {@code pos=x,y,z} 敲成 {@code pos x,y,z}
     * 时只会"看起来没生效"）。
     *
     * <p>引号：{@code "…"} / {@code '…'} 包起来的算一个 token（值里可以有空格）。
     */
    public static Map<String, Object> parseCommandLine(String line) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (line == null || line.isBlank()) {
            return out;
        }
        List<String> parts = splitTokens(line);
        if (parts.isEmpty()) {
            return out;
        }
        String name = parts.get(0);
        out.put("__name", name);

        MaidCmd cmd = CmdRegistry.get(name);
        MaidCmd.SubSpec sub = cmd == null ? null : cmd.sub();
        MaidCmd.Variadic var = cmd == null ? null : cmd.variadic();

        int i = 1;
        // ── ① 子命令：第一个不含 '=' 的 token ──
        if (sub != null) {
            if (i < parts.size() && parts.get(i).indexOf('=') < 0) {
                out.put(sub.argName(), parts.get(i));
                i++;
            } else if (sub.required()) {
                throw new CmdException(CmdException.BAD_ARG,
                        name + " 缺少子命令（" + sub.desc() + "）",
                        "用法：" + name + " <" + sub.desc() + "> [参数]");
            }
        }

        // ── ② 其余 token：键=值 进 pairs；裸词交给 variadic（没有就报错）──
        List<String> words = new ArrayList<>();
        Map<String, String> pairs = new LinkedHashMap<>();
        for (; i < parts.size(); i++) {
            String p = parts.get(i);
            int eq = p.indexOf('=');
            if (eq > 0) {
                pairs.put(p.substring(0, eq), p.substring(eq + 1));
            } else if (var != null) {
                words.add(p);
            } else {
                throw new CmdException(CmdException.BAD_ARG,
                        "参数要写成「键=值」：" + p,
                        "正确写法如 pos=-40,130,-40 · count=5");
            }
        }

        // ── ③ 变长参数收口 ──
        if (var != null) {
            if (var.mode() == MaidCmd.Variadic.Mode.WORDS) {
                if (!pairs.isEmpty()) {
                    throw new CmdException(CmdException.BAD_ARG,
                            name + " 只接受键名，不接受「键=值」：" + pairs.keySet().iterator().next(),
                            "例：" + name + " mode follow");
                }
                out.put(var.argName(), words);
            } else {
                if (!words.isEmpty()) {
                    throw new CmdException(CmdException.BAD_ARG,
                            name + " 只接受「键=值」：" + words.get(0),
                            "例：" + name + " follow=true sit=false");
                }
                out.put(var.argName(), pairs);
            }
        } else {
            out.putAll(pairs);
        }
        return out;
    }

    /**
     * 按空白切 token，但尊重引号：{@code set name="酒 狐"} ⇒ {@code ["set","name=酒 狐"]}。
     * <p>引号本身不进值；不配对的引号按"到行尾"处理（宽松，不报错）。
     */
    static List<String> splitTokens(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuote) {
                if (c == quote) {
                    inQuote = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                inQuote = true;
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }
}
