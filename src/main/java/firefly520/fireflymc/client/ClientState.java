package firefly520.fireflymc.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端状态管理
 * 用于记录本次游戏会话中的状态
 */
public class ClientState {

    /**
     * 服务端同步的称号数据：UUID字符串 → 称号（含§颜色代码）
     */
    public static final Map<String, String> titleMap = new ConcurrentHashMap<>();
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
     * 当前会话是否正在公开单人世界联机房间
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

    /**
     * 云端伪关机维护状态（由 QQ 群 /关机 指令触发，经云端下发）。
     * 为 true 时禁止进入多人服务器、禁用中继联机；单人/局域网/P2P 不受影响。
     */
    public static volatile boolean serverShutdown = false;

    /**
     * 当前 ConnectScreen.startConnecting 是否由联机大厅发起（P2P/中继本地代理）。
     * 用于让 ConnectScreenMixin 放行大厅发起的连接，仅拦截原版多人菜单发起的连接。
     * 由调用方在 startConnecting 前置 true、返回后立即复位。
     */
    public static boolean isLobbyInitiatedConnection = false;

    /**
     * 当前/最近一次由原版多人菜单发起连接的目标服务器地址（ServerData.ip，"host:port"）。
     * 由 ConnectScreenMixin 在放行 startConnecting 时记录，
     * 供客户端收到 AuthLockoutPayload 时拼接限流 key 使用。
     * 单人 / LAN / 大厅代理连接时不更新。
     */
    public static String currentServerIp = null;
}
