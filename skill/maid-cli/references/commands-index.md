<!-- 自动生成，勿手改：由 sync_commands_index.py 从服务端 GET /help 生成 -->

# 指令总表（含全部参数）

> 本文件是**机器生成**的参数事实表，用于查漏；讲语义、返回值、坑、示例请读 [`commands.md`](commands.md)。
> 校验：`python sync_commands_index.py --check`（与服务端不一致时退出码 1）。

## 查询（只读）

| 指令 | 子命令 | 参数 | 类型 | 必填 | 默认 | 取值 | 说明 |
|---|---|---|---|---|---|---|---|
| `blocks` | — | `pos` | pos | 是 | — | — | 单点 / 方区 / 球（§2.1 坐标格式） |
|  |  | `state` | bool | 否 | True | — | 是否列状态属性 |
|  |  | `nbt` | bool | 否 | False | — | 是否列方块实体 NBT |
|  |  | `mode` | enum | 否 | list | list, count | list 逐格 / count 只统计 |
| `get` | — | `keys` | string_or_list | 否 | — | — | 要读的键（空格分隔） |
| `help` | what | `what` | string | 否 | — | — | 主题：mode（工作模式清单）/ errors（错误码表） |
| `maids` | — | `filter` | string | 否 | — | — | 只看匹配的（名字 / 标签 / uuid） |
| `sense` | domain | `domain` | string | 是 | — | — | self / state / inventory / craftable / blocks / entities / session / world |
|  |  | `part` | string | 否 | — | — | inventory 用：hands / backpack / armor / baubles |
|  |  | `radius` | int | 否 | 0 | — | blocks / entities 用（blocks 默认 8，entities 默认 16） |
|  |  | `find` | string | 否 | — | — | blocks 用：只找某种方块（#logs 亦可） |
|  |  | `below` | bool | 否 | False | — | blocks 用：只看她脚下 |
|  |  | `type` | string | 否 | — | — | entities 用：只要这个实体类型 |
|  |  | `what` | string | 否 | — | — | entities 用：all / mob / item / player / orb |
|  |  | `filter` | string | 否 | — | — | craftable 用：产物关键词 |
|  |  | `times` | int | 否 | 1 | — | craftable 用：份数 |
|  |  | `item` | string | 否 | — | — | craftable 用：指定产物 |
|  |  | `state` | bool | 否 | True | — | blocks 用：是否列状态 |
|  |  | `nbt` | bool | 否 | False | — | blocks 用：是否列方块实体 NBT |
|  |  | `mode` | enum | 否 | list | list, count | blocks 用：list / count |
|  |  | `history` | int | 否 | — | — | state 用：最多返回 N 条未读历史 |
|  |  | `since` | int | 否 | — | — | state 用：返回 id 大于 N 的全部历史 |

## 操作（会改世界 / 改状态）

| 指令 | 子命令 | 参数 | 类型 | 必填 | 默认 | 取值 | 说明 |
|---|---|---|---|---|---|---|---|
| `break` | — | `pos` | pos_list | 是 | — | — | 目标方块（支持 a;b;c 批量） |
|  |  | `expect` | string | 否 | — | — | "挖到的东西应该是这个"（不符跳过） |
|  |  | `drops` | enum | 否 | inv | inv, ground | inv 进背包 / ground 留在地上 |
| `close` | — | — | — | — | — | — | 关界面（余料自动退回背包：退鼠标槽 + 退工位余料 + 合盖）—— 幂等 |
| `control` | — | `off` | bool | 否 | False | — | true = 退出受控 |
| `go` | — | `pos` | pos_list | 是 | — | — | 目标点（支持 a;b;c 批量） |
|  |  | `no_tp` | bool | 否 | False | — | 禁止"走不到就传送"的兜底 |
| `hit` | — | `entity` | entity | 否 | — | — | 实体 id 或 owner（打它） |
|  |  | `pos` | pos | 否 | — | — | 目标方块（只挥手） |
| `input` | — | `text` | string | 是 | — | — | 要输入的文本 |
| `login` | — | `player` | string | 否 | — | — | 玩家名（必须已在服务端 usercache 里） |
|  |  | `off` | bool | 否 | False | — | true = 登出 |
| `macro` | macro | `macro` | string | 是 | — | — | 宏名：shoot / swap / hold / drop / look / dismount / give / sort / craft |
|  |  | `pos` | pos | 否 | — | — | 坐标（look / shoot） |
|  |  | `entity` | entity | 否 | — | — | 实体 id 或 owner（look / shoot） |
|  |  | `from` | string | 否 | — | — | 槽位（hold） |
|  |  | `item` | string | 否 | — | — | 物品（give / craft） |
|  |  | `count` | int | 否 | 0 | — | 数量（drop / give） |
|  |  | `times` | int | 否 | 1 | — | 份数（craft） |
|  |  | `timeout` | double | 否 | 10.0 | — | 最多跑多少秒 |
| `mode` | task | `task` | string | 是 | — | — | 模式名（可省 shouhou_little_maid: 前缀） |
|  |  | `target` | int | 否 | 0 | — | attack 类模式的目标（实体 id） |
| `move` | — | `from` | string | 是 | — | — | 源槽位 inv:N / box:N |
|  |  | `to` | string | 否 | — | — | （不接受：要精确放哪格请用 take + put） |
| `open` | — | `pos` | pos | 否 | — | — | 方块坐标（容器 / 工位） |
|  |  | `entity` | int | 否 | 0 | — | 实体 id（村民 / 马） |
| `pickup` | — | `entity` | int | 否 | 0 | — | 掉落物实体 id |
|  |  | `item` | string | 否 | — | — | 物品 id（捡这种） |
| `place` | — | `pos` | pos_list | 是 | — | — | 目标位置（支持 a;b;c 批量） |
|  |  | `item` | string | 否 | — | — | 放什么（可带 blockstate，如 oak_stairs[facing=north]） |
|  |  | `interval` | int | 否 | 8 | — | 放置间隔（tick）—— 默认 8（0.4 秒）。调小部署更快但动作生硬，调大更接近真人手感 |
| `put` | — | `to` | string | 是 | — | — | 槽位 inv:N / box:N / hand:main，或 ground |
|  |  | `count` | int | 否 | 0 | — | 放几个（不给 = 全部） |
| `ride` | — | `target` | string | 否 | — | — | 目标类型（minecraft:boat / minecraft:minecart …） |
|  |  | `entity` | int | 否 | 0 | — | 目标实体 id（优先于 target） |
| `select` | — | `index` | int | 否 | 0 | — | 选项 / 按钮的下标（从 0 起；无按钮工位忽略） |
|  |  | `times` | int | 否 | 1 | — | 点几次（切石机/工作台一次做一组，如 64） |
|  |  | `auto` | bool | 否 | False | — | 产物直接进背包（模拟 Shift 点击），塞不下掉地上 |
| `set` | — | `keys` | raw | 否 | — | — | 键=值（空格分隔，一次多个） |
| `stop` | — | — | — | — | — | — | 打断：清队列 + 停当前动作 + 关会话（鼠标槽的东西退回背包），立即生效 |
| `take` | — | `from` | string | 是 | — | — | 槽位：inv:N / hand:main / hand:off / box:N |
|  |  | `count` | int | 否 | 0 | — | 拿几个（不给 = 整堆） |
| `tp` | — | `pos` | pos | 否 | — | — | 目标点 |
|  |  | `entity` | entity | 否 | — | — | 传送到某个实体处：数字 id 或 owner |
|  |  | `max_distance` | int | 否 | 64 | — | 距离上限；0 = 不限（默认 64） |
| `use` | — | `pos` | pos_list | 否 | — | — | 目标方块（可批量） |
|  |  | `entity` | int | 否 | 0 | — | 目标实体 id |
|  |  | `face` | string | 否 | — | — | 贴哪一面 up/down/north/south/east/west |
|  |  | `item` | string | 否 | — | — | 用哪个物品（会自动换手） |
|  |  | `duration` | int | 否 | -1 | — | 持续多少 tick（只对蓄力类有意义；不给 = 自然完成） |
|  |  | `aim` | string | 否 | — | — | 单位向量 "x,y,z"（只对空气有效） |
| `wake` | — | `maid` | string | 是 | — | — | 标签 / uuid / 名字 |
|  |  | `off` | bool | 否 | False | — | true = 取消唤醒（卸载 + 放开） |

<!-- 共 27 条指令 · 73 个参数 -->
