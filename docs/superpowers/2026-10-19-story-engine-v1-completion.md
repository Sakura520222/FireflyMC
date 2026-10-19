# Story Engine v1 完成记录

日期：2026-10-19（Asia/Shanghai）

本次从 `2026-10-14-story-engine-v1-handoff.md` 续做，完成引擎 v1 和正式序章。任务书 UI/HUD、对话、NPC、过场和客户端定义下发继续按设计留给后续子项目。

## 当天实机反馈后的修复

用户反馈重启服务器后丢出、拾取回响碎片，find_memory 仍 ACTIVE 且 counters 为空；随后确认本次只完成密码验证，没有再次确认准则。该状态会被服务端剧情会话门控拦截，属于尚未完成入服流程。

同时修复了客户端确认流程中的三个缺口：

- “看过准则”状态原先跨重连保留，而且弹窗一打开就标记；现在只在实际确认时标记，并在断开/进入新连接时清空。
- 重复准则窗口在倒计时结束前被 Esc 关闭时，原先不回传确认；现在与该窗口既有的倒计时确认语义一致，仅回传一次。首次确认窗口仍需用户明确同意。
- 密码成功通知原先无条件关闭当前界面，可能关闭已经打开的准则窗口；现在仅关闭密码界面。

新增所有玩家可用的 `/fireflymc rules`，用于重新打开准则窗口；命令本身不标记同意，也不绕过密码验证。story status 新增 story_enabled、session_open、session_block，准则未确认时还显示恢复命令。用户确认测试和序章均通过后，移除了正常登录时多余的自动准则聊天提醒，保留弹窗修复与手动恢复入口。

更新客户端和服务端并重连后，完成密码验证，在准则窗口点击同意；也可执行 `/fireflymc rules` 重开。确认后再次丢出并拾起现有碎片即可，原有存档和任务进度保留，之前被门控忽略的拾取不追溯计数。

本轮新鲜验证：`gradlew.bat build` 通过，398 项 JUnit 全绿；11 项 required GameTest 全绿。新增覆盖真实玩家丢弃/拾取延迟，以及恢复存档后“密码已验证、准则未确认 → 物品入库但剧情不计数 → 重开窗口不自动同意 → 确认后再次拾取推进”的路径。后者显式注入专服门控条件，因为 GameTestServer 本身不是 DedicatedServer。用户随后反馈测试和序章均已通过。

以下“新鲜验证”章节的 392 项 / 9 项为本轮修复前的首轮交付证据，最新证据以上述 398 项 / 11 项为准。

## 交付

- 修复 custom trigger 在计数增加但任务未完成时漏同步，以及事务异常后重复调用 dirty callback；评估阶段和事务阶段分别负责自己的异常路径。同类 gameplay dispatch 的部分修改异常也会正确同步。
- 修复 Brigadier 使用字符串参数导致 `fireflymc:prologue/find_memory` 无法直接输入的问题，任务和事件 ID 使用原版 ResourceLocation 参数。
- 修复世界 `setflag` 误读玩家参数，以及世界 `setvar` 写入后查询仅支持玩家范围的变量兴趣索引而返回失败的问题。世界变量持久化并广播；v1 没有世界变量条件，不产生玩家变量重评估。
- 正式序章为 `first_light → find_memory → heard_voice → quiet_night → old_trail → embers_end`。新玩家 bootstrap 后 `first_light=COMPLETED`、`find_memory=ACTIVE`。
- 玩家拾取 1 个回响碎片后进入声音桥接节点，再击败 3 只僵尸、背包持有 8 支火把，最后完成序章并设置世界标记 `prologue.awaiting_firefly=true`。聊天提示、标题与说明均有中英文翻译。箱子取出的碎片可丢出再拾起；序章不绑定绝对坐标，也不等待未来 NPC 事件。
- 新增 `StoryItemPickupMixin`：Minecraft 1.21.1 的 `Inventory.add` 在部分入库后可能返回 false，原拾取代码因此不发 NeoForge Post。仅在此调用处将实际缩小物品堆的情况视为成功，使原有 Post、统计和剩余物处理正常执行；没有修改全局 Inventory.add。真实生存模式测试验证 10 个物品只入库 4 个、地面剩余 6 个、剧情仅增加 4，随后满包再次拾取不增加计数。
- 成就条件新增推荐字段 `advancement`，独立于 requirement 的持久化 `id`；原 `id` 目标字段仍兼容。示例：`{"id":"stone","type":"fireflymc:advancement","advancement":"minecraft:story/mine_stone"}`。
- malformed JSON 的错误现在包含完整数据包文件路径。覆盖全部 10 种条件和 9 种动作的内容位于 `src/test/resources/story_fixture/coverage/`，不进入发布数据包。

## 新鲜验证

Windows / Microsoft Java 21.0.12 / Minecraft 1.21.1 / NeoForge 21.1.241，使用仓库 Gradle Wrapper。

- `gradlew.bat compileJava test`：392 项 JUnit，0 失败、0 错误、0 跳过。
- `gradlew.bat clean build`：通过，包含全量 JUnit。
- `gradlew.bat runGameTestServer`：9 项 required GameTest 全部通过。
- `git diff --check`、新增源码和 JSON 的空白/重复键检查、JAR 内容检查通过。

GameTest 覆盖：真实部分拾取和失败拾取、击杀/维度/成就事件总线、异步 custom event 拒绝旧连接和替换玩家、正式六节点全链、登录与真实死亡重生发包、附件死亡复制与玩家磁盘重登、世界 SavedData 文件读回、一次完成链只发一个快照、注册后的命令树和两种 reset、维度/群系/坐标/已加载结构与结构标签探测、坏 JSON 保留原快照、真实数据包重载更换 revision 和兴趣索引、移除覆盖包恢复原 revision。

GameTest 在 DEDICATED 发行环境运行，已覆盖 common/server 类加载；不需要为同一项检查另起常驻服务。模拟连接使用 NeoForge 的 `configureMockConnection`，它不等同于真实客户端的网络协商。

测试中的临时数据包由独立目录创建并在 finally 中移除；测试批次隔离全局快照。SavedData 和玩家重登验证在读取前等待 NeoForge IO worker 完成，不依赖偶然 tick 顺序。

## 规则自查

- `.sakura/rules/`：运行时与发包保持服务器主线程；异步公共 trigger 经 server executor，并复核玩家连接实例；客户端快照使用不可变 payload 和 volatile 引用，在退出/进入世界时清空。
- 配置关闭时仍加载定义，重新启用调和进度；测试覆盖权限、reset token 绑定/有效期/单次使用和相互独立的玩家/世界范围。
- 任务 ID 由原版参数解析，未注册 ID 向操作者报错。新日志不输出 reset token、密码或其他凭据。
- Mixin 仅补齐已经发生部分拾取的后续处理，保留原有 pickup delay、目标玩家限制、完整拾取及失败拾取路径。发布 JAR 保留 NOTICE、LGPL 文本和嵌套 JLayer。

## 仍需真人客户端验收的范围

本次没有启动并操作真实 Minecraft 客户端。以下是明确的交互验收，不作为已经完成的 live round-trip 证据：

1. 在已经接受 Minecraft EULA 的本地测试环境，分别运行 `gradlew.bat runServer`、`gradlew.bat runClient`，使用同版本 Mod 连接服务器。开启密码验证时，完成 Mod 握手、规则确认和密码验证后才应出现序章文本；确认前不能推进剧情。
2. OP 执行 `/fireflymc story status <玩家>`，应看到 first_light 完成、find_memory 激活。执行 `/give <玩家> minecraft:echo_shard 1`，玩家将碎片丢出并拾起，quiet_night 应激活，聊天出现下一步提示。
3. 玩家击败 3 只僵尸，或用 OP 命令 `/fireflymc story counter fireflymc:prologue/quiet_night zombies set 3 <玩家>` 推进到 old_trail。执行 `/give <玩家> minecraft:torch 8`，等待最多一次轮询（20 ticks）；序章应全部完成，并出现终章标题、聊天与面包奖励。
4. 在任务中间死亡并点击重生、断开后重连，再用 status 检查计数与进度保持。关闭服务端并重新启动，确认世界标记仍存在。
5. 在客户端调试器观察 `ClientPayloadHandler.handleStoryProgressSnapshot` / `ClientStoryState.install`，确认服务器的 revision 和进度进入镜像；退出世界后 snapshot 为空。连续进入不同单人世界、再连专服，确认无前一世界残留。此版没有用于展示镜像的任务书界面。

服务端网络入口、codec、快照合并和 client mirror 已有自动测试；真实握手交互、客户端收包时序和反复切换集成服务器仍属于上述手工验收。

## Git 与版本

- 当前分支 `3.1.0`，本地基线为 `41b701a`，与旧交接记录的 `233f8f2` 不同；未改写任何历史。
- 用户已授权将本次剧情系统及相关集成修复提交并推送到 `3.1.0`。提交范围为剧情实现、序章、配套测试和相关文档；无关的 `docs/report.md` 保留在工作区，不纳入提交。未进行发布或部署。
- `gradle.properties:mod_version` 与 `FireflyMCMod.VERSION` 均保持 `3.1.0`；网络协议保持本次剧情包所需的 `1.5.0`。没有暂存 3.2.0 发布。当前机器没有相邻的 FireflyMC-APP checkout，未声称验证其显示版本。
- 可构建产物：`build/libs/fireflymc-3.1.0.jar`。正式剧情资源共 1 个章节和 6 个任务；测试 fixtures 不入包。

旧 `.superpowers/sdd/` Task 12 report/manifest 不在当前工作区，因此不改写或伪造旧模型复审。本记录提供本次实际执行的验证与自查证据。
