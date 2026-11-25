package firefly520.fireflymc.ai;

import com.google.gson.*;
import firefly520.fireflymc.ai.context.*;
import firefly520.fireflymc.ai.network.AiProtocol;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContextViewTextTest {
    @Test void playerSeesChineseEstimateScopesAndSourceVersionsInsteadOfInternalJsonOrBillingClaims() {
        var content = new JsonObject(); var goals = new JsonArray(); goals.add("保留我的原始目标"); content.add("goals", goals);
        var refs = new JsonArray(); refs.add("seq:12"); content.add("sourceRefs", refs);
        var summary = new ContextCompactor.Summary(1, 2, 15, 1, 12, 1000, "extractive-fallback", "digest", content);
        var usage = new ContextCompactor.Usage(16, 0, 1100, 2100, 400, 2500, 6000, 2, "INTERNAL_LAST_SCOPE");
        var view = new ContextCompactor.View(8192, 1024, 1024, 144, 6000, .75, 4, 18, 0, 900,
                "INTERNAL_HISTORY_SCOPE", 0, summary, usage);
        String text = ContextViewText.describe(view, "COMPACTED");
        assertTrue(text.contains("本地保守估算，不是供应商计费 Token 数"));
        assertTrue(text.contains("不含系统策略、工具定义及任务状态前缀"));
        assertTrue(text.contains("上次请求完整估算：2500 / 6000")); assertTrue(text.contains("摘要版本：2"));
        assertTrue(text.contains("seq:1–12")); assertTrue(text.contains("保留我的原始目标"));
        assertFalse(text.contains("INTERNAL_")); assertFalse(text.contains("\"sourceRefs\""));
    }
    @Test void pendingCompressionExplainsTransactionBoundaryAndRequiresAdvertisedAppendOnlyAction() {
        var view = new ContextCompactor.View(8192, 1024, 1024, 144, 6000, .75, 4, 18, 0, 900,
                "INTERNAL_HISTORY_SCOPE", 2, null, null);
        String text = ContextViewText.describe(view, "WAITING_TOOL_TRANSACTION");
        assertTrue(text.contains("等待完整工具轮次")); assertTrue(text.contains("关闭工作台不会取消"));
        assertTrue(AiProtocol.requiresAdvertisement(AiProtocol.Action.CONTEXT_COMPACT));
        assertTrue(AiProtocol.Action.CONTEXT_COMPACT.ordinal() > AiProtocol.Action.TASK_MESSAGES.ordinal());
        assertTrue(AiProtocol.implementedActions().contains("CONTEXT_COMPACT"));
    }
}
