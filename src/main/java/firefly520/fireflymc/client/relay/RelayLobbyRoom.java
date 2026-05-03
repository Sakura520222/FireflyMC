package firefly520.fireflymc.client.relay;

import com.google.gson.annotations.SerializedName;

/**
 * 公开大厅房间条目。
 */
public class RelayLobbyRoom {
    @SerializedName("roomId")
    private String roomId;

    @SerializedName("worldName")
    private String worldName;

    @SerializedName("hostPlayerName")
    private String hostPlayerName;

    @SerializedName("hostUuid")
    private String hostUuid;

    @SerializedName("currentPlayers")
    private int currentPlayers;

    @SerializedName("maxPlayers")
    private int maxPlayers;

    @SerializedName("modVersion")
    private String modVersion;

    @SerializedName("minecraftVersion")
    private String minecraftVersion;

    @SerializedName("status")
    private String status;

    @SerializedName("lastHeartbeat")
    private double lastHeartbeat;

    @SerializedName("p2pSupported")
    private boolean p2pSupported;

    @SerializedName("p2pTransport")
    private String p2pTransport;

    @SerializedName("p2pProtocolVersion")
    private int p2pProtocolVersion;

    public String roomId() {
        return roomId;
    }

    public String worldName() {
        return worldName == null || worldName.isBlank() ? "未命名单人世界" : worldName;
    }

    public String hostPlayerName() {
        return hostPlayerName == null || hostPlayerName.isBlank() ? "未知房主" : hostPlayerName;
    }

    public String hostUuid() {
        return hostUuid;
    }

    public int currentPlayers() {
        return currentPlayers;
    }

    public int maxPlayers() {
        return maxPlayers;
    }

    public String modVersion() {
        return modVersion;
    }

    public String minecraftVersion() {
        return minecraftVersion;
    }

    public String status() {
        return status == null ? "unknown" : status;
    }

    public double lastHeartbeat() {
        return lastHeartbeat;
    }

    public boolean p2pSupported() {
        return p2pSupported;
    }

    public String p2pTransport() {
        return p2pTransport == null ? "" : p2pTransport;
    }

    public int p2pProtocolVersion() {
        return p2pProtocolVersion;
    }
}
