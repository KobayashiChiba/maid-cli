package com.xiaofeng.maidcli.ops;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xiaofeng.maidcli.engine.MaidHandle;
import com.xiaofeng.maidcli.engine.Session;
import com.xiaofeng.maidcli.transport.CmdException;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 槽位统一 API（COMMANDS.md §2.6 · "一切皆槽位引用"）。
 *
 * <p><b>核心模型 = 照抄玩家的「鼠标槽（cursor）」</b>：
 * <pre>
 * inv:N      她的背包（0=主手 1=副手 2..=背包）—— 世界概念，一直存在
 * hand:main  = inv:0      hand:off = inv:1
 * box:N      当前打开的那个容器第 N 格
 * ground     外面（只作为 put 的目标）
 * </pre>
 *
 * <p>⚠️ <b>鼠标槽（cursor）≠ 主手</b>：cursor 是【界面概念】（只有界面开着才有，take/put 操作它）；
 * 主手 inv:0 是【世界概念】（宏 drop/place 用它）。
 *
 * <p>⚠️⚠️ <b>物品守恒四条红线</b>（任何一条破了就是"东西凭空消失"）：
 * <ol>
 *   <li>{@code split} 是原子的 —— 先取出、再放入的两步操作必须处理"放不回去"</li>
 *   <li>塞不下就 {@code spawnAtLocation} 掉地上，**绝不吞**</li>
 *   <li>move 前先 dry-run 算容量（保证"要么全搬、要么一个都没动"）</li>
 *   <li>先记物品名，再 split（split 会把它掏空 —— 老坑）</li>
 * </ol>
 */
public final class SlotOps {

    /** 送进"她那一侧"时要跳过的槽 —— 材料不该进主手/副手/展示栏（老规矩） */
    public static final Set<Integer> NOT_FOR_MATERIAL = Set.of(0, 1, 7);

    private SlotOps() {
    }

    /** 解析后的槽位引用 */
    public record Ref(Kind kind, int slot) {
        public boolean isBox() {
            return kind == Kind.BOX;
        }
    }

    /** 槽位视图的种类 */
    public enum Kind {
        /** 她的统一背包视图（0=主手 1=副手 2..=背包 7=展示栏） */
        MAID,
        /** 当前打开的容器 */
        BOX,
        /** 护甲四格（0=feet 1=legs 2=chest 3=head） */
        ARMOR,
        /** TLM 饰品栏（0..29） */
        BAUBLE
    }

    /** 护甲格：名字 ↔ 索引（顺序照原版 EquipmentSlot，脚在前） */
    private static final List<String> ARMOR_SLOTS = List.of("feet", "legs", "chest", "head");

    private static final String SYNTAX_HINT =
            "写法：inv:N · hand:main · hand:off · armor:head|chest|legs|feet · bauble:N · box:N · ground";

    // ────────────────────────── 解析 ──────────────────────────

    /** 语法检查（命令层的 precheck 用）—— 只查格式，不查范围 */
    public static void checkSyntax(String ref, String what) {
        if (ref == null || ref.isBlank()) {
            throw CmdException.badArg(what + " 不能为空");
        }
        String s = ref.trim().toLowerCase();
        if (s.equals("ground")) {
            return;
        }
        int colon = s.indexOf(':');
        if (colon < 0) {
            throw CmdException.badArg(what + " 少了冒号和槽号：" + ref, SYNTAX_HINT);
        }
        String target = s.substring(0, colon);
        String tail = s.substring(colon + 1);
        switch (target) {
            case "inv", "box", "bauble" -> {
                if (!tail.matches("\\d+")) {
                    throw CmdException.badArg(what + " 的槽号要是数字：" + ref, SYNTAX_HINT);
                }
            }
            case "hand" -> {
                if (!tail.equals("main") && !tail.equals("off")) {
                    throw CmdException.badArg(what + " 的手槽只能是 main / off：" + ref, SYNTAX_HINT);
                }
            }
            case "armor" -> {
                if (!ARMOR_SLOTS.contains(tail)) {
                    throw CmdException.badArg(what + " 的护甲格只能是 head / chest / legs / feet：" + ref,
                            "例：armor:head · armor:chest · armor:legs · armor:feet");
                }
            }
            case "owner" -> throw CmdException.badArg("owner: 槽位本期不提供：" + ref,
                    "主人背包不能直接读写 —— 要把东西交给主人用 macro give");
            default -> throw CmdException.badArg("不认识的槽位类型「" + target + "」：" + ref, SYNTAX_HINT);
        }
    }

    /** 解析引用（range 检查交给 {@link #handler}） */
    public static Ref parse(String ref) {
        String s = ref.trim().toLowerCase();
        if (s.equals("hand:main")) {
            return new Ref(Kind.MAID, 0);
        }
        if (s.equals("hand:off")) {
            return new Ref(Kind.MAID, 1);
        }
        int colon = s.indexOf(':');
        if (colon < 0) {
            throw CmdException.badArg("槽位要带槽号：" + ref, SYNTAX_HINT);
        }
        String target = s.substring(0, colon);
        String tail = s.substring(colon + 1);
        if (target.equals("armor")) {
            int i = ARMOR_SLOTS.indexOf(tail);
            if (i < 0) {
                throw CmdException.badArg("护甲格只能是 head / chest / legs / feet：" + ref,
                        "例：armor:head · armor:chest · armor:legs · armor:feet");
            }
            return new Ref(Kind.ARMOR, i);
        }
        int slot;
        try {
            slot = Integer.parseInt(tail);
        } catch (NumberFormatException e) {
            throw CmdException.badArg("槽号要数字：" + ref, SYNTAX_HINT);
        }
        return switch (target) {
            case "inv" -> new Ref(Kind.MAID, slot);
            case "box" -> new Ref(Kind.BOX, slot);
            case "bauble" -> new Ref(Kind.BAUBLE, slot);
            case "owner" -> throw CmdException.badArg("owner: 槽位本期不提供：" + ref,
                    "主人背包不能直接读写 —— 要把东西交给主人用 macro give");
            default -> throw CmdException.badArg("不认识的槽位类型「" + target + "」：" + ref, SYNTAX_HINT);
        };
    }

    /**
     * 取引用对应的槽位视图。
     * <p>⚠️ {@code box} 的坐标来自<b>当前打开的会话</b>（设计原因：move/slots 只和"已打开的方块"交互，
     * 不用写坐标）；没开会话就报错。
     */
    public static IItemHandler handler(EntityMaid maid, MaidHandle h, Ref r) {
        switch (r.kind()) {
            case MAID -> {
                IItemHandler inv = maid.getAvailableInv(true);   // 0=主手 1=副手 2..=背包
                if (r.slot() < 0 || r.slot() >= inv.getSlots()) {
                    throw CmdException.badArg("槽号越界：inv:" + r.slot()
                            + "（她有 " + inv.getSlots() + " 格）");
                }
                return inv;
            }
            case ARMOR -> {
                if (r.slot() < 0 || r.slot() >= ARMOR_SLOTS.size()) {
                    throw CmdException.badArg("护甲格越界：" + r.slot());
                }
                return new ArmorView(maid);
            }
            case BAUBLE -> {
                IItemHandler bh = maid.getMaidBauble();          // TLM：BaubleItemHandler extends ItemStackHandler
                if (r.slot() < 0 || r.slot() >= bh.getSlots()) {
                    throw CmdException.badArg("槽号越界：bauble:" + r.slot()
                            + "（她的饰品栏有 " + bh.getSlots() + " 格）");
                }
                return bh;
            }
            case BOX -> {
                Session s = h.session;
                if (s == null || s.container == null) {
                    throw CmdException.badState("还没打开任何容器 —— 先 open <坐标>",
                            "open pos=x,y,z");
                }
                if (r.slot() < 0 || r.slot() >= s.container.getSlots()) {
                    throw CmdException.badArg("槽号越界：box:" + r.slot()
                            + "（这个容器有 " + s.container.getSlots() + " 格）");
                }
                return s.container;
            }
        }
        throw CmdException.badArg("槽位解析失败：" + r);
    }

    /**
     * 护甲格视图：把 4 个装备槽包成 {@link IItemHandler}，让 take / put / move / slots 一视同仁。
     * <p>索引顺序照原版：0=feet 1=legs 2=chest 3=head。
     * <p>⚠️ 直接 {@code setItemSlot} 不会触发原版 {@code Item.onEquip} 钩子（玩家换甲会触发）。
     * 原版护甲属性是动态读装备算的，所以属性正常生效；只是少数模组钩子收不到该事件。
     */
    static final class ArmorView implements IItemHandlerModifiable {
        private static final EquipmentSlot[] SLOTS = {
                EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
        };

        private final EntityMaid maid;

        ArmorView(EntityMaid maid) {
            this.maid = maid;
        }

        @Override
        public int getSlots() {
            return SLOTS.length;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return maid.getItemBySlot(SLOTS[slot]);
        }

        @Override
        public void setStackInSlot(int slot, ItemStack stack) {
            maid.setItemSlot(SLOTS[slot], stack == null ? ItemStack.EMPTY : stack);
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;                 // 每格只穿一件
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (stack.isEmpty()) {
                return false;
            }
            // 1.21 起没有 ItemStack.canEquip —— 装备部位由 Equippable 组件决定
            net.minecraft.world.item.Equipable eq = net.minecraft.world.item.Equipable.get(stack);
            return eq != null && eq.getEquipmentSlot() == SLOTS[slot];
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty() || !isItemValid(slot, stack)) {
                return stack;
            }
            if (!getStackInSlot(slot).isEmpty()) {
                return stack;         // 已占用 —— 不自动替换（要换用 put）
            }
            if (!simulate) {
                setStackInSlot(slot, stack.copyWithCount(1));
            }
            return stack.getCount() <= 1 ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - 1);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            ItemStack there = getStackInSlot(slot);
            if (there.isEmpty() || amount <= 0) {
                return ItemStack.EMPTY;
            }
            ItemStack out = there.copyWithCount(Math.min(amount, there.getCount()));
            if (!simulate) {
                setStackInSlot(slot, ItemStack.EMPTY);
            }
            return out;
        }
    }

    /** 这个会话能不能写（take/put/move 要写槽位） */
    private static IItemHandlerModifiable writable(IItemHandler h, String what) {
        if (!(h instanceof IItemHandlerModifiable m)) {
            throw new CmdException(CmdException.BLOCKED, what + "：这个槽位视图不可写");
        }
        return m;
    }

    /** 物品简短名（★ 必须在 split 之前调用 —— split 会把那堆掏空） */
    private static String nameOf(ItemStack s) {
        return s == null || s.isEmpty() ? "空" : s.getItem().toString() + " ×" + s.getCount();
    }

    // ────────────────────────── take（拿起 → 鼠标槽） ──────────────────────────

    /**
     * {@code take from=<槽位> [count=N]} —— 拿进鼠标槽（= 左键点槽位）。
     * <p>鼠标槽已有别的 ⇒ 拒（玩家也拿不了两样）。
     */
    public static Map<String, Object> take(EntityMaid maid, MaidHandle h, String from, int count) {
        Session s = requireSession(h);
        Ref r = parse(from);
        IItemHandlerModifiable inv = writable(handler(maid, h, r), "take");
        ItemStack src = inv.getStackInSlot(r.slot());
        if (src.isEmpty()) {
            throw CmdException.badArg("那一格是空的：" + from);
        }
        String name = nameOf(src);                       // ★ 先记名字（下面 split 会掏空它）
        ItemStack cur = s.cursor;
        if (!cur.isEmpty() && !ItemStack.isSameItemSameComponents(cur, src)) {
            throw CmdException.badState("手上还拿着 " + nameOf(cur) + " —— 先 put 放下",
                    "put to=<槽位> 或 put to=ground");
        }
        int maxStack = src.getMaxStackSize();
        int room = cur.isEmpty() ? maxStack : maxStack - cur.getCount();
        if (room <= 0) {
            throw CmdException.badState("手上那一堆已经满了（" + maxStack + "）");
        }
        int want = count <= 0 ? src.getCount() : count;
        int k = Math.min(want, Math.min(src.getCount(), room));
        if (k <= 0) {
            throw CmdException.badArg("没什么可拿的");
        }
        // ★ 必须用 extractItem，不能用 src.split()：
 // "结果格取产物要消耗材料"这种副作用挂在 IItemHandler.extractItem 上，
 // 用 split 直接改引用会白拿产物不扣料（实测抓到：能无限刷工作台）。
        ItemStack taken = inv.extractItem(r.slot(), k, false);
        if (taken.isEmpty()) {
            throw CmdException.badArg("没拿出来（这个槽位拒绝提取）：" + from);
        }
        if (cur.isEmpty()) {
            s.cursor = taken;
        } else {
            cur.grow(taken.getCount());                  // 同类合并
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("took", taken.getCount());
        out.put("item", name);
        out.put("cursor", desc(s.cursor));
        out.put("from_slot", desc(inv.getStackInSlot(r.slot())));
        out.put("message", "拿了 " + taken.getCount() + " 个 " + name);
        return out;
    }

    // ────────────────────────── put（鼠标槽 → 目标） ──────────────────────────

    /**
     * {@code put to=<槽位> [count=N]} —— 放下（= 左键点槽位）。
     * <p>目标空 ⇒ 放入；同类 ⇒ 合并（超上限的留在鼠标槽）；异类 ⇒ <b>交换</b>；{@code ground} ⇒ 扔外面。
     */
    public static Map<String, Object> put(EntityMaid maid, MaidHandle h, String to, int count) {
        Session s = requireSession(h);
        ItemStack cur = s.cursor;
        if (cur.isEmpty()) {
            throw CmdException.badState("手上什么都没拿 —— 先 take from=<槽位>");
        }
        String t = to == null ? "" : to.trim().toLowerCase();
        // ① 扔到外面
        if (t.equals("ground") || t.equals("world")) {
            int n = count <= 0 ? cur.getCount() : Math.min(count, cur.getCount());
            ItemStack outStack = cur.split(n);
            if (cur.isEmpty()) {
                s.cursor = ItemStack.EMPTY;
            }
            maid.spawnAtLocation(outStack);
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("dropped", desc(outStack));
            o.put("cursor", desc(s.cursor));
            o.put("message", "扔出去 " + nameOf(outStack));
            return o;
        }
        Ref r = parse(to);
        IItemHandlerModifiable inv = writable(handler(maid, h, r), "put");
        // ★ 统一按原版规则校验：setStackInSlot 会绕过 isItemValid，必须自己先问一次。
 // 规则来源全是现成接口 —— 真容器走 InvWrapper / 工位走 Slot.mayPlace /
 // 护甲走 Equippable 组件 / 饰品走 TLM 自己的实现 —— 我们不维护任何黑白名单。
        if (!inv.isItemValid(r.slot(), cur)) {
            throw CmdException.badArg(
                    "这个格子不收 " + nameOf(cur) + "（" + to + "）",
                    switch (r.kind()) {
                        case ARMOR -> "护甲格只接受对应部位的装备";
                        case BAUBLE -> "饰品栏只认 TLM 的饰品物品，普通物品放背包格";
                        case BOX -> "按原版规则这里放不进去（燃料格 / 结果格这类）";
                        case MAID -> "这一格不接受该物品";
                    });
        }
        ItemStack there = inv.getStackInSlot(r.slot());
        // ② 目标空 ⇒ 放入
        if (there.isEmpty()) {
            int n = count <= 0 ? cur.getCount() : Math.min(count, cur.getCount());
            if (r.kind() == Kind.ARMOR) {
                n = 1;               // ★ 护甲格只放一件，剩下的继续留在鼠标槽
            }
            ItemStack moved = cur.split(n);
            inv.setStackInSlot(r.slot(), moved);
            if (cur.isEmpty()) {
                s.cursor = ItemStack.EMPTY;
            }
            return putResult("放入", moved, r, s, inv);
        }
        // ③ 同类 ⇒ 合并（超上限的留在鼠标槽）
        if (ItemStack.isSameItemSameComponents(there, cur)) {
            int can = Math.min(cur.getCount(), there.getMaxStackSize() - there.getCount());
            if (can <= 0) {
                throw CmdException.badState("那一格满了（" + there.getMaxStackSize() + "）—— 换个格放");
            }
            int n = count <= 0 ? can : Math.min(count, can);
            there.grow(n);
            cur.shrink(n);
            if (cur.isEmpty()) {
                s.cursor = ItemStack.EMPTY;
            }
            inv.setStackInSlot(r.slot(), there);
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("merged", n);
            o.put("to_slot", desc(there));
            o.put("cursor", desc(s.cursor));
            o.put("message", "合并了 " + n + " 个");
            return o;
        }
        // ④ 异类 ⇒ 交换（对面的东西上手）
        inv.setStackInSlot(r.slot(), cur.copy());
        s.cursor = there.copy();
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("swapped_in", nameOf(cur));
        o.put("cursor", desc(s.cursor));
        o.put("to_slot", desc(inv.getStackInSlot(r.slot())));
        o.put("message", "交换了（现在手上是 " + nameOf(s.cursor) + "）");
        return o;
    }

    private static Map<String, Object> putResult(String verb, ItemStack moved, Ref r,
                                                 Session s, IItemHandler inv) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("put", moved.getCount());
        o.put("item", moved.getItem().toString());
        o.put("to_slot", desc(inv.getStackInSlot(r.slot())));
        o.put("cursor", desc(s.cursor));
        o.put("message", verb + " " + nameOf(moved));
        return o;
    }

    // ────────────────────────── move（Shift 点击） ──────────────────────────

    /**
     * {@code move from=<槽位>} —— 玩家的 Shift 点击：<b>自动在"对面"找位</b>。
     *
     * <p>⚠️ <b>不接受 {@code to}</b> —— 想精确指定放到哪一格，用 {@code take} + {@code put}。
     *
     * <p>两道账（保证"要么全搬、要么一个都没动"）：先 dry-run 算对面能收多少；收不下就原样留着报失败。
     */
    public static Map<String, Object> move(EntityMaid maid, MaidHandle h, String from) {
 // ★ move = 界面里的"Shift 点击"，一律要开着界面（不搞特例）
        Session sess = h.session;
        if (sess == null) {
            throw CmdException.badState("move 要先把界面打开",
                    "open pos=<坐标>（容器）/ open（打开她自己的背包）");
        }
        if (!sess.kind.canQuickMove()) {
            throw CmdException.badState(
                    "move 不支持这个界面（" + sess.kind.wire + "）",
                    "move 只用于箱子类容器间快速搬运；工位 / 熔炉类请用 take + put 精确控制");
        }
        Ref srcRef = parse(from);
        IItemHandlerModifiable srcInv = writable(handler(maid, h, srcRef), "move");
        ItemStack src = srcInv.getStackInSlot(srcRef.slot());
        if (src.isEmpty()) {
            throw CmdException.badArg("那一格是空的：" + from);
        }
        String name = nameOf(src);
 // "对面"（统一规则）：界面 = 自身格子 + box 格子
 // 打开【她自己的背包】⇒ 一律"收进背包"（对面 = 背包，排除主手/副手/展示栏）
 // 打开【容器】+ 源是自身格子（背包/护甲/饰品）⇒ 送进容器
 // 打开【容器】+ 源是容器格子 ⇒ 送回她背包
        IItemHandlerModifiable dstInv;
        Set<Integer> skip;
        String dstLabel;
        if (sess.kind == com.xiaofeng.maidcli.engine.FaceKind.INV) {
            dstInv = writable(maid.getAvailableBackpackInv(), "move");
            skip = Set.of();
            dstLabel = "她的背包";
        } else if (srcRef.kind() == Kind.BOX) {
            dstInv = writable(maid.getAvailableInv(true), "move");
            skip = NOT_FOR_MATERIAL;
            dstLabel = "她的背包";
        } else {
            if (sess.container == null) {
                throw CmdException.badState("这个界面没有可搬的容器槽位", "换个容器 open");
            }
            dstInv = writable(sess.container, "move");
            skip = Set.of();
            dstLabel = "当前界面（" + sess.kind.wire + "）";
        }
        // ① dry-run：对面能收多少
        int cap = capacityFor(dstInv, src, skip);
        if (cap <= 0) {
            throw CmdException.badState("对面放不下（" + dstLabel + "）—— 原样留着（一个都没动）",
                    "先腾个空位，或者 take + put 精确控制");
        }
        int moved = Math.min(src.getCount(), cap);
        // ★ 用 extractItem，不能用 src.split()：结果格"取产物要消耗材料"的副作用挂在它上面
 // （和 take 同一个坑 —— 第二轮复查抓到 move 也漏了）
        ItemStack takenOut = srcInv.extractItem(srcRef.slot(), moved, false);
        if (takenOut.isEmpty()) {
            throw new CmdException(CmdException.BLOCKED, "没搬动（这个槽位拒绝提取）：" + from);
        }
        int left = autoPlace(dstInv, takenOut, skip);
        if (left > 0) {
            // 理论不该发生（dry-run 过了）⇒ 兜底还回源槽，绝不吞
            ItemStack back = takenOut.copyWithCount(left);
            ItemStack rest = srcInv.insertItem(srcRef.slot(), back, false);
            if (!rest.isEmpty()) {
                maid.spawnAtLocation(rest);
            }
            throw new CmdException(CmdException.BLOCKED,
                    "搬的时候对面放不下 " + left + " 个 —— 已经还回去了（没丢东西）");
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("moved", moved);
        o.put("item", name);
        o.put("from_slot", desc(srcInv.getStackInSlot(srcRef.slot())));
        o.put("message", "搬了 " + moved + " 个 " + name + " → " + dstLabel);
        return o;
    }

    /** dry-run：对面能收多少（先同类未满的，再空格） */
    private static int capacityFor(IItemHandler dst, ItemStack src, Set<Integer> skip) {
        int cap = 0;
        int max = src.getMaxStackSize();
        for (int i = 0; i < dst.getSlots(); i++) {
            if (skip.contains(i)) {
                continue;
            }
            ItemStack d = dst.getStackInSlot(i);
            if (d.isEmpty()) {
                // ★ 空格也要问一句"收不收"（熔炉燃料格收煤、结果格谁都不收…）
                if (dst.isItemValid(i, src)) {
                    cap += Math.min(max, Math.max(1, dst.getSlotLimit(i)));
                }
            } else if (ItemStack.isSameItemSameComponents(d, src)) {
                cap += Math.max(0, max - d.getCount());
            }
        }
        return cap;
    }

    /** 两趟找位：① 先合并同类未满 ② 再找空格。返回没放下的数量 */
    private static int autoPlace(IItemHandlerModifiable dst, ItemStack stack, Set<Integer> skip) {
        int remaining = stack.getCount();
        // ① 合并同类
        for (int i = 0; i < dst.getSlots() && remaining > 0; i++) {
            if (skip.contains(i)) {
                continue;
            }
            ItemStack d = dst.getStackInSlot(i);
            if (d.isEmpty() || !ItemStack.isSameItemSameComponents(d, stack)) {
                continue;
            }
            int can = Math.min(remaining, d.getMaxStackSize() - d.getCount());
            if (can > 0) {
                d.grow(can);
                dst.setStackInSlot(i, d);
                remaining -= can;
            }
        }
        // ② 找空格
        for (int i = 0; i < dst.getSlots() && remaining > 0; i++) {
            if (skip.contains(i)) {
                continue;
            }
            if (!dst.getStackInSlot(i).isEmpty()) {
                continue;
            }
            if (!dst.isItemValid(i, stack)) {          // ★ 这格不收它就跳过（燃料格 / 结果格）
                continue;
            }
            int put = Math.min(remaining,
                    Math.min(stack.getMaxStackSize(), Math.max(1, dst.getSlotLimit(i))));
            dst.setStackInSlot(i, stack.copyWithCount(put));
            remaining -= put;
        }
        stack.setCount(remaining);
        return remaining;
    }

    // ────────────────────────── 收口：鼠标槽退料 ──────────────────────────

    /** 鼠标槽的东西退回背包（close / 被打断 / 走远 / 超时 / 她死 —— 全都调它） */
    public static void cursorReturnHome(EntityMaid maid, Session s) {
        if (s == null || s.cursor == null || s.cursor.isEmpty()) {
            return;
        }
        ItemStack cur = s.cursor;
        s.cursor = ItemStack.EMPTY;
        ItemOps.stash(maid, cur);        // 塞不下就掉地上，绝不吞
    }

    // ────────────────────────── 小工具 ──────────────────────────

    private static Session requireSession(MaidHandle h) {
        Session s = h.session;
        if (s == null) {
            throw CmdException.badState("界面没开 —— 这个操作要先把界面打开",
                    "open pos=<坐标> / open（开自己背包）");
        }
        return s;
    }

    public static Map<String, Object> desc(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item", s.getItem().toString());
        m.put("count", s.getCount());
        return m;
    }

    /** 列一个槽位视图（给 sense session 用） */
    public static List<Map<String, Object>> listSlots(IItemHandler inv) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack st = inv.getStackInSlot(i);
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("index", i);
            one.put("item", st.isEmpty() ? null : st.getItem().toString());
            if (!st.isEmpty()) {
                one.put("count", st.getCount());
            }
            out.add(one);
        }
        return out;
    }

    /** 某个坐标离她多远（手长判定用） */
    public static double distanceTo(EntityMaid maid, BlockPos pos) {
        return Math.sqrt(maid.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
    }
}
