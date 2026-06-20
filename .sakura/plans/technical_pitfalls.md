# 技术陷阱与反模式

## IPv6 陷阱

### `isSiteLocalAddress()` 不覆盖 ULA（PR53）
Java 标准库的 `InetAddress.isSiteLocalAddress()` **不包含** IPv6 ULA 地址段 `fd00::/8`。
仅依赖此方法过滤地址会导致 ULA 地址被上报。

```java
// ❌ 不完整：仅过滤 link-local，遗漏 ULA
if (!addr.isSiteLocalAddress()) { report(addr); }

// ✅ 正确：同时排除 link-local 和 ULA
if (!addr.isLinkLocalAddress() && !isULA(addr)) { report(addr); }
```
ULA 判断：`addr instanceof Inet6Address && addr.getAddress()[0] == (byte)0xfd`

## 渲染架构迁移风险

### Mixin→独立渲染器失去视锥体剔除（PR54/PR55）
从 `EntityRenderer.render()` 注入迁移到 `RenderLevelStageEvent`：
- ✅ 优点：减少 Mixin 依赖，提升模组兼容性
- ❌ 代价：**失去引擎原生的视锥体剔除**，需手动实现距离裁剪

迁移检查清单：
1. 渲染阶段选择（`AFTER_SOLID_BLOCKS` vs `AFTER_TRANSLUCENT_BLOCKS`）
2. `PoseStack` push/pop 必须严格配对
3. `RenderSystem` 状态退出时完整恢复
4. 距离裁剪必须重新实现
5. 功能对等：隐形、旁观者、死亡、坐骑等边界状态

## WebSocket 生命周期膨胀

### 复杂度指数增长（PR54）
每次扩展 WebSocket 用途，生命周期管理复杂度非线性增长：
```
连接→使用→断开（基础）
连接→使用→保持→重连→最终断开（维护期扩展）
连接→使用→保持→聊天去重→断线重连→最终断开（持续扩展）
```
建议：审查时要求开发者提供状态机图或生命周期说明。

## 静态状态泛滥（21 次反思最高频问题）

### 根本原因（PR53）
项目采用"功能驱动"而非"基建驱动"开发风格：
- 新功能快速落地 ✓
- 线程安全、状态生命周期等横切关注点总是后补 ✗

### 应对策略
- `volatile`/`AtomicBoolean` 缺失无例外标记 `major`
- 推送工具化约束（grep pre-commit hook 检测 plain static 修饰）
- 审查时追问：谁写入？谁重置？closeHandler 链是否覆盖？

## 版本号硬编码的执行力问题

### 规则存在但 21 次反思后仍违反（PR55）
说明仅靠"审查发现→标记→修复"循环效率极低。

建议工具化约束：
```bash
# pre-commit hook：禁止字面量版本号
git diff --cached | grep -n '": "2\.' && echo "版本号硬编码!" && exit 1
# 所有版本号统一使用 FireflyMCMod.VERSION
```

## "打地鼠式"修复反模式（PR56_incr2）

### 表现
针对审查意见仅做最小化改动（改被指出的那一行），缺乏举一反三，且在单行修改中引入新错误（如未导入变量）。

### 应对
- 审查意见应明确指出"共有 N 处需要修复"
- 开发者回复中必须列出同文件/同模块排查结果
- 1 行修改也可能引入编译阻断风险（缺少上下文依赖）
