package firefly520.fireflymc.story;

import firefly520.fireflymc.story.engine.SyncSink;

/** Test-only bridge for older loader tests that were written in a different package. */
public final class StoryEngineTestAccess {
    private StoryEngineTestAccess() {}

    public static StoryEngine instance() {
        return StoryEngine.instance();
    }

    public static boolean consumePendingReconcile(StoryEngine engine) {
        return engine.consumePendingReconcile();
    }

    public static boolean hasPendingReconcile(StoryEngine engine) {
        return engine.hasPendingReconcile();
    }

    public static void markAllPlayersForReconcile(StoryEngine engine) {
        engine.markAllPlayersForReconcile();
    }

    public static void setSyncSink(StoryEngine engine, SyncSink sink) {
        engine.setSyncSink(sink);
    }

    public static void onServerStarted(StoryEngine engine, boolean storySyncInstalled) {
        engine.onServerStarted(storySyncInstalled);
    }
}
