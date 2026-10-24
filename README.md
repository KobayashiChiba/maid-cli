# Maid CLI

> 给车万女仆装一套 API —— 读感官、下指令、开会话，附 Python CLI 与 LLM 自省指令表。

> **状态：v1.0.0 已发布** —— [下载 Release](https://github.com/KobayashiChiba/maid-cli/releases/tag/v1.0.0)
> （`maid_cli-1.0.0.jar` 丢进服务端 `mods/` 即可）。CI 在 ubuntu + windows 双平台构建通过。

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green.svg)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.219-orange.svg)](https://neoforged.net/)

**Touhou Little Maid 的 HTTP 控制层**：一个 NeoForge 附属 mod，在本机起一个只听 `127.0.0.1`
的 HTTP 服务，让你（或者你的脚本、你的 LLM agent）从游戏外面读女仆的感官、指挥她干活。

```
外部脚本 / LLM agent
      │  HTTP (127.0.0.1:8644)
      ▼
   maid_cli 附属 mod  ──►  指令层（27 条，可自省）
      │                        │
      │                        ▼
      │                   执行层（Job / Action / ops）
      │                        │
      ▼                        ▼
  Python CLI            Touhou Little Maid + 原版 API
```

---

## 这是什么 / What is this

车万女仆（Touhou Little Maid）本来是个"她自己干活"的 mod：你给她设任务，她自己跑。
Maid CLI 不改变她的行为逻辑，而是**给她开一个对外控制面**：

- **读她的感官** —— 血量、背包、手上拿的、视线看到的方块、脚下的地形、周围实体
- **下世界指令** —— 走过去、传送、挥剑、右键、挖、放、定向捡、骑乘
- **开会话干活** —— 打开箱子/熔炉/工作台/村民交易，然后按槽位搬东西、点按钮、结算产物
- **宏** —— 换装、射箭、整理背包、看某个方向这类成套动作
- **接管与释放** —— 接管时切到「指令模式」任务（她不主动行动、不乱走），释放后交还她原来的工作模式

设计取向：**不内嵌 LLM，不自带智能体**。mod 只提供"干净、自省、可被程序调用"的指令表，
智能放在外面 —— 你的 Python 脚本、你的 agent 框架、你的 Claude/GPT 都行。
指令表走 `/help` 自省，agent 不需要硬编码任何工具定义。

> **English summary**: Maid CLI is a NeoForge addon for Touhou Little Maid that exposes a
> localhost-only HTTP control layer (27 self-describing commands) plus a dependency-free
> Python CLI. Read a maid's senses, drive her through world operations and container
> sessions, run macros — from a script or an LLM agent. Deliberately no built-in LLM:
> the mod stays a thin, introspectable control plane.

---

## 能干什么

| 类 | 指令 | 说明 |
|---|---|---|
| 登录 | `login` `wake` | 拿密钥、把不在加载范围里的她唤醒回来 |
| 查询 | `maids` `blocks` `sense` `help` | 女仆列表 / 查方块 / 她的感知 / 指令自省 |
| 世界 | `go` `tp` `hit` `use` `break` `place` `pickup` `open` `ride` | 跟玩家操作一一对应 |
| 界面 | `take` `put` `move` `select` `input` `close` | 按槽位操作容器与工位；`select` 结算配方 |
| 控制 | `stop` `mode` `control` | 打断动作 / 换工作模式 / 接管与释放 |
| 宏 | `macro` | 换装、射箭、整理、看向某个方向…… |
| 设置 | `get` `set` | 读写她的配置项 |

**共 27 条**，语法统一（同一件事只有一种写法）：完整规格见
[`skill/maid-cli/references/commands.md`](skill/maid-cli/references/commands.md)。

几个设计上的硬约束（这些是踩过坑之后定下来的）：

- **物品守恒**：容器会话里所有搬运都走 `IItemHandler.extractItem` / 插入校验，
  不存在"从结果格白拿产物"；关会话、走远、超时、服务端正常关闭都会自动退料
- **会话有超时**：默认 180 秒；她走远超过 16 格自动关会话
- **只听 127.0.0.1**：不对外网开端口（避免把操控权暴露到公网）；密钥存 `~/.maidcli/key.json`（或 `MAIDCLI_KEY` 环境变量），CLI 自动管理
- **虚拟槽不持久化**：与原版一致 —— 崩溃会丢，正常关闭不会丢

### 服务器不在本机怎么办

模组故意只监听 `127.0.0.1`。服务端在远端时，建一条隧道再把 CLI 指过去：

```bash
ssh -L 8644:127.0.0.1:8644 <用户>@<服务器>     # 保持这个终端开着
maidcli ping                                  # 本地默认地址即可
```

隧道换了本地端口就用 `--port 18644`（或 `MAIDCLI_PORT`）；有反代/端口转发时用
`--host <地址>`（或 `MAIDCLI_HOST`）。key 是按 `host:port` 分别保存的，多个服务器互不干扰。

---

## 30 秒跑通

```bash
# 1) 服务端：放进 mods/ 后启动
#    需要 Minecraft 1.21.1 + NeoForge 21.1.219+ + Touhou Little Maid 1.5.3+

# 2) CLI
python -m venv .venv && . .venv/bin/activate     # Windows: .venv\Scripts\activate
pip install -e cli/
maidcli ping
# {"ok": true, "mod": "maid_cli", "version": "1.0.0", ...}

# 3) 登录换 key（第一次必须给玩家名，之后复用 ~/.maidcli/key.json）
maidcli login <你的玩家名>

# 4) 找到她并唤醒（不唤醒 = 她不在世界里，所有指令都会报 not_awake）
maidcli maids
export MAIDCLI_MAID=<她的标签>
maidcli wake maid=$MAIDCLI_MAID

# 5) 让她干活
maidcli sense self
maidcli go pos=-140,141,34                 # 坐标写成 x,y,z
maidcli batch "get follow sit" "get mode"  # 一次请求发多条（最多 32 条，读写不能混批）
```

完整演示见 [`examples/demo.md`](examples/demo.md)；建房全流程工具见 [`tools/house-building/`](tools/house-building/README.md)。

---

## 安装

**三种方式**，任选：

1. **Releases 直接下** → 把 `maid_cli-1.0.0.jar` 丢进服务端 `mods/`（推荐给玩家）
2. **自己编** → `git clone` 后 `./gradlew build`，产物在 `build/libs/`（需要 JDK 21）
3. **开发机上极速迭代** → `./gradlew build --offline`（依赖已在 Gradle 缓存里时可用）

不打包 MC 与 TLM 的任何代码/资源 —— 两者都是编译期依赖（`compileOnly`）。

---

## 给 LLM agent 用

这是本项目相对其他 TLM 附属 mod 的差异点：**指令表自省**。

```bash
maidcli cmds                    # 列全部指令 + 参数 + 类型（现读服务端 /help）
maidcli help what=mode          # 查某个主题的取值清单
```

服务端加新指令，CLI 和 agent **立刻能用，不用改代码** —— 因为它们的工具定义是从
`GET /help` 现读的，不是写死的。

`skill/maid-cli/` 是一份可以直接喂给 agent 的技能包（SKILL.md + references），
按渐进式披露组织：入口薄，细节按需读。

---

## 文档

| 文档 | 内容 |
|---|---|
| [`COMMANDS.md`](COMMANDS.md) | 27 条指令的权威规格（参数 / 语义 / 错误码） |
| [`skill/maid-cli/SKILL.md`](skill/maid-cli/SKILL.md) | 入口：能力边界、铁律、指令速查 |
| [`references/architecture.md`](skill/maid-cli/references/architecture.md) | 4 层架构 + 一条指令的一生 |
| [`references/cli.md`](skill/maid-cli/references/cli.md) | CLI 全用法 |
| [`references/mechanics-*.md`](skill/maid-cli/references/) | 机制笔记：女仆行为盒子 / 区块加载 / 容器语义 / 原版怪癖 / 安全守则 |
| [`references/troubleshooting.md`](skill/maid-cli/references/troubleshooting.md) | 401 / not_awake / too_far / 界面残留 …… |

---

## 已知限制

- **信标界面**不支持（`open` 直接拒绝）—— 选效果要走网络包 + 金字塔底座 + 支付矿物
- `open entity=` 只支持村民，马/驴/羊驼不做
- `owner:` 槽位（主人背包）不提供，会明确报错
- 虚拟槽 / 鼠标槽不持久化（与原版一致）
- **骑乘中无法走位**：她坐在船/矿车里时位置由载具决定，`go` / `tp` 一律失效（原版机制）；先 `macro dismount` 再操作
- **极个别角度会「看得见但打不到」**：`macro shoot` 的弹道在障碍物让所有弹道提前中断时，会如实报「射不中」而不是硬射（宁可不射，也不赌一发）
- 目前没有自动化测试套件，CI 只保证"编译不坏"

---

## 第三方与许可

| 项目 | 许可 | 关系 |
|---|---|---|
| [Touhou Little Maid](https://modrinth.com/mod/touhou-little-maid)（tartaric_acid） | MIT | 编译期依赖，不打包、非官方附属 |
| [NeoForge](https://neoforged.net/) | LGPL-2.1 | 运行平台 |
| Minecraft（Mojang） | Mojang EULA | 不随包分发 |

本项目自身代码以 [MIT](LICENSE) 发布。
