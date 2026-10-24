package com.xiaofeng.maidcli.engine.actions;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.engine.Action;
import com.xiaofeng.maidcli.engine.MaidHandle;
import com.xiaofeng.maidcli.infra.MaidFakePlayer;
import com.xiaofeng.maidcli.ops.ItemOps;
import com.xiaofeng.maidcli.ops.SlotOps;
import com.xiaofeng.maidcli.ops.WalkOps;
import com.xiaofeng.maidcli.transport.CmdException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 宏（COMMANDS.md §6.4）—— 语法糖垃圾桶：凡是复合的 / 便利的 / 说不清归哪的，都往这儿扔。
 *
 * <p>⚠️ 和 {@link SlotAction} 同理：这 8 个宏都<b>同质</b>（一 tick 完成或几步就完、无进度），
 * 合成一个类用 {@code op} 区分，加新宏不用改调度器 ✓
 *
 * <p><b>三条规矩</b>（COMMANDS.md §6.4）：
 * <ol>
 *   <li>每个宏声明属"世界宏"还是"界面宏"—— 前置状态跟着走（不匹配报 {@code bad_state}）</li>
 *   <li>宏和原语一样"只受理"：进队列、做完写历史；<b>宏超时 = 写一条失败历史</b></li>
 *   <li>定位 = 语法糖垃圾桶</li>
 * </ol>
 */
public final class MacroAction extends Action {

    public enum Op {SWAP, HOLD, DROP, LOOK, DISMOUNT, GIVE, SORT, CRAFT}

    private final MaidHandle handle;
    private final Op op;
    private final String a;          // hold: from 槽位 / look: pos / craft: 产物 / give: item
    private final int count;         // drop/give/craft 的数量参数
    private Map<String, Object> lastResult;

    public MacroAction(MaidHandle handle, Op op, String a, int count) {
        this.handle = handle;
        this.op = op;
        this.a = a;
        this.count = count;
    }

    public Map<String, Object> result() {
        return lastResult;
    }

    @Override
    public Phase tick(ServerLevel lv, EntityMaid maid) {
        try {
 // ★ 界面门禁：世界宏 / 界面宏严格分开，不许混用
 // 界面宏（sort / craft）：必须先把界面打开
 // 世界宏（其余 6 个）：必须先把界面关掉
            boolean faceMacro = (op == Op.SORT || op == Op.CRAFT);
            boolean sessionOpen = handle.session != null;
            if (faceMacro && !sessionOpen) {
                return fail("macro " + op.name().toLowerCase()
                        + " 是【界面宏】—— 先用 open 打开界面（背包 / 容器 / 工位）");
            }
            if (!faceMacro && sessionOpen) {
                return fail("macro " + op.name().toLowerCase()
                        + " 是【世界宏】—— 界面开着时会互相干扰，先 close 再发");
            }
            lastResult = switch (op) {
                case SWAP -> swap(maid);
                case HOLD -> hold(maid);
                case DROP -> drop(maid);
                case LOOK -> look(lv, maid);
                case DISMOUNT -> dismount(lv, maid);
                case GIVE -> give(lv, maid);
                case SORT -> sort(maid);
                case CRAFT -> craft(lv, maid);
            };
            return Phase.DONE;
        } catch (CmdException e) {
            return fail(e.getMessage());
        } catch (Throwable t) {
            return fail("宏执行失败：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
        }
    }

    // ────────────────────────── swap（= 原版 F） ──────────────────────────

    private Map<String, Object> swap(EntityMaid maid) {
        ItemStack main = maid.getMainHandItem().copy();
        ItemStack off = maid.getOffhandItem().copy();
        ItemOps.swapHands(maid);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("main", ItemOps.idOf(maid.getMainHandItem()));
        o.put("off", ItemOps.idOf(maid.getOffhandItem()));
        o.put("message", "主副手换过了");
        return o;
    }

    // ────────────────────────── hold（某格 → 主手） ──────────────────────────

    private Map<String, Object> hold(EntityMaid maid) {
        if (a == null || a.isBlank()) {
            throw CmdException.badArg("hold 要给 from=<槽位>（把哪一格搬到主手）");
        }
        // ★ hold = 纯代码实现的"换手"，【完全不碰会话、不碰容器】
 // —— 就是"挖/放方块时的自动换手"那条路（ItemOps 的真交换），
 // 设计原因：宏只是"一组动作的入口"，不必都依赖会话 / 走位——
 // 像挖放方块时的自动换手那样纯代码实现即可，因此也不需要会话。
 // ⚠️ 原来这里调了 SlotOps.checkSyntax / SlotOps.parse ⇒ 会触发会话检查
 // ⇒ 背包满时想用 hold 清东西，报"界面没开"（实测踩到）
        int slot = parseInvSlot(a);
        IItemHandler inv = maid.getAvailableInv(true);
        if (slot < 0 || slot >= inv.getSlots()) {
            throw CmdException.badArg("槽号越界：" + a);
        }
        ItemStack want = inv.getStackInSlot(slot);
        if (want.isEmpty()) {
            throw CmdException.badArg("那一格是空的：" + a);
        }
        String before = ItemOps.idOf(maid.getMainHandItem());
        if (slot == 0) {
            throw CmdException.badArg("它本来就在主手");
        }
        if (slot == 1) {
            ItemOps.swapHands(maid);          // 主副手互换（原版 F）
        } else {
            // ★ 真交换：主手 ↔ 那一格（同"挖放的自动换手"，物品任何时刻只有一份）
            String id = BuiltInRegistries.ITEM.getKey(want.getItem()).toString();
            // ⚠️ 不要调 restoreMainHand！它是"收尾还原主手"（会把 backup 换回主手 = 撤销本次换手）。
 // takeToMainHand 内部已经把【原主手物】塞进背包了，主手现在就是目标物 ⇒ hold 语义已达成 ✓
 // （实测遇到过：多调这一句 ⇒ hold 报 done 但主手没变）
            ItemOps.takeToMainHand(maid, id, want.getCount());
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("main", ItemOps.idOf(maid.getMainHandItem()));
        o.put("previous", before);
        o.put("message", "主手现在是 " + ItemOps.idOf(maid.getMainHandItem()));
        return o;
    }

    /**
     * 解析 hold 的 from —— <b>只用背包槽位，不碰容器</b>（所以不需要会话）。
     * <p>支持：{@code inv:N} · {@code hand:main} · {@code hand:off}
     */
    private static int parseInvSlot(String a) {
        if (a == null || a.isBlank()) {
            throw CmdException.badArg("hold 要给 from=<槽位>（把哪一格搬到主手）");
        }
        String s = a.trim();
        if (s.equals("hand:main")) {
            return 0;
        }
        if (s.equals("hand:off")) {
            return 1;
        }
        if (s.startsWith("inv:")) {
            try {
                return Integer.parseInt(s.substring(4));
            } catch (NumberFormatException e) {
                throw CmdException.badArg("槽号不是数字：" + a);
            }
        }
        throw CmdException.badArg("hold 的 from 只支持 inv:N / hand:main / hand:off（hold 不碰容器，"
                + "要搬容器里的东西请先 open 再用 take/put）");
    }

    private static String itemIdOfSlot(EntityMaid maid, SlotOps.Ref r) {
        IItemHandler h = maid.getAvailableInv(true);
        if (r.slot() < 0 || r.slot() >= h.getSlots()) {
            throw CmdException.badArg("槽号越界：" + r.slot());
        }
        ItemStack s = h.getStackInSlot(r.slot());
        if (s.isEmpty()) {
            throw CmdException.badArg("那一格是空的");
        }
        return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }

    // ────────────────────────── drop（= 原版 Q） ──────────────────────────

    private Map<String, Object> drop(EntityMaid maid) {
        ItemStack out = ItemOps.dropMainHand(maid, count);
        Map<String, Object> o = new LinkedHashMap<>();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("item", ItemOps.idOf(out));
        d.put("count", out.getCount());
        o.put("dropped", d);
        o.put("message", "丢了 " + out.getCount() + " 个 " + ItemOps.idOf(out));
        return o;
    }

    // ────────────────────────── look（只转头） ──────────────────────────

    private Map<String, Object> look(ServerLevel lv, EntityMaid maid) {
        Vec3 target = null;
        if (a != null && !a.isBlank()) {
            if (a.startsWith("entity:")) {
                Entity e = lv.getEntity(Integer.parseInt(a.substring(7)));
                if (e != null) {
                    WalkOps.faceEntity(maid, e);
                    target = e.position();
                }
            } else {
                var pts = com.xiaofeng.maidcli.command.PosParse.parsePoints(a);
                if (!pts.isEmpty()) {
                    var p = pts.get(0);
                    target = new Vec3(p.x(), p.y(), p.z());
                    WalkOps.faceTarget(maid, target);
                }
            }
        }
        if (target == null) {
            throw CmdException.badArg("look 要给 pos=<坐标> / entity=<实体id> / owner=true");
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("looking_at", List.of(target.x, target.y, target.z));
        o.put("message", "看过去了");
        return o;
    }

    // ────────────────────────── dismount（下坐骑 + 找安全落脚点） ──────────────────────────

    /**
     * ⚠️ <b>落点必须"同层优先"</b> —— 别用 {@code findStandable}（那个是一路向下找，
     * 在平台上会让她穿过平台掉到下面 8 格，实测遇到过）。
     */
    private Map<String, Object> dismount(ServerLevel lv, EntityMaid maid) {
        Entity vehicle = maid.getVehicle();
        if (vehicle == null) {
            throw CmdException.badState("她没骑任何东西", "先 ride（用 use entity= 上坐骑）");
        }
        String was = BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).toString();
        BlockPos want = maid.blockPosition();
        BlockPos landing = null;
        // 同层就近（±2 格，含下一层）优先
        outer:
        for (int dy = 0; dy >= -2; dy--) {
            int r = dy == 0 ? 2 : 4;
            List<BlockPos> cand = new ArrayList<>();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > r) {
                        continue;
                    }
                    cand.add(want.offset(dx, dy, dz));
                }
            }
            cand.sort(Comparator.comparingInt(p -> Math.abs(p.getX() - want.getX()) + Math.abs(p.getZ() - want.getZ())));
            for (BlockPos p : cand) {
                if (WalkOps.isStandable(lv, p)) {
                    landing = p;
                    break outer;
                }
            }
        }
        maid.stopRiding();
        if (landing != null) {
            maid.randomTeleport(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5, true);
        } else {
            maid.spawnAtLocation(ItemStack.EMPTY);   // 不动（找不到落点就原地站着）
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("was_riding", was);
        if (landing != null) {
            o.put("landed", List.of(landing.getX(), landing.getY(), landing.getZ()));
        }
        o.put("message", "从 " + was + " 上下来了" + (landing == null ? "（没找到安全落点，原地站着）" : ""));
        return o;
    }

    // ────────────────────────── give（把东西交给主人） ──────────────────────────

    /**
     * ⚠️ 需要<b>主人在线</b>（离线没法塞东西）。
     * <p>⚠️ 不给 {@code item} = 把背包里能给的都给他；给了就<b>只给那个</b>
     * （旧实现有个坑：`all` 默认 true ⇒ 传 item 也被"全部"吃掉）。
     */
    private Map<String, Object> give(ServerLevel lv, EntityMaid maid) {
        ServerPlayer owner = lv.getServer().getPlayerList().getPlayer(maid.getOwnerUUID());
        if (owner == null) {
            throw CmdException.badState("她主人不在线（交接需要他在）", "等他上线再做，或者用 put to=box:");
        }
        IItemHandlerModifiable inv = (IItemHandlerModifiable) maid.getAvailableInv(true);
        List<Map<String, Object>> given = new ArrayList<>();
        int total = 0;
        int want = count <= 0 ? Integer.MAX_VALUE : count;
        // ⚠️ 从 0 开始（含主手/副手）—— 旧实现从 2 开始，导致"烟花在她主手时 give 报背包是空的"
        for (int i = 0; i < inv.getSlots() && total < want; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            if (a != null && !a.isBlank() && !id.equals(a) && !id.endsWith(":" + a)) {
                continue;
            }
            int n = Math.min(s.getCount(), want - total);
            ItemStack giving = s.copyWithCount(n);
            ItemStack left = net.neoforged.neoforge.items.ItemHandlerHelper
                    .insertItemStacked(new net.neoforged.neoforge.items.wrapper.InvWrapper(owner.getInventory()),
                            giving, false);
            int moved = n - left.getCount();
            if (moved > 0) {
                s.shrink(moved);
                if (s.isEmpty()) {
                    inv.setStackInSlot(i, ItemStack.EMPTY);
                }
                total += moved;
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("item", id);
                g.put("count", moved);
                given.add(g);
            }
            if (!left.isEmpty()) {
                // 他装不下 ⇒ 剩下的还留着（不丢）
                break;
            }
        }
        if (total == 0) {
            throw CmdException.badState("没什么可给的（背包是空的，或者他装不下）",
                    a == null ? null : "她身上没有 " + a);
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("given", given);
        o.put("message", "给了主人 " + total + " 件东西");
        return o;
    }

    // ────────────────────────── sort（合并同类 + 排序） ──────────────────────────

    private Map<String, Object> sort(EntityMaid maid) {
        // ★ 无参数：整理【当前打开的界面】（COMMANDS.md §6.4）
        com.xiaofeng.maidcli.engine.Session s = handle.session;
        if (s == null) {
            throw CmdException.badState("sort 要先把界面打开",
                    "open（打开她自己的背包）或 open pos=<坐标>（打开容器）");
        }
        if (!s.kind.canQuickMove()) {
            throw CmdException.badState(
                    "sort 不支持这个界面（" + s.kind.wire + "）",
                    "sort 只用于箱子类容器和她自己的背包；熔炉类的格子有语义（输入 / 燃料 / 输出），"
                            + "排序会把正在烧的东西挪走；工位是虚拟槽更不该整理");
        }
        boolean box = !s.isInv();               // 开的是她背包 → 整理背包；否则整理容器
        IItemHandler target = box ? s.container : maid.getAvailableBackpackInv();
        if (target == null) {
            throw CmdException.badState("这个界面没有可整理的槽位");
        }
        if (!(target instanceof IItemHandlerModifiable inv)) {
            throw CmdException.badState("这个槽位视图不可写");
        }
        int moved = sortHandler(inv);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("moved", moved);
        o.put("target", box ? "box" : "inv");
        o.put("message", "整理了（动了 " + moved + " 格）");
        return o;
    }

    /** 合并同类 + 按物品名排序 + 空槽后移；返回动过的格数 */
    private static int sortHandler(IItemHandlerModifiable inv) {
        int n = inv.getSlots();
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.isEmpty()) {
                items.add(s.copy());
            }
        }
        // 合并同类
        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack s : items) {
            boolean done = false;
            for (ItemStack m : merged) {
                if (ItemStack.isSameItemSameComponents(m, s) && m.getCount() < m.getMaxStackSize()) {
                    int can = Math.min(s.getCount(), m.getMaxStackSize() - m.getCount());
                    m.grow(can);
                    s.shrink(can);
                    if (s.isEmpty()) {
                        done = true;
                        break;
                    }
                }
            }
            if (!done && !s.isEmpty()) {
                merged.add(s);
            }
        }
        // 排序（按物品 id）
        merged.sort(Comparator.comparing(ItemStack::getItem, Comparator.comparing(Object::toString)));
        int moved = 0;
        for (int i = 0; i < n; i++) {
            ItemStack want = i < merged.size() ? merged.get(i) : ItemStack.EMPTY;
            ItemStack now = inv.getStackInSlot(i);
            if (!ItemStack.matches(now, want)) {
                inv.setStackInSlot(i, want);
                moved++;
            }
        }
        return moved;
    }

    // ────────────────────────── craft（合成） ──────────────────────────

    /**
     * 合成（界面宏 —— 前置要 open 工作台 / 背包）。
     *
     * <p>实现照旧 CraftOps 的思路：<b>在背包上记账式预分配 → getRecipeFor 复核 → 扣材料 → 给产物</b>。
     * <p>⚠️ <b>数量要用"实际产出"</b>，不是 {@code assemble(空输入)} 的试算值
     * （木棍一次出 4 根却报成 1 个 —— 遇到过）。
     * <p>⚠️ <b>必须返还容器 / 剩余物</b>（用桶合成后空桶要还 —— 原版玩家是自动还的）。
     */
    private Map<String, Object> craft(ServerLevel lv, EntityMaid maid) {
        if (handle.session == null) {
            throw CmdException.badState("craft 要先 open（工作台 / 自己的背包）",
                    "open（开背包 2×2）或 open pos=<工作台>");
        }
        if (a == null || a.isBlank()) {
            throw CmdException.badArg("craft 要给 item=<产物>");
        }
        IItemHandlerModifiable bag = (IItemHandlerModifiable) maid.getAvailableInv(true);
        int times = count <= 0 ? 1 : count;
        int made = 0;
        List<String> missing = new ArrayList<>();
        for (int t = 0; t < times; t++) {
            var found = findRecipe(lv, maid, a, bag);
            if (found.isEmpty()) {
                if (t == 0) {
                    throw CmdException.badState("造不出 " + a + "（材料不够 / 没这个配方）",
                            "用 sense craftable 看现有材料能造什么");
                }
                missing.add("第 " + (t + 1) + " 次：材料不够");
                break;
            }
            CraftingRecipe recipe = found.get().recipe();
            // 扣材料（记账式已经核对过，这里真扣）
            for (Ingredient ing : recipe.getIngredients()) {
                if (ing.isEmpty()) {
                    continue;
                }
                consumeOne(bag, ing);
            }
            // 产物（★ 用实际产出数量）
            ItemStack out;
            try {
                out = recipe.getResultItem(lv.registryAccess()).copy();
            } catch (Throwable ex) {
                out = recipe.getResultItem(lv.registryAccess());
            }
            if (!out.isEmpty()) {
                ItemOps.stash(maid, out);
                made += out.getCount();
            }
            // ★ 返还容器 / 剩余物（桶 / 瓶子 —— 原版玩家是自动还的）
            try {
                for (ItemStack rem : recipe.getRemainingItems(craftInput(recipe))) {
                    if (!rem.isEmpty()) {
                        ItemOps.stash(maid, rem);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("made", made);
        o.put("times", times);
        o.put("missing", missing);
        o.put("message", "造了 " + made + " 个" + (missing.isEmpty() ? "" : "（中途材料不够）"));
        return o;
    }

    private record Found(CraftingRecipe recipe, List<Ingredient> ings) {
    }

    /** 找一个"产物匹配 + 材料够"的配方（不动真背包，只记账） */
    private Optional<Found> findRecipe(ServerLevel lv, EntityMaid maid, String want, IItemHandler bag) {
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
            String id = BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
            if (!id.equals(want) && !id.endsWith(":" + want) && !id.contains(want)) {
                continue;
            }
            List<Ingredient> ings = recipe.getIngredients();
            if (canCraft(bag, ings)) {
                return Optional.of(new Found(recipe, ings));
            }
        }
        return Optional.empty();
    }

    /** 记账式试算：这套材料够造一份吗（不动真背包） */
    private boolean canCraft(IItemHandler bag, List<Ingredient> ings) {
        Map<String, Integer> avail = new LinkedHashMap<>();
        for (int i = 0; i < bag.getSlots(); i++) {
            ItemStack s = bag.getStackInSlot(i);
            if (!s.isEmpty()) {
                avail.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum);
            }
        }
        Map<String, Integer> need = new LinkedHashMap<>();
        for (Ingredient ing : ings) {
            if (ing.isEmpty()) {
                continue;
            }
            String pick = null;
            for (ItemStack cand : ing.getItems()) {
                String cid = BuiltInRegistries.ITEM.getKey(cand.getItem()).toString();
                if (avail.getOrDefault(cid, 0) - need.getOrDefault(cid, 0) > 0) {
                    pick = cid;
                    break;
                }
            }
            if (pick == null) {
                return false;
            }
            need.merge(pick, 1, Integer::sum);
        }
        return true;
    }

    /** 真扣一份材料 */
    private void consumeOne(IItemHandlerModifiable bag, Ingredient ing) {
        for (int i = 0; i < bag.getSlots(); i++) {
            ItemStack s = bag.getStackInSlot(i);
            if (s.isEmpty() || !ing.test(s)) {
                continue;
            }
            s.shrink(1);
            if (s.isEmpty()) {
                bag.setStackInSlot(i, ItemStack.EMPTY);
            }
            return;
        }
    }

    /** 给 getRemainingItems 用的占位输入（原版只看物品类型，不看数量） */
    private net.minecraft.world.item.crafting.CraftingInput craftInput(CraftingRecipe recipe) {
        int w = 3, h = 3;
        if (recipe instanceof net.minecraft.world.item.crafting.ShapedRecipe sr) {
            w = sr.getWidth();
            h = sr.getHeight();
        }
        List<ItemStack> grid = new ArrayList<>();
        for (Ingredient ing : recipe.getIngredients()) {
            grid.add(ing.isEmpty() ? ItemStack.EMPTY : ing.getItems()[0].copy());
        }
        while (grid.size() < w * h) {
            grid.add(ItemStack.EMPTY);
        }
        return net.minecraft.world.item.crafting.CraftingInput.of(w, h, grid);
    }

    @Override
    public String op() {
        return switch (op) {
            case SWAP -> "macro_swap";
            case HOLD -> "macro_hold";
            case DROP -> "macro_drop";
            case LOOK -> "macro_look";
            case DISMOUNT -> "macro_dismount";
            case GIVE -> "macro_give";
            case SORT -> "macro_sort";
            case CRAFT -> "macro_craft";
        };
    }
}
