# 指令全表（语义 / 自动行为 / 返回 / 报错）

> 参数的类型、必填、默认值、枚举以 [`commands-index.md`](commands-index.md) 为**唯一事实来源**（机器生成）。
> 本文讲**语义和坑**：这条指令实际会做什么、会连带发生什么、失败长什么样。
>
> 公共约定：所有指令都要先 `wake`；`ok` 只代表受理，结论看 `sense state` / 重新读世界。

---

## 身份与唤醒

### `login`
建立身份，换一个客户端 key（默认 12 小时）。CLI 会把 key 存到 `~/.maidcli/key.json`。

- **参数**：`player`（玩家名，必须是服务端 usercache 里的名字）· `off=true` 登出
- **返回**：`{key, valid_seconds, player}`
- **报错**：`bad_arg`（没给玩家名）· `unauthorized`（名字不在 usercache）
- **例**：`maidcli login Steve`

### `wake`
唤醒 = 加载区块 + 接管 + 起 300 秒空闲超时。**不唤醒时她不在世界里**（零加载票）。

- **参数**：`maid`（必填）· `off=true` 取消唤醒（释放加载票）
- **返回**：`{maid, loaded, ticks}`
- **报错**：`not_found`
- **注意**：300 秒内没有新指令会自动释放；长任务中间要续（再发一条指令即可）

---

## 查询（只读）

### `maids`
列出所有女仆，**包括未加载的**（未加载的会标"失联"）。唤醒前先用它拿标签。

- **参数**：`filter`（名字/标签片段）
- **返回**：`{maids:[{tag, name, pos, loaded, awake}]}`

### `blocks`
读世界方块。三种坐标写法：

| 写法 | 例 | 说明 |
|---|---|---|
| 单点 | `pos=-150,64,30` | 返回扁平对象 |
| 方区 | `pos=-150,64,30:-148,66,32` | 返回 `blocks[]` 列表 |
| 球 | `pos=-150,64,30:8` | 半径 8 |

- **参数**：`pos`（必填）· `state`（是否返回属性，默认 true）· `nbt`（方块实体 NBT）· `mode`（`list`/`count`）
- **返回**：`{pos, block, state:"facing=north,waterlogged=false"}` 或 `{blocks:[…]}`
- **注意**：负数坐标写在等号里（`pos=-150,64,30`），不然 `-150` 会被当选项名

### `sense <域>`
女仆感知，8 个域：

| 域 | 关键参数 | 返回 |
|---|---|---|
| `self` | — | 血量/好感/经验/位置/yaw/pitch |
| `state` | `history=N` `since=N` | 动作队列 + 历史（作业成败都在这）|
| `inventory` | `part=hands/backpack/armor/baubles` | 主手/副手 + 逐格 `inv:N` |
| `craftable` | `filter=` `item=` `times=` | 当前材料能合成什么 |
| `blocks` | `radius=` `find=` `below=` | 身边方块（`find=#logs` 支持标签）|
| `entities` | `radius=` `type=` `what=` | 身边实体（含 `id`，可用于 `hit`/`ride`/`open`）|
| `session` | — | 当前界面：`menu`、`box:N`、`virtual:N`、按钮/选项 |
| `world` | — | 时间/天气/维度 |

- **返回**：按域不同；`state` 返回 `{queue:[], history:[{id, op, ok, result, message}]}`
- **坑**：`since=N` 的语义是"id **大于** N"，要看到自己那条得传 `since=id-1`（CLI 已处理）

### `get`
读她的配置，21 个键：`mode follow sit schedule pickup rideable name tag model …`

- **参数**：`keys`（空格分隔，可多个；不给=读全部）
- **例**：`maidcli get mode follow`

### `help`
- `maidcli help` 列全部指令 + 参数
- `maidcli help what=mode` 列工作模式清单
- `maidcli help what=errors` 列错误码

---

## 世界操作（会改世界）

### `go`
走过去。支持一次给多组坐标（依次走），走不到时**默认传送兜底**。

- **参数**：`pos`（必填，`x,y,z`；多组用 `;` 分隔）· `no_tp=true` 关闭传送兜底
- **到达判定**：水平 ≤ 3 格且高差 ≤ 1 格
- **自动行为**：寻路；超时 → 传送到落脚点（除非 `no_tp`）
- **返回**：`{result, total, ok_count, failures[]}`
- **注意**：`place`/`break` 等会自己走，不需要先 `go`

### `tp`
直接传送，**指哪去哪**（不做寻路、不改坐标）。

- **参数**：`pos=x,y,z` 或 `entity=<id|owner>` · `max_distance`（上限 512）
- **返回**：`{pos:[x,y,z]}`
- **报错**：`bad_arg`（坐标越界/落点被占）· `too_far`（超距离上限）

### `break`
挖方块。自动换最优工具；掉落默认进背包。

- **参数**：`pos`（必填，支持批量）· `expect`（只挖指定方块，不符就跳过）· `drops`（`inv` 默认 / `ground` 留原地）
- **自动行为**：寻路 + 换工具；掉落物**手动弹回原位**（保留"掉落在地上"的观感）
- **返回**：`{total, ok_count, failed, failures[]}`；挖矿会给经验球
- **坑**：`stone` 掉的是 `cobblestone`；`expect` 用错会全部跳过（`failed` 有原因）

### `place`
放方块。`item` 可以带 blockstate 指定朝向/半砖/开关。

- **参数**：`pos`（必填，支持批量）· `item`（`oak_stairs[facing=east,half=bottom]`）· `interval`（tick，默认 8）
- **自动行为**：寻路 + 自动从背包取方块换手；**不自动避让** —— 她站位挡住就报错
- **返回**：`{total, ok_count, failed, failures[{what, reason}]}`
- **常见失败**：`走位后仍够不到目标`（屋内高处无落脚点）、`身上没有可放的方块`
- **调参**：`interval` 调小=更快但动作生硬；调大=更像真人（录像用大一点）

### `hit`
左键：对实体造成伤害+击退；对方块/空气=挥手。

- **参数**：`entity=<id>` 或 `pos=x,y,z`
- **注意**：伤害是**下一 tick 生效**，立刻读血量会看到旧值

### `use`
右键。三种目标：

| 目标 | 用途 |
|---|---|
| `pos=` | 放方块 / 开门 / 拉杆 / 骨粉 / 剪羊毛（对方块）|
| `entity=` | 挤奶 / 剪毛 / 喂食 / 上船 |
| `item=`（无目标） | 吃 / 喝 / 蓄力（对空气）|

- **参数**：`pos` / `entity` / `face`（贴哪个面）· `item` · `duration`（按住多少 tick）· `aim`
- **坑**：放置型右键的作用格是 `点击位置.relative(face)`，**不是**被点的方块本身

### `pickup`
定向捡起**一个**掉落物。

- **参数**：`entity=<id>` / `item=<物品id>` / 都不给=捡最近的
- **返回**：`{picked, item, count}`

### `open`
打开界面 —— 所有界面操作的入口。

- **参数**：`pos=x,y,z`（方块）· `entity=<id>`（村民）· 都不给 = 自己背包
- **支持**：箱子/木桶/熔炉/漏斗/潜影盒/工作台/铁砧/切石机/附魔台/砂轮/锻造台/制图台/织布机/信标(拒绝)/讲台/村民
- **返回**：`{menu, slots, box:[…]}`；`sense session` 看详情
- **坑**：**每个 `open` 必须配对 `close`**，否则后续界面操作 `bad_state`

### `ride`
骑上载具（她自己坐上去，不是假玩家）。

- **参数**：`target=<类型>`（如 `minecraft:boat`）或 `entity=<id>`
- **坑**：**骑乘中她不能走位/传送**，所有移动类指令会超时 → 先 `macro dismount`

---

## 界面会话（先 `open`）

槽位写法：`inv:N`（背包，手在前）· `box:N`（当前界面的容器格）· `virtual:N`（工位/村民的虚拟格）· `ground`

### `take`
拿起一格 → 进**鼠标槽**（不是主手）。

- **参数**：`from`（必填）· `count`（拿几个）
- **坑**：鼠标槽非空时再 `take` 会被拒；先 `put` 或 `close`

### `put`
放下。

- **参数**：`to`（必填）· `count`
- **行为**：目标空 → 放入 · 同类 → 合并（超上限留在手上）· 异类 → 交换 · `to=ground` 扔出去

### `move`
Shift 搬运：**自动在"对面"找位**（先合并同类，再找空格）。只给 `from`，不接受 `to`。

- **参数**：`from`（必填）
- **例**：`move from=inv:12`（背包第 12 格整摞搬进当前界面）· `move from=box:3`（界面第 3 格搬进背包）

### `select`
点按钮 / 取产物。

- **参数**：`index`（选第几档，0 起）· `times`（连点几次）· `auto=true`（选一个能做的）
- **无按钮工位**（工作台/铁砧/砂轮/锻造台/制图台）：直接确认并取产物
- **村民**：`select index=N` 走专用结算（扣支付物 + notifyTrade）

### `input`
文本框输入（铁砧改名 / 告示牌 / 命令方块）。

- **参数**：`text`（必填）
- **坑**：铁砧是"暂存 → 再 `select` 才生效"

### `close`
关界面：退鼠标槽 + 退工位余料 + 合盖。**幂等**（没开也不报错）。

- **返回**：`{returned_items:[…]}`

---

## 控制与配置

### `control`
受控开关。

- `control` → 切成「指令模式」：不注册主动行为、关闭随机游走（她不捡东西、不跟随、不乱走）
- `control off=true` → 还她自主，并**归队到主人身边**

### `mode`
换工作模式（`idle` / `attack` / `farm` / `milk` …）。

- **参数**：`mode`（子命令）· `target`（有些模式要目标）
- **重要**：**切换模式会自动退出受控** —— 想继续受控要重新 `control`

### `stop`
打断：清队列 + 停当前动作 + 关会话（鼠标槽物品退回背包）。立即生效。

### `set`
写配置，一次可改多个，立即生效。

- **例**：`maidcli set follow=true sit=false pickup=true`

---

## 宏

### `macro`
一条入口，九个子功能。

| 宏 | 参数 | 说明 |
|---|---|---|
| `shoot` | `pos=` / `entity=` | 射箭（真蓄力 20 tick；自己造箭扣箭）|
| `look` | `pos=` / `entity=` | 转头看 |
| `swap` | — | 主副手交换 |
| `hold` | `from=<槽位>` | 指定格换到主手（纯换手，不碰会话）|
| `drop` | `item=` `count=` | 丢出物品 |
| `give` | `item=` `count=` | 生成物品（需权限）|
| `sort` | — | 整理当前界面 |
| `craft` | `item=` `times=` | 一键合成（自动找配方与工位）|
| `dismount` | — | 下船/下车 |

- **射箭的坑**：解不出弹道时报 `射不中：按配置的速度算，这个距离/高度打不到`，
  这是设计意图（宁可不射，也不赌一发）。服务端日志会留 `[MaidCLI] 弹道无解 from=… bestErr=…`
