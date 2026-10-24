# 自动建房流水线

六个工具串成一条链，都在 `tools/house-building/`。

| 步骤 | 工具 | 作用 |
|---|---|---|
| ① | `01-blueprint-to-plan/build_plan.py` | `.litematic` 蓝图 → 施工计划 JSON |
| ② | `02-terrain-probe/probe.py` | 区域勘察，选平地 / 定平台高度 |
| ③ | `03-plan-preview/render.py` | 渲染预览图（动手前看一眼）|
| ④ | `04-build-runner/build.py` | 逐格放置（phase 1 主体 → phase 2 附着物）|
| ⑤ | `05-verify-build/verify.py` | 独立核验：重读世界逐格比对 |
| ⑥ | `06-repair-loop/repair.py` | 每轮"全量核验 → 只修不一致"，直到收敛 |

## 完整流程

```bash
cd tools/house-building

# ① 蓝图 → 计划（anchor 是你希望房子原点所在的世界坐标）
python 01-blueprint-to-plan/build_plan.py \
    --from-litematic house.litematic --anchor -203 125 21 --outdir plan/

# ② 选地（可选，但强烈建议）
python 02-terrain-probe/probe.py --center=-190,120,30 --radius 16 --step 3 --maid A1B2C3

# ③ 预览（可选）
python 03-plan-preview/render.py --plan plan/house.json --out plan/house.png

# ④ 施工
python 04-build-runner/build.py --plan plan/house.json --maid A1B2C3

# ⑤ 核验
python 05-verify-build/verify.py --plan plan/house.json --maid A1B2C3

# ⑥ 补漏（⑤ 有残留时）
python 06-repair-loop/repair.py --plan plan/house.json --maid A1B2C3 --rounds 6
```

## 关键前提（不做就会失败）

1. **材料要先进她背包**：流水线不做备料。材料不够时 `place` 会报"身上没有可放的方块"
2. **她要能自由走动**：`control` 接管（指令模式）+ 别让她坐在船里
3. **相位的意义**：`phase 1` 先铺主体，`phase 2` 才放需要依附面的方块（灯笼/活板门/门/地毯）；
   顺序反了会出现"放不上"
4. **成败只看世界**：④ 跑完一定跑 ⑤，别信 ④ 的返回

## 预期成绩（实测参考）

1034 格的木屋：
- 首轮施工 ≈ 65 分钟，到位约 96%（883 格新放 + 61 格本来就对）
- 补漏两轮 → **1033/1034（99.9%）**
- 剩的那 1 格在屋顶最高点，周围没有可站落点 —— 属于正确失败，不是 bug

## 常见失败分类

| 报错 | 原因 | 怎么处理 |
|---|---|---|
| `身上没有可放的方块` | 材料没备 / 用完了 | 补料到背包，再跑 ⑥ |
| `走位后仍够不到目标` | 屋内高处没有落脚点 | 搭脚手架，或接受这一格 |
| `那个位置不是空地` | 有错方块 | ⑥ 会先 `break` 再 `place` |
| 相位错乱（附着物先放）| 计划里 phase 标错 | 回 ① 检查 `phase` 字段 |

## 自定义

- 换蓝图：`--from-litematic <任意 .litematic>`
- 挪位置：施工时用 `--anchor x y z` 覆盖
- 调节奏：`--interval`（默认 2 tick；录像用大一点更自然）
- 只做一部分：`--limit N`（调试用）
