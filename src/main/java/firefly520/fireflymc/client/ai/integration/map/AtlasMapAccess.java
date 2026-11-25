package firefly520.fireflymc.client.ai.integration.map;

import java.lang.reflect.*;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import static firefly520.fireflymc.client.ai.integration.map.MapReflection.*;

/** Map Atlases 1.21-6.6.0: only the explicitly held atlas and its already selected active map metadata. */
final class AtlasMapAccess {
    private final Class<?> client = type("pepjebs.mapatlases.client.MapAtlasesClient");
    private final Class<?> holder = type("pepjebs.mapatlases.utils.MapDataHolder");
    private final Class<?> atlasItem = type("pepjebs.mapatlases.item.MapAtlasItem");
    private final Class<?> gui = type("pepjebs.mapatlases.client.screen.AtlasOverviewScreen");
    private final Method activeAtlas = method(client, "getCurrentActiveAtlas", ItemStack.class);
    private final Method activeMap = method(client, "getActiveMap", holder);
    private final Field data = holder.getField("data");
    private final Constructor<?> screenConstructor = gui.getConstructor(ItemStack.class, LecternBlockEntity.class, boolean.class);
    private final Method screenAtlas = method(gui, "getAtlas", ItemStack.class);
    AtlasMapAccess() throws ReflectiveOperationException {
        if (data.getType() != MapItemSavedData.class) throw new NoSuchFieldException("MAP_ATLAS_DATA_SIGNATURE_CHANGED");
    }
    private ItemStack heldAtlas() {
        var player = Minecraft.getInstance().player;
        if (player == null) throw new IllegalStateException("MAP_PLAYER_OFFLINE");
        for (var stack : new ItemStack[]{player.getMainHandItem(), player.getOffhandItem()})
            if (!stack.isEmpty() && atlasItem.isInstance(stack.getItem())) return stack;
        throw new IllegalStateException("MAP_HOLD_ATLAS_IN_HAND");
    }
    boolean ready() throws ReflectiveOperationException {
        ItemStack held = heldAtlas();
        if (call(activeAtlas, null) != held || call(activeMap, null) == null) throw new IllegalStateException("MAP_ATLAS_ACTIVE_MAP_NOT_READY");
        return true;
    }
    MapPoint center(UUID task) throws ReflectiveOperationException {
        ItemStack held = heldAtlas();
        if (call(activeAtlas, null) != held) throw new IllegalStateException("MAP_ATLAS_ACTIVE_MAP_NOT_READY");
        Object map = nonnull(call(activeMap, null));
        var value = (MapItemSavedData) nonnull(data.get(map));
        // Do not read colors, decoration maps, the atlas collection, directories or Xaero import helpers.
        return new MapPoint(UUID.randomUUID(), "手持地图中心", value.dimension.location().toString(), value.centerX, null, value.centerZ,
                MapPoint.Source.ATLAS_MAP_CENTER, MapIntegrationPolicy.Provider.MAP_ATLASES.version(), task);
    }
    void open() throws ReflectiveOperationException {
        ItemStack held = heldAtlas();
        var screen = (Screen) screenConstructor.newInstance(held, null, false);
        Minecraft.getInstance().setScreen(screen);
        if (Minecraft.getInstance().screen != screen || call(screenAtlas, screen) != held) throw new IllegalStateException("MAP_NATIVE_SCREEN_NOT_OPENED");
    }
}
