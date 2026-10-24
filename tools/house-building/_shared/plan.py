"""共享：施工计划（plan JSON）的读写与比对。

计划文件长这样（由 01-blueprint-to-plan 生成）：
    {
      "anchor": [x, y, z],                        # 计划坐标系原点对应的世界坐标
      "cells": [{"p": [dx, dy, dz], "s": "minecraft:oak_stairs[facing=east]", "phase": 1}]
    }
phase 1 = 主体，phase 2 = 附着物（活板门 / 灯笼 / 地毯 …），施工时先 1 后 2。
"""
from __future__ import annotations

import json
from pathlib import Path


def load(path: str | Path) -> dict:
    d = json.loads(Path(path).read_text(encoding="utf-8"))
    if "anchor" not in d or "cells" not in d:
        raise ValueError(f"不是施工计划（缺 anchor / cells）：{path}")
    return d


def world_pos(anchor, p) -> tuple[int, int, int]:
    return anchor[0] + p[0], anchor[1] + p[1], anchor[2] + p[2]


def parse_state(s: str) -> tuple[str, dict]:
    """'minecraft:oak_stairs[facing=east,half=bottom]' → (id, {facing: east, half: bottom})"""
    if "[" not in s:
        return s, {}
    bid, rest = s.split("[", 1)
    props = {}
    for kv in rest.rstrip("]").split(","):
        if "=" in kv:
            k, v = kv.split("=", 1)
            props[k.strip()] = v.strip()
    return bid, props


def compatible(want_state: str, got_block: str, got_props: dict) -> bool:
    """蓝图声明的每个属性都必须一致；世界侧多出来的属性（如 stairs.shape）不算错。"""
    want_id, want_props = parse_state(want_state)
    if got_block != want_id:
        return False
    return all(got_props.get(k) == v for k, v in want_props.items())


def phase_of(cell: dict) -> int:
    return int(cell.get("phase") or 1)
