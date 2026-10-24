package com.xiaofeng.maidcli.command.defs;

import com.xiaofeng.maidcli.command.CmdRegistry;
import com.xiaofeng.maidcli.command.MaidCmd;
import com.xiaofeng.maidcli.command.Param;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 帮助类（COMMANDS.md §8）。
 *
 * <p>两个用途：<b>指令自省</b>（列出所有指令 + 参数 —— 内嵌智能体据此生成"工具定义"）·
 * <p><b>查公开资料</b>（工作模式清单 / 模型 / 音效包 / 方块物品 id）。
 *
 * <p>不依赖女仆、不需要唤醒。
 */
public final class HelpCmds {

    private HelpCmds() {
    }

    public static void registerAll() {
        CmdRegistry.register(new Help());
    }

    static final class Help implements MaidCmd {
        @Override
        public String name() {
            return "help";
        }

        @Override
        public Kind kind() {
            return Kind.QUERY;
        }

        @Override
        public String desc() {
            return "指令自省（列所有指令 + 参数）+ 公开资料（help what=mode 列工作模式）";
        }

        @Override
        public SubSpec sub() {
            return new SubSpec("what", false, "主题：mode（工作模式清单）/ errors（错误码表）");
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("what", Param.optStr("what", null, "主题：mode（工作模式清单）/ errors（错误码表）"));
            return p;
        }

        @Override
        public boolean worldOk() {
            return true;
        }

        @Override
        public boolean needsKey() {
            return false;
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            return CmdRegistry.help(ctx.opt("what"));
        }
    }
}
