package firefly520.fireflymc.event.websocket;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import firefly520.fireflymc.ServerConfig;

/**
 * 福利包标记请求消息
 * 向服务端标记玩家已领取福利包
 */
public class StarterKitClaimRequest {
    private static final Gson GSON = new Gson();

    @SerializedName("type")
    private final String type = "starter_kit_claim";

    @SerializedName("playerUuid")
    private final String playerUuid;

    @SerializedName("playerName")
    private final String playerName;

    @SerializedName("timestamp")
    private final long timestamp;

    @SerializedName("key")
    private final String key;

    /**
     * 创建福利包标记请求
     * @param playerUuid 玩家UUID
     * @param playerName 玩家名称
     */
    public StarterKitClaimRequest(String playerUuid, String playerName) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.timestamp = System.currentTimeMillis();
        this.key = ServerConfig.SERVER.wsAuthKey.get();
    }

    /**
     * 转换为JSON字符串
     */
    public String toJson() {
        return GSON.toJson(this);
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
