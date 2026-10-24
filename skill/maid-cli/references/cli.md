# CLI 用法

零第三方依赖，Python 3.9+。

## 安装

```bash
pip install -e cli/          # 装好后有 maidcli 命令
# 或者直接用：python cli/maidcli.py <指令>
```

## 全局选项

| 选项 | 环境变量 | 说明 |
|---|---|---|
| `--maid <tag>` | `MAIDCLI_MAID` | 目标女仆（6 位标签）|
| `--host <地址>` | `MAIDCLI_HOST` | 服务端地址，默认 `127.0.0.1` |
| `--port <端口>` | `MAIDCLI_PORT` | 默认 `8644` |
| `--player <名>` | `MAIDCLI_PLAYER` | 登录用的玩家名 |
| `--json` | — | 输出原始 JSON |
| `--no-wait` | — | 写操作不等完成（默认会等）|
| `--timeout <秒>` | — | 等完成的上限（默认 60）|

key 相关：`MAIDCLI_KEY`（直接给 key，不落盘）· `MAIDCLI_KEY_FILE`（key 文件路径，默认 `~/.maidcli/key.json`）

## 元命令

| 命令 | 用途 |
|---|---|
| `maidcli ping` | 健康检查（含模组版本）|
| `maidcli version` | 打印 CLI 版本 + 服务端信息 |
| `maidcli cmds` | 列服务端全部指令（机器可读）|
| `maidcli help [what=mode\|errors]` | 指令自省 / 资料 |
| `maidcli batch "cmd1" "cmd2"` | 一次请求发多条（≤32，读写不能混批）|
| `maidcli batch -f file.mcl` | 从文件读，一行一条，`#` 注释 |

## 退出码

| 码 | 含义 |
|---|---|
| 0 | 成功（作业真的做完了）|
| 1 | 有失败（命令受理但执行失败/部分失败）|
| 130 | Ctrl+C |

脚本里可直接用 `if maidcli place ...; then ... fi`。

## 远程服务器

模组故意只监听 `127.0.0.1`。服务端在远端时先建隧道：

```bash
ssh -L 8644:127.0.0.1:8644 <用户>@<服务器>
maidcli ping        # 本地默认地址即可
```

隧道换了本地端口用 `--port`；有反代时用 `--host`。key 按 `host:port` 分别保存。

## 版本一致性

CLI 每次调用前会比对 `ping` 返回的模组版本，不一致会打警告（不阻塞）。
想跳过：`MAIDCLI_NO_VERSION_CHECK=1`。
