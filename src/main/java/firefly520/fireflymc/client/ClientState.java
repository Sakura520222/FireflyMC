package firefly520.fireflymc.client;

/**
 * 客户端状态管理
 * 用于记录本次游戏会话中的状态
 */
public class ClientState {
    /**
     * 本次会话是否已显示过准则
     * 用于判断是否需要显示确认按钮（首次需要确认，后续自动消失）
     */
    public static boolean hasSeenRulesThisSession = false;
}
