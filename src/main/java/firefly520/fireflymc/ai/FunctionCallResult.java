package firefly520.fireflymc.ai;

/**
 * 函数执行结果
 * <p>
 * 表示AI函数工具执行后的结果，包含成功/失败状态和相关信息
 */
public class FunctionCallResult {
    private final boolean success;
    private final String message;
    private final ErrorType errorType;

    private FunctionCallResult(boolean success, String message, ErrorType errorType) {
        this.success = success;
        this.message = message;
        this.errorType = errorType;
    }

    /**
     * 创建成功结果
     *
     * @param message 成功消息（将返回给AI）
     * @return 成功的FunctionCallResult
     */
    public static FunctionCallResult success(String message) {
        return new FunctionCallResult(true, message, ErrorType.NONE);
    }

    /**
     * 创建失败结果
     *
     * @param errorType 错误类型
     * @param message   错误消息（将返回给AI）
     * @return 失败的FunctionCallResult
     */
    public static FunctionCallResult failure(ErrorType errorType, String message) {
        return new FunctionCallResult(false, message, errorType);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public ErrorType getErrorType() {
        return errorType;
    }

    /**
     * 错误类型
     */
    public enum ErrorType {
        NONE,               // 无错误
        PERMISSION_DENIED,  // 权限不足
        INVALID_ARGUMENT,   // 参数无效
        EXECUTION_FAILED    // 执行失败
    }
}
