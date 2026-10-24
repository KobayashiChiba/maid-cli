#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把施工计划渲染成等距/体素图（交付用视觉）。

用法：python scripts/render_plan.py --plan downloads/schematics/demo-pavilion.json --out /tmp/x.png
"""
import argparse
import json
import os

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from mpl_toolkits.mplot3d import Axes3D  # noqa: F401

COLORS = {
    "minecraft:stone_bricks": "#8a8a8a",
    "minecraft:oak_log": "#9c7a4a",
    "minecraft:spruce_planks": "#7a5a3a",
    "minecraft:lantern": "#ffd479",
    "minecraft:air": "#ffffff",
}


# 常见材质的关键词 → 颜色（命中即用，按顺序匹配；越具体的放越前）
KEYWORD_COLORS = [
    ("stripped_spruce", "#c8a878"), ("stripped_oak", "#d2b48c"), ("stripped_dark_oak", "#5a3d28"),
    ("spruce", "#7a5a3a"), ("birch", "#e0d0a8"), ("jungle", "#9c7042"), ("acacia", "#b8642c"),
    ("dark_oak", "#4a3020"), ("mangrove", "#7a4030"), ("cherry", "#e8b0b8"), ("bamboo", "#c8c060"),
    ("oak", "#b8945c"), ("planks", "#b8945c"), ("log", "#9c7a4a"),
    ("stone_brick", "#8a8a8a"), ("deepslate", "#4a4a4e"), ("tuff_brick", "#6e6e68"),
    ("tuff", "#6e6e68"), ("cobble", "#7a7a7a"), ("stone", "#8f8f8f"),
    ("sandstone", "#d8cc9c"), ("sand", "#e0d8a8"), ("gravel", "#8a857c"),
    ("dirt", "#7a5434"), ("grass_block", "#5a8f3c"), ("moss", "#5a7a3c"),
    ("brick", "#9a5444"), ("terracotta", "#a06048"), ("concrete", "#8a8a8a"),
    ("wool", "#e8e8e8"), ("glass", "#a8d8e0"), ("lantern", "#e8c050"),
    ("torch", "#f0c040"), ("leaves", "#3f7a30"), ("chest", "#a8762c"),
    ("trapdoor", "#a8843c"), ("door", "#a8843c"), ("carpet", "#d8d0c0"),
    ("slab", "#a89c8c"), ("stairs", "#a89c8c"), ("bed", "#b03030"),
    ("water", "#3060d0"), ("lava", "#e05010"), ("snow", "#f0f8ff"), ("ice", "#a0d0f0"),
]


def _hash_hue(name: str) -> str:
    """未知方块：按名字哈希给一个稳定的粉彩色（同一方块每次都是同一色）。"""
    h = 0
    for ch in name:
        h = (h * 131 + ord(ch)) & 0xFFFFFF
    hue = h % 360
    # HSL(0.45 饱和度, 0.62 亮度) → 转 RGB
    import colorsys
    r, g, b = colorsys.hls_to_rgb(hue / 360.0, 0.62, 0.45)
    return "#%02x%02x%02x" % (int(r * 255), int(g * 255), int(b * 255))


def color_of(state):
    bid = state.split("[")[0]
    if bid in COLORS:
        return COLORS[bid]
    name = bid.split(":")[-1]
    for kw, col in KEYWORD_COLORS:
        if kw in name:
            return col
    return _hash_hue(name)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--plan", required=True)
    ap.add_argument("--out", default="/tmp/plan.png")
    a = ap.parse_args()

    with open(a.plan, encoding="utf-8") as f:
        plan = json.load(f)
    cells = plan["cells"]
    sx, sy, sz = (plan.get("size") or [5, 5, 5])
    # ⚠️ matplotlib 三维图里【Z 轴才是"上"】—— 所以 MC 的 y（向上）必须放到数组第 3 维，
    #    否则整栋建筑会"躺倒"（第一版就是这么翻车的）。
    filled = np.zeros((sx, sz, sy), dtype=bool)
    colors = np.empty((sx, sz, sy), dtype=object)
    for c in cells:
        x, y, z = c["p"]
        filled[x, z, y] = True
        colors[x, z, y] = color_of(c["s"])

    fig = plt.figure(figsize=(7, 6))
    ax = fig.add_subplot(111, projection="3d")
    ax.voxels(filled, facecolors=colors, edgecolors="#333333", linewidths=0.4)
    ax.set_xlabel("X")
    ax.set_ylabel("Z")
    ax.set_zlabel("Y (up)")
    ax.set_title(f"{plan['name']}  ({len(cells)} blocks)\nanchor={plan['anchor']}", fontsize=11)
    ax.view_init(elev=22, azim=-60)
    fig.tight_layout()
    fig.savefig(a.out, dpi=130, bbox_inches="tight")
    print("saved:", a.out)


if __name__ == "__main__":
    main()
