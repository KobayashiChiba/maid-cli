package com.xiaofeng.maidcli.engine;

import com.xiaofeng.maidcli.MaidCliMod;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 存档持久化 —— 身份账本（core-concepts §1「两层生命周期」的<b>身份层</b>）。
 *
 * <p>跟世界存档走：{@code data/maid_cli.dat}。存的是**标签 → 身份**这份副本，
 * 唯一用途是"她不在世界时（读不到 NBT）也能认得她"。
 *
 * <p>⚠️ 只增不减 —— 她"消失"分不清是死了还是会回来，宁可留一条死记录（几十字节），
 * 也绝不能误删活的那条（core-concepts §2.3）。
 */
public class MaidData extends net.minecraft.world.level.saveddata.SavedData {

    public static final String NAME = "maid_cli";

    /** 标签 → 身份 */
    public final Map<String, MaidIdentity> identities = new LinkedHashMap<>();

    public static net.minecraft.world.level.saveddata.SavedData.Factory<MaidData> factory() {
        return new net.minecraft.world.level.saveddata.SavedData.Factory<MaidData>(
                MaidData::new, MaidData::load, net.minecraft.util.datafix.DataFixTypes.LEVEL);
    }

    /** 取（没有就建一个并挂到存档上） */
    public static MaidData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(factory(), NAME);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (MaidIdentity id : identities.values()) {
            list.add(id.save());
        }
        tag.put("maids", list);
        return tag;
    }

    public static MaidData load(CompoundTag tag, HolderLookup.Provider registries) {
        MaidData d = new MaidData();
        ListTag list = tag.getList("maids", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            try {
                MaidIdentity id = MaidIdentity.load(list.getCompound(i));
                if (id.tag != null && !id.tag.isEmpty()) {
                    d.identities.put(id.tag, id);
                }
            } catch (Throwable t) {
                MaidCliMod.LOG.warn("[MaidCLI] 读一条身份记录失败: {}", t.toString());
            }
        }
        return d;
    }

    /** 取或建 */
    public MaidIdentity getOrCreate(String tag) {
        MaidIdentity id = identities.get(tag);
        if (id == null) {
            id = new MaidIdentity(tag);
            identities.put(tag, id);
            setDirty();
        }
        return id;
    }

    public MaidIdentity byUuid(java.util.UUID uuid) {
        if (uuid == null) {
            return null;
        }
        for (MaidIdentity id : identities.values()) {
            if (uuid.equals(id.uuid)) {
                return id;
            }
        }
        return null;
    }

    /** 按名字找（唯一时才认；重名返回多个候选） */
    public java.util.List<MaidIdentity> byName(String name) {
        java.util.List<MaidIdentity> out = new java.util.ArrayList<>();
        for (MaidIdentity id : identities.values()) {
            if (name != null && name.equals(id.name)) {
                out.add(id);
            }
        }
        return out;
    }

    public Collection<MaidIdentity> all() {
        return identities.values();
    }

    public void markDirty() {
        setDirty();
    }
}
