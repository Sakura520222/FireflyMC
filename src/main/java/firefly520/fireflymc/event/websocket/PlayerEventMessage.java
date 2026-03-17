package firefly520.fireflymc.event.websocket;

import com.google.gson.Gson;

/**
 * 玩家事件消息
 */
public class PlayerEventMessage {
    private static final Gson GSON = new Gson();

    private final String eventType;  // "join" 或 "leave"
    private final String playerName;
    private final long timestamp;

    public PlayerEventMessage(String eventType, String playerName) {
        this.eventType = eventType;
        this.playerName = playerName;
        this.timestamp = System.currentTimeMillis();
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
}
