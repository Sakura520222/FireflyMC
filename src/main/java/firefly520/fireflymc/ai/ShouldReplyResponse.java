package firefly520.fireflymc.ai;

/**
 * AI主动回复判断结果
 */
public record ShouldReplyResponse(boolean shouldReply, String reason) {
    public static final ShouldReplyResponse NO_REPLY = new ShouldReplyResponse(false, "");

    /**
     * 创建"应该回复"的结果
     */
    public static ShouldReplyResponse shouldReply(String reason) {
        return new ShouldReplyResponse(true, reason);
    }

    /**
     * 创建"不回复"的结果
     */
    public static ShouldReplyResponse noReply() {
        return NO_REPLY;
    }
}
