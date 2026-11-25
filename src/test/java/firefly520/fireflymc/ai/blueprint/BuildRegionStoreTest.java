package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.blueprint.BlueprintDocument.*;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildRegionStoreTest {
    @TempDir Path directory;
    final Actor a = Actor.player(UUID.randomUUID(), true, true, 0), b = Actor.player(UUID.randomUUID(), true, true, 4);
    final Position first = new Position(0, 64, 0), second = new Position(3, 66, 3);
    private static BlueprintDocument document(Position origin) {
        return new BlueprintDocument(1, "region test", "test", "1", "", origin,
                List.of(new Block(new Position(0, 0, 0), new State("minecraft:oak_planks", Map.of()))));
    }
    @Test void regionIsPrivatePersistentAndDuplicatesCannotRebindTheSameRequest() throws Exception {
        UUID request = UUID.randomUUID(); BuildRegionStore.Selection saved;
        try (var manager = new WorkspaceManager(directory)) {
            var store = new BuildRegionStore(manager.getWorkspace(a));
            saved = store.save(a, request, 0, 0, "minecraft:overworld", first, second, false);
            assertEquals(saved, store.save(a, request, 0, 0, "minecraft:overworld", first, second, false));
            assertThrows(SecurityException.class, () -> store.save(a, request, 0, 0, "minecraft:overworld", second, first, false));
            assertThrows(SecurityException.class, () -> store.current(b));
            assertTrue(new BuildRegionStore(manager.getWorkspace(b)).current(b).isEmpty());
            assertThrows(IllegalStateException.class, () -> store.save(a, UUID.randomUUID(), 0, 0, "minecraft:overworld", first, second, true));
        }
        try (var manager = new WorkspaceManager(directory)) { assertEquals(saved, new BuildRegionStore(manager.getWorkspace(a)).current(a).orElseThrow()); }
    }
    @Test void onlyConfirmedMatchingDimensionContainsTheWholeBlueprintAndConfirmationDoesNotGrantExecution() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(a); var store = new BuildRegionStore(workspace);
            store.save(a, UUID.randomUUID(), 0, 0, "minecraft:overworld", first, second, false);
            assertThrows(IllegalStateException.class, () -> store.requireContains(a, "minecraft:overworld", document(first)));
            store.save(a, UUID.randomUUID(), 1, 0, "minecraft:overworld", first, second, true);
            assertDoesNotThrow(() -> store.requireContains(a, "minecraft:overworld", document(second)));
            assertThrows(IllegalStateException.class, () -> store.requireContains(a, "minecraft:the_nether", document(first)));
            assertThrows(IllegalStateException.class, () -> store.requireContains(a, "minecraft:overworld", document(new Position(4, 64, 0))));
            assertFalse(BuildRegionService.view(workspace, store.current(a)).get("constructionApproved").getAsBoolean());
        }
    }
    @Test void changingOrClearingRegionInvalidatesExistingBlueprintApprovalWithoutChangingItsContents() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(a); var repository = new BlueprintRepository(workspace);
            var saved = repository.save(a, UUID.randomUUID(), null, 0, "", null, "minecraft:overworld", document(first));
            String stamp = repository.reviewStamp(a, saved.entry().id(), saved.entry().hash());
            var lease = repository.reviewLease(a, saved.entry().id(), saved.entry().hash(), stamp); lease.check();
            var store = new BuildRegionStore(workspace); store.save(a, UUID.randomUUID(), 0, 0, "minecraft:overworld", first, second, true);
            assertThrows(IllegalStateException.class, lease::check);
            assertNotEquals(stamp, repository.reviewStamp(a, saved.entry().id(), saved.entry().hash()));
            var next = repository.reviewLease(a, saved.entry().id(), saved.entry().hash(), repository.reviewStamp(a, saved.entry().id(), saved.entry().hash()));
            store.save(a, UUID.randomUUID(), 1, 0, "", null, null, false);
            assertThrows(IllegalStateException.class, next::check); assertEquals(saved, repository.read(a, saved.entry().id(), 1, saved.entry().hash()));
        }
    }
    @Test void boundsAreInclusiveOrderIndependentAndRejectOverflowOrUnboundedWork() {
        assertEquals(BuildRegionStore.bounds(first, second), BuildRegionStore.bounds(second, first));
        assertEquals(48, BuildRegionStore.bounds(first, second).volume());
        assertEquals(1, BuildRegionStore.bounds(first, first).volume());
        assertThrows(IllegalArgumentException.class, () -> BuildRegionStore.bounds(first, new Position(128, 64, 0)));
        assertThrows(IllegalArgumentException.class, () -> BuildRegionStore.bounds(new Position(0, 0, 0), new Position(31, 31, 31)));
        assertThrows(IllegalArgumentException.class, () -> BuildRegionStore.bounds(new Position(Integer.MIN_VALUE, 0, 0), first));
        assertThrows(IllegalStateException.class, () -> BuildRegionStore.bounds(first, null));
    }
    @Test void explicitPrivacyEraseClearsSelectionAndRejectsLateStoreWrites() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(a); var old = new BuildRegionStore(workspace);
            old.save(a, UUID.randomUUID(), 0, 0, "minecraft:overworld", first, second, false);
            assertTrue(workspace.conversation().regionSequence() > 0);
            workspace.conversation().erase(workspace.conversation().revision());
            assertEquals(0, workspace.conversation().regionSequence()); assertTrue(new BuildRegionStore(workspace).current(a).isEmpty());
            assertThrows(IllegalStateException.class, () -> old.save(a, UUID.randomUUID(), 1, 0, "minecraft:overworld", first, second, true));
        }
    }
}
