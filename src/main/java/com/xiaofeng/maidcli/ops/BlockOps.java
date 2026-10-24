package com.xiaofeng.maidcli.ops;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 方块操作实现（★ 只给 {@code Action.tick()} 调）—— 破坏 / 放置 / 读状态。
 *
 * <p><b>功能</b>：提供 {@link #destroy} / {@link #place} 两个原子操作，以及读方块状态、
 * 判定站立面、自占检查等工具方法。
 *
 * <p><b>原因</b>：破坏必须走 TLM 的 {@code destroyBlock} 以保留其权限校验，
 * 而它覆写了原版流程、不会掉经验 ⇒ 这里手工补 {@code spawnAfterBreak}；
 * 掉落"留在地上"又不能用 {@code destroyBlock(pos,false)}（那个是"什么都不掉"），
 * 因此拆成"先算掉落 → 破坏 → 手动弹回原位"三段式。
 *
 * <p><b>注意</b>：她被原版判定"放不上去"的常见原因是自己站在目标格上（有碰撞体积），
 * 这种情况由编排层负责挪位，本类只如实报错、不自动避让。
 */
public final class BlockOps {

    private BlockOps() {
    }

    public static String id(BlockState st) {
        return BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
    }

    public static String idAt(ServerLevel lv, BlockPos pos) {
        return id(lv.getBlockState(pos));
    }

    /**
     * 破坏 —— <b>走 TLM 的 {@code destroyBlock}，别绕过它直接 setBlock(air)</b>：
     * 那是唯一保住 TLM 的 {@code canDestroyBlock} 权限校验的路径。
     *
     * @param toGround true = 掉落留地上（她自己不捡）；false = 走 TLM 原生（先塞她背包，塞不下才掉地）
     */
    public static boolean destroy(EntityMaid maid, BlockPos pos, ItemStack tool, boolean toGround) {
        ServerLevel lv = (ServerLevel) maid.level();
        if (!toGround) {
            return maid.destroyBlock(pos);      // TLM: dropResourcesToMaidInv（先塞背包）
        }
        // ⚠️ ⚠️ destroyBlock(pos, false) 是「什么都不掉」，不是「掉地上」！
 // 要"原地掉落"必须三段式：先算掉落 → 破坏（不掉）→ 手动弹回原位
        BlockState state = lv.getBlockState(pos);
        BlockEntity be = lv.getBlockEntity(pos);
        List<ItemStack> drops = Block.getDrops(state, lv, pos, be, maid, tool);

        // ★ 经验：原版玩家采矿时由 {@code spawnAfterBreak(..., true)} 掉经验球
 // （TLM 的 destroyBlock 覆写了原版流程 ⇒ 不补的话挖矿永远不掉经验）
 // ⚠️ 实测：必须在【方块还在时】调 —— 先 destroyBlock 再调，原版不会生成经验球
        int orbsBefore = (int) countOrbs(lv, pos);
        try {
            state.spawnAfterBreak(lv, pos, tool, true);
        } catch (Throwable t) {
            com.xiaofeng.maidcli.MaidCliMod.LOG.warn("[MaidCLI] spawnAfterBreak 失败 {}: {}", state.getBlock(), t.toString());
        }
        // 兜底：原版没吐经验球就自己按 xpRange 补（反射读 xpRange，和原版同一份数据）
        if (countOrbs(lv, pos) == orbsBefore) {
            dropXpFallback(lv, pos, state);
        }

        boolean ok = maid.destroyBlock(pos, false);
        if (ok) {
            for (ItemStack d : drops) {
                Block.popResource(lv, pos, d);
            }
        }
        return ok;
    }

    /** 兜底掉经验：反射读 {@code DropExperienceBlock.xpRange} → 采样 → {@code ExperienceOrb.award} */
    private static void dropXpFallback(ServerLevel lv, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.DropExperienceBlock)) {
            return;
        }
        try {
            var f = net.minecraft.world.level.block.DropExperienceBlock.class.getDeclaredField("xpRange");
            f.setAccessible(true);
            var xp = (net.minecraft.util.valueproviders.IntProvider) f.get(state.getBlock());
            int amount = xp.sample(lv.getRandom());
            if (amount > 0) {
                net.minecraft.world.entity.ExperienceOrb.award(lv,
                        net.minecraft.world.phys.Vec3.atCenterOf(pos), amount);
                com.xiaofeng.maidcli.MaidCliMod.LOG.info(
                        "[MaidCLI] 经验兜底：block={} xp={}", state.getBlock(), amount);
            }
        } catch (Throwable t) {
            com.xiaofeng.maidcli.MaidCliMod.LOG.warn("[MaidCLI] 经验兜底失败: {}", t.toString());
        }
    }

    private static long countOrbs(ServerLevel lv, BlockPos pos) {
        return lv.getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class,
                new net.minecraft.world.phys.AABB(pos).inflate(6)).size();
    }

    public static void breakEffect(ServerLevel lv, BlockPos pos) {
        lv.levelEvent(2001, pos, Block.getId(lv.getBlockState(pos)));
    }

    /**
     * ⚠️ <b>她有没有站在这个格子上</b> —— 站上去就放不上（原版 isUnobstructed 检查玩家碰撞箱）。
     *
     * <p>注意：她有碰撞体积，站处方块交界处时会同时占据 2×2 格，
     * 因此"放不上"并不一定是 bug。
     * <p>⚠️ 挪位置是<b>编排层职责</b>，mod 只报失败（设计原因：站位由编排层决定，mod 不擅自挪动她）。
     */
    public static boolean selfBlocking(EntityMaid maid, BlockPos pos) {
        double x = maid.getX();
        double y = maid.getY();
        double z = maid.getZ();
        // ±0.45 格方形（她的碰撞箱宽度约 0.6~0.9）
        boolean hx = Math.abs(x - (pos.getX() + 0.5)) <= 0.45 + 0.3;
        boolean hz = Math.abs(z - (pos.getZ() + 0.5)) <= 0.45 + 0.3;
        // 脚层 / 头层（她 2 格高）
        double dy = pos.getY() - y;
        boolean vy = dy > -1.0 && dy < 1.0;
        return hx && hz && vy;
    }

    /** 解析 {@code face=} 参数：显式方向优先，不给就按"从她看向目标"推 */
    public static Direction resolveFace(EntityMaid maid, BlockPos pos, String face) {
        if (face != null && !face.isBlank()) {
            String f = face.trim().toLowerCase();
            Direction d = switch (f) {
                case "up", "上" -> Direction.UP;
                case "down", "下" -> Direction.DOWN;
                case "north", "北" -> Direction.NORTH;
                case "south", "南" -> Direction.SOUTH;
                case "west", "西" -> Direction.WEST;
                case "east", "东" -> Direction.EAST;
                default -> null;
            };
            if (d != null) {
                return d;
            }
            throw new com.xiaofeng.maidcli.transport.CmdException(
                    com.xiaofeng.maidcli.transport.CmdException.BAD_ARG,
                    "face 只能是 up/down/north/south/east/west（收到 " + face + "）");
        }
        Vec3 d = maid.position().subtract(Vec3.atCenterOf(pos));
        return Direction.getNearest(d.x, 0, d.z).getOpposite();
    }

    /** 这个坐标在不在手长内（place 要收 1 格余量） */
    public static double distanceTo(EntityMaid maid, BlockPos pos) {
        return Math.sqrt(maid.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
    }
}
