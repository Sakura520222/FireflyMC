package firefly520.fireflymc.ai.context;

import com.google.gson.*;
import java.time.Instant;
import java.util.Map;

/** Human-facing private diagnostics; local estimates are never presented as provider billing usage. */
public final class ContextViewText {
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("goals", "目标与原文摘录"), Map.entry("confirmedFacts", "已确认事实"), Map.entry("preferences", "偏好"),
            Map.entry("decisions", "决定"), Map.entry("taskRefs", "任务引用"), Map.entry("worldCoordinates", "坐标引用"),
            Map.entry("modVersions", "模组版本"), Map.entry("sourceRefs", "原文来源"), Map.entry("pendingQuestions", "待答问题"),
            Map.entry("failures", "失败与限制"), Map.entry("safetyConstraints", "权限与安全边界"), Map.entry("text", "内容"),
            Map.entry("source", "来源"), Map.entry("confidence", "可信程度"), Map.entry("taskId", "任务"), Map.entry("dimension", "维度"));
    private ContextViewText() {}
    public static String describe(ContextCompactor.View view, String status) {
        var text = new StringBuilder("上下文与摘要\n\n本地保守估算，不是供应商计费 Token 数。\n")
                .append("输入预算：").append(view.inputBudget()).append(" / 模型窗口 ").append(view.contextWindow())
                .append("\n预留：输出 ").append(view.outputReserve()).append("，工具结果增长 ").append(view.toolReserve())
                .append("，安全余量 ").append(view.safetyReserve())
                .append("\n当前历史估算：").append(view.estimatedHistoryTokens())
                .append("（历史投影和摘要，不含系统策略、工具定义及任务状态前缀）\n");
        var usage = view.lastAssembly();
        if (usage == null) text.append("上次请求完整估算：尚无本次服务中的模型输入记录。\n");
        else text.append("上次请求完整估算：").append(usage.estimatedInputTokens()).append(" / ").append(usage.inputBudget())
                .append("（消息 ").append(usage.messageTokens()).append(" + 工具定义与封套 ").append(usage.externalTokens()).append("）\n")
                .append("组装时间：").append(Instant.ofEpochMilli(usage.assembledAt())).append("；来源会话版本 ").append(usage.conversationRevision())
                .append("。这条历史估算不能当作下一次请求的精确占用。\n");
        text.append("当前会话版本：").append(view.conversationRevision()).append("；未闭合工具调用：").append(view.openToolCalls()).append("\n\n");
        text.append(switch (status) {
            case "COMPACTED" -> "手动压缩已完成；完整历史没有删除。";
            case "NO_CLOSED_HISTORY" -> "没有可压缩的旧完整轮次；近期原文和未完成事项保持。";
            case "WAITING_TOOL_TRANSACTION" -> "压缩请求已排队，等待完整工具轮次或稳定的历史边界；关闭工作台不会取消。";
            default -> "可点击「压缩」整理旧完整轮次；手动压缩不发送模型请求或消耗供应商额度。";
        });
        var summary = view.summary();
        if (summary == null) return text.append("\n\n目前没有压缩摘要；完整对话仍可直接回看。").toString();
        text.append("\n\n上次压缩：").append(Instant.ofEpochMilli(summary.timestamp())).append("\n摘要版本：").append(summary.summaryRevision())
                .append("；原文范围 seq:").append(summary.sourceFromSeq()).append("–").append(summary.sourceThroughSeq())
                .append("\n完整原文可通过更早历史、检索和查看全文恢复；摘要不授予任何操作权限。\n\n目前摘要\n");
        for (var field : summary.content().entrySet()) {
            if (field.getValue().isJsonArray() && field.getValue().getAsJsonArray().isEmpty()) continue;
            text.append('\n').append(LABELS.getOrDefault(field.getKey(), "摘要记录")).append('：').append('\n');
            if (field.getValue().isJsonArray()) for (var value : field.getValue().getAsJsonArray()) text.append("• ").append(readable(value)).append('\n');
            else text.append(readable(field.getValue())).append('\n');
        }
        return text.toString();
    }
    private static String readable(JsonElement value) {
        if (value == null || value.isJsonNull()) return "未记录";
        if (value.isJsonPrimitive()) return value.getAsString();
        if (value.isJsonArray()) return String.join("；", value.getAsJsonArray().asList().stream().map(ContextViewText::readable).toList());
        return String.join("；", value.getAsJsonObject().entrySet().stream()
                .map(entry -> LABELS.getOrDefault(entry.getKey(), entry.getKey()) + "：" + readable(entry.getValue())).toList());
    }
}
