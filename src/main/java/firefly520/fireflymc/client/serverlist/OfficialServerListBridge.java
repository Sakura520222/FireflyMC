package firefly520.fireflymc.client.serverlist;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 官方服务器列表与原版多人游戏界面的桥接层，由两个 Mixin 调用：
 * <ul>
 *   <li>{@link #onScreenInit}：界面初始化后读取缓存、向后台拉取列表；</li>
 *   <li>{@link #inject}：原版每次重建列表（{@code ServerSelectionList.refreshEntries()}）后注入官方条目；</li>
 *   <li>{@link #onScreenRemoved}：界面关闭时把图标 / 资源包选择写回本地缓存。</li>
 * </ul>
 * <p>
 * 官方条目只存在于列表控件里，不进入 {@code ServerList}，因此不会被原版写进 servers.dat。
 * 它们排在玩家自己的服务器之后、局域网分区之前：原版的上移 / 下移直接拿行号当 servers.dat 下标，
 * 插在玩家条目前面会让玩家拖动排序时数组越界。
 * <p>
 * 接管：玩家本地可见列表里与官方同地址的条目（例如整合包自带的）会被移入原版的隐藏列表并保存，
 * 列表里只保留官方条目。隐藏列表里的条目图标与资源包选择都还在，用「添加服务器」填同一地址即可恢复。
 * <p>
 * 所有方法都只在客户端主线程调用（后台拉取的结果经 {@link OfficialServerList} 转回主线程后才回调）。
 */
@OnlyIn(Dist.CLIENT)
public final class OfficialServerListBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(OfficialServerListBridge.class);

    /** 官方条目对应的 ServerData，按归一化地址缓存：同一会话内复用 ping 结果、图标与资源包选择 */
    private static final Map<String, ServerData> SERVER_DATA = new HashMap<>();

    /** 当前的多人游戏界面及其列表控件；界面关闭时清空，避免持有界面引用 */
    private static JoinMultiplayerScreen screen;
    private static ServerSelectionList list;

    /** 列表控件里当前已注入的列表签名（见 {@link OfficialServerList#signature()}） */
    private static String injectedSignature = "";

    private OfficialServerListBridge() {
    }

    /**
     * 界面初始化后调用（{@code JoinMultiplayerScreen.init()} 末尾）。
     */
    public static void onScreenInit(JoinMultiplayerScreen multiplayerScreen, ServerSelectionList selectionList) {
        OfficialServerList official = OfficialServerList.getInstance();
        if (!official.enabled()) {
            return;
        }
        official.ensureLoaded();
        screen = multiplayerScreen;
        list = selectionList;
        official.setChangeListener(OfficialServerListBridge::rebuildIfShowing);
        syncSavedState();
        official.refreshAsync();

        // 界面被「编辑服务器 / 确认删除」等子界面盖住期间后台结果到达：那时不重建，回到界面时在这里补上
        if (!official.signature().equals(injectedSignature)) {
            rebuild(multiplayerScreen, selectionList);
        }
    }

    /**
     * 原版「刷新」按钮：跳过节流立即重新拉取（进行中的拉取不会重复发起）。
     */
    public static void onManualRefresh() {
        OfficialServerList.getInstance().refreshAsync(true);
    }

    /**
     * 界面关闭时调用（{@code JoinMultiplayerScreen.removed()} 末尾）。
     */
    public static void onScreenRemoved() {
        OfficialServerList official = OfficialServerList.getInstance();
        if (official.enabled()) {
            syncSavedState();
            official.flush();
        }
        screen = null;
        list = null;
    }

    /**
     * 把官方条目插入刚重建完的原版列表（{@code ServerSelectionList.refreshEntries()} 末尾）。
     */
    public static void inject(JoinMultiplayerScreen multiplayerScreen, ServerSelectionList selectionList) {
        OfficialServerList official = OfficialServerList.getInstance();
        if (!official.enabled()) {
            return;
        }
        official.ensureLoaded();
        screen = multiplayerScreen;
        list = selectionList;

        List<OfficialServer> servers = official.servers();
        if (servers.isEmpty()) {
            injectedSignature = official.signature();
            return;
        }

        // 接管：本地可见列表里与官方同地址的条目移入隐藏列表。本地列表变了就交给原版重建，
        // 重建会再次进入本方法完成注入（那时已没有需要接管的条目）
        if (adoptLocalDuplicates(multiplayerScreen, official) > 0) {
            selectionList.updateOnlineServers(multiplayerScreen.getServers());
            return;
        }

        List<ServerData> entries = new ArrayList<>(servers.size());
        for (OfficialServer server : servers) {
            entries.add(serverData(server, official));
        }
        // 已下架的地址不再保留其 ServerData
        SERVER_DATA.keySet().removeIf(key -> !official.isOfficial(key));

        // 此时列表控件里依次是：玩家自己的服务器 → 局域网分区 → 公开大厅房间。插在局域网分区之前
        int index = multiplayerScreen.getServers().size();
        for (ServerData data : entries) {
            selectionList.children().add(index++, new OfficialServerEntry(selectionList, multiplayerScreen, data));
        }
        injectedSignature = official.signature();
    }

    /**
     * 把玩家可见列表里与官方同地址的条目移入隐藏列表并保存 servers.dat。
     *
     * @return 被移入隐藏列表的条目数
     */
    private static int adoptLocalDuplicates(JoinMultiplayerScreen multiplayerScreen, OfficialServerList official) {
        ServerList servers = multiplayerScreen.getServers();
        int adopted = 0;
        for (int i = 0; i < servers.size(); ) {
            ServerData data = servers.get(i);
            if (official.isOfficial(data.ip)) {
                servers.remove(data);
                servers.add(data, true);
                adopted++;
            } else {
                i++;
            }
        }
        if (adopted > 0) {
            servers.save();
            LOGGER.info("[FireflyMC] 已将 {} 个本地同地址的服务器条目交由官方服务器列表接管", adopted);
        }
        return adopted;
    }

    /** 列表内容有变化时由 {@link OfficialServerList} 在主线程回调。 */
    private static void rebuildIfShowing() {
        JoinMultiplayerScreen current = screen;
        ServerSelectionList selectionList = list;
        if (current == null || selectionList == null || Minecraft.getInstance().screen != current) {
            return;
        }
        OfficialServerList official = OfficialServerList.getInstance();
        // 界面内容已是最新就不动它：重建会清空玩家当前的选中项
        if (official.signature().equals(injectedSignature) && !hasLocalDuplicate(current, official)) {
            return;
        }
        rebuild(current, selectionList);
    }

    private static void rebuild(JoinMultiplayerScreen multiplayerScreen, ServerSelectionList selectionList) {
        // 原版重建会清空选中项：记下选中的是哪台服务器，重建后尽量恢复
        ServerData selected = selectionList.getSelected() instanceof ServerSelectionList.OnlineServerEntry entry
                ? entry.getServerData()
                : null;
        selectionList.updateOnlineServers(multiplayerScreen.getServers());
        if (selected != null) {
            for (ServerSelectionList.Entry candidate : selectionList.children()) {
                if (candidate instanceof ServerSelectionList.OnlineServerEntry entry
                        && entry.getServerData() == selected) {
                    selectionList.setSelected(candidate);
                    break;
                }
            }
        }
    }

    private static boolean hasLocalDuplicate(JoinMultiplayerScreen multiplayerScreen, OfficialServerList official) {
        ServerList servers = multiplayerScreen.getServers();
        for (int i = 0; i < servers.size(); i++) {
            if (official.isOfficial(servers.get(i).ip)) {
                return true;
            }
        }
        return false;
    }

    private static ServerData serverData(OfficialServer server, OfficialServerList official) {
        String key = server.normalizedAddress();
        ServerData data = SERVER_DATA.get(key);
        if (data == null) {
            data = new ServerData(server.name(), server.address(), ServerData.Type.OTHER);
            OfficialServerList.SavedState saved = official.savedState(key);
            if (saved != null) {
                applySaved(data, saved);
            }
            SERVER_DATA.put(key, data);
        }
        // 名称或地址写法可能被后台改过，以本次下发的为准
        data.name = server.name();
        data.ip = server.address();
        return data;
    }

    /** 恢复上次保存的图标与资源包选择；数据损坏时忽略，等 ping 重新取回。 */
    private static void applySaved(ServerData data, OfficialServerList.SavedState saved) {
        if (saved.iconBase64() != null) {
            try {
                data.setIconBytes(ServerData.validateIcon(Base64.getDecoder().decode(saved.iconBase64())));
            } catch (IllegalArgumentException ignored) {
                // 图标损坏：保持无图标
            }
        }
        if (saved.packStatus() != null) {
            try {
                data.setResourcePackStatus(ServerData.ServerPackStatus.valueOf(saved.packStatus()));
            } catch (IllegalArgumentException ignored) {
                // 未知状态：保持默认（连接时询问）
            }
        }
    }

    /** 把内存里的图标 / 资源包选择写回列表状态，下次落盘时带上。 */
    private static void syncSavedState() {
        OfficialServerList official = OfficialServerList.getInstance();
        for (Map.Entry<String, ServerData> entry : SERVER_DATA.entrySet()) {
            ServerData data = entry.getValue();
            byte[] icon = data.getIconBytes();
            official.updateState(entry.getKey(),
                    icon == null ? null : Base64.getEncoder().encodeToString(icon),
                    data.getResourcePackStatus().name());
        }
    }
}
