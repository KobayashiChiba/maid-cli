package com.xiaofeng.maidcli;

import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.api.entity.data.TaskDataKey;
import com.github.tartaricacid.touhoulittlemaid.entity.data.TaskDataRegister;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/**
 * Maid CLI 的 TLM 扩展：给女仆注册一个「固定标签」数据槽。
 *
 * <p>为什么需要它：TLM 把女仆收进物品用的是 {@code saveWithoutId()}（不保存 UUID），
 * 所以"收起来再放出来"会换一个新 uuid，靠 uuid 做身份会断。
 * 而通过 {@link TaskDataKey} 挂的数据会进实体 NBT 的 {@code MaidTaskDataMaps}
 * ⇒ {@code saveWithoutId} 会把它一起带走 ⇒ 物品化、收放、重启都不影响 ——
 * <p><b>这才是真正稳定的身份</b>（而且实测扛复活）。
 */
@LittleMaidExtension
public class MaidCliExtension implements ILittleMaid {

    public static final ResourceLocation TAG_ID = ResourceLocation.fromNamespaceAndPath("maid_cli", "tag");

    /**
     * 注册后由 TLM 注入；未注册成功时为 null（所有用到的地方都要判空）。
     *
     * <p>⚠️ 这里必须是<b>编码成 CompoundTag</b> 的 codec（如 {@link CompoundTag#CODEC}）。
     * TLM 的 {@code TaskDataRegister} 对 codec 的编码结果做了裸 cast：
     * {@code .map(tag -> (CompoundTag) tag)}。用 {@code Codec.STRING} 会得到 StringTag
     * → ClassCastException → 女仆一 tick 就崩服（实测会遇到）。
     */
    public static TaskDataKey<CompoundTag> TAG;

    /** 标签实际存在 CompoundTag 的这个字段里 */
    public static final String FIELD = "tag";

    @Override
    public void registerTaskData(TaskDataRegister register) {
        TAG = register.register(TAG_ID, CompoundTag.CODEC);
    }

    /**
     * 注册「指令模式」任务（受控 = 她的任务换成它）。
     *
     * <p>TLM 官方扩展点：{@code TaskManager.init()} 会遍历所有 {@code @LittleMaidExtension}
     * 注册的扩展并调本方法。相比"反射清 brain 行为盒子"，这条路没有反射、没有顺序依赖，
     * 而且 {@code refreshBrain()} 重建出来的就是正确的一套（恢复旧做法）。
     */
    @Override
    public void addMaidTask(TaskManager manager) {
        manager.add(new com.xiaofeng.maidcli.task.TaskCommand());
    }
}
