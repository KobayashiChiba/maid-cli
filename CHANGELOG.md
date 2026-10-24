# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与
[语义化版本](https://semver.org/lang/zh-CN/)。

版本号的唯一来源是 `gradle.properties` 的 `mod_version`；
发版时 tag 必须与它一致（`v<mod_version>`），CI 会校验，不一致直接失败。

---

## [Unreleased]

## [1.0.0] - 2026-10-24

首发版本。功能已完整，27 条指令全部实测通过（含 1034 格建房任务的端到端复现）。

### Added

- **HTTP 控制层**：仅监听 `127.0.0.1:8644`，本地密钥鉴权（`login` 换 key，存 `~/.maidcli/key.json`，或 `MAIDCLI_KEY` 环境变量）
- **27 条顶层指令**，语法统一（同一件事只有一种写法）
  - 登录 2 条：`login` · `wake`
  - 查询 4 条：`maids` · `blocks` · `sense <域>` · `help [<主题>]`
  - 世界操作 9 条：`go` · `tp` · `hit` · `use` · `break` · `place` · `pickup` · `open` · `ride`
  - 界面操作 6 条：`take` · `put` · `move` · `select` · `input` · `close`
  - 控制 3 条：`stop` · `mode <模式名>` · `control`
  - 宏 1 条：`macro <宏名>`
  - 设置 2 条：`get` · `set`
- **指令表自省**：`GET /help` 返回完整指令表（名字、参数、类型、说明），
  CLI 与 LLM agent 从这里现读，服务端加指令无需改客户端
- **容器与工位会话**：箱子 / 熔炉 / 工作台 / 附魔台 / 切石机 / 村民交易 / 信标（拒绝）/
  随身 2×2 合成格；统一走 `open` → 摆料 → `select` → `take` → `close` 流程
- **物品守恒**：所有搬运经 `IItemHandler` 抽取与插入校验；关会话 / 走远 / 超时 /
  服务端正常关闭自动退料
- **接管机制**：`control` 接管 = 切到内置「指令模式」任务（不注册主动行为、关闭随机游走）；`control off` / `stop` 后交还原工作模式
- **唤醒制区块加载**：未唤醒 = 零加载票 = 真消失；`wake` 用 NeoForge 官方
  `TicketController.forceChunk(forceLoad=true)` 保证实体真 tick
- **Python CLI**：零第三方依赖；位置参数糖、批量 `run`、`--json`、操作类自动轮询到完成
- **技能文档（`skill/maid-cli/`）**：SKILL.md + 6 个 references，附 `sync_commands_index.py` 从 `/help` 自动生成参数总表并防漂移
- **示例脚本（`examples/`）**：蓝图 → 施工计划 JSON、施工驱动、独立核验（只走 `blocks`，不用 RCON）、收敛式补漏
- **技能包**：`skill/maid-cli/`（SKILL.md + references，渐进式披露）

### 设计取向

- **不内嵌 LLM、不自带智能体**：mod 只做干净的、可自省的指令表；智能留在外部
- **不打包依赖**：MC 与 Touhou Little Maid 都是 `compileOnly`，产物 jar 约 300 KB
- **不对外开端口**：只听 `127.0.0.1`

### Known limitations

- 信标界面不支持
- `open entity=` 只支持村民
- `owner:` 槽位不提供
- 虚拟槽 / 鼠标槽不持久化（与原版一致，崩溃会丢）
- 无自动化测试套件，CI 只保证编译不坏
