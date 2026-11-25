package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.blueprint.BlueprintDocument.Position;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.util.*;

/** The owner's selected working region is a durable main-conversation fact, never a world-write approval. */
public final class BuildRegionStore {
    public static final int MAX_AXIS = 128;
    public static final long MAX_VOLUME = 16_384;
    public record Bounds(Position minimum, Position maximum, long volume) {
        public boolean contains(Position p) { return p.x() >= minimum.x() && p.x() <= maximum.x()
                && p.y() >= minimum.y() && p.y() <= maximum.y() && p.z() >= minimum.z() && p.z() <= maximum.z(); }
    }
    public record Selection(int version, UUID server, UUID conversation, String owner, long privacyEpoch,
                            long revision, UUID requestId, String dimension, Position first, Position second,
                            boolean confirmed, String hash) {
        public boolean active() { return first != null || second != null; }
        public boolean complete() { return first != null && second != null; }
        public Bounds bounds() { return BuildRegionStore.bounds(first, second); }
    }
    private final Workspace workspace;
    private final long epoch;
    public BuildRegionStore(Workspace workspace) { this.workspace = workspace; epoch = workspace.conversation().privacyEpoch(); }
    public Optional<Selection> current(Actor actor) {
        synchronized (workspace.conversation()) {
            access(actor);
            return workspace.conversation().latestEvent("build_region").map(event -> verified(AiJson.GSON.fromJson(event.data(), Selection.class)));
        }
    }
    public Selection save(Actor actor, UUID request, long expectedRevision, long expectedEpoch, String dimension,
                          Position first, Position second, boolean confirmed) throws IOException {
        synchronized (workspace.conversation()) {
            access(actor);
            if (request == null || expectedEpoch != epoch) throw new IllegalStateException("BUILD_REGION_PRIVACY_CHANGED");
            validate(dimension, first, second, confirmed);
            for (var event : workspace.conversation().getSnapshot(epoch).events()) {
                if (!event.kind().equals("build_region")) continue;
                var old = verified(AiJson.GSON.fromJson(event.data(), Selection.class));
                if (!old.requestId().equals(request)) continue;
                if (!old.dimension().equals(dimension) || !Objects.equals(old.first(), first) || !Objects.equals(old.second(), second)
                        || old.confirmed() != confirmed || old.revision() != expectedRevision + 1)
                    throw new SecurityException("BUILD_REGION_REQUEST_REUSED");
                return old;
            }
            long previous = current(actor).map(Selection::revision).orElse(0L);
            if (previous != expectedRevision) throw new IllegalStateException("BUILD_REGION_REVISION_CHANGED");
            var unsigned = new Selection(1, workspace.metadata().serverInstanceId(), workspace.primaryConversationId(), workspace.owner(),
                    epoch, Math.addExact(previous, 1), request, dimension, first, second, confirmed, "");
            var accepted = withHash(unsigned, AiJson.digest(AiJson.canonical(unsigned)));
            workspace.conversation().append(List.of(new Draft(null, "build_region", null, AiJson.GSON.toJsonTree(accepted).getAsJsonObject(), null)));
            return accepted;
        }
    }
    public void requireContains(Actor actor, String dimension, BlueprintDocument document) {
        var selected = current(actor);
        if (selected.isEmpty() || !selected.get().active()) return;
        var region = selected.get();
        if (!region.confirmed()) throw new IllegalStateException("BUILD_REGION_NOT_CONFIRMED");
        if (!region.dimension().equals(dimension)) throw new IllegalStateException("BUILD_REGION_DIMENSION_CHANGED");
        Bounds bounds = region.bounds();
        if (document.blocks().stream().anyMatch(block -> !bounds.contains(document.origin().add(block.position()))))
            throw new IllegalStateException("BLUEPRINT_OUTSIDE_SELECTED_REGION");
    }
    private Selection verified(Selection selection) {
        if (selection == null || selection.version() != 1 || !workspace.owner().equals(selection.owner())
                || !workspace.metadata().serverInstanceId().equals(selection.server()) || !workspace.primaryConversationId().equals(selection.conversation())
                || selection.privacyEpoch() != epoch || selection.revision() < 1 || selection.requestId() == null
                || !AiJson.digest(AiJson.canonical(withHash(selection, ""))).equals(selection.hash()))
            throw new IllegalStateException("BUILD_REGION_RECORD_INVALID");
        validate(selection.dimension(), selection.first(), selection.second(), selection.confirmed()); return selection;
    }
    private void access(Actor actor) {
        actor.requireAccess(workspace.owner());
        if (workspace.conversation().privacyEpoch() != epoch) throw new IllegalStateException("BUILD_REGION_PRIVACY_CHANGED");
    }
    public static Bounds bounds(Position first, Position second) {
        if (first == null || second == null) throw new IllegalStateException("BUILD_REGION_INCOMPLETE");
        position(first); position(second);
        var min = new Position(Math.min(first.x(), second.x()), Math.min(first.y(), second.y()), Math.min(first.z(), second.z()));
        var max = new Position(Math.max(first.x(), second.x()), Math.max(first.y(), second.y()), Math.max(first.z(), second.z()));
        long x = (long) max.x() - min.x() + 1, y = (long) max.y() - min.y() + 1, z = (long) max.z() - min.z() + 1;
        if (Math.max(Math.max(x, y), z) > MAX_AXIS || x * y * z > MAX_VOLUME) throw new IllegalArgumentException("BUILD_REGION_SIZE_LIMIT");
        return new Bounds(min, max, x * y * z);
    }
    private static void validate(String dimension, Position first, Position second, boolean confirmed) {
        if (dimension == null || (first == null && second == null ? !dimension.isEmpty() || confirmed
                : !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))) throw new IllegalArgumentException("BUILD_REGION_DIMENSION_INVALID");
        if (first != null) position(first); if (second != null) position(second);
        if (confirmed || first != null && second != null) bounds(first, second);
    }
    private static void position(Position p) {
        if (Math.abs((long) p.x()) > BlueprintDocument.MAX_COORDINATE || Math.abs((long) p.y()) > BlueprintDocument.MAX_COORDINATE
                || Math.abs((long) p.z()) > BlueprintDocument.MAX_COORDINATE) throw new IllegalArgumentException("BUILD_REGION_COORDINATE_LIMIT");
    }
    private static Selection withHash(Selection s, String hash) {
        return new Selection(s.version(), s.server(), s.conversation(), s.owner(), s.privacyEpoch(), s.revision(), s.requestId(),
                s.dimension(), s.first(), s.second(), s.confirmed(), hash);
    }
}
