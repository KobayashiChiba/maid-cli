# 配方（现成可复制的任务写法）

每个配方都是完整流程，可直接粘贴；参数按你的世界改。

---

## 1. 状态速览
```bash
maidcli ping && maidcli maids
maidcli wake maid=$MAIDCLI_MAID
maidcli sense self
maidcli sense inventory
maidcli get mode follow sit
```

## 2. 找并挖指定方块（挖矿回收材料）
```bash
maidcli sense blocks radius=8 find=minecraft:diamond_ore
# 从返回里挑一个 pos，然后：
maidcli break pos=-140,12,33 expect=minecraft:diamond_ore drops=inv
```
- `drops=inv`：矿石进她背包（回收）
- `drops=ground`：留在地上（矿场用，让主人用磁铁收）

## 3. 从箱子取指定数量
```bash
maidcli open pos=-150,64,30
maidcli sense session                # 找 box:N → 物品
maidcli take from=box:3 count=16     # 鼠标槽拿 16 个
maidcli put to=inv:8                 # 放进背包第 8 格
maidcli close
```
整摞搬更快：`maidcli move from=box:3`。

## 4. 把背包东西放进箱子
```bash
maidcli open pos=-150,64,30
maidcli sense inventory              # 确认 inv:N 是目标物品
maidcli move from=inv:12             # Shift 整摞搬进箱子
maidcli close
```

## 5. 工作台合成
```bash
maidcli open pos=-148,64,30          # 工作台
maidcli take from=inv:2 count=1      # 鼠标槽拿 1 个木板
maidcli put to=box:0                 # 放到合成格
maidcli select                       # 无按钮 → 直接取产物
maidcli take from=box:9              # 产物格（工作台的结果在 9）
maidcli close
```
一次做多份：`select times=4`。

## 6. 一键合成（不知道工位在哪时）
```bash
maidcli macro craft item=minecraft:stick times=4
```
它会自己找配方和合适的工位。

## 7. 村民交易
```bash
maidcli sense entities type=minecraft:villager radius=8
maidcli open entity=123
maidcli sense session                # 看 options / 支付格
maidcli put to=box:0 count=3         # 放支付物
maidcli select index=0               # 选第 1 个交易
maidcli take from=box:2              # 取产物
maidcli close
```

## 8. 铁砧改名
```bash
maidcli open pos=-144,64,30
maidcli take from=inv:5              # 拿要改名的物品
maidcli put to=box:0
maidcli input text=钻石剑·初号机
maidcli select                       # 确认
maidcli take from=box:2
maidcli close
```

## 9. 射箭
```bash
maidcli sense entities type=minecraft:zombie radius=16
maidcli macro shoot entity=28        # 移动目标会自动预判
maidcli macro shoot pos=-145.5,142.5,20.5   # 打固定点
```
报 `射不中` = 这段距离/高度解不出弹道（常见于被方块挡住所有抛物线），换角度或换站位。

## 10. 完全接管做一串活
```bash
maidcli control                      # 她不再自主行动
maidcli wake maid=$MAIDCLI_MAID
maidcli go pos=-140,64,30
maidcli break pos=-140,64,30 drops=inv
maidcli place pos=-140,64,30 item=minecraft:stone
maidcli tp pos=-140,64,35
maidcli sense self                   # 核对结果（世界侧）
maidcli control off=true             # 还她自主（会归队到主人身边）
```

## 11. 批量读
```bash
maidcli batch "sense self" "get mode" "sense inventory"
maidcli batch -f plan.mcl            # 一行一条，# 是注释
cat plan.mcl | maidcli batch         # 也可以走管道
```
一批最多 32 条，**读和写不能混批**。

## 12. 盖房子
见 [`house-building.md`](house-building.md)。
