"""Maid CLI · HTTP 客户端（v2 架构）。

职责只有三件：
  ① **key 管理** —— 存本地、自动带上、失效自动重登（LLM 全程不接触 key）
  ② **路由** —— 查 /help 拿指令表（自省），查询类走 GET /q、操作类走 POST /cmd
  ③ **轮询** —— 操作类"受理即回"，这里替调用方在 history 里等到完成

⚠️ 设计要点：**指令表永远从服务端现读**（不写死在本地）⇒ 服务端加新指令，CLI 立刻能用。
"""
from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8644

# key 存放：环境变量优先（适合 CI / 容器），其次用户目录（不写代码目录 —— 装成包后那里可能只读）
KEY_ENV = "MAIDCLI_KEY"
KEY_FILE = Path(os.environ.get("MAIDCLI_KEY_FILE") or (Path.home() / ".maidcli" / "key.json"))

# 操作类里"立即生效、不进队列"的那几条 —— 它们没有 history 可等，别去轮询
NO_WAIT_CMDS = {"stop", "mode", "control", "set", "wake", "login"}


class CliError(Exception):
    """带 code / hint 的错误（服务端错误码原样透传）"""

    def __init__(self, code: str, message: str, hint: str | None = None, data: dict | None = None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.hint = hint
        self.data = data or {}


class Client:
    def __init__(self, host: str | None = None, port: int | None = None,
                 player: str | None = None, verbose: bool = False):
        self.host = host or os.environ.get("MAIDCLI_HOST") or DEFAULT_HOST
        self.port = port or int(os.environ.get("MAIDCLI_PORT", DEFAULT_PORT))
        self.base = f"http://{self.host}:{self.port}"
        self.player = player or os.environ.get("MAIDCLI_PLAYER") or None
        self.verbose = verbose
        self._key: str | None = None
        self._help: dict | None = None
        self._load_key()

    # ────────────────────────── key ──────────────────────────

    def _load_key(self) -> None:
        env = os.environ.get(KEY_ENV)
        if env:
            self._key = env.strip()
            return
        try:
            if KEY_FILE.exists():
                d = json.loads(KEY_FILE.read_text(encoding="utf-8"))
                if (d.get("host") or DEFAULT_HOST) == self.host and d.get("port") == self.port and d.get("key"):
                    self._key = d["key"]
                    self.player = d.get("player") or self.player
        except Exception:
            self._key = None

    def _save_key(self) -> None:
        if os.environ.get(KEY_ENV):
            return                      # 用环境变量时不要往磁盘写
        try:
            KEY_FILE.parent.mkdir(parents=True, exist_ok=True)
            KEY_FILE.write_text(
                json.dumps({"host": self.host, "port": self.port, "key": self._key, "player": self.player},
                           ensure_ascii=False),
                encoding="utf-8",
            )
            try:
                KEY_FILE.chmod(0o600)
            except Exception:
                pass
        except Exception:
            pass

    def login(self, player: str | None = None) -> dict:
        if player:
            self.player = player
        if not self.player:
            # 先确认连得上 —— 连不上时报"地址不通"比报"没给玩家名"有用得多
            self.ping()
            raise CliError("bad_arg", "还不知道用哪个玩家名登录",
                           f"服务端 {self.base} 是通的，但没给玩家名：maidcli login <玩家名>"
                           "（或 --player <名> / 环境变量 MAIDCLI_PLAYER）；"
                           "名字必须是服务端 usercache 里有的正版/离线玩家名")
        r = self._request("POST", "/cmd", {"cmd": "login", "player": self.player}, auth=False)
        self._key = r["data"]["key"]
        self._save_key()
        return r["data"]

    def ensure_key(self) -> None:
        if not self._key:
            self.login()

    # ────────────────────────── HTTP ──────────────────────────

    def _request(self, method: str, path: str, body: dict | None = None, auth: bool = True,
                 retry_on_401: bool = True) -> dict:
        headers = {"Content-Type": "application/json; charset=utf-8"}
        if auth and self._key:
            headers["X-Maid-Key"] = self._key
        data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            raw = urllib.request.urlopen(req, timeout=30).read()
            return json.loads(raw.decode("utf-8"))
        except urllib.error.HTTPError as e:
            payload = json.loads(e.read().decode("utf-8"))
            # ★ key 失效 ⇒ 自动重登一次再重试（LLM 看不到这一步；服务端重启后会命中）
            if auth and retry_on_401 and payload.get("code") == "unauthorized":
                self._key = None
                self.login()
                return self._request(method, path, body, auth, retry_on_401=False)
            raise CliError(payload.get("code", "http_" + str(e.code)),
                           payload.get("message", str(e)),
                           payload.get("hint"), {k: v for k, v in payload.items()
                                                 if k not in ("ok", "code", "message", "hint")})
        except urllib.error.URLError as e:
            raise CliError("unreachable",
                           f"连不上游戏服务端（{self.base}）—— 服务端在跑吗？",
                           "确认服务端已启动并装了 maid_cli；默认模组只监听 127.0.0.1，"
                           "远程时请先建隧道：ssh -L 8644:127.0.0.1:8644 <用户>@<服务器>，"
                           "再在本地用默认地址即可（地址/端口可用 --host/--port 或 MAIDCLI_HOST/MAIDCLI_PORT 指定）") from e

    # ────────────────────────── 指令表（自省） ──────────────────────────

    def help_table(self, refresh: bool = False) -> dict:
        """取指令表（缓存）。★ 不写死在本地 —— 服务端加指令 CLI 立刻能用。"""
        if self._help is None or refresh:
            r = self._request("GET", "/help", auth=False)
            self._help = {c["name"]: c for c in r["data"]["cmds"]}
        return self._help

    def cmd_info(self, name: str) -> dict:
        t = self.help_table()
        if name not in t:
            raise CliError("unknown_cmd", f"没有这条指令：{name}",
                           "可用：" + " / ".join(sorted(t)))
        return t[name]

    def is_query(self, name: str) -> bool:
        return self.cmd_info(name).get("kind") == "query"

    # ────────────────────────── 发指令 ──────────────────────────

    def call(self, name: str, args: dict, maid: str | None = None,
             wait: bool = True, timeout: float = 60.0) -> dict:
        """发一条指令。

        查询类 → GET /q（直接返回）
        操作类 → POST /cmd（受理即回；wait=True 时轮询到完成）
        """
        self.ensure_key()
        payload = {"cmd": name}
        payload.update({k: v for k, v in args.items() if v is not None})
        if maid:
            payload["maid"] = maid

        if self.is_query(name):
            # ★ 统一语法：参数拼回命令行形式，走 ?line=（服务端解析子命令 / 变长参数）
            qs = urllib.parse.urlencode({"line": self.build_line(name, args)})
            if maid:
                qs += "&maid=" + urllib.parse.quote(maid, safe="")
            return self._request("GET", "/q?" + qs)

        r = self._request("POST", "/cmd", payload)
        job_id = (r.get("data") or {}).get("id")
        if not wait or name in NO_WAIT_CMDS or job_id is None:
            return r
        return self._wait_done(name, maid, job_id, timeout)

    def batch(self, cmds: list[str], maid: str | None = None) -> dict:
        """批量（协议里的 cmds）—— CLI 提前检查"读/写不混批"（COMMANDS.md §11）"""
        self.ensure_key()
        if len(cmds) > 32:
            raise CliError("bad_arg", f"一批最多 32 条（收到 {len(cmds)}）")
        # 原样发：服务端按指令声明解析子命令 / 变长参数（不再本地翻译）
        lines = [c.strip() for c in cmds if c.strip()]
        kinds = {self.is_query(c.split()[0]) for c in lines}
        if len(kinds) > 1:
            raise CliError("bad_arg", "读和写不能同批（响应形状不同）—— 分成两批发")
        body: dict = {"cmds": lines}
        if maid:
            body["maid"] = maid
        return self._request("POST", "/cmd", body)

    def build_line(self, name: str, args: dict) -> str:
        """把 {参数: 值} 拼回命令行形式（和批量 / 文档同一种语法）。

        - 子命令参数放回位置：`{domain: self}` → `sense self`
        - 变长参数展开：WORDS → 多个裸词；PAIRS → 多个 k=v
        - 其余 k=v；值含空格时加引号
        """
        info = self.cmd_info(name)
        a = {k: v for k, v in args.items() if v is not None}
        parts = [name]

        sub = (info.get("sub") or {}).get("name")
        if sub and sub in a:
            parts.append(str(a.pop(sub)))

        var = info.get("variadic") or {}
        vname = var.get("name")
        if vname and vname in a:
            v = a.pop(vname)
            if var.get("mode") == "words":
                if isinstance(v, (list, tuple)):
                    parts.extend(str(x) for x in v)
                elif str(v).strip():
                    parts.extend(str(v).split())
            else:
                if isinstance(v, dict):
                    parts.extend(f"{k}={x}" for k, x in v.items())
                elif str(v).strip():
                    parts.append(str(v))

        for k, v in a.items():
            sv = str(v)
            if " " in sv:
                sv = '"' + sv + '"'
            parts.append(f"{k}={sv}")
        return " ".join(parts)

    def _wait_done(self, name: str, maid: str | None, job_id: int, timeout: float) -> dict:
        """轮询 sense state 的 history，等 id == 我发的那条（COMMANDS.md §2.5）

        ⚠️ `since=N` 的语义是「返回 id **大于** N 的全部」⇒ 要看到自己那条，
        必须传 `since = job_id - 1`（传 job_id 会永远等不到 —— 踩过）。
        """
        deadline = time.time() + timeout
        while time.time() < deadline:
            time.sleep(0.3)
            qs = urllib.parse.urlencode({"cmd": "sense", "domain": "state",
                                         "since": job_id - 1, "maid": maid or ""})
            try:
                r = self._request("GET", "/q?" + qs)
            except CliError:
                time.sleep(0.3)
                continue
            for h in (r.get("data") or {}).get("history", []):
                if h.get("id") == job_id:
                    # ★ 把【作业的成败】反映到顶层 ok —— 不然"操作失败了"脚本还拿 0 退出码
                    return {"ok": bool(h.get("ok")), "cmd": name, "data": h}
        raise CliError("timeout", f"{name} 超过 {timeout:.0f} 秒还没做完",
                       "用 sense state 看它卡在哪；或者 stop 打断")

    # ────────────────────────── 小工具 ──────────────────────────

    def ping(self) -> dict:
        return self._request("GET", "/ping", auth=False)

    def maids(self) -> list[dict]:
        return self._request("GET", "/q?cmd=maids").get("data", {}).get("maids", [])


def _qs(v) -> str:
    """query string 里布尔要发 true/false（服务端只认这两种）"""
    if isinstance(v, bool):
        return "true" if v else "false"
    return str(v)
