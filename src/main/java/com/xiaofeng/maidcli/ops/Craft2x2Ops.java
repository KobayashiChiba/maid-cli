package com.xiaofeng.maidcli.ops;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.engine.Session;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 她自己的背包界面里的【随身 2×2 合成格】。
 *
 * <p>玩家背包界面 = 左边背包 + 右边 2×2 合成格。我们的会话里对应：
 * <ul>
 *   <li>{@code inv:N} → 她的背包（含主手 / 副手 / 展示栏）</li>
 *   <li>{@code box:0..3} → 2×2 的四个材料格</li>
 *   <li>{@code box:4} → 结果格（<b>只读</b>）</li>
 * </ul>
 *
 * <p>★★ <b>：流程对齐工位（用户拍板"2×2 和 3×3 统一"）</b> ——
 * 原来材料一变就自动把产物写进结果格、`take` 时扣料；现在改成和 3×3 工作台一模一样：
 * <pre>
 *   摆料 → select（= 结算：算配方 → 写结果格 → 每格材料扣 1）→ take from=box:4
 * </pre>
 * 好处：两种合成台只有一套流程，用户 / LLM 都不用记"这个要不要 select"。
 *
 * <p>⚠️ 因此 {@code take from=box:4} 只是<b>搬运</b>：材料早在 select 时就扣了。
 * 摆好料不 select 直接 close ⇒ 产物作废、材料原样退回（不白嫖、也不丢料）。
 */
public final class Craft2x2Ops {

    /** 2×2 的材料格数 */
    public static final int MATERIAL_SLOTS = 4;
    /** 结果格的下标 */
    public static final int RESULT_SLOT = 4;

    private Craft2x2Ops() {
    }

    /** 造一个 5 格的虚拟合成格视图（材料格可读写；结果格只读，由 select 结算写入） */
    public static IItemHandlerModifiable view(EntityMaid maid, Session s) {
        return new IItemHandlerModifiable() {
            @Override
            public int getSlots() {
                return RESULT_SLOT + 1;
            }

            @Override
            public ItemStack getStackInSlot(int slot) {
                if (slot < 0 || slot > RESULT_SLOT) {
                    return ItemStack.EMPTY;
                }
                ItemStack v = s.virtual.get(slot);
                return v == null ? ItemStack.EMPTY : v;
            }

            @Override
            public void setStackInSlot(int slot, ItemStack stack) {
                if (slot < 0 || slot > RESULT_SLOT) {
                    throw new IllegalArgumentException("随身合成格只有 5 格（0-3 材料 / 4 结果）");
                }
                if (slot == RESULT_SLOT) {
                    return;                 // 结果格由 select 结算写；外部写不进来
                }
                s.virtual.put(slot, stack == null ? ItemStack.EMPTY : stack);
                invalidateResult(s);        // 材料一变，之前没结算的产物作废
            }

            @Override
            public int getSlotLimit(int slot) {
                return 64;
            }

            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return slot >= 0 && slot < MATERIAL_SLOTS && stack != null && !stack.isEmpty();
            }

            @Override
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                if (!isItemValid(slot, stack)) {
                    return stack;
                }
                ItemStack there = getStackInSlot(slot);
                if (there.isEmpty()) {
                    if (!simulate) {
                        setStackInSlot(slot, stack.copy());
                    }
                    return ItemStack.EMPTY;
                }
                if (ItemStack.isSameItemSameComponents(there, stack)) {
                    int can = Math.min(stack.getCount(), there.getMaxStackSize() - there.getCount());
                    if (can <= 0) {
                        return stack;
                    }
                    if (!simulate) {
                        ItemStack merged = there.copy();
                        merged.grow(can);
                        setStackInSlot(slot, merged);
                    }
                    ItemStack rest = stack.copy();
                    rest.shrink(can);
                    return rest;
                }
                return stack;
            }

            /**
             * 取东西：材料格 = 普通搬运；结果格 = 纯搬运（材料早在 select 时扣过了）。
             */
            @Override
            public ItemStack extractItem(int slot, int amount, boolean simulate) {
                ItemStack there = getStackInSlot(slot);
                if (there.isEmpty() || amount <= 0) {
                    return ItemStack.EMPTY;
                }
                ItemStack out = there.copyWithCount(Math.min(amount, there.getCount()));
                if (simulate) {
                    return out;
                }
                if (slot == RESULT_SLOT) {
                    s.virtual.put(RESULT_SLOT, ItemStack.EMPTY);   // 结算过的产物拿走了
                    return out;
                }
                there.shrink(out.getCount());
                s.virtual.put(slot, there.isEmpty() ? ItemStack.EMPTY : there);
                invalidateResult(s);
                return out;
            }
        };
    }

    /** 材料变了 ⇒ 之前没结算的产物作废（结果格清空） */
    private static void invalidateResult(Session s) {
        s.virtual.put(RESULT_SLOT, ItemStack.EMPTY);
    }

    /**
     * <p><b>结算</b>：算 2×2 配方 → 产物写进 {@code virtual[4]} → 每格材料扣 1。
     *
     * <p>语义与工位的 {@code StationOps.pullResult} 完全一致，所以 {@code select} 对两边是同一套流程。
     *
     * @return {@code {ok, got, message}}；算不出就 {@code ok=false}（材料原样留着）
     */
    public static Map<String, Object> settle(EntityMaid maid, Session s) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (s == null) {
            out.put("ok", false);
            out.put("message", "没有会话");
            return out;
        }
        ItemStack product = computeResult(maid, s);
        if (product.isEmpty()) {
            out.put("ok", false);
            out.put("message", "算不出结果（2×2 里拼不出配方）—— 材料原样留着（一样没动）");
            return out;
        }
        s.virtual.put(RESULT_SLOT, product.copy());
        // 每格材料扣 1（和原版 ResultSlot.onTake 一致）
        for (int i = 0; i < MATERIAL_SLOTS; i++) {
            ItemStack m = s.virtual.get(i);
            if (m != null && !m.isEmpty()) {
                m.shrink(1);
                s.virtual.put(i, m.isEmpty() ? ItemStack.EMPTY : m);
            }
        }
        out.put("ok", true);
        out.put("got", Map.of("item", product.getItem().toString(), "count", product.getCount()));
        out.put("message", "产出 " + product.getItem() + " ×" + product.getCount()
                + "（在 box:" + RESULT_SLOT + "；料已消耗）");
        return out;
    }

    /** 只算不写（预览用）：2×2 摆出什么 */
    public static ItemStack computeResult(EntityMaid maid, Session s) {
        try {
            List<ItemStack> grid = new ArrayList<>(MATERIAL_SLOTS);
            boolean any = false;
            for (int i = 0; i < MATERIAL_SLOTS; i++) {
                ItemStack v = s.virtual.get(i);
                ItemStack c = (v == null || v.isEmpty()) ? ItemStack.EMPTY : v.copy();
                if (!c.isEmpty()) {
                    any = true;
                }
                grid.add(c);
            }
            if (!any) {
                return ItemStack.EMPTY;
            }
            CraftingInput in = CraftingInput.of(2, 2, grid);
            var level = maid.level();
            var found = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, in, level);
            if (found.isEmpty()) {
                return ItemStack.EMPTY;
            }
            CraftingRecipe recipe = found.get().value();
            ItemStack result = recipe.assemble(in, level.registryAccess());
            return result.isEmpty() ? ItemStack.EMPTY : result;
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }
}
