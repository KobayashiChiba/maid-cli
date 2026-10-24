# ⑤ 独立核验

重新读一遍世界，逐格比对计划。**只读**，不写世界。

判据：方块 id 必须一致，且蓝图里声明的**每个属性**都要对上（世界侧多出来的属性如
`stairs.shape` 不算错）。

## 前置
- 服务端跑着、女仆已加载

## 用法
```bash
python verify.py --plan plan/house.json --maid A1B2C3
python verify.py --plan plan/house.json --maid A1B2C3 --limit 50 --quiet
```

## 参数
| 参数 | 说明 |
|---|---|
| `--plan` | 计划 JSON |
| `--maid` | 她的 6 位标签 |
| `--limit` | 只核验前 N 格（调试）|
| `--quiet` | 不打印进度 |

## 输出
```
蓝图 1034 格：一致 1033，不一致 1
不一致清单：
  (-181,145,40)  蓝图=minecraft:purple_wool  →  实际=minecraft:air
```
退出码：`0` 全一致 · `1` 有差异 · `2` 参数/连接错误。

## 限制
- 每格一次 HTTP 查询，1034 格约 2~3 分钟
