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

    @SerializedName("guestPlayerName")
    private final String guestPlayerName;

    @SerializedName("guestUuid")
    private final String guestUuid;

    @SerializedName("guestSessionId")
    private final String guestSessionId;

    @SerializedName("streamId")
    private final String streamId;

    @SerializedName("reason")
    private final String reason;

    @SerializedName("p2pSupported")
    private final boolean p2pSupported;

    @SerializedName("p2pTransport")
    private final String p2pTransport;

    @SerializedName("p2pProtocolVersion")
    private final int p2pProtocolVersion;

    @SerializedName("p2pSessionId")
    private final String p2pSessionId;

    @SerializedName("p2pToken")
    private final String p2pToken;

    /** P2P candidate（IPv6 直连自报用），仅 p2p_candidate 消息非空。 */
    @SerializedName("candidate")
    private final Candidate candidate;

    @SerializedName("modVersion")
    private final String modVersion;

    @SerializedName("minecraftVersion")
    private final String minecraftVersion;

    @SerializedName("timestamp")
    private final long timestamp;

    private RelayLobbyMessage(String type, String roomId, String worldName, String hostPlayerName,
                              String hostUuid, int lanPort, int maxPlayers) {
        this(type, roomId, worldName, hostPlayerName, hostUuid, lanPort, maxPlayers,
            null, null, null, null, null);
        }

        private RelayLobbyMessage(String type, String roomId, String worldName, String hostPlayerName,
                      String hostUuid, int lanPort, int maxPlayers, String guestPlayerName,
                      String guestUuid, String guestSessionId, String streamId, String reason) {
        this(type, roomId, worldName, hostPlayerName, hostUuid, lanPort, maxPlayers, guestPlayerName, guestUuid,
            guestSessionId, streamId, reason, false, null, 0, null, null);
        }

        private RelayLobbyMessage(String type, String roomId, String worldName, String hostPlayerName,
                  String hostUuid, int lanPort, int maxPlayers, String guestPlayerName,
                  String guestUuid, String guestSessionId, String streamId, String reason,
                  boolean p2pSupported, String p2pTransport, int p2pProtocolVersion,
                  String p2pSessionId, String p2pToken) {
        this.type = type;
        this.roomId = roomId;
        this.worldName = worldName;
        this.hostPlayerName = hostPlayerName;
        this.hostUuid = hostUuid;
        this.lanPort = lanPort;
        this.maxPlayers = maxPlayers;
        this.guestPlayerName = guestPlayerName;
        this.guestUuid = guestUuid;
        this.guestSessionId = guestSessionId;
        this.streamId = streamId;
        this.reason = reason;
        this.p2pSupported = p2pSupported;
        this.p2pTransport = p2pTransport;
        this.p2pProtocolVersion = p2pProtocolVersion;
        this.p2pSessionId = p2pSessionId;
        this.p2pToken = p2pToken;
        this.candidate = null;
        this.modVersion = FireflyMCMod.VERSION;
        this.minecraftVersion = "1.21.1";
        this.timestamp = System.currentTimeMillis();
    }

    private RelayLobbyMessage(String type, String roomId, String guestSessionId, String p2pSessionId,
                              String p2pToken, String reason, Candidate candidate) {
        this.type = type;
        this.roomId = roomId;
        this.worldName = null;
        this.hostPlayerName = null;
        this.hostUuid = null;
        this.lanPort = -1;
        this.maxPlayers = -1;
        this.guestPlayerName = null;
        this.guestUuid = null;
        this.guestSessionId = guestSessionId;
        this.streamId = null;
        this.reason = reason;
        this.p2pSupported = true;
        this.p2pTransport = "udp_reliable_v1";
        this.p2pProtocolVersion = 1;
        this.p2pSessionId = p2pSessionId;
        this.p2pToken = p2pToken;
        this.candidate = candidate;
        this.modVersion = FireflyMCMod.VERSION;
        this.minecraftVersion = "1.21.1";
        this.timestamp = System.currentTimeMillis();
    }

    public static RelayLobbyMessage hostOpen(String roomId, String worldName, String hostPlayerName,
                                             String hostUuid, int lanPort, int maxPlayers) {
        return new RelayLobbyMessage("host_open", roomId, worldName, hostPlayerName, hostUuid, lanPort, maxPlayers);
    }

    public static RelayLobbyMessage hostOpenP2P(String roomId, String worldName, String hostPlayerName,
                                                String hostUuid, int lanPort, int maxPlayers,
                                                String p2pSessionId) {
        return new RelayLobbyMessage("host_open", roomId, worldName, hostPlayerName, hostUuid, lanPort, maxPlayers,
                null, null, null, null, null, true, "udp_reliable_v1", 1, p2pSessionId, null);
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

    public static RelayLobbyMessage guestJoin(String roomId, String guestPlayerName, String guestUuid) {
        return new RelayLobbyMessage("guest_join", roomId, null, null, null, -1, -1,
                guestPlayerName, guestUuid, null, null, null);
    }

    public static RelayLobbyMessage guestLeave(String roomId, String guestSessionId, String reason) {
        return new RelayLobbyMessage("guest_leave", roomId, null, null, null, -1, -1,
                null, null, guestSessionId, null, reason);
    }

    public static RelayLobbyMessage streamOpen(String roomId, String guestSessionId, String streamId) {
        return new RelayLobbyMessage("stream_open", roomId, null, null, null, -1, -1,
                null, null, guestSessionId, streamId, null);
    }

    public static RelayLobbyMessage streamClose(String roomId, String streamId, String reason) {
        return new RelayLobbyMessage("stream_close", roomId, null, null, null, -1, -1,
                null, null, null, streamId, reason);
    }

    public static RelayLobbyMessage p2pOffer(String roomId, String guestSessionId, String p2pSessionId, String p2pToken) {
        return new RelayLobbyMessage("p2p_offer", roomId, guestSessionId, p2pSessionId, p2pToken, null, null);
    }

    public static RelayLobbyMessage p2pReady(String roomId, String guestSessionId, String p2pSessionId, String p2pToken) {
        return new RelayLobbyMessage("p2p_ready", roomId, guestSessionId, p2pSessionId, p2pToken, null, null);
    }

    public static RelayLobbyMessage p2pFailed(String roomId, String guestSessionId, String p2pSessionId, String p2pToken, String reason) {
        return new RelayLobbyMessage("p2p_failed", roomId, guestSessionId, p2pSessionId, p2pToken, reason, null);
    }

    public static RelayLobbyMessage relayFallback(String roomId, String guestSessionId, String p2pSessionId, String p2pToken, String reason) {
        return new RelayLobbyMessage("relay_fallback", roomId, guestSessionId, p2pSessionId, p2pToken, reason, null);
    }

    /**
     * 构造 p2p_candidate 消息：向对端上报本端 candidate。
     * IPv6 直连用，candidateAddress 可为 IPv6 字面量，经服务器原样转发给对端。
     */
    public static RelayLobbyMessage p2pCandidate(String roomId, String guestSessionId, String p2pSessionId,
                                                 String p2pToken, String candidateAddress, int candidatePort) {
        return new RelayLobbyMessage("p2p_candidate", roomId, guestSessionId, p2pSessionId, p2pToken, null,
                new Candidate(candidateAddress, candidatePort));
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
        if (guestPlayerName != null) {
            json.addProperty("guestPlayerName", guestPlayerName);
        }
        if (guestUuid != null) {
            json.addProperty("guestUuid", guestUuid);
        }
        if (guestSessionId != null) {
            json.addProperty("guestSessionId", guestSessionId);
        }
        if (streamId != null) {
            json.addProperty("streamId", streamId);
        }
        if (reason != null) {
            json.addProperty("reason", reason);
        }
        if (p2pSupported) {
            json.addProperty("p2pSupported", true);
        }
        if (p2pTransport != null) {
            json.addProperty("p2pTransport", p2pTransport);
        }
        if (p2pProtocolVersion > 0) {
            json.addProperty("p2pProtocolVersion", p2pProtocolVersion);
        }
        if (p2pSessionId != null) {
            json.addProperty("p2pSessionId", p2pSessionId);
        }
        if (p2pToken != null) {
            json.addProperty("p2pToken", p2pToken);
        }
        if (candidate != null) {
            JsonObject candidateJson = new JsonObject();
            candidateJson.addProperty("address", candidate.address());
            candidateJson.addProperty("port", candidate.port());
            json.add("candidate", candidateJson);
        }
        json.addProperty("modVersion", modVersion);
        json.addProperty("minecraftVersion", minecraftVersion);
        json.addProperty("timestamp", timestamp);
        return GSON.toJson(json);
    }

    /** P2P candidate 载荷，address 可为 IPv4 或 IPv6 字面量。 */
    public record Candidate(String address, int port) {
    }
}
