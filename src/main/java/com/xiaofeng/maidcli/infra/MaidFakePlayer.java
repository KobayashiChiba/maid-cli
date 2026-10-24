package com.xiaofeng.maidcli.infra;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把女仆"包装成一个玩家"，好让<b>原版逻辑直接作用在她身上</b>（原版机制复用）。
 *
 * <p>原版的挖掘效率、放置判定（朝向/可替换/草被顶掉）、交互入口都写在
 * {@code Player} / {@code BlockItem} 里，它们只认 {@code Player}。
 * 所以造一个"假玩家"，把位置 / 手持 / 药水 / 水下空中状态全部<b>转发给女仆本体</b> ——
 * 原版看到的就是"一个站在女仆位置、拿着女仆工具、带着女仆药水的玩家"。
 * <p>于是挖掘速度自动含<b>效率附魔 / 急迫·挖掘疲劳 / 水下 / 空中</b>修正（原版公式，我们一行不抄）。
 */
public class MaidFakePlayer extends FakePlayer {

    private static final Map<UUID, MaidFakePlayer> CACHE = new ConcurrentHashMap<>();

    // ⚠️⚠️ 【必须】每个覆写方法都判 maid == null！
 // 因为 FakePlayer(ServerPlayer) 的【父类构造函数内部】就会调这些方法（如 level()），
 // 那时 this.maid 还没赋值 → NPE。表现：动作 0.2 秒消失 + 日志一条 NPE（查了四轮才定位）
    private final EntityMaid maid;

    /** 每个女仆一个（缓存），她死了就重建 */
    public static MaidFakePlayer of(EntityMaid maid) {
        MaidFakePlayer cached = CACHE.get(maid.getUUID());
        MaidFakePlayer target;
        if (cached != null && cached.maid.isAlive()) {
            target = cached;
        } else {
            target = new MaidFakePlayer(maid);
            CACHE.put(maid.getUUID(), target);
        }
        // 每次取用同步两件事（Entity.getX/getY/getZ 是 final、Player.inventory 也是 final，只能这样借身体）：
 // ① 位置挪到女仆身上
 // ② 把女仆手上的工具塞进假玩家的选中槽
        target.setPos(maid.getX(), maid.getY(), maid.getZ());
        // ⚠️ 关键：原版 Player.getDestroySpeed() 读的是【快捷栏选中槽】，不是 getMainHandItem()
 // ⇒ 不写这里，原版看到的是空手、进度算成 0（实测：挖黑曜石 0.6 秒就"完成"）
        target.getInventory().selected = 0;
        target.getInventory().setItem(0, maid.getMainHandItem());
        return target;
    }

    /**
     * 给附魔台用：设置附魔种子（调用方按她的标签算，保证稳定）。
     * <p>原版把种子存在玩家身上（同一玩家反复开界面档位不变）。我们每次命令都新建 Menu
     * ⇒ 种子载体没了 ⇒ 必须手动喂一个<b>稳定</b>的种子，否则"先看档位、再选档位"两次调用对不上。
     */
    public void setEnchantSeed(int seed) {
        this.enchantmentSeed = seed;
    }

    private MaidFakePlayer(EntityMaid maid) {
        super((ServerLevel) maid.level(),
                new GameProfile(UUID.randomUUID(), maid.getName().getString()));
        this.maid = maid;
    }

    // ────────────────── 位置 ──────────────────

    @Override
    public Vec3 position() {
        return maid == null ? super.position() : maid.position();
    }

    @Override
    public BlockPos blockPosition() {
        return maid == null ? super.blockPosition() : maid.blockPosition();
    }

    @Override
    public Level level() {
        return maid == null ? super.level() : maid.level();
    }

    @Override
    public ServerLevel serverLevel() {
        return maid == null ? super.serverLevel() : (ServerLevel) maid.level();
    }

    // Entity.getX/getY/getZ 在 1.21 是 final，覆写不了 ⇒ 靠 of() 里每次 setPos

    // ────────────────── 手持物品 ──────────────────

    @Override
    public ItemStack getMainHandItem() {
        return maid == null ? super.getMainHandItem() : maid.getMainHandItem();
    }

    @Override
    public ItemStack getItemInHand(InteractionHand hand) {
        return maid == null ? super.getItemInHand(hand) : maid.getItemInHand(hand);
    }

    // ★★ 补上【写】的方向：原来只转发"读"，于是原版里
 // `player.setItemInHand(hand, new ItemStack(MILK_BUCKET))` 这类代码把产物写进【假玩家自己】的背包，
 // 而 of() 每次都把假玩家槽 0 覆盖成女仆的手持 ⇒ 产物当场被抹掉
 // （现象：桶消耗了、牛奶却拿不到）。覆写后，"给玩家东西"的原版逻辑直接落到她身上。
    @Override
    public void setItemInHand(InteractionHand hand, ItemStack stack) {
        if (maid == null) {
            super.setItemInHand(hand, stack);
            return;
        }
        maid.setItemInHand(hand, stack);
    }

    @Override
    public void setItemSlot(net.minecraft.world.entity.EquipmentSlot slot, ItemStack stack) {
        if (maid == null) {
            super.setItemSlot(slot, stack);
            return;
        }
        maid.setItemSlot(slot, stack);
    }

    // ────────────────── 交互状态：明确"普通右键" ──────────────────

    // ⚠️ NeoForge 的 FakePlayer 默认带"潜行/次要使用"语义（它被设计来模拟 Shift 强放），
 // 于是拿铲子右键营火时 ShovelItem 直接 PASS（原版前置条件里有 isSecondaryUseActive()）。
 // 我们做的是"普通右键交互"，必须显式说清自己没在潜行。
    @Override
    public boolean isSecondaryUseActive() {
        return false;
    }

    @Override
    public boolean isShiftKeyDown() {
        return false;
    }

    @Override
    public boolean isCrouching() {
        return false;
    }

    // ────────────────── 药水 / 环境（挖掘速度的修正项） ──────────────────

    @Override
    public boolean hasEffect(net.minecraft.core.Holder<MobEffect> effect) {
        return maid == null ? super.hasEffect(effect) : maid.hasEffect(effect);
    }

    @Override
    public MobEffectInstance getEffect(net.minecraft.core.Holder<MobEffect> effect) {
        return maid == null ? super.getEffect(effect) : maid.getEffect(effect);
    }

    @Override
    public boolean isEyeInFluid(TagKey<Fluid> tag) {
        return maid == null ? super.isEyeInFluid(tag) : maid.isEyeInFluid(tag);
    }

    @Override
    public boolean onGround() {
        return maid == null ? super.onGround() : maid.onGround();
    }

    // ────────────────── 属性（1.21 里"效率附魔"走属性！） ──────────────────

    /**
     * 挖掘相关属性转发给女仆；女仆没有的（玩家专属属性）按她的装备折算。
     *
     * <p>原版 {@code Player.getDestroySpeed} 的逻辑：
     * <pre>
     *   f  = inventory.getDestroySpeed(state)      // 工具材质基础速度
     *   f += getAttributeValue(MINING_EFFICIENCY)  // 效率附魔（1.21 起改成属性了）
     *   if (急迫)     f *= 1 + (等级+1) * 0.2
     *   if (挖掘疲劳) f *= 0.3 / 0.09 / …
     *   if (在水里且无水下速掘) f /= 5
     *   if (不在地面) f /= 5
     * </pre>
     * <p>MINING_EFFICIENCY 是玩家专属属性，女仆没有 ⇒ 直接转发抛 {@code Can't find attribute}
     * ⇒ 改成按主手的效率附魔等级折算（1.21 里每级 +1），这样"拿附魔镐挖得更快"就跟玩家一致。
     */
    @Override
    public double getAttributeValue(net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr) {
        if (maid == null) {
            return super.getAttributeValue(attr);
        }
        if (attr.is(net.minecraft.world.entity.ai.attributes.Attributes.MINING_EFFICIENCY)) {
            var inst = maid.getAttribute(attr);
            if (inst != null) {
                return inst.getValue();
            }
            var reg = maid.level().registryAccess()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
            var eff = reg.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.EFFICIENCY);
            return net.minecraft.world.item.enchantment.EnchantmentHelper
                    .getItemEnchantmentLevel(eff, maid.getMainHandItem());
        }
        if (attr.is(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_BREAK_SPEED)
                || attr.is(net.minecraft.world.entity.ai.attributes.Attributes.SUBMERGED_MINING_SPEED)
                || attr.is(net.minecraft.world.entity.ai.attributes.Attributes.SNEAKING_SPEED)) {
            var inst = maid.getAttribute(attr);
            if (inst != null) {
                return inst.getValue();
            }
        }
        return super.getAttributeValue(attr);
    }

    public EntityMaid maid() {
        return maid;
    }
}
