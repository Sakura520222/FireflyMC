# AI 4.0 施工所有权连续性：实现与剩余证明缺口

日期：2026-10-26。本文只记录施工所有权子任务，不表示设计 6.4 或整个安全撤销已验收。

## 已实现的可信边界

- 原生 `ChunkSerializer.write` 返回的同一 `CompoundTag` 内写入 `fireflymc_ownership_v1`；绑定维度、chunk 坐标、chunkNonce、generation、claimNonce、当前运行 epoch、玩家/任务/计划/材料操作/步骤及 before/after。结构 SHA-256 对 compound 键排序，保留列表顺序、NBT 类型、数组内容，仅排除根层所有权字段，避免自引用。
- `OwnershipNbt.readCanonical` 严格检查同 payload 的摘要、坐标、维度、schema、类型、重复位置、nonce 与 generation。该读取本身不授予撤销权。
- `NativeOwnership.Scope` 必须先于实际原生施工开启；只有深层 palette 回调实际观察到一次精确转移且最终 footprint 一致，才可 `finish` 发出 opaque token。相同最终方块、无操作目标、未见到的写入、额外目标及回调中的重复写入/ABA 均不能补造 token。
- 观察 `PalettedContainer` 的带锁/unchecked/私有 index setter，palette 的网络整体读取、resize 后实际存储、`SimpleBitStorage` 的直接 setter，以及原生 BE 添加/移除。任何已存在 claim 的格子再次写入都会撤权，即使之后还原原方块。
- 原始 section 数组、底层 long[] 和外部提供数组的别名可绕过 setter；未知引用逃逸会永久撤销该 chunk 当前对象的撤销能力。经过本地原生源码审计的只读 getter 调用点单独允许；不替换数组、不改变原生 getter 返回值，不阻止其他 Mod 的读写。未适配的 BitStorage 也拒绝授予所有权。
- 有界 `OwnershipRevocations` 记录负向 claimNonce/chunkNonce，并保留“该物理chunk曾发行的nonce”供缺marker/坏payload时撤权。该定位缓存的成员资格绝不用于授予权利，不存可代替chunk正向证明的施工日志。同 JVM 正常卸载/重载只有同一仍存活 runtime、canonical payload 验证、无加载期修改/逃逸、无负向撤权才保留旧 token；格子撤权不连带撤销其他未改变格子。索引耗尽时本 runtime 的新旧撤销权全部拒绝，不驱逐负向记录后允许重放。
- `require` 额外核验当前加载 chunk、维度、运行 epoch、负向索引、chunkNonce、claimNonce、generation、精确 token 与实际 after state。BE、流体及已计划的 block/fluid tick 需要专门的撤销适配器，通用接口拒绝承诺。`retire` 可在真正调用原生移除前一次性消费该权。
- 所有权协议只产生/撤销元数据。它不执行裸 setBlock、创造物品、管理员命令、返还材料或原生删除。安装仅允许专服；原生钩子不创建 workspace、AI 目录或后台服务，单人/LAN 不安装所有权服务。

## 主服务接线（已实现，等待统一验证）

1. 当前启用专服施工服务安装 `NativeOwnership.install(server, executionEpoch)`；同一服务关闭时 `remove(server, executionEpoch)`。
2. 在已通过原有材料/保护/批准门禁后、原生施工前 `begin(level, beforeStates)`。此 scope 覆盖实际原生放置及真实玩家的 owner 回调；所有其他审计仍独立生效。
3. 只有真实 `outcome.success` 且所有原生副作用均已审计时，调用 `scope.finish(new OwnershipToken.Owner(serverInstanceId, player, taskId, materialOperationId, stepId, planHash), afterStates)`。保存返回的完整 tokens；与同 chunk 原生保存一起等待既有 save receipt，不把内存 token 当磁盘保存证明。
4. begin/finish 失败只记独立的 `ownershipReason`，保留原生真实成功与材料消耗事实。finish 对部分发出的 token 自动撤权；不得因无撤销 token 把已放置改称未执行、退款或重放。
5. 规划和实际每次原生撤销前都核验对应 token，且必须保留当前所有保护、范围、材料、KleeSlabs/多格与原生移除门禁。历史 marker 不是管理员级逆向写入授权。

`ConstructionService` 已随 epoch 安装/移除服务，围绕真实放置及 owner 回调开启 witness，实际原生成功后生成 token；`RemovalService` 在预览及每次真正 REMOVE 前检查当前权。检查不放在会于本次写入后重入的保护 guard 内。主线程只访问 source plan 的同步内存因果/token 缓存和原生当前对象，不读 journal、不获取主会话 IO 锁。

`build_ownership` 是永久主会话中的独立内部事件，记录 source plan/task/actor、实际 action、完整新 tokens、退权位置与失败原因，不进入普通模型可读事件。写入运行在已有 `commitOwned` evidence-IO 回填中；先保留真实世界/材料结果，再独立记所有权。该事件写入失败只关掉此 source plan 的撤销能力，不改变真实 native success、已消耗物料、工具、饥饿或掉落，不产生退款。元数据回填确认前，缓存处于 pending，不能开始后续逆向步骤。

复合贡献使用 `BuildOwnershipChain` 按位置保留原生因果栈。例如同任务 `air→bottom`、`bottom→double` 的第二次写会撤掉旧 nonce；撤销 `double→bottom` 时，必须由本次成功原生移除的新 witness 创建新 token，并用它承接仍剩余的第一步来源，之后才可继续 `bottom→air`。旧 token 从不复活。原生一次整格移除可消费多个连续贡献；门等同次多格来源必须整组逆转。KleeSlabs 的中间 TOP/BOTTOM 按已核验来源贡献的 predecessor 固定，不能用最终状态相同代替来源链。

## 跨进程连续性仍未完成

同 chunk palette 与 nonce 可同时因崩溃回滚到旧保存。其他玩家改后尚未保存即崩溃时，重启后“方块和 marker 都相同”不能证明其未曾修改。因此本实现对不同 runtime 一律拒绝旧撤销权；它不会因为 marker coherent、时间接近或历史 journal 成功而升级权利。

已只读核对本地 Minecraft 1.21.1 原生源码：

- `DirectoryLock.create` 每次仅向 `session.lock` 写入同一个 UTF-8 雪人常量，`force(true)` 后取得 OS 文件锁。文件内容不是每次 boot 唯一、持久的 nonce；OS 锁在进程退出后不构成历史启动证明。
- `PrimaryLevelData` 的 `LastPlayed` 是正常保存时的 wall-clock 时间，不能涵盖保存前崩溃，更不能作为单调、唯一的 boot 身份。

上述字段无法证明 AI 禁用、单人或 LAN 期间没有未知启动。自建 dirty/clean seal 同样存在 dirty 持久化失败但世界继续运行的缺口。本文未修改原生锁格式、未添加全局启动封锁、未在单人创建 AI 数据，也未声称已找到完整可用的原生 boot 证明。若仍要求跨进程安全 undo，需要设计会话补足可靠的持久启动连续性机制；仅加保护性拒绝不等于完成该目标。

## 当前验证证据及边界

本子任务遵守主代理统一串行验证约束，未自行运行 Gradle、客户端、专服或供应商。新增 36 项纯协议/NBT/主会话持久化 JUnit，覆盖无因果转移不授权、重复写入与 ABA、精确 footprint、同 runtime 保留、逐格负向撤权、缺marker后旧快照拒权、跨 runtime 拒权、引用逃逸、索引耗尽、canonical 摘要、复合台阶新逆向证明续接、多格来源整组、pending/IO失败、陈旧permit与事件重开/隐私/模型读取边界；运行结果由主代理统一补记。

本地 `javap -private` 已只读确认当前 native ABI：`ChunkAccess.sections` 为 protected final 数组，`PalettedContainer.data` 为 private volatile Data，Data.storage 为 BitStorage，私有 `getAndSet(int,Object)` / `set(int,Object)`、网络 read、resize、`SimpleBitStorage(int,int,long[])`、`getRaw`、`ChunkSerializer.read` 的 RegionStorageInfo 参数、`getChunkNow` 与 `hasScheduledTick` 均存在。该结果不是 Mixin 实际加载证明。

仍需主代理的编译/JUnit，以及一次有明确必要性的真实原生验证，覆盖严格 Mixin 加载、普通放置后同chunk marker 落盘、取消/回调 ABA、直接 palette/storage 写、未知数组逃逸、同 JVM 卸载重载与进程重启拒权。不同游戏环境和其他 Mod 的读取可能触发明确的引用逃逸拒权；必须报告具体原因，不静默减弱保护或宣称全部撤销可用。

## 本次最小原生验证入口（待主代理运行）

`ConstructionScenario` 接受专用开发 JVM 属性 `fireflymc.validation.constructionOwnershipOnly=true`。它复用隔离专服、真实认证 mock 玩家、生产施工/物料/撤销服务，运行单独小组而不是重复完整施工矩阵：

- 两个真实付费原生放置产生两条永久私有因果回执，当前对象接受各自 opaque token。
- 实际调用原生玩家/世界保存，经 `ChunkMap.read` 读取真实磁盘 chunk tag，核验同一 canonical payload 中的 marker；不把直接调用 serializer 的内存返回值当保存收据。
- 对该真实 payload 调用原生 `ChunkSerializer.read`，核验同一存活 runtime 内的干净反序列化不会无故撤掉权利。此项是原生反序列化检查，不能扩写成真实卸载/重载服务器生命周期检查。
- 另一项独立真实付费放置经生产审批计划执行原生撤销，核对方块、真实工具磨损与实际掉落；不退款原始物料账本。
- 直接原生 palette 写石头再恢复木板，撤销被改格子的 nonce，同时保留同 chunk 未改格子；返回底层 bit array 的未知可变别名撤销整个 chunk 的权限，但不阻止原生玩法。
- 更换施工服务 epoch 后旧 token 被明确拒绝。这不是两个 JVM 的正常/异常重启验收。

本组只存在于 validation 源集，未加入发布 JAR 或玩家协议；默认属性为 false，不改变原完整场景。尚未运行的检查不记通过。

首次真实运行 `dedicated-construction-construction-db27dfc5-ef5c-448b-a96f-afe48b60472f.json` 前 13 项通过，含真实放置、磁盘 marker、原生反序列化、生产撤销、实际磨损/掉落及 palette ABA。最后引用逃逸断言失败：夹具只接受当前对象的 `OWNERSHIP_MUTABLE_REFERENCE_ESCAPED`，忽略生产 `require` 有意先检查负向 nonce 索引。已将夹具改为同时核验实际 storage 类/绑定、非空底层数组、chunk unsafe 及精确逃逸原因，并要求 token 被拒绝；负向索引优先时允许其明确的 `OWNERSHIP_NONCE_OR_GENERATION_CHANGED`。生产安全检查未放宽，完整复验结果仍待主代理记录。

## 需带回设计会话的精确决策

跨进程历史 undo 若必须可用，需要补上“每次任意世界启动，世界写入开始前，唯一 boot 边界已持久化”的可信契约，并规定边界写入失败时如何处理。两种明确选择是：

1. 允许一个纯世界完整性元数据钩子覆盖 AI 禁用、单人、LAN 与专服的所有启动；它不启动 AI、工作区、调度器或网络，但必须在世界可写前持久化唯一启动凭据，失败时至少使全部旧撤销权不可恢复。还需解决持久化失败后凭据未变却继续改世界、下一次又误认旧 clean 状态的缺口；仅在内存记失败不够。
2. 保持当前部署边界，在不能证明启动连续性的重启后只提供真实世界/材料历史对账和新计划，明确不恢复旧 undo 权。该选择是产品能力收窄，实施会话不能自行将它宣称为完整的跨进程安全撤销。

更改 `session.lock` 格式或拦截其写入失败也属于全世界启动契约，不能把它包装成普通 AI 服务内缓存。当前没有采用此类全局变更；无需该决策的同 runtime 原生撤销继续实施与验证。
