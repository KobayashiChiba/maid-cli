package com.xiaofeng.maidcli.infra;

import com.xiaofeng.maidcli.MaidCliExtension;
import net.minecraft.nbt.CompoundTag;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

/**
 * 6 位标签的生成与读写（core-concepts §2.1）。
 *
 * <p><b>标签的"真身"在她 NBT 里</b>（走 TLM 的 {@code TaskDataKey}，数据槽 {@code maid_cli:tag}）——
 * 所以它扛重启、扛物品化、<b>扛复活</b>。我们账本里那份只是索引副本。
 *
 * <p>字母表去掉了易混的 {@code I} {@code O} {@code 0} {@code 1}（避免与数字混淆）——
 * 这标签是给人念/手敲的，26+9 个字符里最容易看错的那几个不能用。
 *
 * <p>⚠️ 标签跟实体走 ⇒ 她真没了（没有胶片）标签也没了 —— 不是永恒编号。
 */
public final class MaidCliTags {

    /** 去掉 I / O / 0 / 1 的字母表 */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int LEN = 6;
    private static final SecureRandom RND = new SecureRandom();

    private MaidCliTags() {
    }

    /** 生成一个随机标签 */
    public static String random() {
        StringBuilder sb = new StringBuilder(LEN);
        for (int i = 0; i < LEN; i++) {
            sb.append(ALPHABET.charAt(RND.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * 生成一个<b>当前世界没用过</b>的标签。
     *
     * @param taken 已被占用的标签集合
     */
    public static String randomUnique(Set<String> taken) {
        for (int i = 0; i < 64; i++) {
            String c = random();
            if (!taken.contains(c)) {
                return c;
            }
        }
        throw new IllegalStateException("生成标签失败（碰撞太多次）");
    }

    /** 校验一个标签格式是否合法（6 位、全在字母表里） */
    public static boolean valid(String tag) {
        if (tag == null || tag.length() != LEN) {
            return false;
        }
        for (int i = 0; i < LEN; i++) {
            if (ALPHABET.indexOf(tag.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    // ────────────────────────── 读写她 NBT 里的标签 ──────────────────────────

    /**
     * 读一只女仆的标签（没贴过返回 null）。
     * <p>⚠️ TLM 的 {@code TaskDataKey} 是我们注册的；注册失败时为 null ⇒ 读的时候要判空。
     */
    public static String read(com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid) {
        if (MaidCliExtension.TAG == null) {
            return null;
        }
        try {
            CompoundTag data = maid.getData(MaidCliExtension.TAG);
            if (data == null) {
                return null;
            }
            String t = data.getString(MaidCliExtension.FIELD);
            return t.isEmpty() ? null : t;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 给她贴上标签（写进她 NBT 的数据槽） */
    public static void write(com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid, String tag) {
        if (MaidCliExtension.TAG == null) {
            return;
        }
        CompoundTag data = new CompoundTag();
        data.putString(MaidCliExtension.FIELD, tag);
        maid.setData(MaidCliExtension.TAG, data);
    }

    /**
     * 她的「已附魔次数」（旧实现 {@code MaidOps.enchantCountOf}）—— 附魔台的种子基数。
     * <p>存在同一个 TAG 的 {@code EnchCount} 字段里（不覆盖标签本身）。
     */
    public static int enchantCount(com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid) {
        if (MaidCliExtension.TAG == null) {
            return 0;
        }
        try {
            CompoundTag data = maid.getData(MaidCliExtension.TAG);
            return data == null ? 0 : data.getInt("EnchCount");
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 附魔次数 +1（写在同一个 CompoundTag 里，不覆盖标签） */
    public static int bumpEnchantCount(com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid) {
        if (MaidCliExtension.TAG == null) {
            return 0;
        }
        try {
            CompoundTag data = maid.getData(MaidCliExtension.TAG);
            if (data == null) {
                data = new CompoundTag();
            }
            int n = data.getInt("EnchCount") + 1;
            data.putInt("EnchCount", n);
            maid.setData(MaidCliExtension.TAG, data);
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 清掉标签（让她下次自动重贴） */
    public static void clear(com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid) {
        if (MaidCliExtension.TAG == null) {
            return;
        }
        maid.setData(MaidCliExtension.TAG, new CompoundTag());
    }

    /** 收集一组女仆已用的标签 */
    public static Set<String> collect(java.util.Collection<? extends com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid> maids) {
        Set<String> out = new HashSet<>();
        for (com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid m : maids) {
            String t = read(m);
            if (t != null) {
                out.add(t);
            }
        }
        return out;
    }
}
