# Maid CLI · 指令规格（v3）

> **版本**：2026-10-20 语法统一版 · 对应 mod `0.1.0.0`
> 这一版把「文档写的」和「代码做的」彻底对齐：**27 条顶层指令**，同一个东西只有一种写法。

---

## 0. 这一版改了什么

| 变化 | 说明 |
|---|---|
| 宏收成 1 条 | 9 个裸名（shoot/swap/…）→ `macro <域名>` |
| 子命令 | `mode <模式名>` · `sense <域>` · `macro <宏名>` · `help <主题>` |
| 变长参数 | `get 键 键 …` · `set 键=值 键=值 …`（空格分隔，只用这一种） |
| `entity=` 统一 | 接受数字 id 或保留字 `owner`；`owner=true` 全部删除 |
| `sort` 无参数 | 整理**当前打开的界面**；没开就报错 |
| 删除 | `trade_list`（并进 `sense session` 的 `options`）· `ride_status`（并进 `sense state` / `entities`） |
| 删除的重复写法 | 槽位别名（mainhand/offhand/world）· `pickup_type` 值别名 · `get/set` 的 JSON/数组/分号格式 |
| 接管语义 | 切**跟随** + 清空行为盒子（等价旧的「指令模式」，不新增任务模式） |
| 外部改任务 | 自动退出受控（玩家在 GUI 换任务 / TLM 内置 agent 换任务） |

---

## 1. 分层（4 层）

```
调用方（CLI / 内嵌智能体 / 测试脚本）
   ↓ HTTP
传输层（HttpApi：鉴权 · 解析 · 序列化 · 门禁）
   ↓
指令层（MaidCmd：参数校验 · 拆解成作业 · 自省）—— 唯一入口
   ↓
执行层（Job / Action / ops：调 TLM 和原版 API 改世界）
```

**指令层是唯一入口**：加一条指令 = 写一个 `MaidCmd` 实现 + 注册一行；CLI 和 LLM 都靠 `/help` 自省拿用法。

---

## 2. 通用约定

### 2.1 命令行语法

```
<指令> [子命令] [参数 ...]
```

| 位置 | 含义 | 规则 |
|---|---|---|
| 第 1 个词 | 指令名 | 必须在注册表里 |
| 第 2 个词 | **子命令** | 只在该指令**声明了子命令**时这么解释（mode / sense / macro / help）|
| 其余 | 参数 | 一律 `键=值` |

**三种特殊形态：**

```
mode attack target=28        ← 子命令 + 参数
sense self                   ← 子命令（无其它参数）
macro shoot pos=-133.5,135.5,1.0
get mode follow              ← 变长裸词（variadic=words）
set follow=true sit=false    ← 变长键值对（variadic=pairs）
```

**硬规矩：**

1. **参数一律 `键=值`**（除子命令和变长参数）
2. **认不出的 token 一律报 `bad_arg`** —— 绝不静默丢弃
3. **引号**：值里含空格时用 `"…"` 或 `'…'` 包起来：`set name="酒 狐"`
4. **批量**：`{"cmds":["sense self","go pos=-40,130,-40"]}`，**最多 32 条 / 1MB**，读写不能混批

### 2.2 身份凭据

`login player=<玩家名>` → 返回 key（12 小时）。之后所有请求带 `X-Maid-Key: <key>`（CLI 自动管理）。

### 2.3 女仆寻址（`maid=<她>`）

| 写法 | 例 | 说明 |
|---|---|---|
| **固定标签**（最稳）| `A1B2C3` | 存在实体 NBT 里，扛重启 / 物品化 / 复活 |
| uuid 短码 / 全串 | `5a4701c9` | 复活后会变 |
| 名字 | `示例女仆` | 重名会报错并列出候选 |
| 名字#序号 | `示例女仆#1` | 同名多只时用 |
| 实体 id | `24` | 重启后会变，别写进脚本 |

### 2.4 响应信封 + 错误码

**成功：**
```json
{ "ok": true, "cmd": "sense", "data": { … } }
```

**失败：**
```json
{ "ok": false, "cmd": "place", "code": "too_far", "message": "…", "hint": "…" }
```

**错误码（14 个）** —— 完整表见 `help errors`：

| code | 含义 | HTTP |
|---|---|---|
| `unauthorized` | 没 key / key 不对 / 过期 | 401 |
| `not_found` | 指不到女仆 / 方块 / 容器 / 实体 | 404 |
| `not_yours` | 她不是这个玩家的女仆 | 403 |
| `not_awake` | 她没被唤醒 | 403 |
| `bad_arg` | 参数不合法 | 400 |
| `bad_state` | 界面状态不对（该开没开 / 该关没关）| 400 |
| `busy` | 队列满 | 400 |
| `timeout` | 动作 / 宏超时 | 400 |
| `too_far` | 够不着（> 8 格）→ CLI 会自动走过去 | 400 |
| `not_loaded` | 目标区块没加载 | 400 |
| `no_space` | 空间不足 | 400 |
| `no_item` | 身上没这件物品 | 400 |
| `blocked` | 被 TLM / 原版拦下 | 400 |
| `internal` | 未预期异常 | 500 |

### 2.5 槽位引用

```
inv:N              她的背包第 N 格（0=主手 1=副手 2..=背包 7=展示栏）
hand:main          主手（= inv:0）
hand:off           副手（= inv:1）
armor:head         护甲四格：head / chest / legs / feet
bauble:N           她的饰品栏第 N 格（0..29，TLM 的 30 格）
box:N              当前打开的那个界面的「容器侧」第 N 格
ground             外面（只作为 put 的目标）
```

**没有别名**：`mainhand` / `offhand` / `world` 都**不接受**。

**槽位视图的归属**：`box:` 指"当前打开的那个界面"，不写坐标 —— 想动某个容器先 `open` 它。

**一个界面 = 自身格子 + box 格子**（2026-10-20 定案）：

| 打开的是什么 | 自身格子 | box 格子 |
|---|---|---|
| 她自己的背包（`open`） | `inv:` `armor:` `bauble:` | **随身 2×2 合成格**（`box:0..3` 材料 · `box:4` 结果） |
| 容器（箱子/熔炉…） | `inv:` `armor:` `bauble:` | 那个容器的格子 |
| 工位（工作台/切石机/铁砧…） | `inv:` `armor:` `bauble:` | 工位的虚拟槽 |

> ★ **随身 2×2 和工位流程完全一致**（2026-10-20 统一）：摆料 → `select`（结算：算配方 + 扣料）→ `take from=box:4`。
> 摆好料不 `select` 直接 `close` ⇒ 产物作废、材料原样退回（不白嫖也不丢料）。

> ⚠️ `owner:`（主人背包）**本期不提供**，会明确报错。要把东西交给主人用 `macro give`。

**原版规则校验**：`put` 会按原版规矩检查"这个格子收不收"——
护甲格只收对应部位（`Equippable` 组件）、饰品栏只收饰品、熔炉燃料格只收燃料、
结果格谁都放不进去。规则全部来自原版/TLM 的现成接口，不是我们手写的名单。

---

## 3. 指令总览（27 条）

```
① 登录    login · wake
② 查询    maids · blocks · sense <域> · help [<主题>]
③ 世界    go · tp · hit · use · break · place · pickup · open · ride
④ 界面    take · put · move · select · input · close
⑤ 控制    stop · mode <模式名> · control
⑥ 宏      macro <宏名>
⑦ 设置    get · set
```

| 类 | 条数 |
|---|:--:|
| 登录 | 2 |
| 查询 | 4 |
| 世界操作 | 9 |
| 界面操作 | 6 |
| 控制 | 3 |
| 宏 | 1 |
| 设置 | 2 |
| **合计** | **27** |

---

## 4. 登录（2 条）

### `login`

```
login [player=<玩家名>] [off=true]
```
建立身份 → 返回 key（12 小时）。`off=true` 登出。

**不需要 key**（它是拿 key 的那一步）。

### `wake`

```
wake maid=<她> [off=true]
```
- **唤醒** = 挂加载票 + 接管（切跟随 + 清盒子 + 日程全天 + 关拾取）
- `off=true` = 取消唤醒（清队列 / 还原任务与跟随设定 / 撤票）
- **300 秒没指令自动取消唤醒**
- 她不在世界里也能唤醒（按 uuid 从 TLM 的索引自举加载回来）

---

## 5. 查询（4 条）

### `maids` —— 女仆列表

```
maids [filter=<关键词>]
```
返回**静态清单**：`name` / `tag` / `uuid` / `dimension` / `pos` / `loaded` / `awake`。
> 运行时细节（血量 / 装备 / 当前任务）**不在这里** → 用 `sense self`。

### `blocks` —— 查**世界**方块

```
blocks pos=<坐标> [state=true] [nbt=false] [mode=list|count]
```
按给定坐标读世界方块。
> ⚠️ 和 `sense blocks`（**她身边**的方块）是两个不同的东西。

### `sense` —— 她的感知

```
sense <域> [参数...]
```

| 域 | 返回什么 |
|---|---|
| `self` | 血量 / 好感（value+level）/ 经验（points+level）/ 位置 / 状态效果 / 骑乘 |
| `state` | 正在做什么（当前动作 + 队列 + history）+ **riding / vehicle** |
| `inventory` | 身上所有槽位 `[part=hands\|backpack\|armor\|baubles]` —— armor 每条带 `slot`（可直接喂 `armor:head`）；baubles 每条带 `slot`（可直接喂 `bauble:N`） |
| `craftable` | 现有材料能造什么 |
| `blocks` | **她身边**的方块 `[radius=8] [find=…]` |
| `entities` | 身边实体 `[radius=16] [type=…] [what=all\|mob\|item\|player\|orb\|vehicle]` |
| `session` | **当前打开的界面**（见下）|
| `world` | 时间 / 天气 / 维度 |

**`sense session` 的统一形状**（村民和附魔台、熔炉一样都是「界面」）：

```json
{ "open": true,
  "what": { "kind": "villager", "entity_id": 130 },
  "slots": [ {"index":0,"item":"minecraft:clay_ball","count":10} ],
  "options": [ {"index":0,"give":"10×clay_ball","get":"1×emerald","uses":"8/16","can":true} ],
  "cursor": null,
  "ability": { "slots":true, "select":true, "input":false, "progress":false, "hint":"…" } }
```

`options` 按 `what.kind` 各自给形状：
- `villager`：`{index, give, get, uses, can}`
- `stonecutter`：`{index, item, count, name}` ← **要先放料才有**
- `enchanting`：`{index, cost, clue, level_clue}` ← **要先放物品才有**
- 其它界面：`null`

### `help` —— 自省

```
help                全部指令 + 参数 + 子命令
help mode           工作模式清单
help errors         错误码表
```

---

## 6. 世界操作（9 条）

> 共同前置：**界面必须没开**（开着会报 `bad_state`）。

### `go` —— 走过去

```
go pos=<坐标>[;<坐标>...] [no_tp=true]
```
- 多组坐标 = 依次走
- 走不到默认**传送兜底**；`no_tp=true` 禁用
- 单段硬上限 64 格

### `tp` —— 直接传送

```
tp pos=<坐标> [max_distance=64]
tp entity=<id|owner> [max_distance=64]
```
- `max_distance=0` = 不限距离
- `entity=owner` = 传送到主人处

### `hit` —— 左键短按

```
hit entity=<id|owner>
hit pos=<坐标>
```
对实体 = 攻击（伤害 + 击退）；对方块 / 空气 = 挥手。

### `use` —— 右键

```
use pos=<坐标> [face=up] [item=<物品>] [duration=<秒>] [aim=x,y,z]
use entity=<id> [item=<物品>]
use aim=x,y,z [duration=<秒>]
```
严格按原版三步顺序：方块交互 → 物品自身行为 → 无物品交互。

### `break` —— 挖

```
break pos=<坐标>[;...] [expect=<方块>] [drops=inv|ground]
```
- `expect` 给了就校验，不符按 `on_mismatch` 处理（默认跳过）
- `drops=inv`（默认）掉落进她背包；`ground` 留在原地

### `place` —— 放

```
place pos=<坐标>[;...] item=<方块状态> [interval=秒]
```
- `item` 可以是完整 blockstate（含朝向）
- `interval` 默认 0.4 秒（别一口气 20 块/秒）

### `pickup` —— 定向捡

```
pickup [entity=<掉落物 id>] [item=<物品 id>]
```
都不给 = 捡最近的。

### `open` —— 打开界面 ★

```
open                      打开她自己的背包 ＝ 自身格子 + 随身 2×2 合成格
open pos=<坐标>           打开方块（箱子 / 熔炉 / 工位…）
open entity=<实体id>      打开实体界面（**目前仅村民**）
```
**唯一需要走位的交互指令**。会话规则：
- 开着时禁止 `go` / `tp` / 和别的方块交互
- 走远 > 8 格自动关；开着超过 60 秒自动关
- `box_peek` 类读取不需要 open

> ⚠️ 马 / 驴 / 羊驼的物品栏**本期不支持**。
> ⚠️ **信标**本期不给开（选效果走网络包，未实现）。

### `ride` —— 骑上去

```
ride target=<实体类型>      如 minecraft:boat / minecraft:minecart
ride entity=<实体id>
```
她自己坐上去（不是假玩家骑）。
> 查她在骑什么 → `sense state` 的 `riding` / `vehicle`；附近可骑的载具 → `sense entities` 的 `vehicles`（每条带 `rideable`）。

---

## 7. 界面操作（6 条）

> 槽位引用见 §2.5。`take` / `put` 操作的是**鼠标槽**（界面里的"临时手上"），不是主手。

### `take` —— 拿起（= 左键点槽位）

```
take from=<槽位> [count=N]
```
进鼠标槽；鼠标槽已有别的东西会被拒。

### `put` —— 放下（= 左键点槽位）

```
put to=<槽位|ground> [count=N]
```
- 目标空 → 放入
- 同类 → 合并（超上限留在鼠标槽）
- 异类 → **交换**
- `to=ground` → 把鼠标槽的东西扔到外面

### `move` —— Shift 点击（搬运）

```
move from=<槽位>
```
**不接受 `to`** —— 它就是在"对面"自动找位（先合并同类、再找空格）。
想精确放到某格 → `take` + `put`。

**必须先 `open`**（一律要开界面，没有特例）。"对面"规则：

| 打开的是 | `from` 给什么 | 搬到哪 |
|---|---|---|
| 她自己的背包 | `inv:` / `armor:` / `bauble:` / `box:` | **她的背包**（排除主手 0 / 副手 1 / 展示栏 7） |
| 箱子类容器 | `inv:` / `armor:` / `bauble:`（自身格子） | **那个容器** |
| 箱子类容器 | `box:N`（容器格子） | **她的背包**（同上排除） |

> ⚠️ **只支持"格子语义同质"的界面**：箱子 · 木桶 · 潜影盒 · 末影箱 · 漏斗 ·
> 投掷器/发射器 · 合成器 · 她自己的背包。
> ❌ 熔炉 / 高炉 / 烟熏炉 / 酿造台（格子有语义：输入 / 燃料 / 输出）
> ❌ 一切工位（虚拟槽 + 结果格只读） ❌ 村民交易格
>
> **用途定位**：`move` 是给**箱子类容器之间**快速搬运用的（省得自己算哪格空）。
> 工位虽然也能 `take`/`put`，但**建议用 `take` + `put` 精确控制** —— 工位有虚拟槽和余料退回规则，
> 自动找位容易落到你不想要的位置。

### `select` —— 点按钮 / 取产物

```
select [index=<N>] [times=<N>] [auto=true|false]
```

| 参数 | 默认 | 含义 |
|---|---|---|
| `index` | 0 | 选项 / 按钮下标。**无按钮的工位忽略它** |
| `times` | 1 | 一次调用点 N 次（切石机切一组、工作台连做） |
| `auto` | false | `true` = 产物**直接进背包**（= 原版 Shift 点击结果格），塞不下掉地上 |

**两类工位，一个入口**：

| 工位 | 有按钮？ | `select` 做什么 |
|---|:--:|---|
| 切石机 · 附魔台 · 织布机 · 讲台 | ✅ | 真点按钮选 `index` |
| **工作台 · 随身2×2 · 铁砧 · 砂轮 · 锻造台 · 制图台** | ❌ | 原版没按钮，`select` 就是**确认取产物** |
| 村民 | — | 走交易专用路径，`select index=N` = 成交第 N 笔 |

**产物在哪（`auto=false` 时要去取的格子）**：

| 工位 | 产物格 |
|---|---|
| 工作台 3×3 | `box:9` |
| 随身 2×2（打开背包） | `box:4` |
| 切石机 | `box:1` |
| 铁砧 · 砂轮 · 制图台 | `box:2` |
| 锻造台 · 织布机 | `box:3` |
| 附魔台 | `box:0`（原地改造，物品本身被附魔） |
| 信标 · 讲台 | 无产物（信标本期不给 open） |

> **`times` 只对"可重复产出"的有意义**：切石机 / 工作台 / 随身2×2 / 织布机 / 村民。
> 附魔台、铁砧这类"一次性改造"的第 2 次必然失败，会自动停下并报告做了几次。
>
> ⚠️ **合成/改名的扣料时机**：原版是"拿走产物时才扣"，我们是 `select` 时就扣（`auto=true` 时两者等价）。

例：切一组石头，产物直接进背包
```bash
open pos=<切石机>
put  to=box:0 item=minecraft:stone count=64
select index=1 times=64 auto=true
close
```

### `input` —— 文本框

```
input text=<文本>
```
铁砧改名 / 告示牌 / 命令方块。铁砧是「暂存 → 确认才生效」。

### `close` —— 关界面

```
close
```
余料自动退回背包；幂等。

---

## 8. 控制（3 条）

> 三条都**立即生效**（不进队列）。

### `stop` —— 打断

```
stop
```
清队列 + 停当前动作 + 关会话（鼠标槽的东西退回背包）。

### `mode` —— 换工作模式

```
mode <模式名> [target=<实体id>]
```
子命令 = TLM 的任务 id（可省 `touhou_little_maid:` 前缀）；清单用 `help mode`。

> ⚠️ **受控状态下换模式**：`setTask` 内部会 `refreshBrain` 重装行为盒子，所以我们换完会**重新清一次**（保持受控）。
> ⚠️ **玩家在 GUI 换模式**：检测到就**自动退出受控**，把控制权还给你。

### `control` —— 受控开关

```
control                  进入受控
control off=true         退出受控
```

**进入受控 = 四件事**：
1. **切到跟随** —— 绕过 TLM 的「留守范围 + 超范围传送」（它在 `homeMode=false` 时整段跳过）
2. **清空行为盒子** —— WORK / IDLE / REST / RIDE / PANIC 清空，CORE 换白名单（走位 / 看 / 爬游 / 呼吸 / 回血）
3. **日程全天**
4. **关自动拾取**

原跟随设定会记下来，`control off=true` 时**还原**。

> 受控 + 已唤醒状态下，她**可以远程干活**（因为 wake 的动态加载票让她所在区块不卸载）。
> 不动的：骑乘开关、她的偏好配置（pickup_type / open_door / …）。

---

## 9. 宏（1 条）

```
macro <宏名> [参数...]
```

| 宏 | 参数 | 属 |
|---|---|---|
| `shoot` | `pos=<精确点>` / `entity=<id\|owner>` + `[timeout=10]` | 世界 |
| `swap` | 无 | 世界 |
| `hold` | `from=<槽位>` | 世界 |
| `drop` | `[count=N]` | 世界 |
| `look` | `pos=` / `entity=<id\|owner>` | 世界 |
| `dismount` | 无 | 世界 |
| `give` | `[item=<id>] [count=N]` | 世界 |
| `sort` | 无 | 界面（要先 open）|
| `craft` | `item=<产物> [times=N]` | 界面（要先 open）|

**三条规矩：**
1. **世界宏**要求界面没开；**界面宏**要求界面开着（不匹配报 `bad_state`）
2. 宏和原语一样「只受理」：进队列、做完写 history；**超时 = 写一条失败历史**
3. 定位 = 语法糖垃圾桶：复合的 / 便利的 / 说不清归哪的，都往这儿扔

**说明：**
- `macro sort` 整理**当前打开的界面**（开的是背包就整理背包，开的是容器就整理容器）；工位类界面直接拒绝
- `macro look entity=owner` / `macro shoot entity=owner` 用的是统一保留字
- 宏在 history 里是 `{op:"macro", name:"swap", …}`

---

## 10. 设置（2 条）

### 键表（21 个）

**只读（3）**：`mode`（当前任务）· `control`（是否受控）· `target`（攻击目标 id）

**可写（18）**：`follow` `home` `sit` `schedule` `pickup` `rideable` `name` `tag` `model` `sound_pack` `pickup_type` `open_door` `open_fence_gate` `active_climbing` `sound_freq` `backpack_show` `back_item_show` `chat_bubble_show`

### `get` —— 读

```
get                     读全部 21 个
get mode follow         只读这几项（空格分隔）
```

### `set` —— 写

```
set follow=true             改一项
set follow=true sit=false   改多项（空格分隔）
set home=100,64,200         值里的逗号正常
set name="酒 狐"            值含空格用引号
```

**只接受这一种格式**：空格分隔的 `键=值`。分号 / JSON / 数组**都不接受**。

`pickup_type` 的取值只有三个：`item` / `xp` / `all`（想"什么都不捡"用 `set pickup=false`）。

---

## 11. CLI 用法

```bash
maidcli <指令> [子命令] [键=值 ...] [CLI 选项]

# 查看
maidcli maids                                  有哪些女仆
maidcli cmds                                   列服务端所有指令（动态读 /help）
maidcli help mode                              工作模式清单
maidcli sense self --maid A1B2C3               看她
maidcli get mode follow --maid A1B2C3          读配置
maidcli set "follow=true sit=false" --maid A1B2C3   写配置（引号是 shell 的）
maidcli mode attack --maid A1B2C3              切模式
maidcli macro shoot pos=-133.5,135.5,1.0 --maid A1B2C3
maidcli macro sort --maid A1B2C3               整理当前界面

# 批量
maidcli batch --maid A1B2C3 "sense self" "sense world"
maidcli batch -f plan.mcl                        一行一条，# 注释
```

CLI 选项：`--maid <她>` · `--no-wait` · `--timeout <秒>` · `--json` · `--port <端口>` · `--player <名>`

---

## 附录 A · 旧命令 → 新指令

| 旧 | 新 |
|---|---|
| `shoot` / `swap` / `hold` / `drop` / `look` / `dismount` / `give` / `sort` / `craft` | `macro <同名>` |
| `ride_status` | `sense state`（riding/vehicle）+ `sense entities`（vehicles/rideable）|
| `trade_list` | `sense session`（`options`）|
| `sense domain=self` | `sense self` |
| `mode task=attack` | `mode attack` |
| `get keys=mode,follow` | `get mode follow` |
| `set keys=follow=true;sit=false` | `set follow=true sit=false` |
| `ride entity_id=23` | `ride entity=23` |
| `look owner=true` / `shoot owner=true` / `tp owner=true` | `entity=owner` |
| `sort target=inv` / `sort target=box` | `sort`（先 `open` 对应界面）|
| `open` 后 `sort`（旧 `target=inv` 不要求界面）| 现在**必须**先 `open` |
| 槽位 `mainhand` / `offhand` / `world` | `hand:main` / `hand:off` / `ground` |

---

## 附录 B · 已知未实现（本期不做）

| 项 | 现状 |
|---|---|
| `owner:` 槽位（主人背包） | ❌ 本期不提供，会明确报错；要把东西交给主人用 `macro give` |
| `open entity=` 马 / 驴 / 羊驼 | ❌ 只支持村民 |
| **信标界面** | ❌ `open` 直接拒绝（选效果走网络包 `ServerboundSetBeaconPacket` + 金字塔底座 + 支付矿物，未实现） |
| `sense session` 的 `options` 讲台 | 返回 `null` |
| `armor:` / `bauble:` 槽位 | ✅ 2026-10-20 起支持**读写**（按原版 `Equippable` / TLM 规则校验） |
| 随身 2×2 合成格 | ✅ 2026-10-20 起 `open`（打开背包）即得：`box:0..3` 材料 · `box:4` 结果（流程与工位一致：摆料 → `select` → `take`） |
| **虚拟槽 / 鼠标槽的持久化** | ❌ **不持久化**（和原版一致）：正常 `close` / 走远 / 超时 / **服务端正常关闭**都会自动退料；只有**服务器崩溃**会丢（原版玩家把东西放合成格崩溃也会丢，这是 MC 固有行为） |
| GUI 改 mode 自动退控 | ✅ 已实现（T3）|
