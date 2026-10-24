package com.xiaofeng.maidcli.command.defs;

import com.xiaofeng.maidcli.command.MaidCmd;
import com.xiaofeng.maidcli.command.Param;
import com.xiaofeng.maidcli.loading.MaidLoader;
import com.xiaofeng.maidcli.transport.Auth;
import com.xiaofeng.maidcli.transport.CmdException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录类（COMMANDS.md §4）—— {@code login} · {@code wake}。
 *
 * <p><b>不依赖女仆、不依赖加载</b>（否则就是死循环：不加载找不到她，找不到她就唤不醒）。
 */
public final class ConnectCmds {

    private ConnectCmds() {
    }

    public static void registerAll() {
        com.xiaofeng.maidcli.command.CmdRegistry.register(new Login());
        com.xiaofeng.maidcli.command.CmdRegistry.register(new Wake());
    }

    /** {@code login player=<玩家名>} / {@code login off=true} */
    static final class Login implements MaidCmd {
        @Override
        public String name() {
            return "login";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "建立身份 → 返回 key（默认 12 小时）；login off=true 登出";
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("player", Param.optStr("player", null, "玩家名（必须已在服务端 usercache 里）"));
            p.put("off", Param.flag("off", "true = 登出"));
            return p;
        }

        // ★ login 免 key（它就是来换 key 的）、不要女仆
        @Override
        public boolean needsKey() {
            return false;
        }

        @Override
        public boolean needsMaid() {
            return false;
        }

        @Override
        public boolean worldOk() {
            return true;
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            boolean off = ctx.flag("off", false);
            if (off) {
                boolean ok = Auth.logout(ctx.opt("key_hint"));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("logged_out", ok);
                return out;
            }
            String player = ctx.opt("player");
            Auth.Key k = Auth.login(ctx.server(), player);
            return k.toMap();
        }
    }

    /** {@code wake maid=<她>} / {@code wake maid=<她> off=true} */
    static final class Wake implements MaidCmd {
        @Override
        public String name() {
            return "wake";
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public String desc() {
            return "唤醒（加载 + 接管 + 起 300 秒超时）；wake maid=<她> off=true 取消唤醒";
        }

        @Override
        public Map<String, Param> params() {
            Map<String, Param> p = new LinkedHashMap<>();
            p.put("maid", Param.reqStr("maid", "标签 / uuid / 名字"));
            p.put("off", Param.flag("off", "true = 取消唤醒（卸载 + 放开）"));
            return p;
        }

        // ★ 她不加载时实体根本找不到 ⇒ 不走"解析女仆实体"那条路
        @Override
        public boolean needsMaid() {
            return false;
        }

        @Override
        public boolean worldOk() {
            return true;
        }

        @Override
        public Map<String, Object> run(Ctx ctx) {
            String target = ctx.opt("maid");
            if (target == null || target.isBlank()) {
                throw CmdException.badArg("要说唤醒哪只（maid=<标签/uuid/名字>）");
            }
            java.util.UUID by = ctx.player();
            return MaidLoader.wake(ctx.server(), target, by, ctx.flag("off", false));
        }
    }
}
