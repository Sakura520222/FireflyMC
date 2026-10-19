package firefly520.fireflymc.client.story;

import firefly520.fireflymc.network.StoryProgressSnapshotPayload;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Client-side, read-only mirror of the latest authoritative story progress.
 *
 * <p>The payload itself is immutable. A single volatile reference is replaced by
 * one assignment, so a reader observes either the complete previous generation
 * or the complete next generation. UI is intentionally outside this task.</p>
 */
public final class ClientStoryState {
    public static final StoryProgressSnapshotPayload EMPTY = new StoryProgressSnapshotPayload(
        Set.of(),
        Set.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Set.of(),
        Set.of(),
        Optional.empty(),
        "0".repeat(64)
    );

    private static volatile StoryProgressSnapshotPayload snapshot = EMPTY;

    private ClientStoryState() {
    }

    public static StoryProgressSnapshotPayload snapshot() {
        return snapshot;
    }

    /**
     * Atomically replaces the mirror. Call only from the client game thread via
     * {@code IPayloadContext#enqueueWork}; no other component may install state.
     */
    public static void install(StoryProgressSnapshotPayload payload) {
        snapshot = java.util.Objects.requireNonNull(payload, "payload");
    }

    public static void clear() {
        snapshot = EMPTY;
    }
}
