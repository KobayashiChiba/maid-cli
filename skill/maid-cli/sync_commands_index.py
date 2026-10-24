#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从服务端 GET /help 生成指令总表（references/commands-index.md），并可校验文档是否漂移。

用法：
    python sync_commands_index.py           # 重新生成
    python sync_commands_index.py --check    # 只校验（文档与服务端不一致 → 退出码 1）

设计原则：总表**只从 /help 生成**，人不手改；手写的是 commands.md（讲语义、示例、坑），
两者由 --check 保证同步。
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE / "references" / "commands-index.md"
CLI = Path(__file__).resolve().parents[2] / "cli" / "maidcli.py"

KIND_ZH = {"query": "查询（只读）", "command": "操作（会改世界 / 改状态）"}


def fetch() -> dict:
    cmd = [sys.executable, str(CLI), "cmds", "--json"]
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=60)
    if r.returncode != 0:
        raise SystemExit(f"✗ 读不到 /help：{(r.stdout or '') + (r.stderr or '')}")
    out = r.stdout
    i = out.find("{")
    return json.loads(out[i:])


def render(table: dict) -> str:
    lines = [
        "<!-- 自动生成，勿手改：由 sync_commands_index.py 从服务端 GET /help 生成 -->",
        "",
        "# 指令总表（含全部参数）",
        "",
        "> 本文件是**机器生成**的参数事实表，用于查漏；讲语义、返回值、坑、示例请读 "
        "[`commands.md`](commands.md)。",
        "> 校验：`python sync_commands_index.py --check`（与服务端不一致时退出码 1）。",
        "",
    ]
    total_params = 0
    for kind in ("query", "command"):
        group = {n: c for n, c in table.items() if c.get("kind") == kind}
        if not group:
            continue
        lines += [f"## {KIND_ZH.get(kind, kind)}", "",
                  "| 指令 | 子命令 | 参数 | 类型 | 必填 | 默认 | 取值 | 说明 |",
                  "|---|---|---|---|---|---|---|---|"]
        for name in sorted(group):
            c = group[name]
            sub = (c.get("sub") or {}).get("name") or "—"
            params = c.get("params") or []
            if not params:
                lines.append(f"| `{name}` | {sub} | — | — | — | — | — | {c.get('desc', '')} |")
            for p in params:
                total_params += 1
                vals = ", ".join(str(v) for v in p["values"]) if p.get("values") else "—"
                lines.append("| {} | {} | `{}` | {} | {} | {} | {} | {} |".format(
                    f"`{name}`" if p is params[0] else "",
                    sub if p is params[0] else "",
                    p["name"], p.get("type", "—"),
                    "是" if p.get("required") else "否",
                    p.get("default", "—"),
                    vals, p.get("desc", "")))
        lines.append("")
    lines += [f"<!-- 共 {len(table)} 条指令 · {total_params} 个参数 -->", ""]
    return "\n".join(lines)


def lint_docs(table: dict) -> int:
    """扫所有 md，把 `maidcli <指令>` 里不存在的指令名报出来。"""
    import re
    names = set(table)
    skip = {"ping", "version", "cmds", "help", "login", "batch", "wake"}  # 元命令
    bad = 0
    for md in sorted(HERE.rglob("*.md")):
        for i, line in enumerate(md.read_text(encoding="utf-8").splitlines(), 1):
            for m in re.finditer(r"maidcli\s+([A-Za-z_][A-Za-z0-9_]*)", line):
                cmd = m.group(1)
                if cmd in skip or cmd in names:
                    continue
                print(f"  ✗ {md.relative_to(HERE)}:{i}  指令不存在：{cmd}")
                bad += 1
    if bad:
        print(f"✗ 文档里有 {bad} 处引用了不存在的指令")
        return 1
    print(f"✅ 文档引用的指令全部存在（{len(names)} 条在册）")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="只校验，不写文件")
    ap.add_argument("--lint-docs", action="store_true", help="校验文档里引用的指令名")
    a = ap.parse_args()

    table = fetch()
    if a.lint_docs:
        return lint_docs(table)
    text = render(table)

    if a.check:
        old = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        if old == text:
            n = text.count("\n") - text.count("\n\n")
            print(f"✅ 文档与服务端一致（{len(table)} 条指令）")
            return 0
        print("✗ 文档已漂移：服务端的指令表与 commands-index.md 不一致")
        print("  → 运行 `python sync_commands_index.py` 重新生成，并检查 commands.md 是否也要补")
        return 1

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print(f"✅ 已生成 {OUT.relative_to(HERE)}（{len(table)} 条指令）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
