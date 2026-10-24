# tools

按用途分组的工具链。每个子目录是一个**完整流程**，里面再按步骤分文件夹。

| 目录 | 用途 | 状态 |
|---|---|---|
| [`house-building/`](house-building/README.md) | 蓝图 → 计划 → 选址 → 预览 → 施工 → 核验 → 补漏 | 可用（详见该目录 README）|

## 约定

- 每个流程目录下有自己的 `README.md` 讲清整条链路
- 每个步骤一个文件夹，含：脚本 + `README.md` + `config.example.json`（+ `requirements.txt` 如果要用第三方库）
- 真实配置 `config.json` 不入库（`.gitignore` 已覆盖）
- **只通过 `maidcli` 与游戏交互**，不使用 RCON、不发作弊指令
