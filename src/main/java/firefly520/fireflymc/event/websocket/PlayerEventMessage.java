package firefly520.fireflymc.event.websocket;

import com.google.gson.Gson;

/**
 * 玩家事件消息
 *
 * 支持的事件类型：
 * - join: 玩家加入
 * - leave: 玩家离开
 * - chat: 玩家聊天
 * - ai_chat: AI助手聊天
 * - death: 玩家死亡
 * - advancement: 玩家解锁成就
 */
public class PlayerEventMessage {
    private static final Gson GSON = new Gson();

    private final String eventType;
    private final String playerName;
    private final long timestamp;
    private final String message;        // 聊天消息内容
    private final String deathMessage;   // 死亡消息
    private final String advancement;    // 成就标题
    private final String senderType;     // 发送者类型: "player" 或 "ai"

    private PlayerEventMessage(String eventType, String playerName, String message,
                               String deathMessage, String advancement, String senderType) {
        this.eventType = eventType;
        this.playerName = playerName;
        this.timestamp = System.currentTimeMillis();
        this.message = message;
        this.deathMessage = deathMessage;
        this.advancement = advancement;
        this.senderType = senderType;
    }

    /**
     * 创建玩家加入/离开事件
     */
    public static PlayerEventMessage join(String playerName) {
        return new PlayerEventMessage("join", playerName, null, null, null, null);
    }

    public static PlayerEventMessage leave(String playerName) {
        return new PlayerEventMessage("leave", playerName, null, null, null, null);
    }

    /**
     * 创建聊天消息事件
     */
    public static PlayerEventMessage chat(String playerName, String message, String senderType) {
        return new PlayerEventMessage("chat", playerName, message, null, null, senderType);
    }

    public static PlayerEventMessage playerChat(String playerName, String message) {
        return chat(playerName, message, "player");
    }

    public static PlayerEventMessage aiChat(String message) {
        return chat("AI", message, "ai");
    }

    /**
     * 创建玩家死亡事件
     */
    public static PlayerEventMessage death(String playerName, String deathMessage) {
        return new PlayerEventMessage("death", playerName, null, deathMessage, null, null);
    }

    /**
     * 创建成就解锁事件
     */
    public static PlayerEventMessage advancement(String playerName, String advancementTitle) {
        return new PlayerEventMessage("advancement", playerName, null, null, advancementTitle, null);
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    // Getters
    public String getEventType() {
        return eventType;
    }

    public String getPlayerName() {
        return playerName;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getMessage() {
        return message;
    }

    public String getDeathMessage() {
        return deathMessage;
    }

    public String getAdvancement() {
        return advancement;
    }

    public String getSenderType() {
        return senderType;
    }
}
