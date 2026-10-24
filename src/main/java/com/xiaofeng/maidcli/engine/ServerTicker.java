package com.xiaofeng.maidcli.engine;

import com.xiaofeng.maidcli.MaidCliMod;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ 唯一的 tick 入口（execution-model §2）。
 *
 * <p>为什么全局只有一个循环：旧实现是 4 个 {@code @EventBusSubscriber} 各自为政
 * （{@code MaidEventRunner} / {@code CollectOps} / {@code ShootOps} / {@code EatOps}）
 * ⇒ 顺序不可控、{@code stop} 收不干净。
 *
 * <p>⚠️ 类上必须有 {@code @EventBusSubscriber} —— 只写 {@code @SubscribeEvent} 方法不会生效
 * （旧实现遇到过：命令返回 ok、status 说 running，但她一步不动、tick 计数一直是 0）。
 */
@EventBusSubscriber(modid = MaidCliMod.MOD_ID)
public final class ServerTicker {

    private ServerTicker() {
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        // 拷一份：tick 过程中可能有 handle 被建出来
        List<MaidHandle> handles = new ArrayList<>(MaidRegistry.all());
        for (MaidHandle h : handles) {
            try {
                h.tick(server);
            } catch (Throwable t) {
                // 一只女仆出问题不能拖垮整个 tick（更不能拖垮别人）
                MaidCliMod.LOG.error("[MaidCLI] handle tick 异常 {}", h.identity.tag, t);
            }
        }
        // 唤醒超时释放（300 秒没指令）
        try {
            com.xiaofeng.maidcli.loading.MaidLoader.tickTimeouts(server);
        } catch (Throwable t) {
            MaidCliMod.LOG.error("[MaidCLI] 唤醒超时检查异常", t);
        }
        // ★ 指令模式 = 自由行动：留守时把锚点挪到她脚下（每 8 tick 一次就够，
 // 判定窗口是 40 tick，她走不出 16 格）。旧版是独立 @EventBusSubscriber，
 // 这里合并进唯一 tick 入口（execution-model §2）。
        if (server.getTickCount() % com.xiaofeng.maidcli.ops.FreeRoam.CHECK_INTERVAL == 0) {
            for (MaidHandle h : handles) {
                try {
                    if (!h.awake) {
                        continue;
                    }
                    com.xiaofeng.maidcli.ops.FreeRoam.tick(h.maid());
                } catch (Throwable t) {
                    MaidCliMod.LOG.error("[MaidCLI] FreeRoam 异常 {}", h.identity.tag, t);
                }
            }
        }
    }
}
