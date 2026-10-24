## 这个 PR 做了什么

<!-- 一句话说清。如果是修 bug，写清「什么情况下会出错」。 -->

## 改了哪一层

- [ ] 传输层（HttpApi / 鉴权）
- [ ] 指令层（新增或修改 `MaidCmd`）
- [ ] 执行层（Job / Action / ops）
- [ ] 加载（MaidLoader / 唤醒票）
- [ ] CLI（Python）
- [ ] 文档 / tools

## 怎么验证的

<!--
别只说「编译过了」。写清实测过程：哪条命令、服务端的实际返回、前后的数值对比。
涉及物品的改动必须给出「守恒」证据（改前多少、改后多少）。
-->

## 检查清单

- [ ] 加了新指令的话，`COMMANDS.md` / `references/commands.md` 同步了
- [ ] 没有把依赖 `implementation` 进产物（应保持 `compileOnly`）
- [ ] 没有提交私服信息（IP / 存档名 / 玩家 uuid / 真正的密钥）
- [ ] `./gradlew build` 本地或 CI 通过
