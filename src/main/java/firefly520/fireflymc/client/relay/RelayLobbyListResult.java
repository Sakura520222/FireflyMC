package firefly520.fireflymc.client.relay;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * 公开大厅列表响应。
 */
public class RelayLobbyListResult {
    private static final Gson GSON = new Gson();

    @SerializedName("type")
    private String type;

    @SerializedName("rooms")
    private List<RelayLobbyRoom> rooms;

    public static RelayLobbyListResult fromJson(String json) {
        try {
            return GSON.fromJson(json, RelayLobbyListResult.class);
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    public boolean isLobbyListResult() {
        return "lobby_list_result".equals(type) || "lobby_update".equals(type);
    }

    public List<RelayLobbyRoom> rooms() {
        return rooms == null ? List.of() : rooms;
    }
}
