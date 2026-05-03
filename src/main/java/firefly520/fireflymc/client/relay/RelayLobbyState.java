package firefly520.fireflymc.client.relay;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端公开大厅状态缓存。
 */
public final class RelayLobbyState {
    private static final List<RelayLobbyRoom> ROOMS = new ArrayList<>();
    private static String statusMessage = "尚未刷新";
    private static boolean refreshing = false;

    private RelayLobbyState() {
    }

    public static synchronized void setRefreshing(boolean value) {
        refreshing = value;
    }

    public static synchronized boolean isRefreshing() {
        return refreshing;
    }

    public static synchronized void updateRooms(List<RelayLobbyRoom> rooms) {
        ROOMS.clear();
        ROOMS.addAll(rooms);
        refreshing = false;
        statusMessage = rooms.isEmpty() ? "暂无公开单人世界" : "已加载 " + rooms.size() + " 个公开单人世界";
    }

    public static synchronized void setStatusMessage(String message) {
        statusMessage = message;
        refreshing = false;
    }

    public static synchronized String statusMessage() {
        return statusMessage;
    }

    public static synchronized List<RelayLobbyRoom> rooms() {
        return List.copyOf(ROOMS);
    }
}
