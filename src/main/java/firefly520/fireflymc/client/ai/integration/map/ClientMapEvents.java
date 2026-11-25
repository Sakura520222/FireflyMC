package firefly520.fireflymc.client.ai.integration.map;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Client-only lifecycle cleanup, including when the workbench is closed. No model or server request is made here. */
@EventBusSubscriber(modid = "fireflymc", value = Dist.CLIENT)
public final class ClientMapEvents {
    private ClientMapEvents() {}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) { MapViewBridge.tickActive(); }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { MapViewBridge.disconnected(); }
}
