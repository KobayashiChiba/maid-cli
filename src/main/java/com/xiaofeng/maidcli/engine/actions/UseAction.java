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
 * 是"将要放置的那一格"（= 点击位置.relative(face)），**不是"我点的方块"** ⇒ 你会摸到隔壁那格。
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
    /** useAir 的准备阶段是否已跑过（换手 / 读时长 / 起手 只做一次） */
    private boolean prepared = false;
    /** 是否已结算（防止 stopUsingItem 后重新起手导致死循环） */
    private boolean finished = false;
    /** 本次实际使用时长（tick）—— 从物品自己的 getUseDuration 读，不给 duration 也正确 */
    private int effectiveDuration = -1;
    /** 手上物品有没有 FOOD 组件 —— 决定走哪条收尾路：
     *  有（牛奶/食物）⇒ 提前收手交给 TLM 的 eat()；没有（药水）⇒ 让 MC 自动完成（它会把手里的东西换成玻璃瓶） */
    /** 开始吃喝那一刻记下的物品身份（v0.0 EatOps 的做法：不靠事后判断，因为 finishUsingItem 会改栈） */
    private String startedId = null;
    private boolean startedIsPotion = false;
    private boolean startedIsMilk = false;
    /** 蜂蜜瓶 —— 和药水一样返还玻璃瓶，但它的 finishUsingItem 走 createFilledResult（对非玩家不给），得自己补 */
    private boolean startedIsHoney = false;
    /** 提前收手的 tick（= duration-3，抢在 MC 自动完成之前） */
    private int stopAt = -1;

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
        //   （读字节码确认过，顺序反了会出现"喂了牛但没繁殖"这类诡异现象）
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
     * 对空气使用（吃 / 喝 / 蓄力 / 投掷）。
     *
     * <p><b>核心：能吃/喝的直接交给 TLM 的
     * {@code EntityMaid.eat(Level, ItemStack, FoodProperties)}</b>。
     * 它内部会「应用食物效果 → 消耗 → 触发 {@code MaidAfterEatEvent}」，
     * 而 TLM 自己注册的 {@code ConvertFoodEatenEvent} / {@code RemainFoodEatenEvent}
     * 会按 {@code FoodProperties.usingConvertsTo()} / {@code getCraftingRemainingItem()}
     * 把玻璃瓶 / 空桶 / 碗塞回她背包 —— <b>补容器我们一行都不用写</b>。
     * （2026-10-26 读 TLM 1.5.3 字节码确认；此前自己算容器才导致反复出错）
     *
     * <p>没有 FoodProperties 的（投掷物 / 骨粉 / 弓…）走原版 {@code stack.use()} 瞬发。
     *
     * <p>三阶段：准备（一次）→ 等动画（duration tick）→ 结算（一次）。
     */
    private Phase useAir(ServerLevel lv, EntityMaid maid) {
        if (finished) {
            return Phase.DONE;
        }

        // ── ① 准备：只做一次 ──
        if (!prepared) {
            prepared = true;
            swapIfNeeded(maid);
            ItemStack stack = maid.getMainHandItem();
            if (stack.isEmpty()) {
                return fail("她手上没东西 —— 对空气右键要拿着能用的东西（吃的 / 药水 / 弓…）");
            }
            effectiveDuration = stack.getUseDuration(maid);
            // ★ 记下物品身份（此时手上是干净的原始物品）
            startedId = ItemOps.idOf(stack);
            startedIsPotion = stack.is(net.minecraft.world.item.Items.POTION)
                    || stack.is(net.minecraft.world.item.Items.SPLASH_POTION)
                    || stack.is(net.minecraft.world.item.Items.LINGERING_POTION);
            startedIsMilk = stack.is(net.minecraft.world.item.Items.MILK_BUCKET);
            startedIsHoney = stack.is(net.minecraft.world.item.Items.HONEY_BOTTLE);
            // ★ 提前 3 tick 收手：MC 的 updateUsingItem() 会在计时归零时【自动完成】，
            //   走原版路径把物品消耗掉，我们就拿不到"完好的物品"去做结算。
            stopAt = Math.max(1, effectiveDuration - 3);
            if (effectiveDuration <= 0) {
                // 瞬发类（投掷物 / 钓鱼 / 骨粉 / 烟花）：直接 use，产物走 salvage 捞回
                MaidFakePlayer fake = MaidFakePlayer.of(maid);
                var rh = stack.use(lv, fake, InteractionHand.MAIN_HAND);
                salvage(maid, fake);
                if (rh.getResult().consumesAction()) {
                    maid.swing(InteractionHand.MAIN_HAND);
                    return Phase.DONE;
                }
                return fail("这件东西对空气右键没反应");
            }
            // 长按类（吃 / 喝 / 蓄力）：起手，游戏里能看到动作
            maid.startUsingItem(InteractionHand.MAIN_HAND);
            useTicks = 0;
            return Phase.RUNNING;
        }

        // ── ② 等动画（★ 提前 3 tick 收手）──
        //   MC 的 LivingEntity.updateUsingItem() 会在 useItemRemaining 归零时【自动完成】：
        //   调 completeUsingItem → finishUsingItem → 物品被消耗，而且走原版路径、
        //   不经过 TLM 的 EntityMaid.eat() ⇒ 容器不会补（2026-10-26 诊断实证：
        //   结算时 getUseItem()/getMainHandItem() 双双为 null，物品早就没了）。
        //   ⇒ 必须在 MC 自动完成【之前】stopUsingItem，把完好的物品交给 maid.eat()。
        if (useTicks < stopAt) {
            useTicks++;
            return Phase.RUNNING;
        }

        // ── ③ 结算：只做一次（完整照搬 v0.0 EatOps.finish 的 2026-10-05 定稿逻辑）──
        //   为什么不用 maid.eat()：它走 TamableAnimal.eat → LivingEntity.eat，
        //   对【非玩家】只应用效果、**不 consume** ⇒ 物品还在手上，所有"消耗了没/该不该补容器"的判断全错位。
        //   v0.0 因此自己「finishUsingItem → 看手上还在不在 → 还在就手动扣 1 → 按类型补容器」。
        finished = true;
        maid.stopUsingItem();

        ItemStack inHand = maid.getMainHandItem();
        if (inHand.isEmpty()) {
            return Phase.DONE;
        }

        boolean willEat = startedId != null && startedId.equals(ItemOps.idOf(inHand));
        if (willEat) {
            inHand.finishUsingItem(lv, maid);      // 应用效果（消耗与否另判）
        }

        // 看消耗了没：手上那份还在 ⇒ 没消耗 ⇒ 自己扣
        ItemStack after = maid.getMainHandItem();
        boolean consumed = after.isEmpty()
                || startedId == null || !startedId.equals(ItemOps.idOf(after));
        if (!consumed && willEat) {
            after.shrink(1);
            consumed = true;
        }
        maid.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        // 容器：按【开始时记下的类型】补（原版对非玩家不给）
        ItemStack residue = ItemStack.EMPTY;
        if (consumed) {
            if (startedIsPotion || startedIsHoney) {
                residue = new ItemStack(net.minecraft.world.item.Items.GLASS_BOTTLE);
            } else if (startedIsMilk) {
                residue = new ItemStack(net.minecraft.world.item.Items.BUCKET);
            }
        }
        if (!residue.isEmpty()) {
            ItemOps.stash(maid, residue);          // stash 只进背包，不会像 getAvailableInv(false) 落到手槽
        }
        return Phase.DONE;
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
