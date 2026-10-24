# ③ 计划预览

把计划 JSON 渲染成 3D 体素图 —— 动手之前先看看盖出来什么样。

## 前置
- `pip install -r requirements.txt`（`matplotlib` `numpy`）
- 不需要连服务器

## 用法
```bash
python render.py --plan plan/house.json --out plan/house.png
```

## 参数
| 参数 | 说明 |
|---|---|
| `--plan` | 计划 JSON |
| `--out` | 输出 PNG 路径 |

## 输出
一张等距视角的体素图（每个方块按方块类型上色，未收录的颜色用灰色兜底）。

## 限制
- 只为「看个大概」，不是精确渲染：不处理纹理、光照、透明方块叠加
- 大房子（>5000 格）渲染会慢，建议先 `--plan` 裁剪
