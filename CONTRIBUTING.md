# 参与开发

## 环境

| 需要 | 版本 | 说明 |
|---|---|---|
| JDK | 21 | MC 1.20.5 起官方发行 Java 21；`build.gradle` 用 toolchain 声明，本机没装也能靠 foojay 自动下 |
| Minecraft | 1.21.1 | `gradle.properties` 的 `minecraft_version` |
| NeoForge | 21.1.219 | `neo_version` |
| Touhou Little Maid | 1.5.3+ | 编译期依赖，走 Modrinth maven 自动下载 |
| Python | 3.9+ | 只有 CLI 与 tools 需要；零第三方依赖 |

版本号一律改 `gradle.properties`，**不要改源码里的字符串**——
`build.gradle` 的 `processResources` 会把 `${...}` 展开进 `neoforge.mods.toml`，
`/ping` 返回的版本也来自同一处。

## 编译

```bash
./gradlew build          # 产物 build/libs/maid_cli-<version>.jar（约 300 KB）
```

产物只有自己的 class —— MC 与 TLM 都是 `compileOnly`。**如果 jar 超过 2 MB，
说明依赖被 `implementation` 进去了，PR 会被 CI 拦下。**

### 国内网络会卡住（实测 2026-10-22）

`maven.neoforged.net` 在国内经常被阻断：

```
DNS 被污染   → maven.neoforged.net 解析到 157.240.10.41（Facebook 的 IP）
直连真实 IP  → Cloudflare DoH 查到 CDN77 的 84.17.57.26 / 156.146.44.89 / 95.173.197.105
               三个 IP 全部在 ~20 ms 收到 TLS RST（不是超时，是主动重置）
其余都正常   → api.modrinth.com ✓  launchermeta.mojang.com ✓  plugins.gradle.org ✓  repo1.maven.org ✓
```

现象是配置阶段就失败：

```
Could not resolve all files for configuration ':neoFormRuntimeTool'.
   > Could not resolve net.neoforged:neoform-runtime:1.0.24
```

**绕法（任选）**：

1. 挂代理跑 Gradle（`-Dhttps.proxyHost=... -Dhttps.proxyPort=...`）
2. 让 CI 编（GitHub Actions 访问 neoforged 无障碍）—— push 到分支看 `build.yml`
3. 本机极速迭代：`MC_SERVER_DIR=/path/to/server TLM_JAR=/path/to/tlm.jar ./tools/build-fast.sh`
   （拿本地服务端的 `libraries/` 当 classpath，完全绕开 Gradle 网络）

⚠️ 别用 `--offline` 试 —— 没缓存时它只会把同一个解析失败伪装成"离线模式没有缓存"，
更难看出根因。

### 依赖坐标

```gradle
compileOnly   "maven.modrinth:touhou-little-maid:${tlm_version}"
localRuntime  "maven.modrinth:touhou-little-maid:${tlm_version}"
```

Modrinth maven 抽风时，把 `touhoulittlemaid-*.jar` 丢进 `libs/`（已在 `.gitignore` 里），
`build.gradle` 有 `flatDir` 兜底。

## 加一条新指令

架构是 4 层，**指令层是唯一入口**：

```
传输层 HttpApi（鉴权 · 解析 · 序列化 · 门禁）
   ↓
指令层 MaidCmd（参数校验 · 拆成作业 · 自省）   ← 只在这层加东西
   ↓
执行层 Job / Action / ops（调 TLM 与原版 API 改世界）
```

步骤：

1. 写一个 `MaidCmd` 实现，声明参数（名字、类型、是否必填、说明）——`/help` 的返回就从这里生成
2. 在 `CmdRegistry` 注册一行
3. 具体动作用 `ops/` 里的能力，不要直接在 cmd 里写 NBT 操作
4. 同步 `COMMANDS.md`（内部）与 `skill/maid-cli/references/commands.md`（对外）

**别在 CLI 里加指令** —— CLI 只是薄壳，指令表从 `GET /help` 现读。CLI 只该有
`cmds` / `ping` / `run` 这类元命令。

## 验证要求（这条最容易被跳，但最重要）

PR 描述里不能只写"编译过了"。要写清：

- 跑了哪条命令、参数是什么
- 服务端的**实际返回**（JSON 或错误码）
- 涉及物品的改动 → 给**守恒证据**：改前多少、改后多少
- 涉及界面会话 → 说明 `close` 之后材料有没有退回原格

历史教训：容器交互层曾连续出现 4 个"白拿物品"的 bug（结果格不扣料、close 时把预览当实物退），
全部靠"改前改后的数量对不上"才发现。所以**数量对不上的一律视为没修好**。

## 不要提交

- 私服信息：服务器 IP / 端口约定 / 玩家 uuid / 存档名
- 真正的密钥：`.maidkey.json`、`server.properties`、`rcon.password`
- 构建产物：`build/`、`build-fast/`、`*.jar`（`gradle/wrapper/gradle-wrapper.jar` 除外）
- 一次性脚本：调试用的 `find_holes2/3/5~9.py` 这类，不进 `tools/`

## PR

- 分支从 `main` 切出，一个 PR 只做一件事
- 按 `.github/PULL_REQUEST_TEMPLATE.md` 填
- CI 必须绿（`build.yml` 在 Ubuntu 与 Windows 各编一次）
- 发版见 `CHANGELOG.md` 顶部流程说明；tag 必须等于 `v<mod_version>`
