# 一段可照抄的完整演示

前提：服务端装了 NeoForge 21.1.219 + Touhou Little Maid + `maid_cli-1.0.0.jar`，
世界里有至少一只女仆，模组已加载（`maidcli ping` 能通）。

## 0. 起服务端

```bash
java -jar neoforge-21.1.219-installer.jar --installServer   # 只需一次
./run.sh                                                     # Windows: run.bat
```

日志里应能看到：

```
[Maid CLI] HTTP 服务已启动 127.0.0.1:8644
[Maid CLI] 密钥文件 .maidkey.json（首次生成）
```

## 1. 装 CLI 并连上

```bash
python -m venv .venv
. .venv/bin/activate          # Windows: .venv\Scripts\activate
pip install -e cli/
maidcli ping                  # {"ok": true, "mod": "maid_cli", "version": "1.0.0", ...}
```

## 2. 找她

```bash
maidcli maids                 # 列出世界里的女仆：标签 / 名字 / 坐标 / 是否加载
```

女仆的**标签**（如 `FH3YVQ`）比名字可靠 —— 同名女仆会有歧义。

```bash
export MAIDCLI_MAID=<你的女仆标签>     # 之后不用每次 --maid
```

## 3. 读她的感官

```bash
maidcli sense self            # 血量 / 饥饿 / 手上拿的 / 背包 / 状态
maidcli sense world           # 她在的维度、时间、天气、脚下地形
maidcli whereabouts           # 全部女仆在哪、是否加载
```

## 4. 让她干活

```bash
maidcli get follow sit        # 先接管（关掉她自己乱走的自由意志）
maidcli go pos=-140 123 30    # 走过去
maidcli hit target=nearest    # 打最近的东西
maidcli say text="我到了"      # 让她说话（聊天栏）
```

## 5. 开一个容器 / 工位会话

```bash
maidcli open kind=chest pos=-140 124 30    # 打开旁边的箱子（她必须是执行者）
maidcli move from=inv:0 to=box:0           # 往里放一个
maidcli select                              # 结算（工作台/附魔台这类需要）
maidcli close                               # 关会话（材料自动退回原格）
```

## 6. 全自动建个凉亭

```bash
# ① 离线算计划（不碰游戏）
python tools/build_plan.py --blueprint demo-pavilion --anchor 0 100 0 --outdir plans

# ② 进场：清场地 → 给材料 → 按层施工
maidcli get follow sit
maidcli clear pos=0 100 0 size=5 5 5 level=3
maidcli give item=oak_log count=12
maidcli build plan=plans/demo-pavilion.json

# ③ 独立核验（用 blocks 重新读世界，不信 mod 的自报结果）
python tools/verify_build.py --plan plans/demo-pavilion.json --password <你的密码>
```

## 7. 收工

```bash
maidcli stop                  # 停掉当前动作
maidcli get free              # 把自由意志还给她
maidcli close                 # 有会话就先关（不然退出/关服才会退料）
```

## 排错

| 现象 | 原因 / 处理 |
|---|---|
| `401 unauthorized` | `.maidkey.json` 跟服务端对不上；删掉它让 CLI 重新 `login` |
| `not_awake` | 她不在加载范围。`maidcli wake <标签>` 先唤醒 |
| `too_far` | 你（假玩家）离她太远；先 `go` 到她附近 |
| `entity_ticking: false` | 区块没 tick；`maidcli wake` 后 `inspect` 复查 |
| 界面里的东西不见了 | 会话超时（默认 180 秒）会退回原格；重新 `open` |
