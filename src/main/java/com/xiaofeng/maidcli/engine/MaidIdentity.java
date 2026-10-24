package com.xiaofeng.maidcli.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * 身份账本（core-concepts §1 / §2）。
 *
 * <p><b>标签是主键，账本是副本</b>：
 * <ul>
 *   <li>标签的<b>真身在她 NBT 里</b>（走 TLM 的 {@code maid_cli:tag} 数据槽）—— 权威，
 *       扛重启 / 扛物品化 / 扛复活</li>
 *   <li>我们这份是<b>索引副本</b> —— 唯一用途：<b>她不在世界时</b>（读不到 NBT）也能认得她</li>
 * </ul>
 *
 * <p>⚠️ uuid <b>会变</b>（实测：复活后 {@code c72f4e9d} → {@code 75e889d6}）⇒ 只能当缓存。
 */
public final class MaidIdentity {

    /** 6 位标签（主键） */
    public String tag;
    /** 最后已知 uuid（缓存 —— 复活会变） */
    public UUID uuid;
    /** 名字（给人看的；重名常见） */
    public String name = "";
    /** 最后已知位置 */
    public BlockPos lastPos;
    /** 最后已知维度 */
    public String lastDim = "";
    /** 最后一次"见过她"的 tick（陈旧记录判断用 —— 只标记不销毁） */
    public long lastSeenTick;
    /** 她的主人 uuid（登录/门禁用） */
    public UUID ownerUuid;

    public MaidIdentity(String tag) {
        this.tag = tag;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("tag", tag == null ? "" : tag);
        t.putString("uuid", uuid == null ? "" : uuid.toString());
        t.putString("name", name == null ? "" : name);
        t.putString("lastDim", lastDim == null ? "" : lastDim);
        if (lastPos != null) {
            t.putIntArray("lastPos", new int[]{lastPos.getX(), lastPos.getY(), lastPos.getZ()});
        }
        t.putLong("lastSeenTick", lastSeenTick);
        t.putString("owner", ownerUuid == null ? "" : ownerUuid.toString());
        return t;
    }

    public static MaidIdentity load(CompoundTag t) {
        MaidIdentity id = new MaidIdentity(t.getString("tag"));
        String u = t.getString("uuid");
        if (!u.isEmpty()) {
            try {
                id.uuid = UUID.fromString(u);
            } catch (IllegalArgumentException ignored) {
                // uuid 坏掉不影响标签主键
            }
        }
        id.name = t.getString("name");
        id.lastDim = t.getString("lastDim");
        if (t.contains("lastPos")) {
            int[] a = t.getIntArray("lastPos");
            if (a.length == 3) {
                id.lastPos = new BlockPos(a[0], a[1], a[2]);
            }
        }
        id.lastSeenTick = t.getLong("lastSeenTick");
        String o = t.getString("owner");
        if (!o.isEmpty()) {
            try {
                id.ownerUuid = UUID.fromString(o);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return id;
    }

    @Override
    public String toString() {
        return tag + "(" + name + ")" + (uuid == null ? "" : "@" + uuid.toString().substring(0, 8));
    }
}
