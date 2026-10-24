#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""核验：拿施工计划逐格比对世界（只读，不写世界、不发任何作弊指令）。

退出码：0 = 完全一致 · 1 = 有不一致 · 2 = 参数/连接错误
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
    if p.exists():
        return json.loads(p.read_text(encoding="utf-8"))
    return {}


def main() -> int:
    ap = argparse.ArgumentParser(description="逐格核验施工结果")
    ap.add_argument("--plan", required=True, help="施工计划 JSON")
    ap.add_argument("--maid", help="她的 6 位标签（也可 MAIDCLI_MAID）")
    ap.add_argument("--limit", type=int, default=0, help="只核验前 N 格（调试用）")
    ap.add_argument("--config", help="配置文件（默认同目录 config.json）")
    ap.add_argument("--quiet", action="store_true", help="只打印汇总")
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

        cells = doc["cells"][: a.limit] if a.limit else doc["cells"]
        ok, bad = 0, []
        for n, c in enumerate(cells, 1):
            x, y, z = planlib.world_pos(doc["anchor"], c["p"])
            got_id, got_props = m.block(x, y, z)
            if planlib.compatible(c["s"], got_id, got_props):
                ok += 1
            else:
                bad.append((x, y, z, c["s"], got_id, got_props))
            if not a.quiet and n % 200 == 0:
                print(f"  ...已核验 {n}/{len(cells)}", flush=True)

        print(f"蓝图 {len(cells)} 格：一致 {ok}，不一致 {len(bad)}")
        if bad:
            print("\n不一致清单：")
            for x, y, z, want, got_id, got_props in bad[:40]:
                print(f"  ({x},{y},{z})  蓝图={want}  →  实际={got_id}{got_props or ''}")
            if len(bad) > 40:
                print(f"  ……还有 {len(bad) - 40} 格")
            return 1
        print("✅ 完全一致 —— 零差异")
        return 0
    except (MaidError, ValueError, OSError) as e:
        print(f"✗ {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
