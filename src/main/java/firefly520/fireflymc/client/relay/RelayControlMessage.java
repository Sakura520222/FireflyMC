package firefly520.fireflymc.client.relay;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;

/**
 * Relay 通用控制响应。
 */
public class RelayControlMessage {
    private static final Gson GSON = new Gson();

    @SerializedName("type")
    private String type;

    @SerializedName("roomId")
    private String roomId;

    @SerializedName("guestSessionId")
    private String guestSessionId;

    @SerializedName("streamId")
    private String streamId;

    @SerializedName("code")
    private String code;

    @SerializedName("message")
    private String message;

    @SerializedName("p2pSupported")
    private boolean p2pSupported;

    @SerializedName("p2pTransport")
    private String p2pTransport;

    @SerializedName("p2pProtocolVersion")
    private int p2pProtocolVersion;

    @SerializedName("p2pSessionId")
    private String p2pSessionId;

    @SerializedName("p2pToken")
    private String p2pToken;

    @SerializedName("p2pUdpHost")
    private String p2pUdpHost;

    @SerializedName("p2pUdpPort")
    private int p2pUdpPort;

    @SerializedName("p2pConnectTimeoutSeconds")
    private int p2pConnectTimeoutSeconds;

    @SerializedName("candidate")
    private P2PCandidate candidate;

    @SerializedName("role")
    private String role;

    @SerializedName("senderRole")
    private String senderRole;

    public static class P2PCandidate {
        @SerializedName("address")
        private String address;

        @SerializedName("port")
        private int port;

        public String address() {
            return address;
        }

        public int port() {
            return port;
        }
    }

    public static RelayControlMessage fromJson(String json) {
        try {
            return GSON.fromJson(json, RelayControlMessage.class);
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    public String type() {
        return type;
    }

    public String roomId() {
        return roomId;
    }

    public String guestSessionId() {
        return guestSessionId;
    }

    public String streamId() {
        return streamId;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
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

    public String p2pSessionId() {
        return p2pSessionId;
    }

    public String p2pToken() {
        return p2pToken;
    }

    public String p2pUdpHost() {
        return p2pUdpHost;
    }

    public int p2pUdpPort() {
        return p2pUdpPort;
    }

    public int p2pConnectTimeoutSeconds() {
        return p2pConnectTimeoutSeconds;
    }

    public P2PCandidate candidate() {
        return candidate;
    }

    public String role() {
        return role;
    }

    public String senderRole() {
        return senderRole;
    }
}
