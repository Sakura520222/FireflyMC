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

    /**
     * 当前单人世界会话是否已处理过联机提示
     */
    public static boolean hasHandledSingleplayerRelayPrompt = false;

    /**
     * 当前会话是否正在为单人世界联机进行 LAN 暴露准备
     */
    public static boolean isSingleplayerRelayHosting = false;

    /**
     * 当前单人世界联机使用的 LAN 端口，-1 表示尚未获取
     */
    public static int singleplayerRelayLanPort = -1;

    /**
     * Mod更新通知
     */
    public static boolean hasUpdateAvailable = false;
    public static String updateVersion = null;
    public static String updateUrl = null;

    /**
     * 更新通知区域（用于点击检测）
     */
    public static int updateNotificationX = 0;
    public static int updateNotificationY = 0;
    public static int updateNotificationWidth = 0;
    public static int updateNotificationHeight = 0;

    /**
     * 跳过按钮区域
     */
    public static int updateNotificationSkipX = 0;
    public static int updateNotificationSkipY = 0;
    public static int updateNotificationSkipSize = 0;
}
