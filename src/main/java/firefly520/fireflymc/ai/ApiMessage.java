package firefly520.fireflymc.ai;

import com.google.gson.JsonArray;
import com.google.gson.annotations.SerializedName;

/**
 * OpenAI Chat Completions 消息体，字段名对齐官方规范。
 * <p>
 * 序列化时 Gson 默认省略 {@code null} 字段，因此：
 * <ul>
 *   <li>普通文本消息：只输出 {@code role/content}（及可选 {@code name}）</li>
 *   <li>assistant 发起工具调用：输出 {@code role/tool_calls}，{@code content} 省略</li>
 *   <li>工具结果：输出 {@code role/content/tool_call_id}</li>
 * </ul>
 */
public record ApiMessage(
        String role,
        String name,
        String content,
        @SerializedName("tool_calls") JsonArray toolCalls,
        @SerializedName("tool_call_id") String toolCallId
) {
    public ApiMessage {
        if (role == null) {
            role = "user";
        }
        // 空 content / 空 name 一律归一为 null，便于 Gson 省略，避免本地端点对空字符串报错
        if (content == null || content.isEmpty()) {
            content = null;
        }
        if (name != null && name.isEmpty()) {
            name = null;
        }
    }

    /** 向后兼容的 3 参构造（无 tool_calls / tool_call_id）。 */
    public ApiMessage(String role, String name, String content) {
        this(role, name, content, null, null);
    }
}
