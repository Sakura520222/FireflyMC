package firefly520.fireflymc.story;

import firefly520.fireflymc.story.engine.StoryEvent;
import firefly520.fireflymc.story.engine.StoryEventType;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Public integration entry point for externally generated story events.
 *
 * <p>The originating player instance is part of the contract: after marshalling to the server
 * thread, a UUID alone cannot distinguish a disconnected player from its replacement.</p>
 */
public final class StoryTriggers {
    private StoryTriggers() {}

    public static void fireCustomEvent(
        MinecraftServer server,
        ServerPlayer player,
        ResourceLocation eventId,
        int amount
    ) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(player, "player");
        fireCustomEvent(
            server::execute,
            server::isStopped,
            server::isSameThread,
            player.getUUID(),
            playerId -> server.getPlayerList().getPlayer(playerId),
            StoryEngine.instance()::dispatch,
            player,
            eventId,
            amount);
    }

    static <P> void fireCustomEvent(
        Consumer<Runnable> scheduler,
        BooleanSupplier serverStopped,
        BooleanSupplier onServerThread,
        UUID playerId,
        Function<UUID, P> playerById,
        BiConsumer<P, StoryEvent> dispatcher,
        P originatingPlayer,
        ResourceLocation eventId,
        int amount
    ) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(serverStopped, "serverStopped");
        Objects.requireNonNull(onServerThread, "onServerThread");
        Objects.requireNonNull(playerById, "playerById");
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(originatingPlayer, "originatingPlayer");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(eventId, "eventId");
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be strictly positive");
        }
        if (serverStopped.getAsBoolean()) {
            return;
        }

        StoryEvent event = new StoryEvent(
            StoryEventType.CUSTOM_EVENT,
            eventId,
            amount,
            Map.of());
        scheduler.accept(() -> {
            if (!onServerThread.getAsBoolean() || serverStopped.getAsBoolean()) {
                return;
            }
            if (originatingPlayer == playerById.apply(playerId)) {
                dispatcher.accept(originatingPlayer, event);
            }
        });
    }
}
