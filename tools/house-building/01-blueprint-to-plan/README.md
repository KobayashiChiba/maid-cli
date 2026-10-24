# ① 蓝图 → 施工计划

把 `.litematic` 蓝图转成本流水线通用的计划 JSON。

## 前置
- Python 3.9+
- `pip install -r requirements.txt`（`litemapy`，用于解析 `.litematic`）

## 用法
```bash
python build_plan.py --blueprint <名字或路径> --anchor <x> <y> <z> --outdir plan/
```

## 参数
| 参数 | 说明 |
|---|---|
| `--blueprint` | 蓝图名（在 `schematics/` 里找）或 `.litematic` 路径 |
| `--anchor` | 计划原点对应的世界坐标 `x y z` |
| `--outdir` | 输出目录（默认当前目录） |

## 输出
`<outdir>/<名字>.json`：
```json
{"anchor": [x,y,z],
 "cells": [{"p": [dx,dy,dz], "s": "minecraft:oak_stairs[facing=east]", "phase": 1}]}
```
`phase 1` = 主体方块，`phase 2` = 需要依附面的方块（灯笼/活板门/门/地毯…），施工时先 1 后 2。

## 示例
```bash
python build_plan.py --blueprint small-cozy-house --anchor -203 125 21 --outdir plan/
# → plan/small-cozy-house.json（1034 格）
```

## 限制
- 只处理方块与方块状态，不处理实体 / 画 / 物品展示框
- 蓝图里的空气格会被丢弃（不会去挖地基）
