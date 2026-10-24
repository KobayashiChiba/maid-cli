# 建房流水线（house-building）

一条从「蓝图文件」到「世界里的房子」的完整工具链。**所有工具都只通过 `maidcli` 与女仆交互**，
不使用 RCON、不发作弊指令。

```
① blueprint-to-plan    .litematic ──→ 施工计划 JSON（每格坐标 + 方块状态 + 相位）
        ↓
② terrain-probe        选一块平地 / 决定平台铺多高（只读勘察）
        ↓
③ plan-preview         把计划渲染成 3D 预览图（先看效果再动手）
        ↓
④ build-runner         驱动女仆逐格放置（phase 1 主体 → phase 2 附着物）
        ↓
⑤ verify-build         独立核验：重新读世界，逐格比对计划（只读）
        ↓
⑥ repair-loop          还有不一致？每轮「全量核验 → 只修不一致」，直到收敛
```

## 工具一览

| 步骤 | 目录 | 入口 | 需要连服务器 | 第三方依赖 |
|---|---|---|---|---|
| ① | `01-blueprint-to-plan/` | `build_plan.py` | 否 | `litemapy` |
| ② | `02-terrain-probe/` | `probe.py` | 是（只读）| 无 |
| ③ | `03-plan-preview/` | `render.py` | 否 | `matplotlib` `numpy` |
| ④ | `04-build-runner/` | `build.py` | 是（写）| 无 |
| ⑤ | `05-verify-build/` | `verify.py` | 是（只读）| 无 |
| ⑥ | `06-repair-loop/` | `repair.py` | 是（写）| 无 |

## 通用约定

- **连接**：一律走 `maidcli`。地址/端口用 `--host/--port` 或环境变量 `MAIDCLI_HOST/MAIDCLI_PORT`；
  女仆用 `--maid <6位标签>` 或 `MAIDCLI_MAID`。共享封装在 `_shared/maidlib.py`。
- **配置**：每个工具目录下可以放 `config.json`（真实配置，**不入库**），
  仓库里只提供 `config.example.json`；用 `--config` 指定别的路径。
- **退出码**：`0` 成功 · `1` 跑完了但有失败项 · `2` 参数错 / 连不上服务端。
- **成败判据**：只认**世界侧读数**（`blocks` 重新读一遍），不认命令自己报的 ok。
- **坐标写法**：`pos=x,y,z`；负数坐标在命令行里要写成 `--center=-160,141,30`（等号形式），
  否则 `-160` 会被当成选项名。

## 一次跑完（示例）

```bash
cd tools/house-building

# ① 蓝图 → 计划
python 01-blueprint-to-plan/build_plan.py --blueprint house --anchor -203 125 21 --outdir plan/

# ② 选地（可选）
python 02-terrain-probe/probe.py --center=-190,120,30 --radius 16 --maid A1B2C3

# ③ 预览（可选）
python 03-plan-preview/render.py --plan plan/house.json --out plan/house.png

# ④ 施工
python 04-build-runner/build.py --plan plan/house.json --maid A1B2C3

# ⑤ 核验
python 05-verify-build/verify.py --plan plan/house.json --maid A1B2C3

# ⑥ 补漏（有残留时）
python 06-repair-loop/repair.py --plan plan/house.json --maid A1B2C3 --rounds 6
```

参考成绩：1034 格的房子 → 首轮施工约 65 分钟到 96%，补漏两轮后 **1033/1034（99.9%）**；
唯一放不上的那格在屋顶最高点，周围没有可站落点 —— 这是正确行为，不是 bug。

## 目录内还有什么

- `_shared/maidlib.py` —— 调 CLI 的唯一入口（子进程封装、`--json` 解析、错误归一）
- `_shared/plan.py` —— 计划读写、blockstate 解析与比对
