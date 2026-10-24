#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""施工执行：按计划逐格放置（phase 1 主体 → phase 2 附着物）。

排序策略：先按 phase，再按 y 分层，同层内按"离上一格最近"贪心排序 —— 少跑路。
成败一律以【世界侧读数】为准（不信命令返回的 ok）。

退出码：0 = 全部到位 · 1 = 有失败格（可再跑 06-repair-loop）· 2 = 参数/连接错误
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "_shared"))
import plan as planlib          # noqa: E402
from maidlib import Maid, MaidError   # noqa: E402

HERE = Path(__file__).resolve().parent


def load_config(path: str | None) -> dict:
    import json
    p = Path(path) if path else HERE / "config.json"
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else {}


def order(cells: list[dict]) -> list[dict]:
    """phase → y → 同层最近邻。"""
    out = []
    for ph in sorted({planlib.phase_of(c) for c in cells}):
        group = [c for c in cells if planlib.phase_of(c) == ph]
        for y in sorted({c["p"][1] for c in group}):
            layer = [c for c in group if c["p"][1] == y]
            cur = (0, 0, 0)
            rest = list(layer)
            while rest:
                rest.sort(key=lambda c: abs(c["p"][0] - cur[0]) + abs(c["p"][2] - cur[2]))
                nxt = rest.pop(0)
                out.append(nxt)
                cur = (nxt["p"][0], y, nxt["p"][2])
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description="按照施工计划驱动她盖房子")
    ap.add_argument("--plan", required=True)
    ap.add_argument("--anchor", nargs=3, type=int, help="覆盖计划里的 anchor（x y z）")
    ap.add_argument("--maid")
    ap.add_argument("--interval", type=int, default=2, help="放置间隔 tick（默认 2）")
    ap.add_argument("--limit", type=int, default=0, help="只做前 N 格（调试）")
    ap.add_argument("--config")
    a = ap.parse_args()

    cfg = load_config(a.config)
    maid_tag = a.maid or cfg.get("maid")
    if not maid_tag:
        print("✗ 缺 --maid（或 config.json 里写 maid）", file=sys.stderr)
        return 2
    interval = a.interval or cfg.get("interval") or 2

    try:
        doc = planlib.load(a.plan)
        if a.anchor:
            doc["anchor"] = list(a.anchor)
        cells = order(doc["cells"])
        if a.limit:
            cells = cells[: a.limit]

        m = Maid(maid=maid_tag, host=cfg.get("host"), port=cfg.get("port"),
                 timeout=cfg.get("timeout", 120))
        m.awake()

        placed = already = failed = 0
        fails = []
        for n, c in enumerate(cells, 1):
            x, y, z = planlib.world_pos(doc["anchor"], c["p"])
            got_id, got_props = m.block(x, y, z)
            if planlib.compatible(c["s"], got_id, got_props):
                already += 1
            else:
                ok = False
                try:
                    m.run("place", f"pos={x},{y},{z}", f"item={c['s']}", f"interval={interval}")
                except MaidError:
                    try:                                  # 那里有错方块 → 先挖掉再放
                        m.run("break", f"pos={x},{y},{z}")
                        m.run("place", f"pos={x},{y},{z}", f"item={c['s']}", f"interval={interval}")
                    except MaidError:
                        pass
                got_id, got_props = m.block(x, y, z)
                ok = planlib.compatible(c["s"], got_id, got_props)
                placed += 1 if ok else 0
                if not ok:
                    failed += 1
                    fails.append((x, y, z, c["s"], got_id))
            if n % 50 == 0:
                print(f"  ...{n}/{len(cells)}  新放 {placed} · 已有 {already} · 失败 {failed}", flush=True)

        print(f"完成：新放 {placed} · 本来就有 {already} · 失败 {failed}（共 {len(cells)} 格）")
        if fails:
            print("\n失败清单（可交给 06-repair-loop 再跑一轮）：")
            for x, y, z, want, got in fails[:40]:
                print(f"  ({x},{y},{z})  蓝图={want}  →  实际={got}")
            if len(fails) > 40:
                print(f"  ……还有 {len(fails) - 40} 格")
            return 1
        return 0
    except (MaidError, ValueError, OSError) as e:
        print(f"✗ {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
