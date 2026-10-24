#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""收敛补漏：每轮"全量核验 → 只修不一致的格"，直到没有进展为止。

为什么这么设计：她放不上的格子往往不是"永远放不上"，而是当时站位/材料/朝向不合适；
换个站位、补上材料再来一轮，通常就成。所以不做单次重试，做**整轮重来**。

退出码：0 = 收敛到零差异 · 1 = 还有残留 · 2 = 参数/连接错误
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


def scan(m: Maid, doc: dict) -> list[dict]:
    """返回所有对不上的格子（附世界坐标）。"""
    bad = []
    for c in doc["cells"]:
        x, y, z = planlib.world_pos(doc["anchor"], c["p"])
        got_id, got_props = m.block(x, y, z)
        if not planlib.compatible(c["s"], got_id, got_props):
            bad.append({"pos": (x, y, z), "state": c["s"], "got": got_id})
    return bad


def fix_one(m: Maid, pos, state: str, interval: int) -> bool:
    """放一格：先直接放；放不上就先挖掉再放。返回最终是否对。"""
    x, y, z = pos
    try:
        m.run("place", f"pos={x},{y},{z}", f"item={state}", f"interval={interval}")
    except MaidError:
        try:
            m.run("break", f"pos={x},{y},{z}")
            m.run("place", f"pos={x},{y},{z}", f"item={state}", f"interval={interval}")
        except MaidError:
            pass
    got_id, got_props = m.block(x, y, z)
    return planlib.compatible(state, got_id, got_props)


def main() -> int:
    ap = argparse.ArgumentParser(description="收敛式补漏")
    ap.add_argument("--plan", required=True)
    ap.add_argument("--maid")
    ap.add_argument("--rounds", type=int, default=6, help="最多几轮（默认 6）")
    ap.add_argument("--interval", type=int, default=2, help="放置间隔 tick（默认 2）")
    ap.add_argument("--config")
    a = ap.parse_args()

    cfg = load_config(a.config)
    maid_tag = a.maid or cfg.get("maid")
    if not maid_tag:
        print("✗ 缺 --maid（或 config.json 里写 maid）", file=sys.stderr)
        return 2

    try:
        doc = planlib.load(a.plan)
        m = Maid(maid=maid_tag, host=cfg.get("host"), port=cfg.get("port"))
        m.awake()

        for rnd in range(1, a.rounds + 1):
            bad = scan(m, doc)
            total = len(doc["cells"])
            print(f"[round {rnd}] 一致 {total - len(bad)} · 不一致 {len(bad)}", flush=True)
            if not bad:
                print("✅ 已收敛到零差异")
                return 0
            fixed = 0
            for n, item in enumerate(bad, 1):
                if fix_one(m, item["pos"], item["state"], a.interval):
                    fixed += 1
                if n % 10 == 0:
                    print(f"   ...修 {n}/{len(bad)}（成功 {fixed}）", flush=True)
            print(f"[round {rnd}] 修完：成功 {fixed} / 待修 {len(bad)}", flush=True)
            if fixed == 0:
                print("这轮没有进展，停止。残留清单见下：")
                for item in bad:
                    x, y, z = item["pos"]
                    print(f"  ({x},{y},{z})  蓝图={item['state']}  →  实际={item['got']}")
                return 1

        rest = scan(m, doc)
        if rest:
            print(f"跑满 {a.rounds} 轮，仍有 {len(rest)} 格不一致")
            return 1
        print("✅ 已收敛到零差异")
        return 0
    except (MaidError, ValueError, OSError) as e:
        print(f"✗ {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
