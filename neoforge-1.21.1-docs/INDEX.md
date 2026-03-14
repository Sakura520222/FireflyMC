# NeoForge 1.21.1 文档索引

**抓取时间**: 2026-03-13 22:26 - 22:28 (GMT+8)
**源站**: https://docs.neoforged.net/docs/1.21.1/
**输出目录**: `/home/firefly/.openclaw/workspace/neoforge-1.21.1-docs/`
**总页面数**: 51

---

## 目录结构

```
neoforge-1.21.1-docs/
├── 📄 gettingstarted.md (入门指南)
├── 📂 advanced/ (高级主题)
│   ├── accesstransformers.md (访问转换器)
│   └── extensibleenums.md (可扩展枚举)
├── 📂 blockentities/ (方块实体)
│   └── ber.md (方块实体渲染器)
├── 📂 blocks/ (方块)
│   └── states.md (方块状态)
├── 📂 concepts/ (核心概念)
│   ├── events.md (事件)
│   ├── registries.md (注册表)
│   └── sides.md (服务端/客户端)
├── 📂 datastorage/ (数据存储)
│   ├── attachments.md (附加数据)
│   ├── codecs.md (编解码器)
│   ├── nbt.md (NBT 数据)
│   └── saveddata.md (已保存数据)
├── 📂 gui/ (图形界面)
│   ├── menus.md (菜单)
│   └── screens.md (屏幕)
├── 📂 inventories/ (物品栏)
│   ├── capabilities.md (能力)
│   └── container.md (容器)
├── 📂 items/ (物品)
│   ├── datacomponents.md (数据组件)
│   ├── interactionpipeline.md (交互管道)
│   ├── mobeffects.md (生物效果)
│   └── tools.md (工具)
├── 📂 misc/ (杂项)
│   ├── config.md (配置)
│   ├── debugprofiler.md (调试分析器)
│   ├── gametest.md (游戏测试)
│   ├── keymappings.md (按键映射)
│   ├── resourcelocation.md (资源位置)
│   └── updatechecker.md (更新检查)
├── 📂 networking/ (网络)
│   ├── configuration-tasks.md (配置任务)
│   ├── entities.md (实体同步)
│   ├── payload.md (网络数据包)
│   └── streamcodecs.md (流编解码器)
├── 📂 resources/ (资源)
│   ├── client/ (客户端资源)
│   │   ├── i18n.md (国际化)
│   │   ├── models/
│   │   │   ├── bakedmodel.md (烘焙模型)
│   │   │   ├── datagen.md (数据生成)
│   │   │   └── modelloaders.md (模型加载器)
│   │   ├── particles.md (粒子)
│   │   ├── sounds.md (声音)
│   │   └── textures.md (纹理)
│   └── server/ (服务端资源)
│       ├── advancements.md (进度)
│       ├── conditions.md (条件)
│       ├── damagetypes.md (伤害类型)
│       ├── loottables/
│       │   ├── custom.md (自定义战利品表)
│       │   ├── glm.md (全局战利品修饰器)
│       │   ├── lootconditions.md (战利品条件)
│       │   └── lootfunctions.md (战利品函数)
│       ├── recipes/
│       │   ├── builtin.md (内置配方)
│       │   └── ingredients.md (配方材料)
│       └── tags.md (标签)
└── 📂 worldgen/ (世界生成)
    └── biomemodifier.md (生物群系修饰器)
```

---

## 按类别浏览

### 🚀 入门指南
- [入门指南](gettingstarted.md) - 开始使用 NeoForge
- [版本管理](gettingstarted/versioning.md) - 理解版本号和依赖
- [模组文件结构](gettingstarted/modfiles.md) - 模组的组织方式
- [项目结构](gettingstarted/structuring.md) - 如何组织代码

### 💡 核心概念
- [注册表](concepts/registries.md) - 游戏对象的注册系统
- [事件](concepts/events.md) - 事件驱动编程
- [服务端/客户端](concepts/sides.md) - 区分服务端和客户端

### 🔧 高级主题
- [访问转换器](advanced/accesstransformers.md) - 修改类和成员的访问权限
- [可扩展枚举](advanced/extensibleenums.md) - 扩展枚举类型

### 📦 数据存储
- [NBT 数据](datastorage/nbt.md) - 二进制标签格式
- [附加数据](datastorage/attachments.md) - 对象附加数据系统
- [编解码器](datastorage/codecs.md) - 数据序列化
- [已保存数据](datastorage/saveddata.md) - 持久化世界数据

### 🎮 图形界面
- [菜单](gui/menus.md) - 创建容器菜单
- [屏幕](gui/screens.md) - 创建 GUI 屏幕

### 🎒 物品栏与容器
- [容器](inventories/container.md) - 物品容器系统
- [能力](inventories/capabilities.md) - 能力系统

### ⚔️ 物品与工具
- [工具](items/tools.md) - 创建自定义工具
- [数据组件](items/datacomponents.md) - 物品数据系统
- [交互管道](items/interactionpipeline.md) - 物品交互
- [生物效果](items/mobeffects.md) - 状态效果

### 🌐 网络
- [网络数据包](networking/payload.md) - 自定义网络数据包
- [流编解码器](networking/streamcodecs.md) - 网络数据编码
- [实体同步](networking/entities.md) - 实体数据同步
- [配置任务](networking/configuration-tasks.md) - 网络配置阶段

### 🎨 资源

#### 客户端资源
- [国际化](resources/client/i18n.md) - 多语言支持
- [纹理](resources/client/textures.md) - 纹理资源
- [声音](resources/client/sounds.md) - 声音事件
- [粒子](resources/client/particles.md) - 粒子效果
- [模型](resources/client/models/)
  - [烘焙模型](resources/client/models/bakedmodel.md)
  - [模型加载器](resources/client/models/modelloaders.md)
  - [数据生成](resources/client/models/datagen.md)

#### 服务端资源
- [进度](resources/server/advancements.md) - 成就系统
- [标签](resources/server/tags.md) - 数据标签
- [条件](resources/server/conditions.md) - 配置条件
- [伤害类型](resources/server/damagetypes.md) - 伤害系统
- [配方](resources/server/recipes/)
  - [配方材料](resources/server/recipes/ingredients.md)
  - [内置配方](resources/server/recipes/builtin.md)
- [战利品表](resources/server/loottables/)
  - [全局战利品修饰器](resources/server/loottables/glm.md)
  - [自定义战利品表](resources/server/loottables/custom.md)
  - [战利品条件](resources/server/loottables/lootconditions.md)
  - [战利品函数](resources/server/loottables/lootfunctions.md)

### 🧩 杂项
- [配置](misc/config.md) - 模组配置
- [按键映射](misc/keymappings.md) - 键位绑定
- [调试分析器](misc/debugprofiler.md) - 性能分析
- [游戏测试](misc/gametest.md) - 自动化测试
- [资源位置](misc/resourcelocation.md) - 资源标识符
- [更新检查](misc/updatechecker.md) - 模组更新

### 🏗️ 方块与实体
- [方块状态](blocks/states.md) - 方块属性系统
- [方块实体渲染器](blockentities/ber.md) - 动态方块渲染

### 🌍 世界生成
- [生物群系修饰器](worldgen/biomemodifier.md) - 修改生物群系

---

## 完整页面列表

1. [gettingstarted](gettingstarted.md) - 入门指南
2. [gettingstarted/versioning](gettingstarted/versioning.md) - 版本管理
3. [gettingstarted/modfiles](gettingstarted/modfiles.md) - 模组文件
4. [gettingstarted/structuring](gettingstarted/structuring.md) - 项目结构
5. [concepts/registries](concepts/registries.md) - 注册表
6. [concepts/events](concepts/events.md) - 事件
7. [concepts/sides](concepts/sides.md) - 服务端/客户端
8. [advanced/accesstransformers](advanced/accesstransformers.md) - 访问转换器
9. [advanced/extensibleenums](advanced/extensibleenums.md) - 可扩展枚举
10. [datastorage/nbt](datastorage/nbt.md) - NBT
11. [datastorage/attachments](datastorage/attachments.md) - 附加数据
12. [datastorage/codecs](datastorage/codecs.md) - 编解码器
13. [datastorage/saveddata](datastorage/saveddata.md) - 已保存数据
14. [gui/menus](gui/menus.md) - 菜单
15. [gui/screens](gui/screens.md) - 屏幕
16. [inventories/container](inventories/container.md) - 容器
17. [inventories/capabilities](inventories/capabilities.md) - 能力
18. [items/tools](items/tools.md) - 工具
19. [items/datacomponents](items/datacomponents.md) - 数据组件
20. [items/interactionpipeline](items/interactionpipeline.md) - 交互管道
21. [items/mobeffects](items/mobeffects.md) - 生物效果
22. [networking/payload](networking/payload.md) - 网络数据包
23. [networking/streamcodecs](networking/streamcodecs.md) - 流编解码器
24. [networking/entities](networking/entities.md) - 实体同步
25. [networking/configuration-tasks](networking/configuration-tasks.md) - 配置任务
26. [resources/client/i18n](resources/client/i18n.md) - 国际化
27. [resources/client/textures](resources/client/textures.md) - 纹理
28. [resources/client/sounds](resources/client/sounds.md) - 声音
29. [resources/client/particles](resources/client/particles.md) - 粒子
30. [resources/client/models/bakedmodel](resources/client/models/bakedmodel.md) - 烘焙模型
31. [resources/client/models/modelloaders](resources/client/models/modelloaders.md) - 模型加载器
32. [resources/client/models/datagen](resources/client/models/datagen.md) - 数据生成
33. [resources/server/advancements](resources/server/advancements.md) - 进度
34. [resources/server/tags](resources/server/tags.md) - 标签
35. [resources/server/conditions](resources/server/conditions.md) - 条件
36. [resources/server/damagetypes](resources/server/damagetypes.md) - 伤害类型
37. [resources/server/recipes/ingredients](resources/server/recipes/ingredients.md) - 配方材料
38. [resources/server/recipes/builtin](resources/server/recipes/builtin.md) - 内置配方
39. [resources/server/loottables/glm](resources/server/loottables/glm.md) - 战利品修饰器
40. [resources/server/loottables/custom](resources/server/loottables/custom.md) - 自定义战利品表
41. [resources/server/loottables/lootconditions](resources/server/loottables/lootconditions.md) - 战利品条件
42. [resources/server/loottables/lootfunctions](resources/server/loottables/lootfunctions.md) - 战利品函数
43. [misc/config](misc/config.md) - 配置
44. [misc/keymappings](misc/keymappings.md) - 按键映射
45. [misc/debugprofiler](misc/debugprofiler.md) - 调试分析器
46. [misc/gametest](misc/gametest.md) - 游戏测试
47. [misc/resourcelocation](misc/resourcelocation.md) - 资源位置
48. [misc/updatechecker](misc/updatechecker.md) - 更新检查
49. [blocks/states](blocks/states.md) - 方块状态
50. [blockentities/ber](blockentities/ber.md) - 方块实体渲染器
51. [worldgen/biomemodifier](worldgen/biomemodifier.md) - 生物群系修饰器

---

## 抓取统计

- ✅ **成功抓取**: 51 个页面
- ❌ **失败**: 0 个页面
- 📊 **成功率**: 100%
- ⏱️ **总耗时**: ~2 分钟
- 📝 **总文件数**: 53 个（51 个文档 + 1 个索引 + 1 个日志）

## 文件说明

- 每个 Markdown 文件头部包含元数据（URL、抓取时间、源站信息）
- 文件按原始文档结构组织到对应目录
- 所有链接保留为原始 URL 格式
- 代码块和列表已转换为 Markdown 格式

## 使用建议

1. 从 [gettingstarted.md](gettingstarted.md) 开始阅读
2. 参考 [SCRAPER_LOG.md](SCRAPER_LOG.md) 查看详细的抓取记录
3. 需要最新内容时访问官方文档: https://docs.neoforged.net/docs/1.21.1/

---

**抓取脚本**: neoforge_scraper.py
**抓取日期**: 2026-03-13
**文档版本**: NeoForge 1.21.1
