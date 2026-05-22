package firefly520.fireflymc.client.relay;

import firefly520.fireflymc.Config;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 联机大厅房间到原版服务器列表的桥接层。
 * <p>
 * 负责将 RelayLobbyState 中的房间转换为 RelayServerEntry 列表，
 * 由 ServerSelectionListMixin 调用 addEntry 注入到原版列表中。
 */
@OnlyIn(Dist.CLIENT)
public final class RelayServerListBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(RelayServerListBridge.class);

    /** 上次注入时的 revision，用于避免重复注入 */
    private static long lastInjectedRevision = -1;

    /** 当前已注入的条目快照，用于去重 */
    private static final List<RelayServerEntry> injectedEntries = new ArrayList<>();

    private RelayServerListBridge() {
    }

    /**
     * 获取需要注入到原版服务器列表的联机大厅房间条目。
     * <p>
     * 由 ServerSelectionListMixin 在 refreshEntries() 后调用。
     * 只有当房间列表 revision 变化时才重新构建条目。
     *
     * @param screen 当前多人游戏界面
     * @return 需要注入的条目列表（不可变）
     */
    public static List<RelayServerEntry> getEntriesToInject(JoinMultiplayerScreen screen) {
        if (!Config.CLIENT.SINGLEPLAYER_RELAY_ENABLED.get()) {
            return Collections.emptyList();
        }

        long currentRevision = RelayLobbyState.revision();
        if (currentRevision == lastInjectedRevision && !injectedEntries.isEmpty()) {
            return Collections.unmodifiableList(injectedEntries);
        }

        // revision 变了，重新构建
        lastInjectedRevision = currentRevision;
        injectedEntries.clear();

        List<RelayLobbyRoom> rooms = RelayLobbyState.rooms();
        for (RelayLobbyRoom room : rooms) {
            injectedEntries.add(new RelayServerEntry(screen, room));
        }

        if (!rooms.isEmpty()) {
            LOGGER.debug("[FireflyMC] 已向原版服务器列表注入 {} 个联机大厅房间", rooms.size());
        }

        return Collections.unmodifiableList(injectedEntries);
    }

    /**
     * 请求刷新联机大厅列表。
     * <p>
     * 由 JoinMultiplayerScreenMixin 在屏幕初始化时调用。
     */
    public static void requestLobbyRefresh() {
        if (!Config.CLIENT.SINGLEPLAYER_RELAY_ENABLED.get()) {
            return;
        }
        RelayLobbyWebSocketClient.getInstance().requestLobbyList();
    }

    /**
     * 加入联机大厅房间。
     * <p>
     * 由 RelayServerEntry 双击或选中后点击"加入服务器"时调用。
     *
     * @param screen 当前多人游戏界面
     * @param room   要加入的房间
     */
    public static void joinRoom(JoinMultiplayerScreen screen, RelayLobbyRoom room) {
        RelayGuestJoiner.join(screen, room);
    }

    /**
     * 重置注入状态（屏幕关闭时调用）。
     */
    public static void reset() {
        lastInjectedRevision = -1;
        injectedEntries.clear();
    }
}
