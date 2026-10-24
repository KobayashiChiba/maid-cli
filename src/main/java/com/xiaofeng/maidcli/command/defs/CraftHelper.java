package com.xiaofeng.maidcli.command.defs;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.command.MaidCmd;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code sense craftable} 的实现 —— 从她的背包推导"现有材料能造出什么"。
 *
 * <p>⚠️ <b>数量要用"实际产出"</b>，不是 {@code assemble(空输入)} 的试算值
 * （旧实现遇到过：木棍一次出 4 根却报成 1 个）。
 */
final class CraftHelper {

    private CraftHelper() {
    }

    /**
     * 列出能造的东西（记账式试算：先看能不能凑齐，凑不齐就跳过）。
     */
    static Map<String, Object> list(MaidCmd.Ctx ctx) {
        EntityMaid maid = ctx.maid();
        ServerLevel lv = (ServerLevel) maid.level();
        String filter = ctx.opt("filter");
        String item = ctx.opt("item");
        int times = Math.max(1, ctx.in("times", 1));

        // 背包可用材料清单
        List<ItemStack> bag = new ArrayList<>();
        var inv = maid.getAvailableBackpackInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.isEmpty()) {
                bag.add(s);
            }
        }
        bag.add(maid.getMainHandItem());
        bag.add(maid.getOffhandItem());

        List<Map<String, Object>> out = new ArrayList<>();
        for (var holder : lv.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = holder.value();
            ItemStack result;
            try {
                result = recipe.getResultItem(lv.registryAccess());
            } catch (Throwable t) {
                continue;
            }
            if (result.isEmpty()) {
                continue;
            }
            String rid = result.getItem().toString();
            if (item != null && !item.isBlank() && !rid.equals(item) && !rid.contains(item)) {
                continue;
            }
            if (filter != null && !filter.isBlank() && !rid.contains(filter)) {
                continue;
            }
            int can = howMany(recipe, bag, times);
            if (can <= 0) {
                continue;
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("result", rid);
            one.put("count", result.getCount());
            one.put("times", can);
            out.add(one);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("list", out);
        res.put("times_requested", times);
        return res;
    }

    /** 用记账数组试算"能造几份"（不动真背包） */
    private static int howMany(CraftingRecipe recipe, List<ItemStack> bag, int want) {
        List<Ingredient> ings;
        try {
            ings = recipe.getIngredients();
        } catch (Throwable t) {
            return 0;
        }
        if (ings.isEmpty()) {
            return 0;
        }
        // 可用数量记账（按物品构建）
        Map<String, Integer> avail = new LinkedHashMap<>();
        for (ItemStack s : bag) {
            if (s == null || s.isEmpty()) {
                continue;
            }
            avail.merge(s.getItem().toString(), s.getCount(), Integer::sum);
        }
        int made = 0;
        outer:
        for (int n = 0; n < want; n++) {
            Map<String, Integer> need = new LinkedHashMap<>();
            for (Ingredient ing : ings) {
                if (ing.isEmpty()) {
                    continue;      // 空格子
                }
                String pick = null;
                for (ItemStack cand : ing.getItems()) {
                    String cid = cand.getItem().toString();
                    if (avail.getOrDefault(cid, 0) - need.getOrDefault(cid, 0) > 0) {
                        pick = cid;
                        break;
                    }
                }
                if (pick == null) {
                    break outer;
                }
                need.merge(pick, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> e : need.entrySet()) {
                avail.merge(e.getKey(), -e.getValue(), Integer::sum);
            }
            made++;
        }
        return made;
    }
}
