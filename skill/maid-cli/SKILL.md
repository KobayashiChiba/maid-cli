---
name: maid-cli
description: 通过 HTTP/CLI 操控 Minecraft 车万女仆（Touhou Little Maid）——读感官、下指令、开容器会话、驱动自动建造。含 27 条指令与零依赖 Python CLI。
---

# Maid CLI —— 让女仆干活的操作手册

给 Minecraft 里的**车万女仆**装一套 API：一个 NeoForge 附属 mod 在 `127.0.0.1:8644` 上提供
27 条指令，配一个零依赖的 Python CLI。你可以读她的感官、让她走位/挖放/开箱/合成/交易/射箭。

**适用**：让女仆执行具体任务（走到某处、挖矿、搬东西、开箱取物、合成、交易、盖房子）、
读取世界与她自身的状态、做自动化脚本。

**不适用**：需要改模组行为、需要多人权限管理、需要在不装 mod 的服务器上操作 —— 这些做不到。

## 0. 开工三步（不通就别往下走）

```bash
maidcli ping                      # 模组活着吗？返回 version 说明通了
maidcli login <你的玩家名>         # 第一次要登录换 key（之后自动复用）
maidcli maids                     # 有哪些女仆（含未加载的）
maidcli wake maid=<她的标签>       # 唤醒：不唤醒 = 她不在世界里
```

`--maid` 也可以写成环境变量 `MAIDCLI_MAID`，省得每条都带。

## 1. 五条铁律

| # | 铁律 | 为什么 |
|---|---|---|
| 1 | **先 `wake` 再下指令** | 未唤醒 = 零加载票 = 她不在世界里，所有指令报 `not_awake`；唤醒后 300 秒无指令会自动释放 |
| 2 | **只信世界侧读数** | 命令返回 `ok` 只代表"受理了"。要结论就跑 `sense`/`blocks` 重新读，或看 `sense state` 的 history |
| 3 | **别在 `place`/`break`/`open` 前手动 `go`** | 需要"够得着"的指令会自己走过去，手动 `go` 是多余动作 |
| 4 | **一次只跑一个作业** | 她只有一条动作队列，后来的排队；要打断用 `stop` |
| 5 | **骑乘中先 `macro dismount`** | 坐在船/矿车里位置由载具决定，走位与传送全部失效 |

## 2. 27 条指令速查

参数与默认值见 [`references/commands.md`](references/commands.md)（人写的语义）
和 [`references/commands-index.md`](references/commands-index.md)（机器生成的完整参数表）。

**查询（只读，直接返回）**

| 指令 | 用途 |
|---|---|
| `maids [filter=]` | 列女仆（含未加载的）|
| `sense <域> [参数]` | 女仆感知：`self` / `state` / `inventory` / `craftable` / `blocks` / `entities` / `session` / `world` |
| `blocks pos= [state= nbt= mode=]` | 读世界方块（单点 `x,y,z` / 方区 `x1,y1,z1:x2,y2,z2` / 球 `x,y,z:r`）|
| `get [键…]` | 读她的配置（21 个键，如 `mode` `follow` `sit` `pickup`）|
| `help [what=]` | 指令自省 / `help what=mode` 列工作模式 |

**身份与唤醒**

| 指令 | 用途 |
|---|---|
| `login player=` / `login off=true` | 登录换 key（12 小时）/ 登出 |
| `wake maid=` / `wake maid= off=true` | 唤醒（加载 + 接管 + 300 秒超时）/ 取消唤醒 |

**世界操作（会改世界）**

| 指令 | 用途 |
|---|---|
| `go pos=` | 走过去（多组坐标依次走；走不到默认传送兜底）|
| `tp pos=` / `tp entity=` | 直接传送（不过寻路，指哪去哪）|
| `break pos= [expect= drops=]` | 挖方块（自动换最优工具；`drops=inv` 进背包 / `ground` 留原地）|
| `place pos= [item= interval=]` | 放方块（`item` 可带 blockstate 指定朝向/半砖）|
| `hit entity=` / `hit pos=` | 左键：打实体（伤害+击退）/ 挥手 |
| `use pos=` / `use entity=` / `use item=` | 右键：放/开门/拉杆/骨粉 · 挤奶/剪毛/喂食/上船 · 吃/喝/蓄力 |
| `pickup [entity= item=]` | 定向捡起一个掉落物 |
| `open pos=` / `open entity=` / `open` | 打开界面（容器/工位/村民/自己背包）|
| `ride target=` / `ride entity=` | 骑上船/矿车/马 |

**界面会话（先 `open`）**

| 指令 | 用途 |
|---|---|
| `take from= [count=]` | 拿起一格 → 进鼠标槽 |
| `put to= [count=]` | 放下：空位放入 / 同类合并 / 异类交换 / `to=ground` 扔出去 |
| `move from=` | Shift 搬运：自动去"对面"找位（合并同类优先）|
| `select [index= times= auto=]` | 点按钮选档 / 无按钮工位直接取产物 |
| `input text=` | 文本框输入（铁砧改名、告示牌）|
| `close` | 关界面（余料自动退回背包，幂等）|

**控制与配置**

| 指令 | 用途 |
|---|---|
| `control` / `control off=true` | 受控开关（受控 = 切「指令模式」，她不自主行动）|
| `mode <模式名>` | 换工作模式（`idle` / `attack` / `farm` …，切换会**自动退出受控**）|
| `stop` | 打断：清队列 + 停当前动作 + 关会话 |
| `set <键>=<值> …` | 写配置（立即生效）|

**宏（一条入口，九个子功能）**

| 宏 | 用途 |
|---|---|
| `macro shoot pos=` / `entity=` | 射箭（真蓄力；解不出弹道会如实报错，不硬射）|
| `macro look pos=` / `entity=` | 转头看 |
| `macro swap` / `macro hold from=` | 换手 / 指定格换到主手 |
| `macro drop item= count=` / `macro give item= count=` | 丢出 / 生成物品 |
| `macro sort` | 整理当前界面 |
| `macro craft item= times=` | 一键合成（自动找配方与工位）|
| `macro dismount` | 下船/下车 |

## 3. 十个常用配方

### ① 她怎么样？（状态速览）
```bash
maidcli sense self          # 血量/好感/位置
maidcli sense inventory     # 主手 + 背包逐格（slot 号可直接用于 move/take）
```

### ② 走过去挖 3 个钻石
```bash
maidcli sense blocks radius=8 find=minecraft:diamond_ore
maidcli break pos=-140,12,33 drops=inv
```
不用先 `go` —— `break` 自己会走过去。

### ③ 从箱子取 16 个木头
```bash
maidcli open pos=-150,64,30
maidcli sense session                     # 看 box:N 里有什么
maidcli move from=box:3                   # 整摞搬进背包
maidcli close
```

### ④ 把背包里的圆石放进仓库箱第 5 格
```bash
maidcli open pos=-150,64,30
maidcli move from=inv:12                  # 先确认 inv:N 是圆石
maidcli close
```

### ⑤ 工作台合成 4 个木棍
```bash
maidcli open pos=-148,64,30
maidcli put to=box:0                      # 放木板（鼠标槽里是什么就放什么）
maidcli select                            # 无按钮工位 = 直接取产物
maidcli take from=box:9
maidcli close
```

### ⑥ 村民交易
```bash
maidcli sense entities type=minecraft:villager
maidcli open entity=123
maidcli move from=box:0                   # 放支付物
maidcli select index=0                    # 选第 1 个交易
maidcli take from=box:2                   # 取产物
maidcli close
```

### ⑦ 射一个靶
```bash
maidcli macro shoot pos=-145.5,142.5,20.5
# 报「射不中」= 解不出弹道（常见于障碍物挡住所有抛物线），换角度/换站位
```

### ⑧ 完全接管，做完再还她自主
```bash
maidcli control                 # 切指令模式：她不自己乱跑、不捡东西
maidcli go pos=-140,64,30
maidcli break pos=-140,64,30 drops=inv
maidcli control off=true        # 还她自主（她会归队到主人身边）
```

### ⑨ 批量读多项
```bash
maidcli batch "sense self" "get mode" "sense inventory"
```

### ⑩ 让她盖一栋房子
见 [`references/house-building.md`](references/house-building.md)（蓝图 → 计划 → 施工 → 核验 → 补漏）。

## 4. 出错先看这张表

| 错误码 | 含义 | 怎么办 |
|---|---|---|
| `not_awake` | 她没被唤醒 | `wake maid=`，然后重试 |
| `not_found` | 找不到这个标签/名字 | `maids` 列一下；标签存在女仆 NBT 里，最稳 |
| `too_far` | 离目标太远 | 该指令不支持自动走位时先 `go`；会话类指令更是要贴近 |
| `bad_state` | 前置状态不对（如界面没开） | 看提示里的"要先把…"；界面类先 `open` |
| `bad_arg` | 参数格式不对 | 看 `hint`；坐标是 `x,y,z`，多值用 `;` 或 `:` |
| `unauthorized` | key 失效 | `maidcli login <玩家名>`（CLI 会自动重登一次）|
| `unreachable` | 连不上服务端 | 服务端没起 / 端口不对；远程先建 SSH 隧道 |
| `timeout` | 作业超时 | `sense state` 看她卡在哪；必要时 `stop` |
| `unknown_cmd` | 没有这条指令（或拼错）| `maidcli help` 看全部指令 |

完整排查（症状 → 原因 → 处置）见 [`references/troubleshooting.md`](references/troubleshooting.md)。

## 5. 该读哪个文件

| 你想知道 | 读这个 |
|---|---|
| 每条指令的全部参数、返回值、报错 | [`references/commands.md`](references/commands.md) |
| 参数的机器全表（类型/必填/默认/枚举）| [`references/commands-index.md`](references/commands-index.md) |
| 为什么要这样设计、自动走位等机制 | [`references/mechanics.md`](references/mechanics.md) |
| 现成任务写法 | [`references/recipes.md`](references/recipes.md) |
| 卡住了怎么查 | [`references/troubleshooting.md`](references/troubleshooting.md) |
| CLI 全套用法（batch / 环境变量 / 退出码 / 远程）| [`references/cli.md`](references/cli.md) |
| 自动建房流水线 | [`references/house-building.md`](references/house-building.md) |

## 6. 三个最容易踩的坑

1. **她坐在船里** → 所有走位/传送失效，`go` 一直超时。判据：`data get entity <她> Pos` 与附近船的 `Pos` 重合。处置：`macro dismount`。
2. **`inv:N` 不是背包下标** → 是"手在前"的槽位号（和 `sense inventory` 打印的一致），直接拿去 `move from=inv:N` 才对。
3. **界面没关就下别的指令** → 会 `bad_state`。每个 `open` 都配对 `close`，或者用 `stop` 一把清。

---

**版本**：适用于 `maid_cli` / `maidcli` 1.0.x。指令表以服务端 `GET /help` 为准 ——
`python sync_commands_index.py --check` 能验证本文档是否与当前服务端一致。
