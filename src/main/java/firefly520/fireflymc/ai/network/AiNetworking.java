package firefly520.fireflymc.ai.network;

import firefly520.fireflymc.FireflyMCMod;
import firefly520.fireflymc.ai.AiServerEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Optional AI capability is negotiated separately; existing non-AI network channels are unchanged. */
public final class AiNetworking {
    private AiNetworking() {}
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(FireflyMCMod.MODID).versioned("ai4-" + AiProtocol.VERSION).optional();
        registrar.playToServer(AiRequestPayload.TYPE, AiRequestPayload.STREAM_CODEC, AiServerEvents::request);
        registrar.playToClient(AiResponsePayload.TYPE, AiResponsePayload.STREAM_CODEC, AiNetworking::client);
    }
    private static void client(AiResponsePayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        context.enqueueWork(() -> {
            try {
                Class.forName("firefly520.fireflymc.client.ai.AiClientNetwork")
                        .getMethod("receive", AiResponsePayload.class).invoke(null, payload);
            } catch (ReflectiveOperationException e) {
                FireflyMCMod.LOGGER.error("[AI4] Client workspace handler unavailable ({})", e.getClass().getSimpleName());
            }
        });
    }
}
