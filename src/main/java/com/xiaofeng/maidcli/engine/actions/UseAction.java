package com.xiaofeng.maidcli.engine.actions;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.engine.Action;
import com.xiaofeng.maidcli.infra.MaidFakePlayer;
import com.xiaofeng.maidcli.ops.BlockOps;
import com.xiaofeng.maidcli.ops.ItemOps;
import com.xiaofeng.maidcli.ops.WalkOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 右键（COMMANDS.md §6.1 {@code use}）—— 覆盖：方块（放/开门/拉杆/打火石/骨粉）·
 * 实体（挤奶/剪毛/喂食/上船/装鞍/命名）· 空气（吃/喝/投掷/钓鱼/烟花/弓弩蓄力）。
 *
 * <p>⚠️⚠️ <b>对方块必须严格按原版玩家的【三步顺序】调</b>，顺序/环节错了表现是"她站着不动、门不开"：
 * <pre>
 * ① state.useItemOn(物品, level, 假玩家, 手, hit)        ← 方块对这件物品的反应
 * ② inHand.getItem().useOn(UseOnContext)                ← 物品自己的行为（★ 最容易漏）
 * ③ state.useWithoutItem(level, 假玩家, hit)            ← 无物品交互（门/活板门/拉杆/按钮）
 * </pre>
 * ⚠️ ② 漏了它，<b>营火+铲子在三步里全 PASS</b>（拍灭是【物品行为】，不是方块行为）。
 * <p>⚠️ 用 {@link UseOnContext} 而不是 {@code BlockPlaceContext}：后者的 {@code clickedPos}
 * 是"将要放置的那一格"（= 点击位置.relative(face)），**不是"被点击的方块"** ⇒ 实际操作的是隔壁那一格。
 * <p>⚠️ <b>「打开界面」不归 {@code use}</b> —— 那是 {@code open}。
 */
public final class UseAction extends Action {

    private final BlockPos pos;
    private final int entityId;
    private final String itemId;
    private final String face;
    private final int duration;      // -1 = 自然完成（成败类）；>0 = 指定 tick（蓄力类）
    private final String aim;

    private boolean handSwapped;
    private ItemStack backup;
    private int useTicks = -1;

    public UseAction(BlockPos pos, int entityId, String itemId, String face, int duration, String aim) {
        this.pos = pos;
        this.entityId = entityId;
        this.itemId = itemId;
        this.face = face;
        this.duration = duration;
        this.aim = aim;
    }

    @Override
    public Phase tick(ServerLevel lv, EntityMaid maid) {
        if (entityId > 0) {
            return useEntity(lv, maid);
        }
        if (pos != null) {
            return useBlock(lv, maid);
        }
        return useAir(lv, maid);
    }

    // ────────────────────────── 对方块 ──────────────────────────

    private Phase useBlock(ServerLevel lv, EntityMaid maid) {
        swapIfNeeded(maid);
        BlockState state = lv.getBlockState(pos);
        Direction dir = BlockOps.resolveFace(maid, pos, face);
        Vec3 hitVec = Vec3.atCenterOf(pos);
        BlockHitResult hit = new BlockHitResult(hitVec, dir, pos, false);
        MaidFakePlayer fake = MaidFakePlayer.of(maid);
        fake.setShiftKeyDown(false);          // ⚠️ 必须显式关潜行（FakePlayer 默认带潜行语义）
        WalkOps.faceTarget(maid, hitVec);

        ItemStack inHand = maid.getMainHandItem();
        boolean acted = false;
        ItemInteractionResult itemRes = ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!inHand.isEmpty()) {
            itemRes = state.useItemOn(inHand, lv, fake, InteractionHand.MAIN_HAND, hit);   // ①
        }
        if (itemRes.consumesAction()) {
            acted = true;
        } else if (!inHand.isEmpty()
                && itemRes == ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
            UseOnContext uc = new UseOnContext(fake, InteractionHand.MAIN_HAND, hit);
            InteractionResult r2 = inHand.getItem().useOn(uc);                            // ②
            if (r2.consumesAction()) {
                acted = true;
            } else {
                acted = state.useWithoutItem(lv, fake, hit).consumesAction();             // ③
            }
        } else {
            acted = state.useWithoutItem(lv, fake, hit).consumesAction();                 // ③（空手走这）
        }

        // 交互可能往假玩家背包塞东西 ⇒ 捞回来（不然物品丢了）
        salvage(maid, fake);

        if (!acted) {
            return fail("右键没产生任何效果（这个方块没反应 / 手上这件东西对它没用）");
        }
        maid.swing(InteractionHand.MAIN_HAND);
        return Phase.DONE;
    }

    // ────────────────────────── 对实体 ──────────────────────────

    private Phase useEntity(ServerLevel lv, EntityMaid maid) {
        Entity e = lv.getEntity(entityId);
        if (!(e instanceof LivingEntity le) || !le.isAlive()) {
            return fail("目标生物已经不在了（id=" + entityId + "）");
        }
        swapIfNeeded(maid);
        MaidFakePlayer fake = MaidFakePlayer.of(maid);
        fake.setShiftKeyDown(false);
        fake.setPos(maid.getX(), maid.getY(), maid.getZ());     // ⚠️ 对实体交互前同步位置
        WalkOps.faceEntity(maid, le);

        // ★ 照原版 Player.interactOn 的顺序：先【实体自己的】，没消费再【物品对实体】
 // （读字节码确认过，顺序反了会出现"喂了牛但没繁殖"这类诡异现象）
        InteractionResult res = le.interact(fake, InteractionHand.MAIN_HAND);
        if (!res.consumesAction()) {
            ItemStack stack = maid.getMainHandItem();
            if (!stack.isEmpty()) {
                res = stack.interactLivingEntity(fake, le, InteractionHand.MAIN_HAND);
            }
        }
        salvage(maid, fake);
        if (!res.consumesAction()) {
            return fail("右键没产生任何效果（这东西对这只有用吗？）");
        }
        maid.swing(InteractionHand.MAIN_HAND);
        return Phase.DONE;
    }

    // ────────────────────────── 对空气（吃 / 喝 / 蓄力） ──────────────────────────

    /**
     * ⚠️⚠️ <b>女仆不会自动完成「使用计时」</b>：{@code startUsingItem} 之后 isUsingItem 一直 true
     * 到超时，MC <b>从不</b>自动完成 ⇒ <b>必须我们自己调 {@code finishUsingItem}</b> 才会应用效果。
     */
    private Phase useAir(ServerLevel lv, EntityMaid maid) {
        swapIfNeeded(maid);
        ItemStack stack = maid.getMainHandItem();
        if (stack.isEmpty()) {
            return fail("她手上没东西 —— 对空气右键要拿着能用的东西（吃的 / 药水 / 弓…）");
        }
        if (duration > 0 && !maid.isUsingItem()) {
            maid.startUsingItem(InteractionHand.MAIN_HAND);     // 起手（有动画）
            useTicks = 0;
            return Phase.RUNNING;
        }
        if (maid.isUsingItem()) {
            useTicks++;
            if (duration > 0 && useTicks < duration) {
                return Phase.RUNNING;
            }
            // 完成（应用效果）—— 这是我们自己调的，MC 不会替我们做
            ItemStack inUse = maid.getUseItem();
            inUse.finishUsingItem(lv, maid);
            maid.stopUsingItem();
            // ⚠️ 非玩家实体的容器转换会被跳过（药水不给玻璃瓶 / 牛奶不给空桶）⇒ 按类型补一次
            containerRefund(maid, inUse);
            return Phase.DONE;
        }
        // 瞬发类（投掷物 / 钓鱼）：直接 use
        var rh = stack.use(lv, MaidFakePlayer.of(maid), InteractionHand.MAIN_HAND);
        if (rh.getResult().consumesAction()) {
            maid.swing(InteractionHand.MAIN_HAND);
            return Phase.DONE;
        }
        return fail("这件东西对空气右键没反应");
    }

    /** 非玩家实体的容器转换被原版跳过 ⇒ 主动补回容器 */
    private static void containerRefund(EntityMaid maid, ItemStack used) {
        try {
            String id = ItemOps.idOf(used);
            if (id == null) {
                return;
            }
            if (id.contains("potion")) {
                ItemOps.stash(maid, new ItemStack(net.minecraft.world.item.Items.GLASS_BOTTLE));
            } else if (id.contains("milk_bucket")) {
                ItemOps.stash(maid, new ItemStack(net.minecraft.world.item.Items.BUCKET));
            }
        } catch (Throwable ignored) {
        }
    }

    // ────────────────────────── 换手 / 收尾 ──────────────────────────

    private void swapIfNeeded(EntityMaid maid) {
        if (handSwapped) {
            return;
        }
        handSwapped = true;
        backup = ItemOps.takeToMainHand(maid, itemId, 1);
    }

    /** 交互可能把产物写进假玩家背包（挤奶得桶等）⇒ 捞回她身上 */
    private static void salvage(EntityMaid maid, MaidFakePlayer fake) {
        try {
            var fi = fake.getInventory();
            for (int i = 0; i < fi.getContainerSize(); i++) {
                ItemStack s = fi.getItem(i);
                if (!s.isEmpty()) {
                    fi.setItem(i, ItemStack.EMPTY);
                    ItemOps.stash(maid, s);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void abort() {
        if (handSwapped && backup != null) {
            // 不用恢复（use 是"用完就用完"），但要把手上剩的东西留住
            backup = null;
        }
    }

    @Override
    public String op() {
        return "use";
    }

    @Override
    public boolean needsReach() {
        return pos != null || entityId > 0;
    }

    @Override
    public BlockPos reachTarget() {
        return pos;
    }

    @Override
    public BlockPos targetPos() {
        return pos;
    }
}
