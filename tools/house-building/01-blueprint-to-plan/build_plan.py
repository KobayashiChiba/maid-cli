#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""全自动建房 · 阶段 1 —— 蓝图 → 施工计划 JSON

流程：
  ① 用 litemapy 生成 .litematic（demo 蓝图，可复用的真文件）
  ② 读回 .litematic → 转成施工计划 JSON（模组 BuildRunner 直接吃）

计划 JSON 格式（见 knowledge/build-module-design.md §5）：
  {
    "name": "demo-pavilion",
    "anchor": [x, y, z],          # 蓝图 (0,0,0) 对应的世界坐标
    "buildDefaultLevel": 3,       # 默认覆盖等级（最高）
    "regions": [ {"yBelow": N, "level": 0} ],   # 可选：区域级等级覆盖
    "cells": [ {"p":[rx,ry,rz], "s":"minecraft:xxx[props]", "lv":3, "phase":1} ]
  }
  phase：1 = 普通方块（第一遍）  2 = 需依附的方块（第二遍：火把/灯笼/门/活板门/告示牌…）

用法：
  python build_plan.py --from-litematic house.litematic --anchor -203 125 21 --outdir plan/
"""
import argparse
import json
import os
import sys
from litemapy import Schematic, Region, BlockState

HERE = os.path.dirname(os.path.abspath(__file__))
# 蓝图放同目录的 schematics/（不用随仓库发布，只有自己想用的那些才需要放进去）
SCHEM_DIR = os.path.join(HERE, "schematics")

# ── 需要依附/形状受邻居影响的方块 → 放第二遍（照抄 Create 的 isBrittle 思路）──
NEEDS_ATTACH = (
    "torch", "lantern", "wall_torch", "soul_torch", "soul_lantern",
    "ladder", "sign", "banner", "button", "lever", "pressure_plate",
    "door", "trapdoor", "bed", "flower_pot", "vine", "cocoa", "cake",
    "carpet", "rail", "repeater", "comparator", "redstone_wire", "bell",
    "chain", "candle", "hanging_roots", "glow_lichen", "sculk_vein",
)


def is_attachment(bid: str) -> bool:
    name = bid.split(":")[-1]
    if name.endswith("_sign") or name.endswith("_wall_sign") or name.endswith("_hanging_sign"):
        return True
    if name.endswith("_button") or name.endswith("_pressure_plate"):
        return True
    if name.endswith("_bed") or name.endswith("_banner") or name.endswith("_door"):
        return True
    if name.endswith("_trapdoor"):   # 活板门能独立放，但形状依赖邻居 → 保守放第二遍
        return True
    return any(k in name for k in NEEDS_ATTACH)


# ─────────────────────────── demo 蓝图：小凉亭 ───────────────────────────
# 5(x) × 5(y) × 5(z)
#   y=0      地板 5×5        stone_bricks ×25
#   y=1..3   四角柱子        oak_log[axis=y] ×12
#   y=4      屋顶 5×5        spruce_planks ×25
#   y=3      挂灯笼 ×5       lantern[hanging=true]（第二遍放）
# 合计 67 格
def build_demo_pavilion() -> Region:
    reg = Region(0, 0, 0, 5, 5, 5)
    for x in range(5):
        for z in range(5):
            reg[x, 0, z] = BlockState("minecraft:stone_bricks")                 # 地板
            reg[x, 4, z] = BlockState("minecraft:spruce_planks")                # 屋顶
    for (x, z) in ((0, 0), (4, 0), (0, 4), (4, 4)):
        for y in (1, 2, 3):
            reg[x, y, z] = BlockState("minecraft:oak_log", axis="y")            # 柱子
    for (x, z) in ((1, 1), (3, 1), (1, 3), (3, 3), (2, 2)):
        reg[x, 3, z] = BlockState("minecraft:lantern", hanging="true")          # 灯笼
    return reg


BLUEPRINTS = {
    "demo-pavilion": build_demo_pavilion,
}

# 1.21.9 新方块 / 我们放不了的 → 同类替代（沿用 2026-10-04 实战定的表）
DEFAULT_SUBS = {
    "minecraft:pale_oak_shelf": "minecraft:birch_planks",
    "minecraft:pale_oak_fence": "minecraft:birch_fence",
    "minecraft:pale_oak_fence_gate": "minecraft:birch_fence_gate",
    "minecraft:iron_chain": "minecraft:iron_bars",
    "minecraft:oak_wall_hanging_sign": "minecraft:oak_hanging_sign",
}


def plan_from_litematic(name: str, path: str, anchor, out_json: str,
                        default_level: int = 3, skip_layers=(), skip_blocks=(),
                        subs=None, regions=None) -> dict:
    """从【已有的 .litematic】生成施工计划。

    ⚠️ 坐标映射（上次"整栋低一格"的根因就在这里）：
       蓝图里的【地面参考层】应该【和世界地面重合】—— 所以 p = 蓝图坐标原样，
       anchor.y 取【世界地面那一格】的 y。
       例：蓝图 y=0 是草坪层（不建）、y=1 是房子最底层；
           世界地面草方块在 y=133 ⇒ anchor.y = 133 ⇒ 蓝图 y=1 → 世界 y=134 ✓
       （旧脚本写的是 OY + (y-1)，把整栋压低了一格 ✗）
    """
    subs = subs if subs is not None else DEFAULT_SUBS
    cells, size = cells_from_litematic(path)
    plan_cells = []
    skipped = {}
    for (x, y, z, state) in cells:
        if y in skip_layers:
            skipped["层 y=" + str(y)] = skipped.get("层 y=" + str(y), 0) + 1
            continue
        bid = state.split("[")[0]
        if bid in skip_blocks:
            skipped[bid] = skipped.get(bid, 0) + 1
            continue
        if bid in subs:                      # 方块替代（属性丢掉，换成同类）
            state = subs[bid]
        plan_cells.append({
            "p": [x, y, z],
            "s": state,
            "lv": default_level,
            "phase": 2 if is_attachment(state) else 1,
        })
    cx, cz = (size[0] - 1) / 2, (size[2] - 1) / 2
    plan_cells.sort(key=lambda c: (c["p"][1], c["phase"],
                                   abs(c["p"][0] - cx) + abs(c["p"][2] - cz)))
    plan = {
        "name": name,
        "anchor": list(anchor),
        "buildDefaultLevel": default_level,
        "regions": regions or [],
        "size": list(size),
        "cells": plan_cells,
    }
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump(plan, f, ensure_ascii=False, indent=1)

    by_y, mats = {}, {}
    for c in plan_cells:
        by_y[c["p"][1]] = by_y.get(c["p"][1], 0) + 1
        k = c["s"].split("[")[0].replace("minecraft:", "")
        mats[k] = mats.get(k, 0) + 1
    print(f"✓ 施工计划: {out_json}")
    print(f"  格子 {len(plan_cells)} ｜ 层数 {len(by_y)} ｜ 材料种类 {len(mats)}")
    if skipped:
        print(f"  跳过: " + ", ".join(f"{k}×{v}" for k, v in skipped.items()))
    print(f"  分层: " + ", ".join(f"y={y}:{n}" for y, n in sorted(by_y.items())))
    print(f"  材料(按量): " + ", ".join(f"{k}×{v}" for k, v in
                                      sorted(mats.items(), key=lambda kv: -kv[1])[:14]))
    # 粗算要分几批（材料种类 ≤18 / 格数 ≤220）
    batches, types, cnt = 1, set(), 0
    for c in plan_cells:
        t = c["s"].split("[")[0]
        if (t not in types and len(types) >= 18) or cnt >= 220:
            batches += 1
            types, cnt = set(), 0
        types.add(t)
        cnt += 1
    print(f"  ⇒ 粗估要分【{batches} 批】")
    return plan


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--blueprint", default="demo-pavilion", choices=list(BLUEPRINTS))
    ap.add_argument("--from-litematic", default=None, help="直接用已有的 .litematic（文件名或绝对路径）")
    ap.add_argument("--schem-dir", default=None, help="蓝图目录（默认同目录 schematics/）")
    ap.add_argument("--anchor", nargs=3, type=int, required=True)
    ap.add_argument("--level", type=int, default=3)
    ap.add_argument("--skip-layer", type=int, action="append", default=None,
                    help="跳过某层（草坪参考层等），可重复。★ 默认 [0] —— 地面层是【环境】，"
                         "不施工（地面属于已有地形，流程规划里不应包含挖地操作）")
    ap.add_argument("--skip-block", action="append", default=[],
                    help="跳过某方块 id（角标记块等），可重复")
    ap.add_argument("--no-subs", action="store_true",
                    help="★ 蓝图里没有的方块【直接跳过、空着】，不拿相近方块顶"
                         "（自动把 DEFAULT_SUBS 里的目标方块全部加入跳过名单）"
                         "（固定约定）")
    ap.add_argument("--outdir", default=SCHEM_DIR)
    a = ap.parse_args()

    os.makedirs(a.outdir, exist_ok=True)
    if a.from_litematic:
        # 路径解析：① 给的是存在的路径就直接用 ② 否则当成蓝图名去 schem-dir 里找
        lit = a.from_litematic
        if not os.path.exists(lit):
            lit = os.path.join(a.schem_dir or SCHEM_DIR, lit)
            if not lit.endswith(".litematic"):
                lit += ".litematic"
        if not os.path.exists(lit):
            print("✗ 找不到蓝图：" + lit)
            print("  → 用 --from-litematic <名字或路径>，或把文件放进 " + (a.schem_dir or SCHEM_DIR))
            return
        name = os.path.basename(lit)
        if name.endswith(".litematic"):
            name = name[: -len(".litematic")]
        js = os.path.join(a.outdir, name + ".json")
        # ★ --no-subs：不只"不替代"，而是把这些方块【整格跳过】（空着）——
        #   因为游戏里压根没有它们（如 1.21.9 的 pale_oak_* / iron_chain）
        skip = list(a.skip_block)
        if a.no_subs:
            skip += list(DEFAULT_SUBS.keys())
        plan_from_litematic(name, lit, a.anchor, js,
                            default_level=a.level,
                            skip_layers=tuple([0] if a.skip_layer is None else a.skip_layer),
                            skip_blocks=tuple(skip),
                            subs={} if a.no_subs else None)
        return

    lit = os.path.join(a.outdir, f"{a.blueprint}.litematic")
    js = os.path.join(a.outdir, f"{a.blueprint}.json")
    make_litematic(a.blueprint, lit)
    to_plan(a.blueprint, lit, a.anchor, js, default_level=a.level)


# ─────────────────────────── 生成 / 解析 ───────────────────────────
def make_litematic(name: str, path: str) -> None:
    reg = BLUEPRINTS[name]()
    s = Schematic(name=name, author="xiaofeng")
    s.regions["main"] = reg
    s.save(path)
    print(f"✓ 生成 litematic: {path}  ({reg.width}×{reg.height}×{reg.length})")


def cells_from_litematic(path: str):
    """读 .litematic → [(rx,ry,rz, blockstate_string)]，跳过空气。"""
    s = Schematic.load(path)
    reg = list(s.regions.values())[0]
    out = []
    for x in reg.xrange():
        for y in reg.yrange():
            for z in reg.zrange():
                b = reg[x, y, z]
                if b.id == "minecraft:air":
                    continue
                props = {}
                try:
                    props = {k: str(v) for k, v in dict(b.properties()).items()}
                except Exception:
                    props = {}
                state = b.id
                if props:
                    state += "[" + ",".join(f"{k}={v}" for k, v in sorted(props.items())) + "]"
                out.append((x, y, z, state))
    return out, (reg.width, reg.height, reg.length)


def to_plan(name: str, litematic: str, anchor, out_json: str,
            default_level: int = 3, regions=None) -> dict:
    cells, size = cells_from_litematic(litematic)
    plan_cells = []
    for (x, y, z, state) in cells:
        plan_cells.append({
            "p": [x, y, z],
            "s": state,
            "lv": default_level,
            "phase": 2 if is_attachment(state) else 1,
        })
    # 分层（y 升序）→ 分期（phase 升序）→ 同层内按到中心的曼哈顿距离（由近及远，少跑路）
    cx, cz = (size[0] - 1) / 2, (size[2] - 1) / 2
    plan_cells.sort(key=lambda c: (c["p"][1], c["phase"],
                                   abs(c["p"][0] - cx) + abs(c["p"][2] - cz)))
    plan = {
        "name": name,
        "anchor": list(anchor),
        "buildDefaultLevel": default_level,
        "regions": regions or [],
        "size": list(size),
        "cells": plan_cells,
    }
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump(plan, f, ensure_ascii=False, indent=1)
    print(f"✓ 施工计划: {out_json}")
    print(f"  格子数 = {len(plan_cells)}（其中第二遍/附着物 "
          f"{sum(1 for c in plan_cells if c['phase'] == 2)} 个）")
    mats = {}
    for c in plan_cells:
        mats[c["s"].split("[")[0]] = mats.get(c["s"].split("[")[0], 0) + 1
    print(f"  材料: " + ", ".join(f"{k.replace('minecraft:','')}×{v}" for k, v in sorted(mats.items())))
    by_y = {}
    for c in plan_cells:
        by_y[c["p"][1]] = by_y.get(c["p"][1], 0) + 1
    print(f"  分层: " + ", ".join(f"y={y}:{n}" for y, n in sorted(by_y.items())))
    return plan


if __name__ == "__main__":
    main()
