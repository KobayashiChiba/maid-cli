#!/usr/bin/env python3
"""Maid CLI 1.0.1 —— 控制 Minecraft 里的「女仆」的薄壳。

    maidcli <指令> [键=值 ...] [CLI 选项]

**CLI 只是协议的一层薄壳**：命令名与参数名跟服务端一致；只在「批量」和「寻址」上加两样薄糖。
★ 指令表**从服务端现读**（`/help` 自省）——服务端加新指令，这里立刻能用，不用改代码。

例：
    maidcli maids                              # 有哪些女仆（世界类，不用 maid）
    maidcli sense self --maid A1B2C3           # 看她（查询类，直接返回）
    maidcli go pos=-40,130,-40 --maid A1B2C3   # 走过去（操作类，默认轮询到完成）
    maidcli batch --maid A1B2C3 "sense self" "sense world"   # 一次请求发多条
    maidcli batch -f plan.mcl                  # 从文件读，一行一条，# 注释
    maidcli cmds                               # 列服务端所有指令
    maidcli help what=mode                     # 查工作模式清单

CLI 选项：
    --maid <她>      指定女仆（也可 export MAIDCLI_MAID=...）
    --no-wait        操作类不等完成（默认会等到 history 里出现结果）
    --timeout <秒>   等完成的上限（默认 60）
    --json           出原始 JSON
    --host <地址>    服务端 HTTP 地址（默认 127.0.0.1 / MAIDCLI_HOST）
    --port <端口>    服务端 HTTP 端口（默认 8644 / MAIDCLI_PORT）
    --player <名>    login 用哪个玩家名（必须是服务端 usercache 里的名字，不填则用上次的）
    --version        打印 CLI 版本 + 服务端模组版本（不一致会警告）

环境变量：MAIDCLI_MAID（默认女仆）· MAIDCLI_HOST（地址）· MAIDCLI_PORT（端口）· MAIDCLI_PLAYER（玩家名）
          MAIDCLI_KEY（直接给 key，跳过磁盘）· MAIDCLI_KEY_FILE（key 文件路径）
          MAIDCLI_NO_VERSION_CHECK=1（跳过版本一致性检查）
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from client import Client, CliError  # noqa: E402
import render  # noqa: E402

VERSION = "1.0.1"

CLI_FLAGS = {"--maid", "--timeout", "--host", "--port", "--player", "-f", "--file"}


def _check_version(client: "Client") -> None:
    """比对 CLI 版本与服务端模组版本 —— 不一致只警告，不阻塞（开发期常错配）。"""
    if os.environ.get("MAIDCLI_NO_VERSION_CHECK"):
        return
    try:
        info = client.ping()
    except CliError:
        return                      # 连不上由后续调用报错，这里不抢戏
    server = str(info.get("version") or "")
    mod = info.get("mod") or "maid_cli"
    if server and server != VERSION:
        print(f"{render.RED}⚠️  版本不一致：CLI {VERSION} · {mod} {server}"
              f"{render.RESET}  → 两者是一套的，请用同一版本的 CLI 与模组", file=sys.stderr)


def _usage() -> str:
    return __doc__


def _split_args(argv: list[str]):
    """把 [键=值 / --flag / --opt 值] 拆成 (kwargs dict, options dict, 多余位置参数)"""
    kwargs: dict[str, str] = {}
    opts: dict[str, object] = {}
    rest: list[str] = []
    i = 0
    while i < len(argv):
        a = argv[i]
        if a in CLI_FLAGS:
            opts[a.lstrip("-")] = argv[i + 1] if i + 1 < len(argv) else None
            i += 2
            continue
        if a in ("--no-wait", "--json", "-j"):
            opts[a.lstrip("-").replace("-", "_")] = True
            i += 1
            continue
        if a.startswith("--") and "=" in a:
            k, v = a[2:].split("=", 1)
            opts[k.replace("-", "_")] = v
            i += 1
            continue
        if "=" in a and not a.startswith("-"):
            k, v = a.split("=", 1)
            kwargs[k] = v
            i += 1
            continue
        if a.startswith("-"):
            raise CliError("bad_arg", f"不认识的选项：{a}", "看 maidcli --help")
        rest.append(a)
        i += 1
    return kwargs, opts, rest


def _mk_client(opts: dict) -> Client:
    return Client(host=opts.get("host"), port=int(opts["port"]) if opts.get("port") else None,
                  player=opts.get("player"),
                  verbose=bool(opts.get("json")))


def _bind_positional(info: dict, rest: list[str], kwargs: dict) -> None:
    """把位置参数按指令声明绑定：先填必填参数（子命令也是必填参数），剩下的裸词收进变长参数。

    例：`sense self` → domain=self · `mode attack` → task=attack
        `get mode follow` → keys=["mode","follow"] · `macro shoot pos=…` → macro=shoot
    """
    params = info.get("params", [])
    required = [p["name"] for p in params if p.get("required")]
    sub = (info.get("sub") or {}).get("name")
    # 子命令优先绑第一个裸词（哪怕它是可选参数，如 help <主题>）
    order = ([sub] if sub else []) + [r for r in required if r != sub]
    var = info.get("variadic") or {}
    vname = var.get("name")
    for p in params:
        if p["name"] == "keys":
            vname = vname or "keys"
    words: list[str] = []
    idx = 0
    for tok in rest:
        if idx < len(order):
            kwargs[order[idx]] = tok
            idx += 1
        elif vname:
            words.append(tok)
        else:
            raise CliError("bad_arg", f"多余的参数：{tok}",
                           "参数写成 键=值；可用参数见 maidcli help " + str(info.get("name", "")))
    if words:
        kwargs[vname] = words if var.get("mode") == "words" else " ".join(words)


def cmd_batch(client: Client, lines: list[str], maid: str | None, json_mode: bool) -> int:
    """批量：读批一起回、写批依次回（协议 §2.1）"""
    resp = client.batch(lines, maid=maid)
    if json_mode:
        render.raw(resp)
        return 0
    for r in resp.get("data", {}).get("results", []):
        name = r.get("cmd", "?")
        render.render(name, r, json_mode=False)
        print()
    return 0


def main(argv: list[str]) -> int:
    if not argv or argv[0] in ("-h", "--help", "usage"):
        print(_usage())
        return 0

    # ── 全局选项可以先出现 ──
    pre_opts: dict = {}
    while argv and argv[0].startswith("-"):
        if argv[0] == "--help":
            print(_usage())
            return 0
        k = argv[0].lstrip("-").replace("-", "_")
        if argv[0] in CLI_FLAGS:
            pre_opts[k] = argv[1]
            argv = argv[2:]
        else:
            pre_opts[k] = True
            argv = argv[1:]
    if not argv:
        print(_usage())
        return 0

    verb = argv[0]
    try:
        kwargs, opts, rest = _split_args(argv[1:])
        opts = {**pre_opts, **opts}
        json_mode = bool(opts.get("json"))

        client = _mk_client(opts)

        if verb == "version":
            try:
                info = client.ping()
            except CliError as e:
                info = {"error": e.message}
            print(f"Maid CLI {VERSION}")
            print(f"服务端：{json.dumps(info, ensure_ascii=False)}")
            return 0

        _check_version(client)

        # ── 元命令（不需要服务端有对应指令） ──
        if verb == "cmds":
            table = client.help_table(refresh=True)
            if json_mode:
                render.raw(table)
                return 0
            for n, c in sorted(table.items()):
                ps = " ".join(f"{p['name']}" + ("" if p.get("required") else "?")
                              for p in c.get("params", []))
                print(f"  {n:<12} {c.get('kind','?'):<8} {ps}")
                if c.get("desc"):
                    print(f"      {render.DIM}{c['desc']}{render.RESET}")
            return 0

        if verb == "ping":
            render.raw(client.ping())
            return 0

        if verb == "help" and "what" not in kwargs and not rest:
            table = client.help_table(refresh=True)
            for n, c in sorted(table.items()):
                print(f"\n{render.BOLD}{n}{render.RESET}  {render.DIM}[{c.get('kind')}]{render.RESET}")
                if c.get("desc"):
                    print(f"    {c['desc']}")
                for p in c.get("params", []):
                    req = "" if p.get("required") else "（可选）"
                    dflt = f"  默认={p['default']}" if "default" in p else ""
                    vals = f"  取{'/'.join(map(str, p['values']))}" if p.get("values") else ""
                    print(f"      {p['name']}{req}  {render.DIM}{p.get('desc','')}{dflt}{vals}{render.RESET}")
            return 0

        if verb == "login":
            player = kwargs.get("player") or (rest[0] if rest else None)
            d = client.login(player)
            render.render("login", {"ok": True, "data": d}, json_mode)
            return 0

        if verb == "batch":
            files = []
            lines = list(rest)
            if opts.get("f") or opts.get("file"):
                fp = Path(str(opts.get("f") or opts.get("file")))
                files.append(fp)
            if kwargs.get("file"):
                files.append(Path(kwargs["file"]))
            for fp in files:
                try:
                    text = fp.read_text(encoding="utf-8")
                except OSError as e:
                    raise CliError("bad_arg", f"读不到文件：{fp}（{e.strerror or e}）",
                                   "检查路径；也可以用管道：cat plan.mcl | maidcli batch --maid X") from e
                for line in text.splitlines():
                    line = line.split("#", 1)[0].strip()
                    if line:
                        lines.append(line)
            # 没有文件参数时，也允许从 stdin 读（管道）
            if not lines and not sys.stdin.isatty():
                for line in sys.stdin.read().splitlines():
                    line = line.split("#", 1)[0].strip()
                    if line:
                        lines.append(line)
            if not lines:
                raise CliError("bad_arg", "batch 没给内容", "maidcli batch --maid X \"sense self\"，或用 -f 文件 / 管道")
            maid = opts.get("maid") or os.environ.get("MAIDCLI_MAID")
            return cmd_batch(client, lines, maid, json_mode)

        # ── 普通指令：名字 -> 参数（服务端负责类型转换） ──
        maid = opts.get("maid") or os.environ.get("MAIDCLI_MAID") or kwargs.pop("maid", None)
        wait = not opts.get("no_wait")
        timeout = float(opts.get("timeout") or 60)

        if rest:
            # 位置参数形式：`maidcli sense self` / `maidcli mode attack` / `maidcli get follow sit`
            _bind_positional(client.cmd_info(verb), rest, kwargs)

        # ★ `set` 的参数是"任意 键=值"—— 全部收进 keys（服务端 variadic=PAIRS 展开成 follow=true sit=false）
        if verb == "set":
            declared = {p["name"] for p in client.cmd_info(verb).get("params", [])}
            pairs = {k: v for k, v in kwargs.items() if k not in declared}
            if pairs:
                kwargs = {"keys": pairs}

        resp = client.call(verb, kwargs, maid=maid, wait=wait, timeout=timeout)
        render.render(verb, resp, json_mode)
        return 0 if resp.get("ok") else 1

    except CliError as e:
        render.render_error({"code": e.code, "message": e.message, "hint": e.hint, **e.data})
        return 1
    except KeyboardInterrupt:
        return 130


def cli_entry() -> None:
    """console_scripts 入口（pip / pipx 安装后直接用 maidcli）。"""
    sys.exit(main(sys.argv[1:]))


if __name__ == "__main__":
    cli_entry()
