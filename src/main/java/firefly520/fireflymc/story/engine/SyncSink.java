package firefly520.fireflymc.story.engine;

import net.minecraft.server.level.ServerPlayer;

/** Narrow dirty-snapshot sink so the engine does not depend on a payload registration. */
@FunctionalInterface
public interface SyncSink {
    void markDirty(ServerPlayer player);
}
