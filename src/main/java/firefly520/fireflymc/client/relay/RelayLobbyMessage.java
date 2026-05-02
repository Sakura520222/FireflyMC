package firefly520.fireflymc.client.relay;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import firefly520.fireflymc.FireflyMCMod;

/**
 * 单人世界公开大厅控制消息。
 *
 * 阶段二先实现房间注册/关闭的 JSON 协议骨架；后续二进制流量中继会使用独立 stream 消息。
 */
public final class RelayLobbyMessage {
    private static final Gson GSON = new Gson();

    @SerializedName("type")
    private final String type;

    @SerializedName("roomId")
    private final String roomId;

    @SerializedName("worldName")
    private final String worldName;

    @SerializedName("hostPlayerName")
    private final String hostPlayerName;

    @SerializedName("hostUuid")
    private final String hostUuid;

    @SerializedName("lanPort")
    private final int lanPort;

    @SerializedName("maxPlayers")
    private final int maxPlayers;

    @SerializedName("modVersion")
    private final String modVersion;

    @SerializedName("minecraftVersion")
    private final String minecraftVersion;

    @SerializedName("timestamp")
    private final long timestamp;

    private RelayLobbyMessage(String type, String roomId, String worldName, String hostPlayerName,
                              String hostUuid, int lanPort, int maxPlayers) {
        this.type = type;
        this.roomId = roomId;
        this.worldName = worldName;
        this.hostPlayerName = hostPlayerName;
        this.hostUuid = hostUuid;
        this.lanPort = lanPort;
        this.maxPlayers = maxPlayers;
        this.modVersion = FireflyMCMod.VERSION;
        this.minecraftVersion = "1.21.1";
        this.timestamp = System.currentTimeMillis();
    }

    public static RelayLobbyMessage hostOpen(String roomId, String worldName, String hostPlayerName,
                                             String hostUuid, int lanPort, int maxPlayers) {
        return new RelayLobbyMessage("host_open", roomId, worldName, hostPlayerName, hostUuid, lanPort, maxPlayers);
    }

    public static RelayLobbyMessage hostClose(String roomId) {
        return new RelayLobbyMessage("host_close", roomId, null, null, null, -1, -1);
    }

    public static RelayLobbyMessage heartbeat(String roomId) {
        return new RelayLobbyMessage("heartbeat", roomId, null, null, null, -1, -1);
    }

    public static RelayLobbyMessage lobbyList() {
        return new RelayLobbyMessage("lobby_list", null, null, null, null, -1, -1);
    }

    public String type() {
        return type;
    }

    public String toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        if (roomId != null) {
            json.addProperty("roomId", roomId);
        }
        if (worldName != null) {
            json.addProperty("worldName", worldName);
        }
        if (hostPlayerName != null) {
            json.addProperty("hostPlayerName", hostPlayerName);
        }
        if (hostUuid != null) {
            json.addProperty("hostUuid", hostUuid);
        }
        if (lanPort >= 0) {
            json.addProperty("lanPort", lanPort);
        }
        if (maxPlayers >= 0) {
            json.addProperty("maxPlayers", maxPlayers);
        }
        json.addProperty("modVersion", modVersion);
        json.addProperty("minecraftVersion", minecraftVersion);
        json.addProperty("timestamp", timestamp);
        return GSON.toJson(json);
    }
}
