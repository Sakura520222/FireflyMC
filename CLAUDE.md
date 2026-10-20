# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

- **项目类型**：Minecraft 1.21.1 模组（Mod）
- **Mod Loader**：NeoForge 21.1.241（Gradle 8.10 Wrapper，Java 21，Parchment 2024.11.17 映射）
- **Mod ID**：`fireflymc`（Group: `top.firefly520.fireflymc`，主入口类 [FireflyMCMod.java](src/main/java/firefly520/fireflymc/FireflyMCMod.java)）
- **核心功能**：游戏内点歌（网易云检索、全服同步播放）、单人世界公开联机（WebSocket 中继大厅 + UDP P2P 打洞）、玩家密码认证（防顶号）、入服规则确认、AI 聊天（函数调用）、新手福利包、掉落物自动清理、在线时长限制、客户端事件 WebSocket 通知（含跨服聊天代发到云端/QQ 群）、更新检查。
- **版本与构建元数据**：集中在 [gradle.properties](gradle.properties)（`mod_version`、`minecraft_version`、`neo_version`、`parchment_*`），版本号以该文件为唯一来源。
- **开发环境**：Ubuntu（Linux），bash。使用仓库内 Gradle Wrapper，无需全局安装 Gradle；需本机 Java 21。
- **云端服务器源码参考路径（Python，含中继与官网）**：`/home/firefly/Projects/firefly_mc`
- **移动端论坛 App 源码参考路径（Android，Kotlin/Compose）**：`/home/firefly/Projects/FireflyMC-APP`
- **整合包实机安装路径（版本隔离实例，game dir 即该目录）**：`D:\Game\MC\.minecraft\versions\FireflyMC`——内含 `mods/`（含实装 `fireflymc-*.jar`）、`logs/`（latest.log）、`crash-reports/`、`saves/`、`config/`、`music-cache/`；排查用户整合包崩溃/日志时直接读取该目录。整合包中的 mod 后续随时会增加、删除、禁用，排查时以该目录 `mods/` 的当前实际内容为准，不要依赖历史结论。

## 常用开发命令 (Gradle)

所有命令在项目根目录下通过 bash 执行：

### 编译与构建

```bash
./gradlew build        # 构建 Mod JAR（输出 build/libs/）
./gradlew compileJava  # 仅编译 Java 代码
```

### 测试（JUnit 5，位于 src/test/java）

```bash
./gradlew test                                                              # 运行全部单元测试
./gradlew test --tests "firefly520.fireflymc.music.MusicQueueManagerTest"  # 单个测试类
./gradlew test --tests "*.MusicQueueManagerTest"                            # 按类名通配
```

- build.gradle 已将 test 源集并入主源集的完整编译 classpath，单测可直接使用 MC / netty 类（如网络包 codec 测试）。
- 项目没有独立 lint 任务。

### 运行与调试

```bash
./gradlew runClient          # 启动客户端（用于开发与 UI/网络调试）
./gradlew runServer          # 启动服务端（默认 --nogui）
./gradlew runGameTestServer  # 运行 GameTest 自动化测试服务器
./gradlew runData            # 运行数据生成器（DataGen）
./gradlew tasks              # 查看所有可用 Gradle 任务
```

### CI 与发布（GitHub Actions）

- [build.yml](.github/workflows/build.yml)：每次 push / PR 自动执行 `./gradlew build`。
- [release-on-pr-merge.yml](.github/workflows/release-on-pr-merge.yml)：**PR 合并到 main 即自动发布**——按 `gradle.properties` 的 `mod_version` 创建/更新 `v{版本}` GitHub Release，把 PR 标题与各提交按 Conventional Commits 前缀（feat/fix/docs/refactor/perf/test/build/ci/chore/i18n，也识别中文前缀"新增/修复/重构/优化"等）分类生成发布说明，随后构建并上传 `FireflyMC-{版本}.jar`。
- 由此约束：**提交信息与 PR 标题应遵循 Conventional Commits**（如 `feat(music): ...`、`fix: ...`），否则会被归入"其他变更"；发新版本前先提升 `mod_version`（版本号不变时，后续合并的 PR 会并入同一 Release 的既有描述并重建附件）。
- 开发环境运行时数据位于 `run/`：配置 `run/config/fireflymc-{client,server}.toml`，持久化 JSON（`fireflymc_passwords.json`、`fireflymc_auth_lockout.json`、`fireflymc_starter_kit.json`、`fireflymc_titles.json`），音频缓存 `run/music-cache/`。

## 高层代码架构与模块设计

源代码根路径：[src/main/java/firefly520/fireflymc/](src/main/java/firefly520/fireflymc/)

```
fireflymc/
├── FireflyMCMod.java       # 主入口：@Mod 注册、配置加载、生命周期与事件总线
├── Config.java / ServerConfig.java   # 客户端 / 服务端 TOML 配置
├── ModEventHandler.java    # 服务端事件处理
├── ItemCleanupManager.java # 掉落物自动清理
├── ai/                     # AI 聊天（上下文、冷却、函数调用 ai/function/）
├── auth/                   # 玩家密码认证（防顶号、尝试锁定）
├── command/                # 生成生物等命令处理
├── kit/                    # 新手福利包
├── music/                  # 点歌系统服务端（搜索、权威队列、同步切歌、失败上报）
├── title/                  # 玩家称号管理
├── playtime/               # 在线时长追踪与限制
├── util/                   # 通用工具（如 ServerLanguageLoader 服务端语言加载）
├── client/                 # 仅客户端逻辑
│   ├── ClientHandler.java / ClientPayloadHandler.java   # 客户端事件与网络包分发
│   ├── HUDRenderer.java / UpdateChecker.java            # HUD 渲染、异步更新检查
│   ├── auth/               # 客户端密码认证锁定（JSON 持久化）
│   ├── eventws/            # 客户端事件通知 WebSocket（含跨服聊天代发 crossChatRelay）
│   ├── music/              # 点歌客户端（音频下载与 LRU 缓存、MP3 解码、歌词 HUD）
│   ├── relay/              # 中继与房间大厅（WebSocket 客户端、房间状态与配置）
│   │   ├── p2p/            # UDP P2P 打洞、候选地址探测、ReorderBuffer 乱序重排、编解码
│   │   └── ipv6/           # IPv6 连通性检测
│   └── screen/             # 客户端界面（RelayLobbyScreen、认证界面等）
├── network/                # 自定义网络包：ModNetwork 通道注册、ModPayloadHandler 编解码与分发、*Payload 载荷
└── mixin/                  # Mixin 注入（聊天事件、多人游戏选服列表注入等）
```

### 核心设计模式与规范

1. **线程模型与主线程委托**：
   - 异步通信（WebSocket、UDP P2P、HTTP 更新检查）运行在虚拟线程或 `ScheduledExecutorService` 中。
   - **严禁**在网络异步回调中直接操作 Minecraft 世界或玩家状态；必须通过 `server.execute(() -> { ... })` 或 `Minecraft.getInstance().execute(() -> { ... })` 委托给游戏主线程。
2. **网络包协议**：
   - 使用 NeoForge 1.21.1 的 `CustomPacketPayload` 机制；通道在 [ModNetwork.java](src/main/java/firefly520/fireflymc/network/ModNetwork.java) 注册，编解码与分发集中在 [ModPayloadHandler.java](src/main/java/firefly520/fireflymc/network/ModPayloadHandler.java)，客户端侧处理在 `client/ClientPayloadHandler.java`。
3. **P2P & 中继网络通信**：
   - 通过 WebSocket 连接中继服务器建立房间与信令交换，再通过 UDP 打洞直连；若直连失败则自动降级走中继代理。
   - 数据包采用 `ReorderBuffer` 进行乱序重组与确认重传。
4. **点歌系统**：
   - 服务端权威计时切歌，所有玩家（含中途加入者）自动同步；各客户端独立下载音频并本地缓存（`music-cache/`，256MB 近似 LRU）。
   - 服务端无法访问外网时自动降级：由点歌者的客户端代为搜索并回传（回包经 token 防伪造校验）。
   - MP3 解码使用内嵌 JLayer 1.0.1（LGPL），以 jarJar 嵌套 JAR 分发。
5. **日志安全与脱敏**：
   - 涉及网络 IP、Token、P2P Candidate 等敏感信息时，必须统一调用脱敏函数，不得在日志中明文打印敏感凭据。

## 开发规范与注意事项

1. **Git 提交限制（严禁自主提交）**：
   - **永远都不许自主提交代码（包括 `git commit`、`git push` 等），子代理（Subagent、Workflow 等）也绝对不允许自主提交。**
   - 必须在用户明确发出提交指令时方可进行 Git 提交或推送。
2. **许可证与分发义务**：项目本体为 All Rights Reserved（见 [README.md](README.md)）；内嵌 JLayer 1.0.1 为 LGPL——`NOTICE.md` 与 `licenses/` 必须随发布 JAR 一同分发（build.gradle 已配置），改动打包方式时不可丢失。
3. **查阅最新官方规范**：
   - 当缺乏 1.21.1 / NeoForge 最新实现细节时，不要仅依赖过时的旧知识，**请明确提出需求由用户协助查询官方最新文档与规范**。
4. **本地参考文档**：
   - [.sakura/docs/](.sakura/docs/)：NeoForge 官方机制、注册表、网络和架构文档。
   - [.sakura/rules/](.sakura/rules/)：项目审查规则（WebSocket 长连接三件套、安全默认值、Mixin/渲染/架构迁移检查清单等），自查或审查代码时对照使用。
   - [.sakura/SAKURA.md](.sakura/SAKURA.md)：项目概览与历史审查遗留的严重问题摘要（与 known-issues.md 互补）。
   - [docs/known-issues.md](docs/known-issues.md)：已知问题与技术债务登记（按模块前缀编号，如 `WS-001`）；修改相关模块前先查阅，避免"修复"有意暂缓的问题。
   - [docs/client-event-websocket-protocol.md](docs/client-event-websocket-protocol.md)：客户端事件通知 WebSocket 协议。
   - [docs/mc-neoforge-lan-bridge-guide.md](docs/mc-neoforge-lan-bridge-guide.md)：LAN 桥接/中继联机设计指南。
5. **Windows → Ubuntu 迁移遗留**：仓库自 Windows 迁移而来。若 `./gradlew` 报 `/bin/sh^M: bad interpreter`，说明脚本被改回 CRLF 行尾，执行 `sed -i 's/\r$//' gradlew` 修复（git 索引内为 LF，`core.autocrlf=input`）。
