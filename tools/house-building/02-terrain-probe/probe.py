#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""选址勘察：在一片区域上采样，找出「平地 / 起伏 / 最高点」，帮你决定平台铺在哪、铺多高。

只读 —— 通过 `blocks` 逐点读世界，不发任何写操作。

退出码：0 = 勘察完成 · 2 = 参数/连接错误
"""
from __future__ import annotations

import argparse
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "_shared"))
from maidlib import Maid, MaidError   # noqa: E402

HERE = Path(__file__).resolve().parent


def load_config(path: str | None) -> dict:
    import json
    p = Path(path) if path else HERE / "config.json"
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else {}


def column_top(m: Maid, x: int, z: int, y_lo: int, y_hi: int) -> tuple[int, str]:
    """从高往低找第一格非空气 —— 返回 (高度, 方块)。全空气返回 (y_lo-1, air)。"""
    for y in range(y_hi, y_lo - 1, -1):
        bid, _ = m.block(x, y, z)
        if bid != "minecraft:air":
            return y, bid
    return y_lo - 1, "minecraft:air"


def main() -> int:
    ap = argparse.ArgumentParser(description="区域地形勘察")
    ap.add_argument("--center", nargs="+", metavar="X,Y,Z",
                    help="中心点：三种写法都行 `-160 141 30` / `-160,141,30`（不给就用她当前所在格）")
    ap.add_argument("--radius", type=int, default=12, help="半径（格，默认 12）")
    ap.add_argument("--step", type=int, default=3, help="采样步长（格，默认 3）")
    ap.add_argument("--y-range", type=int, nargs=2, default=[-8, 8], metavar=("LO", "HI"),
                    help="相对中心点的竖向搜索范围（默认 -8 8）")
    ap.add_argument("--maid")
    ap.add_argument("--config")
    a = ap.parse_args()

    cfg = load_config(a.config)
    maid_tag = a.maid or cfg.get("maid")
    if not maid_tag:
        print("✗ 缺 --maid（或 config.json 里写 maid）", file=sys.stderr)
        return 2

    try:
        m = Maid(maid=maid_tag, host=cfg.get("host"), port=cfg.get("port"))
        m.awake()

        if a.center:
            raw = " ".join(a.center).replace(",", " ").split()
            if len(raw) != 3:
                print("✗ --center 要给 3 个数字", file=sys.stderr)
                return 2
            cx, cy, cz = (int(v) for v in raw)
        else:
            d = m.data("sense", "self", "part=pos") if False else m.data("sense", "self")
            pos = d.get("pos") or d.get("position")
            if not pos:
                print("✗ 读不到她的坐标，请用 --center 指定", file=sys.stderr)
                return 2
            cx, cy, cz = int(pos[0]), int(pos[1]), int(pos[2])

        y_lo, y_hi = cy + a.y_range[0], cy + a.y_range[1]
        r = a.radius
        tops: list[tuple[int, int, int, str]] = []
        for x in range(cx - r, cx + r + 1, a.step):
            for z in range(cz - r, cz + r + 1, a.step):
                h, bid = column_top(m, x, z, y_lo, y_hi)
                tops.append((x, z, h, bid))
            print(f"  ...扫描到 x={x}", flush=True)

        heights = [h for _, _, h, _ in tops]
        blocks = Counter(b for _, _, _, b in tops)
        hi_pt = max(tops, key=lambda t: t[2])
        lo_pt = min(tops, key=lambda t: t[2])

        print(f"\n采样点 {len(tops)} 个（半径 {r} · 步长 {a.step} · y {y_lo}~{y_hi}）")
        print(f"  地表高度：最高 {hi_pt[2]} @({hi_pt[0]},{hi_pt[1]})"
              f" · 最低 {lo_pt[2]} @({lo_pt[0]},{lo_pt[1]}) · 起伏 {hi_pt[2] - lo_pt[2]} 格")
        print(f"  最常见高度：{Counter(heights).most_common(3)}")
        print(f"  地表方块：{blocks.most_common(4)}")
        print(f"\n铺平台建议：底面取 y={max(heights) + 1}（最高点之上），"
              f"覆盖 x {cx - r}~{cx + r} · z {cz - r}~{cz + r}")
        return 0
    except (MaidError, ValueError, OSError) as e:
        print(f"✗ {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
