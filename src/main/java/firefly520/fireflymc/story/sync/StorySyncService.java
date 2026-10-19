package firefly520.fireflymc.story.sync;

import com.mojang.logging.LogUtils;
import firefly520.fireflymc.network.StoryProgressSnapshotPayload;
import firefly520.fireflymc.story.engine.SyncSink;
import firefly520.fireflymc.story.engine.StoryRuntimeSnapshot;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

/**
 * Coalesces story snapshot dirtiness and sends the resulting payload on the server thread.
 *
 * <p>The engine reports dirty players once per transaction. This service is registered after
 * {@code StoryEngine}'s tick listener, so the resulting send is deferred to the tail of the same
 * {@link ServerTickEvent.Post}. UUIDs are retained, never player objects.</p>
 */
public final class StorySyncService implements SyncSink {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final StorySyncService INSTANCE = new StorySyncService();
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    /** Narrow source installed by {@code StoryEngine.register}; not a gameplay extension point. */
    @FunctionalInterface
    public interface SnapshotSource {
        StoryRuntimeSnapshot get();
    }

    private final Set<UUID> dirtyPlayers = new LinkedHashSet<>();
    private volatile SnapshotSource snapshotSource = StoryRuntimeSnapshot::empty;

    StorySyncService() {}

    public static StorySyncService instance() {
        return INSTANCE;
    }

    /**
     * Installs the production sink and server-tail flush exactly once.
     *
     * <p>Call after {@link StoryEngine#register(IEventBus)} so ordering guarantees that gameplay
     * dirtiness is collected before this listener sends it.</p>
     */
    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(
            (ServerTickEvent.Post event) -> instance().flush(event.getServer())
        );
        NeoForge.EVENT_BUS.addListener(
            (ServerStoppingEvent event) -> instance().onServerStopping(event.getServer())
        );
    }

    /** Integration-only source hookup; called once by {@code StoryEngine.register}. */
    public static void attachSnapshotSource(SnapshotSource source) {
        INSTANCE.snapshotSource = Objects.requireNonNull(source, "source");
    }

    public static boolean isInstalled() {
        return INSTALLED.get();
    }

    @Override
    public void markDirty(ServerPlayer player) {
        markPlayerDirty(player.getUUID(), player.server::isSameThread);
    }

    public void markPlayerDirty(ServerPlayer player) {
        markDirty(player);
    }

    public void markWorldDirty(MinecraftServer server) {
        requireServerThread(server::isSameThread);
        Set<UUID> onlinePlayers = new LinkedHashSet<>();
        server.getPlayerList().getPlayers()
            .forEach(player -> onlinePlayers.add(player.getUUID()));
        markWorldDirty(onlinePlayers, server::isSameThread);
    }

    public void flush(MinecraftServer server) {
        requireServerThread(server::isSameThread);
        flush(
            playerId -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null) {
                    return null;
                }
                StoryProgressSnapshotPayload payload =
                    StoryProgressSnapshotPayload.from(
                        player,
                        currentSnapshot()
                    );
                return () -> PacketDistributor.sendToPlayer(player, payload);
            },
            playerId -> server.getPlayerList().getPlayer(playerId) != null,
            server::isSameThread
        );
    }

    public void sendNow(ServerPlayer player) {
        requireServerThread(player.server::isSameThread);
        sendNow(
            player.getUUID(),
            () -> {
                StoryProgressSnapshotPayload payload = StoryProgressSnapshotPayload.from(
                    player,
                    currentSnapshot()
                );
                return () -> PacketDistributor.sendToPlayer(player, payload);
            },
            player.server::isSameThread
        );
    }

    void markPlayerDirty(UUID playerId, BooleanSupplier onServerThread) {
        requireServerThread(onServerThread);
        dirtyPlayers.add(playerId);
    }

    void markWorldDirty(Collection<UUID> onlinePlayers, BooleanSupplier onServerThread) {
        requireServerThread(onServerThread);
        dirtyPlayers.addAll(onlinePlayers);
    }

    void flush(
        Function<UUID, Runnable> senders,
        Predicate<UUID> online,
        BooleanSupplier onServerThread
    ) {
        requireServerThread(onServerThread);
        Set<UUID> targets = new LinkedHashSet<>(dirtyPlayers);
        dirtyPlayers.clear();
        for (UUID playerId : targets) {
            if (!online.test(playerId)) {
                continue;
            }
            try {
                Runnable send = senders.apply(playerId);
                if (send != null) {
                    send.run();
                }
            } catch (RuntimeException exception) {
                dirtyPlayers.add(playerId);
                LOGGER.error(
                    "Failed to send story snapshot; player={}",
                    playerId,
                    exception
                );
            }
        }
    }

    void sendNow(UUID playerId, Supplier<Runnable> delivery, BooleanSupplier onServerThread) {
        requireServerThread(onServerThread);
        // Establish the marker before source/payload construction. Keep it through any
        // construction or delivery failure so login/respawn retries at the next tick tail.
        dirtyPlayers.add(playerId);
        try {
            delivery.get().run();
        } catch (RuntimeException exception) {
            LOGGER.error(
                "Failed to send story snapshot now; player={}",
                playerId,
                exception
            );
            throw exception;
        }
        dirtyPlayers.remove(playerId);
    }

    Set<UUID> dirtyTargets() {
        return Set.copyOf(dirtyPlayers);
    }

    void onServerStopping(MinecraftServer server) {
        requireServerThread(server::isSameThread);
        dirtyPlayers.clear();
    }

    private static void requireServerThread(BooleanSupplier onServerThread) {
        if (!onServerThread.getAsBoolean()) {
            throw new IllegalThreadStateException(
                "StorySyncService is confined to the server thread");
        }
    }

    private StoryRuntimeSnapshot currentSnapshot() {
        StoryRuntimeSnapshot snapshot = snapshotSource.get();
        if (snapshot == null) {
            throw new IllegalStateException("Story snapshot source returned null");
        }
        return snapshot;
    }
}
