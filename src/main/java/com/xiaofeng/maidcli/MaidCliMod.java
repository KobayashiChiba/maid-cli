package com.xiaofeng.maidcli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xiaofeng.maidcli.engine.MaidData;
import com.xiaofeng.maidcli.engine.MaidRegistry;
import com.xiaofeng.maidcli.infra.MaidCliConfig;
import com.xiaofeng.maidcli.loading.MaidLoader;
import com.xiaofeng.maidcli.transport.Auth;
import com.xiaofeng.maidcli.transport.CmdException;
import com.xiaofeng.maidcli.transport.HttpApi;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Maid CLI / Maid CLI（v2 架构）。
 *
 * <p>给车万女仆（Touhou Little Maid）的「女仆」开一个外部控制接口：
 * 调用方在游戏外通过 HTTP 读她的感官、下指令操控她。
 *
 * <p>设计要点：
 * <ol>
 *   <li>只监听 127.0.0.1，不对外网开放</li>
 *   <li>一切涉及游戏世界的读写都通过 {@link #onMain} 回服务端主线程执行</li>
 *   <li>感官直接复用 TLM 官方上下文注册表；只读操作照抄官方 AI Tool 的底层调用，不改 TLM 本体</li>
 * </ol>
 *
 * <p>架构：{@code transport（进来）→ command（拆解入队）→ engine（调度 + 数据）→ ops（实现）→ 原版 API}；
 * {@code loading} 管"把她拉起来" · {@code infra} 是地基 · {@code MaidHandle} 是一只女仆的全部。
 */
@Mod(MaidCliMod.MOD_ID)
@EventBusSubscriber(modid = MaidCliMod.MOD_ID)
public class MaidCliMod {

    public static final String MOD_ID = "maid_cli";
    /** 版本号单一来源 = mods.toml；build.sh 编译前会自动同步这一行 */
    public static final String VERSION = "0.1.0.0";
    /** 默认端口；实际用配置里的 {@code http_port} */
    public static final int DEFAULT_PORT = 8644;

    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    public static final Logger LOG = LoggerFactory.getLogger("MaidCLI");

    private static volatile MinecraftServer server;
    private static HttpServer http;
    private static ExecutorService pool;

    public MaidCliMod() {
        LOG.info("[MaidCLI] {} loaded", VERSION);
    }

    /**
     * ★ 注册我们的区块票据控制器（NeoForge 官方 API）。
     * <p>这是 <b>mod 总线</b>事件，必须在 {@link EventBusSubscriber.Bus#MOD} 上收 —— 不能挂在默认（游戏）总线上。
     */
    @EventBusSubscriber(modid = MOD_ID, bus = EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        @SubscribeEvent
        public static void onRegisterTicketControllers(RegisterTicketControllersEvent event) {
            event.register(MaidLoader.WAKE_CONTROLLER);
            LOG.info("[MaidCLI] 已注册票据控制器 {}", MaidLoader.WAKE_CONTROLLER.id());
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        // 静态字段随进程重启重置 ⇒ 每次启动注册一轮
        if (com.xiaofeng.maidcli.command.CmdRegistry.isEmpty()) {
            com.xiaofeng.maidcli.command.defs.ConnectCmds.registerAll();
            com.xiaofeng.maidcli.command.defs.QueryCmds.registerAll();
            com.xiaofeng.maidcli.command.defs.WorldCmds.registerAll();
            com.xiaofeng.maidcli.command.defs.SessionCmds.registerAll();
            com.xiaofeng.maidcli.command.defs.MacroCmds.registerAll();
            com.xiaofeng.maidcli.command.defs.ConfigCmds.registerAll();
            com.xiaofeng.maidcli.command.defs.ControlCmds.registerAll();
            com.xiaofeng.maidcli.command.defs.HelpCmds.registerAll();
        }
        MaidCliConfig.invalidate();
        Auth.clearAll();
        MaidRegistry.clearAll();
        try {
            MaidData.get(event.getServer());     // 触发一次读盘（顺带把数据挂上）
        } catch (Throwable t) {
            LOG.warn("[MaidCLI] 读身份账本失败: {}", t.toString());
        }
        try {
            int port = MaidCliConfig.get(event.getServer()).http_port;
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            http.createContext("/", MaidCliMod::dispatch);
            pool = Executors.newFixedThreadPool(4);
            http.setExecutor(pool);
            http.start();
            LOG.info("[MaidCLI] HTTP ready on http://127.0.0.1:{}", port);
        } catch (Throwable t) {
            LOG.error("[MaidCLI] failed to start HTTP server", t);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
 // ★ 0) 先给所有会话收口 —— 对齐原版机制（复查发现）
 // 原版：玩家关界面/掉线 → AbstractContainerMenu.removed() → clearContainer() 把材料还回去；
 // 服务端 /stop 会 disconnect 所有玩家 ⇒ 也走这条路。所以原版不持久化界面槽位也不丢。
 // 我们：女仆没有"掉线"生命周期 ⇒ 正常关服时没人给她退料 ⇒ 虚拟槽 / 鼠标槽里的东西会丢
 // （实测丢了 16 个圆石）。这里补上等价收口。
 // ⚠️ 必须在 releaseAll() 之前 —— 撤了加载票实体可能已卸载，就取不到 maid 退料了。
        try {
            for (com.xiaofeng.maidcli.engine.MaidHandle h : com.xiaofeng.maidcli.engine.MaidRegistry.all()) {
                try {
                    h.reset("服务端关闭");
                } catch (Throwable t) {
                    LOG.warn("[MaidCLI] 关服收口失败: {}", t.toString());
                }
            }
            LOG.info("[MaidCLI] 关服收口完成：已给所有会话退料");
        } catch (Throwable t) {
            LOG.warn("[MaidCLI] 关服收口失败: {}", t.toString());
        }
        try {
            MaidLoader.releaseAll();      // 撤掉所有加载票
        } catch (Throwable t) {
            LOG.warn("[MaidCLI] 释放加载票失败: {}", t.toString());
        }
        if (http != null) {
            http.stop(0);
            http = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        server = null;
        LOG.info("[MaidCLI] HTTP stopped");
    }

    public static MinecraftServer server() {
        return server;
    }

    /**
     * 把一段要碰游戏世界的逻辑丢回服务端主线程跑，再把结果交回调用线程。
     * <p>HTTP 线程直接读世界会炸（TLM 自己也用同样的机制）。
     */
    public static <T> T onMain(Function<MinecraftServer, T> task, long timeoutSec) throws Exception {
        MinecraftServer s = server;
        if (s == null) {
            throw new IllegalStateException("server not ready");
        }
        // 已经在主线程上 ⇒ 直接跑（避免自己等自己，死锁）
        if (s.isSameThread()) {
            return task.apply(s);
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        s.execute(() -> {
            try {
                future.complete(task.apply(s));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(timeoutSec, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception real) {
                throw real;
            }
            throw e;
        }
    }

    private static void dispatch(HttpExchange exchange) throws IOException {
        try {
            HttpApi.handle(exchange);
        } catch (CmdException e) {
            HttpApi.json(exchange, 400, Map.of("ok", false, "code", e.code,
                    "message", e.getMessage() == null ? e.code : e.getMessage()));
        } catch (Throwable t) {
            LOG.error("[MaidCLI] request failed", t);
            try {
                String msg = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
                HttpApi.json(exchange, 500, Map.of("ok", false, "code", "internal", "message", msg));
            } catch (Throwable ignored) {
                // 响应已经发出去了
            }
        }
    }
}
