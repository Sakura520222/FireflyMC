package firefly520.fireflymc.ai;

import firefly520.fireflymc.FireflyMCMod;
import firefly520.fireflymc.ai.config.AiConfiguration;
import firefly520.fireflymc.ai.network.*;
import firefly520.fireflymc.ai.security.AiEnvironmentGate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import firefly520.fireflymc.ai.events.PersonalEventStore;
import com.google.gson.JsonObject;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** No command/chat/console entry. Unsupported servers never read AI config or create AI state. */
@EventBusSubscriber(modid = FireflyMCMod.MODID)
public final class AiServerEvents {
    private static final Map<MinecraftServer, AiServerService> SERVICES = new ConcurrentHashMap<>();
    private static final Map<MinecraftServer, AiServerService> STOPPING = new ConcurrentHashMap<>();
    private static final Map<MinecraftServer, AiEnvironmentGate.Availability> STATES = new ConcurrentHashMap<>();
    private AiServerEvents() {}

    @SubscribeEvent public static void started(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        if (!server.isDedicatedServer()) return;
        try {
            var config = AiConfiguration.load(FMLPaths.CONFIGDIR.get().resolve("fireflymc-ai4.json"));
            if (config.isEmpty()) { STATES.put(server, AiEnvironmentGate.Availability.NOT_READY); return; }
            if (!config.get().enabled()) { STATES.put(server, AiEnvironmentGate.Availability.DISABLED); return; }
            SERVICES.put(server, new AiServerService(server, config.get()));
        } catch (Exception e) {
            STATES.put(server, AiEnvironmentGate.Availability.NOT_READY);
            FireflyMCMod.LOGGER.error("[AI4] New server configuration or storage unavailable ({})",
                    e.getClass().getSimpleName());
        }
    }
    @SubscribeEvent public static void stopped(ServerStoppingEvent event) {
        var service = SERVICES.remove(event.getServer());
        if (service != null) { STOPPING.put(event.getServer(), service); service.stop(); }
        STATES.remove(event.getServer());
    }
    /** Invoked after every ServerStopped listener, before NeoForge releases its exit latch. */
    public static void savesFinished(MinecraftServer server) {
        var service = STOPPING.remove(server);
        if (service == null) service = SERVICES.remove(server); // Startup/crash paths may skip ServerStopping.
        if (service != null) service.finishStopped();
        else firefly520.fireflymc.ai.material.NativeSaveObserver.serverStopped(server);
        STATES.remove(server);
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (event.getServer().isDedicatedServer())
            firefly520.fireflymc.ai.material.NativeSaveObserver.tickExisting(event.getServer());
        var service = SERVICES.get(event.getServer());
        if (service != null) service.tick();
    }
    @SubscribeEvent public static void datapacks(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) return;
        var service = SERVICES.get(event.getPlayerList().getServer());
        if (service != null) service.datapacksReloaded();
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            var service = SERVICES.get(player.server);
            if (service != null) service.disconnected(player.getUUID());
        }
    }
    @SubscribeEvent public static void death(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !player.server.isDedicatedServer()) return;
        var service = SERVICES.get(player.server);
        if (service == null) return;
        JsonObject data = new JsonObject();
        data.addProperty("text", event.getSource().getLocalizedDeathMessage(player).getString());
        data.addProperty("dimension", player.level().dimension().location().toString());
        data.addProperty("position", player.blockPosition().toShortString());
        service.observe(player, PersonalEventStore.Type.DEATH, java.util.UUID.randomUUID().toString(), data);
    }
    @SubscribeEvent public static void advancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !player.server.isDedicatedServer()) return;
        var service = SERVICES.get(player.server);
        if (service == null || event.getAdvancement().value().display().isEmpty()) return;
        JsonObject data = new JsonObject();
        data.addProperty("advancementId", event.getAdvancement().id().toString());
        data.addProperty("dimension", player.level().dimension().location().toString());
        service.observe(player, PersonalEventStore.Type.ADVANCEMENT,
                event.getAdvancement().id() + ":" + System.currentTimeMillis(), data);
    }
    public static void request(AiRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.server;
            var service = SERVICES.get(server);
            if (!server.isDedicatedServer() || service == null) {
                var status = server.isDedicatedServer()
                        ? STATES.getOrDefault(server, AiEnvironmentGate.Availability.NOT_READY)
                        : AiEnvironmentGate.Availability.NOT_SUPPORTED;
                PacketDistributor.sendToPlayer(player, new AiResponsePayload(AiProtocol.VERSION,
                        payload.requestId(), AiProtocol.NO_SESSION, "availability",
                        "{\"status\":\"" + status + "\"}"));
                return;
            }
            service.request(player, payload);
        });
    }
}
