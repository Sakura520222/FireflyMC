package firefly520.fireflymc.event.websocket;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import firefly520.fireflymc.ServerConfig;

/**
 * 福利包检查请求消息
 * 向服务端查询玩家是否已领取福利包
 */
public class StarterKitCheckRequest {
    private static final Gson GSON = new Gson();

    @SerializedName("type")
    private final String type = "starter_kit_check";

    @SerializedName("playerUuid")
    private final String playerUuid;

    @SerializedName("playerName")
    private final String playerName;

    @SerializedName("requestId")
    private final String requestId;

    @SerializedName("timestamp")
    private final long timestamp;

    @SerializedName("key")
    private final String key;

    /**
     * 创建福利包检查请求
     * @param playerUuid 玩家UUID
     * @param playerName 玩家名称
     * @param requestId 请求ID，用于匹配响应
     */
    public StarterKitCheckRequest(String playerUuid, String playerName, String requestId) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.requestId = requestId;
        this.timestamp = System.currentTimeMillis();
        this.key = ServerConfig.SERVER.wsAuthKey.get();
    }

    /**
     * 转换为JSON字符串
     */
    public String toJson() {
        return GSON.toJson(this);
    }

    public String getRequestId() {
        return requestId;
    }

    public String getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
