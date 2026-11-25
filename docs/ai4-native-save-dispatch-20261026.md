# AI 4.0 已接受效果的原生保存派发

日期：2026-10-26。此文记录代码边界，编译及运行结果由主实施记录补记。

- `NativeSaveObserver.changed` 继续只登记已接受真实效果的保存义务，同时将精确身份/目的地送入 `NativeSaveDispatchQueue`。`tickExisting(server)` 只访问现有专服 observer，不创建模型、工作区或新服务。
- 每个真实 server Tick 最多一次原生调用、最多16次目标解析；同一原生物理存储异步提交在途时不重派。单个原生调用不可抢占，2ms是软预算，实际最大调用/帧耗及超预算次数写入只读 metrics。
- 保存重试有限：同一登记代次至多3次原生保存，至少20Tick退避；未知身份、替换对象、未加载目标和耗尽重试保持未确认。真实新效果可以登记新保存代次，但不能靠重复派发伪造回执。
- 玩家只调用其真实 `PlayerDataStorage.save`；统计/进度分别核对原生缓存同一对象及UUID目的地。不会全服 `saveAll`。
- chunk/BE在当前原生 holder/loaded chunk 中核同一对象后调用 `ChunkMap.save`；POI限定当前已加载的精确 section 和 column；SavedData限定真实已加载 cache 条目及原生 canonical 文件，调用其基类保存入口。只重新标记真实存储 dirty，不改游戏内容。
- 实体/原生删除只保存相应实体 chunk，使用 `PersistentEntitySectionManager.storeChunkSections`。尚未加载的原生实体存储先走其受限读取，再等正常处理完成；不以世界中不存在推断删除保存。一次实体chunk最多1024个原生待存实体。
- 女仆mode/farm/combat的opaque witness通过 `changedEntityWitness` 绑定物理实体供调度；原native tag匹配、epoch和canonical写入仍是证明权。别名不会产生保存成功，也不授权新游戏动作。
- `NativeSaveSubmissionScope` 只观察真实chunk/POI/entity写future及NeoForge SavedData IO Runnable的提交生命周期；它不调用保存barrier的ack。成功仍由已有NbtIo/原生writer/flush钩子确认，再由receipt IO写入原操作journal。
- IOUtilities锁定实现以 `saveDataTasks.thenRunAsync` 串联普通主线程提交；pump额外等待自己的同store提交完成后才可能重存。原生错误仍需真实后续回执，不能因提交future完成、标脏或文件存在而称保存成功。
- `hasPendingJournalWrites(operation)` 可供最终工具gate区分尚有真实回执写入和缺失coverage；`awaitJournalled`含义不变，完成空等待不能代替 `ledger.saved()`。

新增7项纯行为回归覆盖身份去重、公平调度、同store在途、有限重试、真实软预算超限计量、准备与保存分离、提交完成不等于持久化及拒绝的IO提交。子代理未运行Gradle、游戏或供应商；主代理统一编译并在取消fixture强制全量保存的精简原生场景中验证。

本地锁定原生源码证据保存在忽略目录 `build/ai4-validation/integrations/persistence/save-dispatch-*.java`，来自项目NeoForge 21.1.264 source artifact，不作为运行通过证明。
