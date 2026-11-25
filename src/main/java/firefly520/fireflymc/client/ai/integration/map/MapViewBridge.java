package firefly520.fireflymc.client.ai.integration.map;

import java.util.*;
import java.util.function.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModList;
import static firefly520.fireflymc.client.ai.integration.map.MapIntegrationPolicy.*;
import static firefly520.fireflymc.client.ai.integration.map.MapSessionState.Scope;

/** Client presentation bridge. The caller supplies the authenticated dedicated-workbench connection identity. */
public final class MapViewBridge {
    public record Outcome(boolean success, String code, String message, int nativeMarkers) {}
    private static MapViewBridge active;
    private Supplier<Scope> currentScope;
    private final MapSessionState state = new MapSessionState();
    private Object connection;
    private XaeroMapAccess minimap;
    private XaeroWorldMapAccess worldMap;
    private AtlasMapAccess atlas;
    private XaeroMapAccess.Selection selection;
    private XaeroWorldMapAccess.View mapView;
    private long selectionExpires, viewExpires;
    /** Reopening the same private workbench keeps its local draft; identity is rechecked on every action and tick. */
    public static MapViewBridge acquire(Supplier<Scope> currentScope) {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("MAP_NOT_CLIENT_THREAD");
        Objects.requireNonNull(currentScope);
        if (active == null) return new MapViewBridge(currentScope);
        active.currentScope = currentScope; return active;
    }
    public MapViewBridge(Supplier<Scope> currentScope) {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("MAP_NOT_CLIENT_THREAD");
        this.currentScope = Objects.requireNonNull(currentScope);
        if (active != null) active.disconnect();
        active = this;
    }
    private Scope requireScope() {
        var minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) throw new IllegalStateException("MAP_NOT_CLIENT_THREAD");
        var scope = currentScope.get();
        if (scope == null || minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null
                || minecraft.getSingleplayerServer() != null || !scope.playerId().equals(minecraft.player.getUUID())) {
            disconnect(); throw new IllegalStateException("MAP_WORKSPACE_UNAVAILABLE");
        }
        if (connection != minecraft.getConnection()) { disconnect(); connection = minecraft.getConnection(); }
        if (state.bind(scope)) { clearNative(); selection = null; mapView = null; }
        return scope;
    }
    public Optional<MapPoint> selected() { requireScope(); return state.selected(); }
    public MapRoute route() { requireScope(); return state.route(); }
    public MapPoint selectRoutePoint(UUID id) {
        var scope = requireScope();
        var point = state.route().points().stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("MAP_ROUTE_POINT_NOT_FOUND"));
        state.select(scope, point); return point;
    }
    public void setRoute(MapRoute route) {
        var scope = requireScope(); Objects.requireNonNull(route); clearNativeForEdit(); state.setRoute(scope, route);
    }
    public void appendSelected() {
        var scope = requireScope();
        var point = state.selected().orElseThrow(() -> new IllegalStateException("MAP_POINT_NOT_SELECTED"));
        var next = state.route().append(point); clearNativeForEdit(); state.setRoute(scope, next);
    }
    public void removeLast() {
        var scope = requireScope(); var next = state.route().removeLast();
        if (next != state.route()) { clearNativeForEdit(); state.setRoute(scope, next); }
    }
    public void clearRoute() {
        var scope = requireScope(); var next = state.route().clear(); clearNativeForEdit(); state.setRoute(scope, next);
    }
    public MapPoint currentPosition(String label) {
        var scope = requireScope(); var minecraft = Minecraft.getInstance(); var position = minecraft.player.blockPosition();
        var point = new MapPoint(UUID.randomUUID(), label, minecraft.level.dimension().location().toString(), position.getX(), position.getY(), position.getZ(),
                MapPoint.Source.CLIENT_POSITION, "minecraft@1.21.1", state.route().taskId());
        state.select(scope, point); return point;
    }
    public MapPoint manual(String label, String dimension, int x, Integer y, int z) {
        var scope = requireScope(); var point = new MapPoint(UUID.randomUUID(), label, dimension, x, y, z,
                MapPoint.Source.MANUAL_COORDINATE, "fireflymc-map-v1", state.route().taskId());
        state.select(scope, point); return point;
    }
    /** Exactly one coordinate report goes to the workbench callback, only after its own explicit confirm button. */
    public void confirmSelected(Consumer<MapPoint> callback) {
        var scope = requireScope(); var point = state.confirmSelected(scope);
        try { callback.accept(point); }
        catch (RuntimeException notQueued) { state.submissionFailed(scope, point.id()); throw notQueued; }
    }
    public Outcome selectXaero(Screen returnScreen) {
        return action(() -> {
            requireProvider(Provider.XAERO_MINIMAP); if (minimap == null) minimap = new XaeroMapAccess();
            selection = minimap.openSelection(returnScreen); selectionExpires = System.currentTimeMillis() + 300_000;
            return success("在 Xaero 中只选一个路标，返回后点“读取单选路标”。");
        });
    }
    public Outcome readSelectedXaero() {
        return action(() -> {
            if (selection == null || System.currentTimeMillis() >= selectionExpires) throw new IllegalStateException("MAP_SELECTION_EXPIRED");
            requireProvider(Provider.XAERO_MINIMAP); var scope = requireScope();
            state.select(scope, minimap.selected(selection, state.route().taskId())); selection = null;
            return success("已读取本人明确单选的一个路标；尚未发送给工作台。");
        });
    }
    public Outcome readAtlasCenter() {
        return action(() -> {
            requireProvider(Provider.MAP_ATLASES); if (atlas == null) atlas = new AtlasMapAccess();
            state.select(requireScope(), atlas.center(state.route().taskId()));
            return success("已读取当前手持地图的中心坐标；没有读取地图图像或其他页。");
        });
    }
    public Outcome openWorldMap(Screen returnScreen) {
        return action(() -> {
            requireProvider(Provider.XAERO_WORLDMAP); if (worldMap == null) worldMap = new XaeroWorldMapAccess();
            mapView = worldMap.open(returnScreen, state.selected().orElse(null)); viewExpires = System.currentTimeMillis() + 300_000;
            return success(mapView.focused() ? "已打开并定位私人坐标；返回后可选用地图中心。"
                    : "已打开本地世界地图；其他维度的所选点未自动定位，可返回选用当前地图中心。");
        });
    }
    public Outcome readWorldMapCenter() {
        return action(() -> {
            requireProvider(Provider.XAERO_WORLDMAP);
            if (mapView == null || System.currentTimeMillis() >= viewExpires) throw new IllegalStateException("MAP_SELECTION_EXPIRED");
            state.select(requireScope(), worldMap.center(mapView, state.route().taskId())); mapView = null;
            return success("已选取一个地图中心坐标；地形和通行性尚未由服务端核验。");
        });
    }
    public Outcome openAtlas() {
        return action(() -> {
            requireProvider(Provider.MAP_ATLASES); if (atlas == null) atlas = new AtlasMapAccess(); atlas.open();
            return success("已打开手持地图册；关闭后按工作台快捷键返回。");
        });
    }
    public Outcome publishMarkers() {
        return action(() -> {
            requireProvider(Provider.XAERO_MINIMAP); if (minimap == null) minimap = new XaeroMapAccess();
            if (state.route().points().isEmpty()) throw new IllegalStateException("MAP_ROUTE_EMPTY");
            int count = minimap.publish(state.route());
            if (count == 0) throw new IllegalStateException("MAP_ROUTE_OTHER_DIMENSION");
            return new Outcome(true, "", "已登记 " + count + " 个本维度私人临时路标；原有路标未修改。", count);
        });
    }
    public Outcome clearMarkers() {
        return action(() -> { if (minimap != null) minimap.clear(); return success("已核对撤回本模组的私人临时路标。"); });
    }
    public List<Status> statuses() {
        requireScope(); var values = new ArrayList<Status>();
        for (var provider : Provider.values()) {
            String version = installed(provider);
            if (version == null) { values.add(unavailable(provider, State.NOT_INSTALLED, "", "未安装，坐标卡仍可用")); continue; }
            if (!supported(provider, version)) { values.add(unavailable(provider, State.VERSION_UNSUPPORTED, version, "该版本未匹配，使用坐标卡")); continue; }
            try {
                switch (provider) {
                    case XAERO_MINIMAP -> { if (minimap == null) minimap = new XaeroMapAccess(); minimap.ready(); }
                    case XAERO_WORLDMAP -> { if (worldMap == null) worldMap = new XaeroWorldMapAccess(); worldMap.ready(); }
                    case MAP_ATLASES -> { if (atlas == null) atlas = new AtlasMapAccess(); atlas.ready(); }
                }
                values.add(new Status(provider, State.RUNTIME_READY, version,
                        provider == Provider.MAP_ATLASES ? "当前手持地图中心可选；路线使用坐标示意" : "当前本地地图上下文已就绪",
                        true, provider == Provider.XAERO_MINIMAP, true));
            } catch (ReflectiveOperationException | LinkageError mismatch) { values.add(unavailable(provider, State.INTERFACE_MISMATCH, version, "本地接口不匹配，使用坐标卡")); }
            catch (RuntimeException notReady) { values.add(unavailable(provider, State.RUNTIME_NOT_READY, version, message(code(notReady)))); }
        }
        return List.copyOf(values);
    }
    private String installed(Provider provider) {
        return ModList.get().getModContainerById(provider.modId()).map(mod -> mod.getModInfo().getVersion().toString()).orElse(null);
    }
    private void requireProvider(Provider provider) {
        requireScope(); String version = installed(provider);
        if (version == null) throw new IllegalStateException("MAP_MOD_NOT_INSTALLED");
        if (!supported(provider, version)) throw new IllegalStateException("MAP_VERSION_UNSUPPORTED");
    }
    private Outcome action(CheckedAction action) {
        try { requireScope(); return action.run(); }
        catch (ReflectiveOperationException | LinkageError mismatch) { return failed("MAP_INTERFACE_MISMATCH"); }
        catch (RuntimeException failure) { return failed(code(failure)); }
    }
    private void clearNative() {
        if (minimap != null) try { minimap.clear(); } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { /* Session teardown cannot touch another origin. */ }
    }
    private void clearNativeForEdit() {
        try { if (minimap != null) minimap.clear(); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { throw new IllegalStateException("MAP_MARKER_CLEAR_FAILED", failure); }
    }
    private void disconnect() {
        clearNative(); if (state.scope() != null || connection != null) state.clear();
        connection = null; selection = null; mapView = null;
    }
    public static void tickActive() {
        if (active != null) try { active.requireScope(); } catch (RuntimeException unavailable) { active.disconnect(); }
    }
    public static void disconnected() {
        var previous = active; if (previous == null) return;
        var minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            Object expectedConnection = previous.connection;
            minecraft.execute(() -> {
                if (previous.connection == expectedConnection) { previous.disconnect(); if (active == previous) active = null; }
            });
            return;
        }
        previous.disconnect(); if (active == previous) active = null;
    }
    private static Outcome success(String text) { return new Outcome(true, "", text, 0); }
    private static Outcome failed(String code) { return new Outcome(false, code, message(code), 0); }
    private static String code(Throwable failure) {
        String value = failure.getMessage(); return value != null && value.matches("MAP_[A-Z0-9_]{1,80}") ? value : "MAP_ACTION_FAILED";
    }
    public static String message(String code) {
        return switch (code) {
            case "MAP_WORKSPACE_UNAVAILABLE", "MAP_PLAYER_OFFLINE", "MAP_SESSION_CHANGED", "MAP_SELECTION_SESSION_CHANGED", "MAP_SELECTION_OTHER_SERVER" -> "工作台连接已变化，请重新打开并选点。";
            case "MAP_SELECT_EXACTLY_ONE_WAYPOINT" -> "请在 Xaero 中只选择一个路标，再返回读取。";
            case "MAP_SELECTION_EXPIRED" -> "选点已过期，请重新打开地图选取。";
            case "MAP_SELECTED_WAYPOINT_REMOVED" -> "所选路标已被移除，请重新选择。";
            case "MAP_HOLD_ATLAS_IN_HAND" -> "请把地图册拿在主手或副手中。";
            case "MAP_ATLAS_ACTIVE_MAP_NOT_READY", "MAP_NATIVE_CONTEXT_NOT_READY" -> "本地地图尚未就绪；可先使用坐标卡。";
            case "MAP_MOD_NOT_INSTALLED" -> "未安装此地图模组，坐标卡与路线示意仍可用。";
            case "MAP_VERSION_UNSUPPORTED", "MAP_INTERFACE_MISMATCH" -> "此地图版本或接口未匹配，使用坐标卡与路线示意。";
            case "MAP_ROUTE_OTHER_DIMENSION", "MAP_OPEN_MATCHING_DIMENSION_FIRST", "MAP_NATIVE_DIMENSION_NOT_CURRENT" -> "请先切换到对应维度；跨维度段保留为坐标卡。";
            case "MAP_ROUTE_EMPTY", "MAP_POINT_NOT_SELECTED", "MAP_ROUTE_POINT_NOT_FOUND" -> "请先明确选择一个地点。";
            case "MAP_POINT_ALREADY_SUBMITTED" -> "该地点已提交，未重复发送。";
            case "MAP_ROUTE_LIMIT", "MAP_ROUTE_TASK_OR_POINT_CHANGED" -> "路线最多 32 点；同一点不可重复，任务引用须一致。";
            case "MAP_COORDINATE_INVALID", "MAP_LABEL_INVALID" -> "坐标或名称无效，请检查后重试。";
            case "MAP_MARKER_CLEAR_FAILED", "MAP_MARKER_READBACK_FAILED" -> "本地路标读回核验未通过，未确认完成；请检查地图或重新连接。";
            default -> "地图操作未完成，坐标卡仍可使用。";
        };
    }
    @FunctionalInterface private interface CheckedAction { Outcome run() throws ReflectiveOperationException; }
}
