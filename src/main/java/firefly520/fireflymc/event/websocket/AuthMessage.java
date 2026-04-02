package firefly520.fireflymc.event.websocket;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

/**
 * WebSocket连接认证消息
 * 连接建立后立即发送，告知服务端身份
 */
public class AuthMessage {
    private static final Gson GSON = new Gson();

    @SerializedName("type")
    private final String type = "auth";

    @SerializedName("key")
    private final String key;

    @SerializedName("timestamp")
    private final long timestamp;

    public AuthMessage(String key) {
        this.key = key;
        this.timestamp = System.currentTimeMillis();
    }

    public String toJson() {
        return GSON.toJson(this);
    }
}
