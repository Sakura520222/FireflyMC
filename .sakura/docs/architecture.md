# FireflyMC 架构

## 概览
基于 Bukkit 的 Minecraft 插件，支持「区域管理」与「游戏模式」。
核心：Plugin 主类 → 各 Manager → 监听器/命令处理器。

## 模块划分
- `Manager`: 跨领域协调与资源持有（RegionManager、ModeManager、BoardManager 等）。
- `Listener`: 事件驱动，封装业务逻辑与权限/区域/模式检查。
- `Command`: 命令层校验参数、权限，调用 Manager/Service 执行并反馈结果。
- `Board`: 记分板展示，不包含业务逻辑。

## 分层约束
- Listener/Command 不直接操作其他模块 Listener；通过 Manager 进行协作。
- GUI 组件无状态或仅持有不可变配置；数据由 Manager 管理。
- 异步任务与回调通过 EventBus/事件委托；避免跨线程共享可变状态。

## 设计原则
- 单一职责：每个 Listener/Command 只对一类事件/命令负责。
- 关注点分离：监听器关注「是否允许」与「前置检查」，Manager 关注「状态变更」与「一致性」。
- 依赖倒置：模块通过接口/事件交互，而非强依赖实现细节。