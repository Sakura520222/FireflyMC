package firefly520.fireflymc.ai.blueprint;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.blueprint.BlueprintDocument.*;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class BuildRegionProposalStoreTest {
    @TempDir Path directory;
    private final Actor owner = Actor.player(UUID.randomUUID(), true, true, 0);
    private static BlueprintDocument document() {
        return new BlueprintDocument(1, "candidate", "test", "1", "", new Position(0, 64, 0), List.of(
                new Block(new Position(-2, 0, -1), new State("minecraft:oak_planks", Map.of())),
                new Block(new Position(2, 2, 1), new State("minecraft:air", Map.of()))));
    }
    private static JsonObject survey() { var value = new JsonObject(); value.addProperty("worldDigest", "test-world-observation"); return value; }

    @Test void proposingDoesNotSelectARegionAndInvalidatesAnOlderConstructionApproval() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var repository = new BlueprintRepository(workspace);
            var saved = repository.save(owner, UUID.randomUUID(), null, 0, "", null, "minecraft:overworld", document());
            var lease = repository.reviewLease(owner, saved.entry().id(), saved.entry().hash(), repository.reviewStamp(owner, saved.entry().id(), saved.entry().hash()));
            var store = new BuildRegionProposalStore(workspace);
            var proposal = store.save(owner, saved, BuildRegionService.blueprintBounds(saved.document()), survey());
            assertTrue(new BuildRegionStore(workspace).current(owner).isEmpty());
            assertThrows(IllegalStateException.class, lease::check);
            assertThrows(IllegalStateException.class, () -> store.requireConfirmedBlueprint(owner, saved.entry()));
            assertThrows(SecurityException.class, () -> store.require(Actor.player(UUID.randomUUID(), true, true, 4), proposal.id(), proposal.hash()));
            assertFalse(store.view().get("constructionApproved").getAsBoolean());
        }
    }

    @Test void confirmationBindsTheExactBlueprintAndRegionAndNewVersionsRequireAnotherReview() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var repository = new BlueprintRepository(workspace);
            var saved = repository.save(owner, UUID.randomUUID(), null, 0, "", null, "minecraft:overworld", document());
            var store = new BuildRegionProposalStore(workspace); var bounds = BuildRegionService.blueprintBounds(saved.document());
            var proposal = store.save(owner, saved, bounds, survey());
            var regions = new BuildRegionStore(workspace);
            var selected = regions.save(owner, UUID.randomUUID(), 0, 0, "minecraft:overworld", bounds.minimum(), bounds.maximum(), true);
            store.confirmed(owner, proposal, selected);
            assertDoesNotThrow(() -> store.requireConfirmedBlueprint(owner, saved.entry()));
            assertTrue(store.view().get("regionConfirmed").getAsBoolean());
            var next = repository.save(owner, UUID.randomUUID(), saved.entry().id(), 1, saved.entry().hash(), null, "minecraft:overworld", document());
            assertThrows(IllegalStateException.class, () -> store.requireConfirmedBlueprint(owner, next.entry()));
            assertThrows(IllegalStateException.class, () -> store.requireConfirmedBlueprint(owner, saved.entry()));
            assertFalse(store.view().get("regionConfirmed").getAsBoolean());
            regions.save(owner, UUID.randomUUID(), selected.revision(), 0, "", null, null, false);
            assertThrows(IllegalStateException.class, () -> store.requireConfirmedBlueprint(owner, saved.entry()));
        }
    }

    @Test void privacyErasureRetiresTheCandidateAndRejectsLateConfirmation() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var repository = new BlueprintRepository(workspace);
            var saved = repository.save(owner, UUID.randomUUID(), null, 0, "", null, "minecraft:overworld", document());
            var store = new BuildRegionProposalStore(workspace); var proposal = store.save(owner, saved, BuildRegionService.blueprintBounds(saved.document()), survey());
            workspace.conversation().erase(workspace.conversation().revision());
            assertTrue(new BuildRegionProposalStore(workspace).latest().isEmpty());
            assertThrows(IllegalStateException.class, () -> store.require(owner, proposal.id(), proposal.hash()));
        }
    }

    @Test void defaultPositionsPutTheWholeBlueprintInFrontWithoutManualCornerCoordinates() {
        Position player = new Position(100, 70, 200); var shape = document();
        for (int[] direction : List.of(new int[]{1, 0}, new int[]{-1, 0}, new int[]{0, 1}, new int[]{0, -1})) {
            var origin = BuildRegionService.suggestedOrigin(shape, player, direction[0], direction[1]);
            var located = new BlueprintDocument(1, shape.name(), shape.source(), shape.sourceVersion(), shape.sourceDigest(), origin, shape.blocks());
            var bounds = BuildRegionService.blueprintBounds(located);
            assertEquals(player.y() - 1, bounds.minimum().y());
            if (direction[0] > 0) assertEquals(player.x() + 4, bounds.minimum().x());
            if (direction[0] < 0) assertEquals(player.x() - 4, bounds.maximum().x());
            if (direction[1] > 0) assertEquals(player.z() + 4, bounds.minimum().z());
            if (direction[1] < 0) assertEquals(player.z() - 4, bounds.maximum().z());
        }
    }
}
