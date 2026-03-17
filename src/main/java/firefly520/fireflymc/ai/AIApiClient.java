package firefly520.fireflymc.ai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AI API客户端 - 异步HTTP请求
 */
public class AIApiClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(AIApiClient.class);
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String SYSTEM_PROMPT = """
            你是小樱，由FireflyMC驱动的一个友好、可爱的AI助手，也是Minecraft 1.21.1的资深玩家。
            如果玩家询问游戏相关问题，请给出专业、准确的回答。
            平时可以和玩家闲聊，语气自然活泼。
            回复要简洁，不要长篇大论，不超过150字。
            """;

    /**
     * 调用AI API获取回复
     *
     * @param history 聊天历史
     * @param userMessage 用户消息
     * @param playerName 玩家名称（用于AI知道是对谁回复）
     * @return AI回复内容，失败返回null
     */
    public static AIResponse callAI(List<ChatMessage> history, String userMessage, String playerName) {
        try {
            // 构建请求体
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", AIConfig.MODEL);

            // 构建消息数组
            var messages = history.stream()
                    .map(ChatMessage::toApiMessage)
                    .collect(Collectors.toList());

            // 添加系统提示（在最前面），明确告诉AI当前要回复的是最后一条消息
            String systemPrompt = SYSTEM_PROMPT + "\n\n玩家 [" + playerName + "] 刚刚发了消息，messages数组中的最后一条就是他/她发送的，请回复最后一条消息。";
            messages.add(0, new ApiMessage("system", null, systemPrompt));

            // 转换为JSON
            var messagesJson = GSON.toJsonTree(messages).getAsJsonArray();
            requestBody.add("messages", messagesJson);

            // 创建HTTP请求
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(AIConfig.API_URL + "/chat/completions"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + AIConfig.API_KEY)
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(requestBody)))
                    .build();

            // 发送请求
            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString());

            // 处理响应
            return handleResponse(response);

        } catch (java.net.SocketTimeoutException e) {
            LOGGER.error("[FireflyMC] AI API请求超时: {}", e.getMessage());
            return new AIResponse(null, ErrorType.TIMEOUT);
        } catch (Exception e) {
            LOGGER.error("[FireflyMC] AI API调用失败: {}", e.getMessage(), e);
            return new AIResponse(null, ErrorType.NETWORK_ERROR);
        }
    }

    /**
     * 处理API响应
     */
    private static AIResponse handleResponse(HttpResponse<String> response) {
        int statusCode = response.statusCode();

        // 成功响应
        if (statusCode == 200) {
            try {
                JsonObject responseJson = GSON.fromJson(response.body(), JsonObject.class);
                String content = responseJson
                        .getAsJsonArray("choices")
                        .get(0).getAsJsonObject()
                        .getAsJsonObject("message")
                        .get("content").getAsString();

                // 清洗消息
                content = sanitizeMessage(content);

                return new AIResponse(content, ErrorType.NONE);
            } catch (Exception e) {
                LOGGER.error("[FireflyMC] 解析AI响应失败: {}", e.getMessage());
                return new AIResponse(null, ErrorType.PARSE_ERROR);
            }
        }

        // 401 未授权 - API密钥错误
        if (statusCode == 401) {
            LOGGER.error("[FireflyMC] AI API密钥无效！请检查配置文件中的apiKey");
            return new AIResponse(null, ErrorType.INVALID_KEY);
        }

        // 429 请求过多
        if (statusCode == 429) {
            LOGGER.warn("[FireflyMC] AI API请求频率过高");
            return new AIResponse(null, ErrorType.RATE_LIMIT);
        }

        // 内容安全过滤 (通常400返回，body包含"content_filter"等)
        if (statusCode == 400 && response.body() != null) {
            if (response.body().toLowerCase().contains("content_filter") ||
                response.body().toLowerCase().contains("safety")) {
                return new AIResponse(null, ErrorType.CONTENT_FILTER);
            }
        }

        // 其他错误
        LOGGER.error("[FireflyMC] AI API返回错误: {} {}", statusCode, response.body());
        return new AIResponse(null, ErrorType.API_ERROR);
    }

    /**
     * 清洗AI生成的消息
     * - 移除换行符
     * - 限制最大长度
     */
    private static String sanitizeMessage(String message) {
        if (message == null) {
            return "";
        }

        // 移除换行符，替换为空格
        message = message.replace("\n", " ").replace("\r", " ");

        // 移除多余空格
        message = message.replaceAll(" +", " ").trim();

        // 限制最大长度
        int maxLength = AIConfig.MAX_RESPONSE_LENGTH;
        if (message.length() > maxLength) {
            message = message.substring(0, maxLength - 3) + "...";
        }

        return message;
    }

    /**
     * 根据错误类型获取提示组件
     */
    public static Component getErrorComponent(ErrorType errorType) {
        return switch (errorType) {
            case TIMEOUT -> Component.literal("§c" + AIConfig.AI_NAME_PLAIN + " 响应超时，请稍后再试...");
            case INVALID_KEY -> Component.literal("§cAPI配置错误，请联系管理员");
            case RATE_LIMIT -> Component.literal("§e" + AIConfig.AI_NAME_PLAIN + " 需要休息一下，请稍后再试~");
            case CONTENT_FILTER -> Component.literal("§7[" + AIConfig.AI_NAME_PLAIN + "觉得这个话题不太合适...]");
            case NETWORK_ERROR, PARSE_ERROR, API_ERROR ->
                Component.literal("§c" + AIConfig.AI_NAME_PLAIN + " 暂时无法回复，请稍后再试...");
            case NONE -> Component.literal("");
        };
    }

    /**
     * AI响应结果
     */
    public record AIResponse(String content, ErrorType errorType) {
        public boolean isSuccess() {
            return errorType == ErrorType.NONE && content != null && !content.isBlank();
        }
    }

    /**
     * 错误类型
     */
    public enum ErrorType {
        NONE,           // 无错误
        TIMEOUT,        // 超时
        INVALID_KEY,    // API密钥无效
        RATE_LIMIT,     // 请求过多
        CONTENT_FILTER, // 内容被过滤
        NETWORK_ERROR,  // 网络错误
        PARSE_ERROR,    // 解析错误
        API_ERROR       // API错误
    }
}
