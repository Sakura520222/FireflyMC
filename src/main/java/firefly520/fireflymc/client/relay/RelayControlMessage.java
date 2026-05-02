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
}
