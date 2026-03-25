package firefly520.fireflymc.ai;

import com.google.gson.JsonObject;

/**
 * AI返回的函数调用请求
 * <p>
 * 当AI决定调用某个函数时，会返回此结构
 *
 * @param id       调用ID（用于关联请求和响应）
 * @param name     函数名称
 * @param arguments 函数参数（JSON对象）
 */
public record FunctionCallRequest(
        String id,
        String name,
        JsonObject arguments
) {
}
