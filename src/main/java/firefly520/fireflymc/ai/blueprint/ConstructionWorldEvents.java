package firefly520.fireflymc.ai.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Installed only for an enabled dedicated AI service; disabled/integrated servers allocate no watches. */
public final class ConstructionWorldEvents {
    private static final Map<MinecraftServer, WorldMutationTracker> TRACKERS = new IdentityHashMap<>();
    private ConstructionWorldEvents() {}
    public static synchronized void install(MinecraftServer server, WorldMutationTracker tracker) {
        if (!server.isDedicatedServer()) throw new IllegalStateException("DEDICATED_SERVER_REQUIRED");
        if (TRACKERS.putIfAbsent(server, tracker) != null) throw new IllegalStateException("BUILD_TRACKER_ALREADY_INSTALLED");
    }
    public static synchronized void remove(MinecraftServer server, WorldMutationTracker tracker) { TRACKERS.remove(server, tracker); }
    /** Trusted main-thread consumers share the existing tracker; this never installs a fallback. */
    public static synchronized WorldMutationTracker tracker(MinecraftServer server) {
        if (!server.isDedicatedServer() || !server.isSameThread()) throw new IllegalStateException("DEDICATED_SERVER_THREAD_REQUIRED");
        var tracker = TRACKERS.get(server);
        if (tracker == null) throw new IllegalStateException("WORLD_MUTATION_TRACKER_UNAVAILABLE");
        return tracker;
    }
    public static void changed(ServerLevel level, BlockPos position) {
        WorldMutationTracker tracker;
        synchronized (ConstructionWorldEvents.class) { tracker = TRACKERS.get(level.getServer()); }
        if (tracker != null) tracker.changed(level, position.asLong());
    }
    public static void beforeChange(ServerLevel level, BlockPos position) {
        firefly520.fireflymc.ai.integration.trees.DynamicTreeNativeScope.beforeChange(level, position);
        WorldMutationTracker tracker;
        synchronized (ConstructionWorldEvents.class) { tracker = TRACKERS.get(level.getServer()); }
        if (tracker != null) tracker.beforeChange(level, position.asLong());
    }
}
