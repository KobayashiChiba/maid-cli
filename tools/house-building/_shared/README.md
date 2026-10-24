# _shared

工具之间唯一的共享代码。**不要在这里放业务逻辑**，只放"连接"和"文件格式"。

| 文件 | 内容 |
|---|---|
| `maidlib.py` | `Maid` 类：起 `maidcli` 子进程、解析 `--json`、归一错误、读单格方块、自动唤醒 |
| `plan.py` | 计划 JSON 读写、`parse_state` / `compatible`（blockstate 比对）|

约定：只用标准库；所有网络访问必须经 `maidlib`（禁止直连 RCON）。
