# FireflyMC

FireflyMC 是一个面向 FireflyMC 服务器生态的 Minecraft 模组，基于 **Minecraft 1.21.1** 与 **NeoForge 21.1.264** 开发。

当前版本：`3.1.0`  
Mod ID：`fireflymc`

[FireflyMC官网](https://mc.firefly520.top)

## 功能特性

- **游戏内点歌**：`/点歌 <歌名>` 或 `/fireflymc music request <歌名>` 从网易云音乐检索歌曲，全服同步收听；HUD 显示曲名、进度条、实时歌词与排队列表，支持单人世界、LAN 联机与专用服务器。
- **服务器信息 HUD**：在游戏内显示服务器名称、在线人数与官网链接。
- **入服规则确认**：玩家加入多人服务器时显示服务器规则弹窗，并在确认前提供临时保护。
- **客户端安装校验**：多人服务器可检测玩家是否安装 FireflyMC 客户端模组。
- **客户端事件通知 WebSocket**：客户端可将本地玩家加入多人服务器、进入单人存档、死亡和获得新成就等事件发送到独立 WebSocket 服务端。
- **AI 4.0 玩家工作台（实施中）**：仅支持完成身份认证的专服玩家，每人一个持久工作区和永久主会话；工作台、任务、知识查询和默认私有分享等正按完整设计实施，实际完成/验证范围见完整实施记录。
- **新手福利包**：玩家首次加入服务器时可发放新手物资。
- **掉落物自动清理**：专用服务器可按配置周期清理掉落物，并在清理前提示。
- **在线时长限制**：专用服务器可限制每日/连续在线时长，并提供管理命令。
- **单人世界公开联机/中继准备**：客户端包含单人世界 LAN 桥接、中继大厅与 P2P UDP 隧道相关功能。
- **更新检查**：客户端主菜单可检测模组更新。

### 点歌系统细节

- **队列规则**：普通玩家最多同时 3 首自点歌曲（播完/被跳过/播放失败即释放额度）；单人世界本人、LAN 房主与 OP（权限等级 2）不受限；全队列为系统硬上限 50 首。
- **同步播放**：服务端权威计时切歌，所有玩家（含中途加入者）自动跟上当前曲目；各客户端独立下载音频并本地缓存（`music-cache/`，256MB 近似 LRU）。
- **独立音量**：原版“音乐和声音选项”中新增“点歌音乐”滑块（默认 30%），实际音量 = 主音量 × 点歌音乐，不受原版“音乐”分类影响，拖动即时生效。
- **搜索降级**：默认由服务端执行搜索；若服务端无法访问外网，自动改由点歌者的客户端代为搜索并将结果回传服务端（回包经 token 防伪造校验）。
- **技术说明**：MP3 解码使用内嵌的 JLayer 1.0.1（LGPL，见 `NOTICE.md` 与 jar 内 `licenses/`）；付费/VIP 歌曲因版权限制无法点播。

## 运行环境

| 项目 | 版本 |
| --- | --- |
| Minecraft | `1.21.1` |
| NeoForge | `21.1.264` |
| Java | `21` |
| Gradle | 使用仓库内 Gradle Wrapper |

## 安装

1. 安装 Minecraft `1.21.1` 与匹配版本的 NeoForge。
2. 从发布产物中获取 `fireflymc-*.jar`。
3. 将 JAR 放入客户端或服务端的 `mods/` 目录。
4. 启动游戏或服务器，首次启动后会在 `config/` 下生成配置文件。

> 服务端专用能力需要在服务端安装本模组；部分入服校验与 HUD 能力需要客户端也安装本模组。

## 配置文件

启动后会生成以下配置：

- `config/fireflymc-client.toml`：客户端配置，例如 HUD 缩放、单人世界中继大厅与 P2P 参数。
- `config/fireflymc-server.toml`：服务端配置，例如玩家密码验证、新手福利包、掉落物清理和在线时长限制。AI 使用下述独立配置。

重要配置项示例：

- `playerAuth.enabled`：是否启用玩家密码验证（离线模式防顶号）。
- `playerAuth.timeoutSeconds`：密码验证超时时间（秒）。
- `playerAuth.maxAttempts`：密码最大尝试次数。
- `server.enableItemCleanup`：是否启用掉落物自动清理。
- AI 4.0 使用专服独立 `config/fireflymc-ai4.json`，不读取旧 AI 配置，也不将模型密钥下发客户端。
- `starterKit.enabled`：是否启用新手福利包。
- `playtime.enablePlaytimeLimiter`：是否启用在线时长限制。
- `singleplayer_relay.enabled`：是否启用单人世界公开联机提示与中继准备功能。
- `event_notification.enabled`：是否启用客户端事件通知 WebSocket。
- `event_notification.webSocketUrl`：客户端事件通知 WebSocket 服务端地址。
- `music_settings.requestVolume`（位于 `fireflymc-client.toml`）：点歌音乐独立音量（0.0 ~ 1.0，默认 0.3，与主音量相乘；游戏内滑块拖动后即时生效并写回此值）。

## 游戏内命令

### 点歌

```text
/点歌 <歌名>
/fireflymc music request <歌名>
/fireflymc music queue
/fireflymc music skip
/fireflymc music stop
```

- `/点歌 <歌名>`（或 `/fireflymc music request <歌名>`）：搜索并点播歌曲，点歌成功后全服播报。
- `/fireflymc music queue`：查看当前播放曲目与完整排队列表（无权限要求；队列上限 50，输出可能较长刷屏）。
- `/fireflymc music skip`：跳过当前歌曲。需要单人世界本人、LAN 房主或 OP 2。
- `/fireflymc music stop`：停止播放并清空整个队列（含正在搜索中的请求）。权限同上。

### AI 工作台

AI 没有玩家或控制台命令入口。连接启用 AI 的专用服务端并通过 FireflyMC 认证后，使用默认 **K** 键打开本人工作台；按键可在控制设置中修改。单人世界和集成 LAN 不提供 AI。

默认伙伴“小萤”，在工作台设置名称与风格。聊天、任务控制、审批、记忆、分享和隐私操作均在同一工作台内完成，关闭界面不取消后台任务。普通聊天不会唤醒 AI，也不会被采集到主会话。

服主使用 [新配置示例](docs/examples/fireflymc-ai4.example.json) 配置专服的 `config/fireflymc-ai4.json`，显式选择 `chat_completions` 或 `responses`；地址填写提供者的 API 基址（通常以 `/v1` 结尾）。缺少新配置时服务显示未就绪。配置在服务器启动时加载，不导入旧地址、密钥、名称或历史。

所有 AI 内容默认私有。分享只发布本人选定、预览确认后的固定正文；死亡/进度记录开关默认关闭。新 AI 不提供管理命令、后台传送、刷物品等能力，OP 身份不会扩大 AI 工具集。点歌继续保留 `/点歌` 快捷例外。

AI 跨服分享默认关闭。启用时，服主需在新配置的 `sharing` 中明确填写通道地址和公开受众；客户端实际连接地址须匹配，预览后更换通道或受众会使批准失效。现有普通跨服聊天仍按自身设置运行。跨服发送结果会区分通道已接收和外部送达未知。

完整历史、任务与产物位于专服世界的 `data/fireflymc/ai/`。本轮仍在持续实施，不能将设计、静态模组发现或单独编译通过当成全部功能已完成。当前需求对照、验收结果和剩余工作见 [完整实施记录](docs/FireflyMC-AI-4.0-完整实施记录.md)；[P0 实施记录](docs/FireflyMC-AI-4.0-P0-实施记录.md)保留为历史证据。

### 在线时长管理

```text
/fireflymc playtime check
/fireflymc playtime check <玩家>
/fireflymc playtime reset <玩家>
/fireflymc playtime resetdaily
```

- `/fireflymc playtime check`：查看自己的剩余在线时长。
- `/fireflymc playtime check <玩家>`：查看其他玩家剩余在线时长，需要 OP 2。
- `/fireflymc playtime reset <玩家>`：重置指定玩家每日在线时长，需要 OP 4。
- `/fireflymc playtime resetdaily`：重置所有玩家每日在线时长，需要 OP 4。

### 玩家密码验证

```text
/fireflymc auth reset <玩家名>
```

- `/fireflymc auth reset <玩家名>`：重置指定玩家的密码，下次加入时重新设置。需要 OP 4。

### 生成生物

```text
/fireflymc spawnall <生物类型> [targets] [数量] [半径]
```

在目标玩家附近批量生成指定类型生物。

## 开发

### 克隆与准备

本项目使用 Gradle Wrapper，无需全局安装 Gradle。需要本机安装 Java 21。

### 常用任务

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
.\gradlew.bat runServer
.\gradlew.bat tasks
```

对应说明：

- `build`：构建项目。
- `runClient`：启动开发客户端。
- `runServer`：启动开发服务端。
- `tasks`：查看所有可用 Gradle 任务。

### 项目结构

```text
src/main/java/firefly520/fireflymc/      Java 源码
src/main/resources/                      资源、语言文件、Mixin 配置
src/main/templates/META-INF/             NeoForge 模组元数据模板
docs/                                    项目文档
licenses/                                第三方许可证文本（随发布 JAR 分发）
```

## 构建产物

执行构建后，模组 JAR 通常输出到：

```text
build/libs/
```

## 许可证

本项目为 **All Rights Reserved**。

第三方组件：点歌系统的 MP3 解码使用 [JLayer 1.0.1](https://repo1.maven.org/maven2/javazoom/jlayer/1.0.1/)（LGPL-2.1），以未修改的嵌套 JAR 形式随本模组分发，详见 `NOTICE.md` 与发布 JAR 内的 `licenses/jlayer-LGPL-2.1.txt`。
