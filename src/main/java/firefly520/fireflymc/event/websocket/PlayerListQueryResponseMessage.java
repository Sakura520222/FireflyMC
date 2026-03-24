package firefly520.fireflymc.event.websocket;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 玩家列表查询响应消息
 * <p>
 * 模组响应服务端的玩家列表查询
 * <p>
 * JSON格式示例:
 * <pre>
 * {
 *   "type": "player_list_response",
 *   "requestId": "req-123",
 *   "success": true,
 *   "playerCount": 2,
 *   "maxPlayers": 20,
 *   "players": [
 *     {"name": "Steve", "uuid": "123e4567-e89b-12d3-a456-426614174000"},
 *     {"name": "Alex", "uuid": "987fcdeb-51a2-43f1-9d87-654321abcdef0"}
 *   ],
 *   "timestamp": 1234567890
 * }
 * </pre>
 */
public class PlayerListQueryResponseMessage {
    private static final Gson GSON = new Gson();

    @SerializedName("type")
    private final String type;

    @SerializedName("requestId")
    private final String requestId;

    @SerializedName("success")
    private final boolean success;

    @SerializedName("error")
    private final String error;

    @SerializedName("playerCount")
    private final int playerCount;

    @SerializedName("maxPlayers")
    private final int maxPlayers;

    @SerializedName("players")
    private final List<PlayerInfo> players;

    @SerializedName("timestamp")
    private final long timestamp;

    private PlayerListQueryResponseMessage(String requestId, boolean success, String error,
                                          int playerCount, int maxPlayers, List<PlayerInfo> players) {
        this.type = "player_list_response";
        this.requestId = requestId;
        this.success = success;
        this.error = error;
        this.playerCount = playerCount;
        this.maxPlayers = maxPlayers;
        this.players = players;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 创建成功的响应
     *
     * @param requestId     请求ID
     * @param serverPlayers 服务器玩家列表
     * @param playerCount   在线玩家数量
     * @param maxPlayers    最大玩家数量
     * @return 响应消息
     */
    public static PlayerListQueryResponseMessage success(String requestId, List<ServerPlayer> serverPlayers,
                                                        int playerCount, int maxPlayers) {
        List<PlayerInfo> playerInfos = serverPlayers.stream()
                .map(p -> new PlayerInfo(
                    p.getGameProfile().getName(),
                    p.getUUID().toString()
                ))
                .collect(Collectors.toList());
        return new PlayerListQueryResponseMessage(requestId, true, null, playerCount, maxPlayers, playerInfos);
    }

    /**
     * 创建服务器未就绪的响应
     *
     * @param requestId 请求ID
     * @return 错误响应消息
     */
    public static PlayerListQueryResponseMessage serverNotReady(String requestId) {
        return new PlayerListQueryResponseMessage(requestId, false, "Server not ready", 0, 0, List.of());
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    /**
     * 玩家信息
     */
    public static class PlayerInfo {
        @SerializedName("name")
        private final String name;

        @SerializedName("uuid")
        private final String uuid;

        public PlayerInfo(String name, String uuid) {
            this.name = name;
            this.uuid = uuid;
        }
    }
}
