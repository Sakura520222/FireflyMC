package firefly520.fireflymc.client.ai.integration.map;

import java.lang.reflect.*;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import static firefly520.fireflymc.client.ai.integration.map.MapReflection.*;

/** World Map 1.44.2 presentation only. No tiles, regions, player tracking or teleport interfaces are read. */
final class XaeroWorldMapAccess {
    private final Class<?> session = type("xaero.map.WorldMapSession");
    private final Class<?> processor = type("xaero.map.MapProcessor");
    private final Class<?> world = type("xaero.map.world.MapWorld");
    private final Class<?> gui = type("xaero.map.gui.GuiMap");
    private final Method current = method(session, "getCurrentSession", session);
    private final Method getProcessor = method(session, "getMapProcessor", processor);
    private final Method getLevel = method(processor, "getWorld", ClientLevel.class);
    private final Method getWorld = method(processor, "getMapWorld", world);
    private final Method getDimension = method(world, "getCurrentDimensionId", ResourceKey.class);
    private final Constructor<?> screenConstructor = gui.getConstructor(Screen.class, Screen.class, processor, Entity.class);
    private final Field cameraX = field(gui, "cameraX", double.class), cameraZ = field(gui, "cameraZ", double.class);
    private final Field resetCamera = field(gui, "shouldResetCameraPos", boolean.class);
    private final Field destination = field(gui, "cameraDestination", int[].class);
    private final Field attached = field(gui, "attachedCamera", boolean.class);
    XaeroWorldMapAccess() throws ReflectiveOperationException {}
    record View(Screen screen, Object session, Object root, boolean focused) {}
    private record Context(Object session, Object processor, Object root, String dimension) {}
    private Context context() throws ReflectiveOperationException {
        Object value = nonnull(call(current, null)); Object map = nonnull(call(getProcessor, value));
        if (call(getLevel, map) != Minecraft.getInstance().level) throw new IllegalStateException("MAP_SELECTION_OTHER_SERVER");
        Object root = nonnull(call(getWorld, map));
        String dimension = ((ResourceKey<?>) nonnull(call(getDimension, root))).location().toString();
        return new Context(value, map, root, dimension);
    }
    boolean ready() throws ReflectiveOperationException { context(); return true; }
    View open(Screen parent, MapPoint focus) throws ReflectiveOperationException {
        var context = context();
        boolean focused = focus != null && focus.dimension().equals(context.dimension());
        var screen = (Screen) screenConstructor.newInstance(parent, parent, context.processor(), nonnull(Minecraft.getInstance().getCameraEntity()));
        Minecraft.getInstance().setScreen(screen);
        if (Minecraft.getInstance().screen != screen) throw new IllegalStateException("MAP_NATIVE_SCREEN_NOT_OPENED");
        if (focused) {
            // This is the native map camera, never an Entity position or a world-state setter.
            // An explicit "view this coordinate" has the same detach-camera meaning as native map panning.
            attached.setBoolean(null, false); resetCamera.setBoolean(screen, false); destination.set(screen, null);
            cameraX.setDouble(screen, focus.x() + .5); cameraZ.setDouble(screen, focus.z() + .5);
            if (cameraX.getDouble(screen) != focus.x() + .5 || cameraZ.getDouble(screen) != focus.z() + .5)
                throw new IllegalStateException("MAP_CAMERA_READBACK_FAILED");
        }
        return new View(screen, context.session(), context.root(), focused);
    }
    MapPoint center(View selected, UUID task) throws ReflectiveOperationException {
        var context = context();
        if (selected == null || context.session() != selected.session() || context.root() != selected.root() || !gui.isInstance(selected.screen()))
            throw new IllegalStateException("MAP_SELECTION_SESSION_CHANGED");
        double x = cameraX.getDouble(selected.screen()), z = cameraZ.getDouble(selected.screen());
        if (!Double.isFinite(x) || !Double.isFinite(z) || x < -MapPoint.HORIZONTAL_LIMIT || x > MapPoint.HORIZONTAL_LIMIT
                || z < -MapPoint.HORIZONTAL_LIMIT || z > MapPoint.HORIZONTAL_LIMIT) throw new IllegalStateException("MAP_COORDINATE_INVALID");
        return new MapPoint(UUID.randomUUID(), "世界地图中心", context.dimension(), (int) Math.floor(x), null, (int) Math.floor(z),
                MapPoint.Source.XAERO_MAP_CENTER, MapIntegrationPolicy.Provider.XAERO_WORLDMAP.version(), task);
    }
}
