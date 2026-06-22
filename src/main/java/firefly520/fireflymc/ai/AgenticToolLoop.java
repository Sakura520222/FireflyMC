package firefly520.fireflymc.ai;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 多轮 Agentic 工具调用循环。
 * <p>
 * 承载 AI 对话中的多轮工具调用流程：AI 返回 tool_calls → 主线程串行执行 → 结果以标准
 * {@code role:tool} 消息回传 → AI 基于结果再次决策，直到 AI 给出文字回复，或达到
 * 轮次（{@link AIConfig#getMaxToolRounds()}）/累计调用数（{@link AIConfig#getMaxToolCalls()}）上限。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>玩家与控制台触发统一为 {@link ToolContext}（控制台 player 为 null）</li>
 *   <li>工具执行串行跑在主线程（MC 实体/玩家操作非线程安全，且工具间可能存在依赖）</li>
 *   <li>AI 网络请求异步执行，响应处理与工具执行回到主线程</li>
 *   <li>权限与启用开关检查集中在 {@link #executeOne}，工具实现不再各自校验</li>
 * </ul>
 */
public class AgenticToolLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgenticToolLoop.class);

    private AgenticToolLoop() {
    }

    /**
     * 启动一轮多轮 AI 对话循环。
     * <p>
     * 本次输入（prompt）对应的 PLAYER/SYSTEM 消息应由调用方按需加入历史；
     * 本方法不再重复添加，以兼容「唤醒词」「/ai 命令」「主动回复」等不同触发场景。
     *
     * @param ctx       执行上下文（console 触发时 player 为 null）
     * @param history   聊天历史管理器
     * @param prompt    本次输入（用于日志与 system prompt 的玩家名提示）
     * @param broadcast 收到 AI 文本回复时的广播回调（在主线程调用）
     */
    public static void run(ToolContext ctx, ChatHistoryManager history,
                           String prompt, Consumer<String> broadcast) {
        callNextRound(ctx, history, prompt, broadcast, 0, 0);
    }

    private static void callNextRound(ToolContext ctx, ChatHistoryManager history,
                                      String prompt, Consumer<String> broadcast,
                                      int round, int executed) {
        int maxRounds = AIConfig.getMaxToolRounds();
        if (round >= maxRounds) {
            LOGGER.warn("[FireflyMC] AI 工具调用达到最大轮次 {}，终止循环", maxRounds);
            return;
        }

        List<AIFunctionTool> tools = AIConfig.getFunctionsEnabled()
                ? FunctionToolRegistry.getEnabledTools()
                : null;

        String playerName = ctx.isConsole() ? "Server Console" : ctx.player().getName().getString();

        CompletableFuture.supplyAsync(() -> {
            // 异步线程：发送网络请求
            var snapshot = List.copyOf(history.getHistory());
            return AIApiClient.callAIWithFunctions(snapshot, prompt, playerName, tools);
        }).thenAccept(response ->
                // 回到主线程：处理响应、执行工具
                ctx.server().execute(() -> handleResponse(ctx, history, prompt, broadcast, response, round, executed))
        );
    }

    private static void handleResponse(ToolContext ctx, ChatHistoryManager history,
                                       String prompt, Consumer<String> broadcast,
                                       AIApiClient.AIWithToolsResponse response,
                                       int round, int executed) {
        if (!response.isSuccess()) {
            if (ctx.isConsole()) {
                LOGGER.error("[FireflyMC] 终端AI请求失败: {}", response.errorType());
            } else {
                ctx.player().sendSystemMessage(AIApiClient.getErrorComponent(response.errorType()));
            }
            return;
        }

        if (response.hasToolCalls()) {
            int maxCalls = AIConfig.getMaxToolCalls();
            List<FunctionCallRequest> calls = response.toolCalls();
            // 累计超限时截断本轮调用
            if (executed + calls.size() > maxCalls) {
                int remaining = Math.max(0, maxCalls - executed);
                calls = calls.subList(0, remaining);
            }

            // OpenAI 规范：assistant 发起的 tool_calls 必须入历史，tool 结果紧随其后
            history.addMessage(ChatMessage.toolCall(
                    AIApiClient.buildToolCallsJson(response.toolCalls()),
                    response.content()));

            // 串行执行全部 tool_call（主线程）
            int newExecuted = executed;
            for (FunctionCallRequest call : calls) {
                FunctionCallResult result = executeOne(ctx, call);
                history.addMessage(ChatMessage.toolResult(call.id(), formatResult(result)));
                newExecuted++;
            }

            if (newExecuted >= maxCalls) {
                // 达到累计上限：提示 AI 用文字总结，不再继续调用工具
                history.addMessage(ChatMessage.of("Server",
                        "已达工具调用上限，请根据已获取的结果直接用文字回复玩家",
                        MessageType.SYSTEM));
            }
            callNextRound(ctx, history, prompt, broadcast, round + 1, newExecuted);
            return;
        }

        // 文本回复：广播并记录到历史
        String content = response.content();
        if (content != null && !content.isEmpty()) {
            broadcast.accept(content);
            history.addMessage(ChatMessage.of(
                    AIConfig.getAiNamePlain(), content, MessageType.ASSISTANT));
        }
    }

    /**
     * 执行单个工具调用：存在性 / 启用 / 权限检查 + 执行 + 异常兜底。
     * 失败原因会以 {@link FunctionCallResult} 形式回传给 AI，便于 AI 调整策略。
     */
    private static FunctionCallResult executeOne(ToolContext ctx, FunctionCallRequest call) {
        var toolOpt = FunctionToolRegistry.getTool(call.name());
        if (toolOpt.isEmpty()) {
            return FunctionCallResult.failure(FunctionCallResult.ErrorType.EXECUTION_FAILED,
                    "未知工具: " + call.name());
        }
        AIFunctionTool tool = toolOpt.get();

        if (!FunctionToolRegistry.isEnabled(tool)) {
            return FunctionCallResult.failure(FunctionCallResult.ErrorType.PERMISSION_DENIED,
                    "工具 " + call.name() + " 已被管理员禁用");
        }
        if (!ctx.hasPermission(tool.getRequiredPermissionLevel())) {
            return FunctionCallResult.failure(FunctionCallResult.ErrorType.PERMISSION_DENIED,
                    "权限不足：需要 " + tool.getRequiredPermissionLevel() + " 级OP权限");
        }

        try {
            JsonObject args = call.arguments();
            if (args == null) {
                args = new JsonObject();
            }
            return tool.execute(ctx, args);
        } catch (Exception e) {
            LOGGER.error("[FireflyMC] 工具 {} 执行异常: {}", call.name(), e.getMessage(), e);
            return FunctionCallResult.failure(FunctionCallResult.ErrorType.EXECUTION_FAILED,
                    "工具执行异常: " + e.getMessage());
        }
    }

    private static String formatResult(FunctionCallResult result) {
        String prefix = result.isSuccess() ? "工具调用成功：" : "工具调用失败：";
        return prefix + result.getMessage();
    }
}
