# ④ 施工执行

按计划逐格放置。先 `phase 1`（主体），再 `phase 2`（附着物）；同层内按最近邻排序，少跑路。

## 前置
- 女仆已唤醒、背包里有对应材料（`build.py` 不做备料）
- 服务端跑着

## 用法
```bash
python build.py --plan plan/house.json --maid A1B2C3
python build.py --plan plan/house.json --maid A1B2C3 --limit 50 --interval 4
```

## 参数
| 参数 | 默认 | 说明 |
|---|---|---|
| `--plan` | 必填 | 计划 JSON |
| `--anchor` | 计划里的 | 覆盖锚点（`x y z`）|
| `--maid` | `MAIDCLI_MAID` | 她的 6 位标签 |
| `--interval` | 2 | 放置间隔（tick）—— 调大可让动作看起来更像人 |
| `--limit` | 0 | 只做前 N 格（调试）|

## 输出
```
完成：新放 883 · 本来就有 61 · 失败 90（共 1034 格）
失败清单（可交给 06-repair-loop 再跑一轮）：
  (-184,134,33)  蓝图=minecraft:dark_oak_slab  →  实际=minecraft:air
```
退出码 `1` 表示有失败格 —— 直接跑 ⑥ 补漏即可，不要重复跑本工具。

## 限制
- 不做备料：材料不够就如实失败（`place` 会报「身上没有可放的方块」）
- 她若坐在船/矿车里，走位会失效 —— 先 `maidcli macro dismount`
