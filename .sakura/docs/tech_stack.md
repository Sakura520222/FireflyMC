# 技术栈

## 核心框架
- Paper/Bukkit（构建时定义 `bukkit_version`，依赖 `paper-api`）。
- Java 8+。

## 依赖管理
- Maven 使用 `provided` 标记 Bukkit/Paper API，避免打入插件包。
- BuildTools 指定最新 Paper 版本与构建参数（若需要）。

## 环境假定
- 用户已在服务器上安装 Paper（推荐高于最低 API 要求）。
- 不依赖外部存储或网络服务（插件能力范围内）。