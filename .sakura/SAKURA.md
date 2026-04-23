# 项目概述文档：FireflyMC

## 1. 项目简介
FireflyMC 是一个基于 Java 开发的 Minecraft 模组项目，旨在为游戏扩展自定义的方块、方块实体及相关玩法机制。

## 2. 技术栈
- 核心语言：Java
- 构建工具：Gradle（含 Gradle Wrapper）
- 基础框架：Minecraft Mod 开发框架（根据文档术语推断为 Forge 或 NeoForge 体系）
- 持续集成：GitHub Actions

## 3. 项目结构
- .github/workflows/：存放 GitHub Actions 自动化构建与测试的工作流配置文件。
- .sakura/：项目的专属开发知识库与规范文档，内容涵盖新手入门、项目结构划分、版本控制，以及底层机制（如注册表、客户端与服务端概念、NBT 数据存储、访问转换器、方块与方块实体索引等）。
- gradle/ 及构建脚本：包含 Gradle Wrapper 的 jar 包和配置文件，以及 build.gradle、settings.gradle 等项目构建与依赖管理声明文件。
- src/main/：项目核心源代码及资源根目录，存放所有的 Java 代码包、游戏 assets 和 data 资源文件。
- .gitignore：Git 版本控制忽略规则文件。

## 4. 开发约定
基于项目结构和知识库目录，该项目遵循以下开发规范：
- **规范的注册机制**：新增的游戏内容（如方块、方块实体）必须严格遵循 Minecraft 的 Registry（注册表）体系进行统一注册。
- **严格的物理侧隔离**：在开发逻辑时需明确区分 Client（客户端）和 Server（服务端），确保逻辑在正确的物理侧执行，避免跨端调用导致的崩溃。
- **合规的原版侵入**：在需要修改 Minecraft 原版代码访问权限（如将 private 改为 public）时，统一使用 Access Transformers (AT) 机制，而非直接反编译混淆源码。
- **标准化的数据存储**：对于需要保存的动态数据（如方块实体的状态），统一使用 NBT（Named Binary Tag）数据格式进行序列化与反序列化。
- **模块化与版本化管理**：代码结构需符合 gettingstarted_structuring 规范，并遵循 gettingstarted_versioning 进行模组版本号的迭代管理。