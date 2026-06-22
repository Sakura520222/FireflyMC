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
        if (name != null && name.isEmpty()) {
            name = null;
        }
        // assistant 携带 tool_calls 的消息必须含 content 字段（部分 provider 如 GLM/月之暗面
        // 要求 assistant tool_calls 消息有 content，即使为空字符串，否则报「messages 参数非法」）；
        // 其它消息的空 content 仍归一为 null 由 Gson 省略
        if (toolCalls != null) {
            if (content == null) {
                content = "";
            }
        } else if (content != null && content.isEmpty()) {
            content = null;
        }
    }

    /** 向后兼容的 3 参构造（无 tool_calls / tool_call_id）。 */
    public ApiMessage(String role, String name, String content) {
        this(role, name, content, null, null);
    }
}
