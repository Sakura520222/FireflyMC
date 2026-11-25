package firefly520.fireflymc.client.ai.integration.map;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import static firefly520.fireflymc.client.ai.integration.map.MapReflection.*;

/** Exact Minimap 26.4.2 APIs. Never opens waypoint files, enumerates worlds or reads an entire waypoint set. */
final class XaeroMapAccess {
    private final Class<?> legacy = type("xaero.common.XaeroMinimapSession");
    private final Class<?> legacyManager = type("xaero.common.minimap.waypoints.WaypointsManager");
    private final Class<?> session = type("xaero.hud.minimap.module.MinimapSession");
    private final Class<?> worldManager = type("xaero.hud.minimap.world.MinimapWorldManager");
    private final Class<?> world = type("xaero.hud.minimap.world.MinimapWorld");
    private final Class<?> container = type("xaero.hud.minimap.world.container.MinimapWorldContainer");
    private final Class<?> root = type("xaero.hud.minimap.world.container.MinimapWorldRootContainer");
    private final Class<?> manager = type("xaero.hud.minimap.waypoint.thirdparty.ThirdPartyWaypointManager");
    private final Class<?> group = type("xaero.hud.minimap.waypoint.thirdparty.ThirdPartyWaypoints");
    private final Class<?> waypoint = type("xaero.common.minimap.waypoints.Waypoint");
    private final Class<?> gui = type("xaero.common.gui.GuiWaypoints");
    private final Class<?> mod = type("xaero.common.IXaeroMinimap");
    private final Method current = method(legacy, "getCurrentSession", legacy);
    private final Method getLegacyManager = method(legacy, "getWaypointsManager", legacyManager);
    private final Method getMod = method(legacy, "getModMain", mod);
    private final Method getWorldManager = method(session, "getWorldManager", worldManager);
    private final Method autoWorld = method(worldManager, "getAutoWorld", world);
    private final Method getContainer = method(world, "getContainer", container);
    private final Method getRoot = method(container, "getRoot", root);
    private final Method getDimension = method(world, "getDimId", ResourceKey.class);
    private final Method getManager = method(container, "getThirdPartyWaypointManager", manager);
    private final Method ownGroup = method(manager, "get", group, ResourceLocation.class);
    private final Method clearGroup = method(group, "clear", void.class);
    private final Method groupCount = method(group, "getCount", int.class);
    private final Method add = method(group, "add", void.class, String.class, waypoint);
    private final Method get = method(group, "get", waypoint, String.class);
    private final Method x = method(waypoint, "getX", int.class), y = method(waypoint, "getY", int.class), z = method(waypoint, "getZ", int.class);
    private final Method name = method(waypoint, "getName", String.class);
    private final Method hasY = method(waypoint, "isYIncluded", boolean.class);
    private final Method deleted = method(waypoint, "isEffectivelyDeleted", boolean.class);
    private final Method setY = method(waypoint, "setYIncluded", void.class, boolean.class);
    private final Method temporary = method(waypoint, "setTemporary", void.class, boolean.class);
    private final Constructor<?> pointConstructor = waypoint.getConstructor(int.class, int.class, int.class, String.class, String.class, int.class);
    private final Constructor<?> screenConstructor = gui.getConstructor(mod, legacy, Screen.class);
    private final Field selected = field(gui, "selectedListSet", ConcurrentSkipListSet.class);
    private final Field displayedWorld = field(gui, "displayedWorld", world);
    private final Field displayedManager = field(gui, "manager", worldManager);
    private final Method selectedOnly = privateMethod(gui, "getSelectedWaypointsList", ArrayList.class);
    private Object lastGroup;
    XaeroMapAccess() throws ReflectiveOperationException {}
    private record Context(Object legacySession, Object world, Object container) {}
    record Selection(Screen screen, Object legacySession) {}
    private Context context() throws ReflectiveOperationException {
        Object nativeSession = nonnull(call(current, null));
        Object module = nonnull(call(getLegacyManager, nativeSession));
        Object selectedWorld = nonnull(call(autoWorld, nonnull(call(getWorldManager, module))));
        return new Context(nativeSession, selectedWorld, nonnull(call(getContainer, selectedWorld)));
    }
    boolean ready() throws ReflectiveOperationException { context(); return true; }
    Selection openSelection(Screen parent) throws ReflectiveOperationException {
        var context = context();
        var screen = (Screen) screenConstructor.newInstance(nonnull(call(getMod, context.legacySession())), context.legacySession(), parent);
        Minecraft.getInstance().setScreen(screen);
        if (Minecraft.getInstance().screen != screen) throw new IllegalStateException("MAP_NATIVE_SCREEN_NOT_OPENED");
        return new Selection(screen, context.legacySession());
    }
    MapPoint selected(Selection selection, UUID task) throws ReflectiveOperationException {
        var context = context();
        if (selection == null || selection.legacySession() != context.legacySession() || !gui.isInstance(selection.screen()))
            throw new IllegalStateException("MAP_SELECTION_SESSION_CHANGED");
        var indices = (Set<?>) nonnull(selected.get(selection.screen()));
        if (indices.size() != 1) throw new IllegalStateException("MAP_SELECT_EXACTLY_ONE_WAYPOINT");
        Object selectedWorld = nonnull(displayedWorld.get(selection.screen()));
        Object displayedAuto = nonnull(call(autoWorld, nonnull(displayedManager.get(selection.screen()))));
        if (call(getRoot, call(getContainer, selectedWorld)) != call(getRoot, call(getContainer, displayedAuto))
                || call(getRoot, call(getContainer, displayedAuto)) != call(getRoot, context.container()))
            throw new IllegalStateException("MAP_SELECTION_OTHER_SERVER");
        // The native method maps only the one selected row; no full list or map-cache traversal occurs here.
        var values = (List<?>) nonnull(call(selectedOnly, selection.screen()));
        if (values.size() != 1 || !waypoint.isInstance(values.getFirst())) throw new IllegalStateException("MAP_SELECT_EXACTLY_ONE_WAYPOINT");
        var value = values.getFirst();
        if ((boolean) call(deleted, value)) throw new IllegalStateException("MAP_SELECTED_WAYPOINT_REMOVED");
        String dimension = ((ResourceKey<?>) nonnull(call(getDimension, selectedWorld))).location().toString();
        return new MapPoint(UUID.randomUUID(), safeName((String) call(name, value)), dimension,
                (int) call(x, value), (boolean) call(hasY, value) ? (int) call(y, value) : null, (int) call(z, value),
                MapPoint.Source.XAERO_WAYPOINT, MapIntegrationPolicy.Provider.XAERO_MINIMAP.version(), task);
    }
    int publish(MapRoute route) throws ReflectiveOperationException {
        var context = context();
        var dimension = ((ResourceKey<?>) nonnull(call(getDimension, context.world()))).location().toString();
        if (!dimension.equals(Minecraft.getInstance().level.dimension().location().toString())) throw new IllegalStateException("MAP_NATIVE_DIMENSION_NOT_CURRENT");
        Object next = nonnull(call(ownGroup, nonnull(call(getManager, context.container())), ResourceLocation.fromNamespaceAndPath("fireflymc", "ai4_workbench")));
        if (lastGroup != null && lastGroup != next) clear();
        lastGroup = next; call(clearGroup, next);
        if ((int) call(groupCount, next) != 0) throw new IllegalStateException("MAP_MARKER_CLEAR_FAILED");
        int count = 0;
        try {
            for (var point : route.points()) if (point.dimension().equals(dimension)) {
                Object value = pointConstructor.newInstance(point.x(), point.y() == null ? 0 : point.y(), point.z(),
                        "小萤 · " + (point.label().isEmpty() ? "地点" : point.label()), Integer.toString(++count), 6);
                call(setY, value, point.y() != null); call(temporary, value, true);
                String id = route.id() + ":" + point.id(); call(add, next, id, value);
                if (call(get, next, id) != value || (int) call(x, value) != point.x() || (int) call(z, value) != point.z())
                    throw new IllegalStateException("MAP_MARKER_READBACK_FAILED");
            }
            if ((int) call(groupCount, next) != count) throw new IllegalStateException("MAP_MARKER_READBACK_FAILED");
            return count;
        } catch (ReflectiveOperationException | RuntimeException failure) { call(clearGroup, next); throw failure; }
    }
    void clear() throws ReflectiveOperationException {
        if (lastGroup != null) {
            call(clearGroup, lastGroup);
            if ((int) call(groupCount, lastGroup) != 0) throw new IllegalStateException("MAP_MARKER_CLEAR_FAILED");
            lastGroup = null;
        }
    }
    private static String safeName(String value) {
        if (value == null) return "已选路标";
        var label = new StringBuilder();
        value.codePoints().filter(c -> !Character.isISOControl(c) && c != 0x00a7).limit(80).forEach(code -> {
            if (label.length() + Character.charCount(code) <= 80) label.appendCodePoint(code);
        });
        return label.toString();
    }
}
