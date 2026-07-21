# FireflyMC

FireflyMC 是一个面向 FireflyMC 服务器生态的 Minecraft 模组，基于 **Minecraft 1.21.1** 与 **NeoForge 21.1.241** 开发。

当前版本：`3.0.0`  
Mod ID：`fireflymc`

## 功能特性

- **服务器信息 HUD**：在游戏内显示服务器名称、在线人数与官网链接。
- **入服规则确认**：玩家加入多人服务器时显示服务器规则弹窗，并在确认前提供临时保护。
- **客户端安装校验**：多人服务器可检测玩家是否安装 FireflyMC 客户端模组。
- **客户端事件通知 WebSocket**：客户端可将本地玩家加入多人服务器、进入单人存档、死亡和获得新成就等事件发送到独立 WebSocket 服务端。
- **AI 聊天助手**：通过 `/ai <消息>` 与 AI 对话，支持聊天上下文、冷却、主动回复与函数调用配置。
- **新手福利包**：玩家首次加入服务器时可发放新手物资。
- **掉落物自动清理**：专用服务器可按配置周期清理掉落物，并在清理前提示。
- **在线时长限制**：专用服务器可限制每日/连续在线时长，并提供管理命令。
- **单人世界公开联机/中继准备**：客户端包含单人世界 LAN 桥接、中继大厅与 P2P UDP 隧道相关功能。
- **更新检查**：客户端主菜单可检测模组更新。

## 运行环境

| 项目 | 版本 |
| --- | --- |
| Minecraft | `1.21.1` |
| NeoForge | `21.1.241` |
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
- `config/fireflymc-server.toml`：服务端配置，例如玩家密码验证、AI 聊天、新手福利包、掉落物清理和在线时长限制。

重要配置项示例：

- `playerAuth.enabled`：是否启用玩家密码验证（离线模式防顶号）。
- `playerAuth.timeoutSeconds`：密码验证超时时间（秒）。
- `playerAuth.maxAttempts`：密码最大尝试次数。
- `server.enableItemCleanup`：是否启用掉落物自动清理。
- `ai.enabled`：是否启用 AI 聊天功能。
- `starterKit.enabled`：是否启用新手福利包。
- `playtime.enablePlaytimeLimiter`：是否启用在线时长限制。
- `singleplayer_relay.enabled`：是否启用单人世界公开联机提示与中继准备功能。
- `event_notification.enabled`：是否启用客户端事件通知 WebSocket。
- `event_notification.webSocketUrl`：客户端事件通知 WebSocket 服务端地址。

## 游戏内命令

### AI 聊天

```text
/ai <消息>
```

向 AI 助手发送消息。聊天中包含“小樱”时也可触发 AI 回复（受配置与冷却限制影响）。

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
.sakura/                                 开发文档与实现参考
```

### 对接文档

- [客户端事件通知 WebSocket 协议](docs/client-event-websocket-protocol.md)

## 构建产物

执行构建后，模组 JAR 通常输出到：

```text
build/libs/
```

## 许可证

本项目为 **All Rights Reserved**。
