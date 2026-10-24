package com.xiaofeng.maidcli.loading;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.MaidCliMod;
import com.xiaofeng.maidcli.ops.StationOps;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * 跨会话清理（旧版 {@code MaidEventRunner.onMaidJoin} 搬回，）。
 *
 * <p>她【被加载】时扫一遍：上次服务端崩掉 / 停掉时残留的工位虚拟槽（存在她 NBT 里）
 * ⇒ 东西退回背包 + 清记录。
 *
 * <p>⚠️ 新版 {@code StationOps.purgeVirtual} 本来是<b>死代码</b>（没有任何调用者）——
 * 等于"上次没关干净，这次她就少东西"。这里把它挂回女仆加载事件上。
 */
@EventBusSubscriber(modid = MaidCliMod.MOD_ID)
public final class MaidJoinCleanup {

    private MaidJoinCleanup() {
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof EntityMaid maid)) {
            return;
        }
        try {
            int n = StationOps.purgeVirtual(maid);
            if (n > 0) {
                MaidCliMod.LOG.info("[MaidCLI] 跨会话清理：把工位上残留的 {} 样东西退回了 {} 的背包",
                        n, maid.getName().getString());
            }
            // 鼠标槽残留（崩溃时她手里已经拿起来的东西）→ 退回背包，绝不吞
            net.minecraft.world.item.ItemStack cur = StationOps.loadCursor(maid);
            if (cur != null && !cur.isEmpty()) {
                com.xiaofeng.maidcli.ops.ContainerOps.giveBack(maid, cur);
                StationOps.saveCursor(maid, net.minecraft.world.item.ItemStack.EMPTY);
                MaidCliMod.LOG.info("[MaidCLI] 跨会话清理：鼠标槽残留的 {} 退回了 {} 的背包",
                        cur.getItem(), maid.getName().getString());
            }
        } catch (Throwable t) {
            MaidCliMod.LOG.warn("[MaidCLI] 跨会话清理失败: {}", t.toString());
        }
    }
}
