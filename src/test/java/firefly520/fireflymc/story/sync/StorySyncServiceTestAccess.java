package firefly520.fireflymc.story.sync;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Same-package bridge used by the focused network payload/service tests. */
public final class StorySyncServiceTestAccess {
    private StorySyncServiceTestAccess() {}

    public static StorySyncService createService() {
        return new StorySyncService();
    }

    public static void markPlayerDirty(
        StorySyncService service,
        UUID playerId,
        boolean onServerThread
    ) {
        service.markPlayerDirty(playerId, () -> onServerThread);
    }

    public static void markDirty(
        StorySyncService service,
        UUID playerId,
        boolean onServerThread
    ) {
        markPlayerDirty(service, playerId, onServerThread);
    }

    public static void markWorldDirty(
        StorySyncService service,
        Collection<UUID> onlinePlayers,
        boolean onServerThread
    ) {
        service.markWorldDirty(onlinePlayers, () -> onServerThread);
    }

    public static void flush(
        StorySyncService service,
        Map<UUID, Runnable> senders,
        Predicate<UUID> online,
        boolean onServerThread
    ) {
        service.flush(
            playerId -> senders.get(playerId),
            online,
            () -> onServerThread
        );
    }

    public static void sendNow(
        StorySyncService service,
        UUID playerId,
        Runnable send,
        boolean onServerThread
    ) {
        service.sendNow(playerId, () -> send, () -> onServerThread);
    }

    public static void sendNow(
        StorySyncService service,
        UUID playerId,
        Supplier<Runnable> delivery,
        boolean onServerThread
    ) {
        service.sendNow(playerId, delivery, () -> onServerThread);
    }

    public static Set<UUID> dirtyTargets(StorySyncService service) {
        return service.dirtyTargets();
    }
}
