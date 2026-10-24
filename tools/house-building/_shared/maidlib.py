"""共享：调 CLI 的唯一入口 + 计划文件读写。

规矩（tools/README.md 里也写了）：
  - 所有网络访问都经过这里，走 `maidcli` CLI，**禁止直连 RCON**
  - 只用标准库，不引第三方依赖
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

# 仓库根：tools/house-building/_shared/maidlib.py → 上溯三层
ROOT = Path(__file__).resolve().parents[3]
DEFAULT_CLI = ROOT / "cli" / "maidcli.py"


class MaidError(RuntimeError):
    """CLI 返回非 0 或输出无法解析。"""

    def __init__(self, message: str, code: str = "tool_error"):
        super().__init__(message)
        self.code = code


class Maid:
    """一条女仆连接的薄封装：每次调用起一个 `maidcli` 子进程。"""

    def __init__(self, maid: str | None = None, host: str | None = None,
                 port: int | None = None, timeout: float = 60.0, cli: str | None = None):
        self.maid = maid or os.environ.get("MAIDCLI_MAID")
        self.host = host or os.environ.get("MAIDCLI_HOST")
        self.port = port or os.environ.get("MAIDCLI_PORT")
        self.timeout = timeout
        self.cli = Path(cli) if cli else Path(os.environ.get("MAIDCLI_BIN", DEFAULT_CLI))
        if not self.cli.exists():
            raise MaidError(f"找不到 maidcli：{self.cli}（用 MAIDCLI_BIN 指定）", "no_cli")

    def run(self, *args: str, timeout: float | None = None) -> str:
        cmd = [sys.executable, str(self.cli), *args]
        if self.host:
            cmd += ["--host", self.host]
        if self.port:
            cmd += ["--port", str(self.port)]
        if self.maid and args and args[0] not in ("ping", "version", "cmds", "login"):
            cmd += ["--maid", self.maid]
        r = subprocess.run(cmd, capture_output=True, text=True,
                           timeout=timeout or self.timeout, encoding="utf-8", errors="replace")
        if r.returncode != 0:
            raise MaidError((r.stdout or "") + (r.stderr or ""), "cli_failed")
        return r.stdout

    def data(self, *args: str, timeout: float | None = None) -> dict:
        """带 --json 执行，返回 data 字段。"""
        out = self.run(*args, "--json", timeout=timeout)
        i = out.find("{")
        if i < 0:
            raise MaidError(f"输出不是 JSON：{out[:200]}", "bad_output")
        payload = json.loads(out[i:])
        if not payload.get("ok"):
            raise MaidError(payload.get("message") or str(payload), payload.get("code", "not_ok"))
        return payload.get("data") or {}

    # ── 常用组合 ──

    def block(self, x: int, y: int, z: int) -> tuple[str, dict]:
        """读一格：返回 (方块 id, 属性 dict)。"""
        d = self.data("blocks", f"pos={x},{y},{z}")
        rec = (d.get("blocks") or [d])[0]      # 单点返回扁平对象，方区才是列表
        state = rec.get("state") or ""
        props = {}
        if state:
            for kv in state.split(","):
                if "=" in kv:
                    k, v = kv.split("=", 1)
                    props[k.strip()] = v.strip()
        return rec.get("block") or "minecraft:air", props

    def awake(self) -> None:
        """没唤醒就唤醒（她不在世界里时所有指令都会 not_awake）。"""
        try:
            self.run("wake", f"maid={self.maid}")
        except MaidError:
            pass                               # 已经醒着 / 没给 maid 都会走到这里，忽略
