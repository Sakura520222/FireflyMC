package firefly520.fireflymc.ai.integration.equipment;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CarrierRepositoryTest {
    @TempDir Path directory;
    private final Actor a = Actor.player(UUID.randomUUID(), true, true, 0);
    private final Actor b = Actor.player(UUID.randomUUID(), true, true, 0);
    private CarrierRepository.Choice choice() {
        return new CarrierRepository.Choice(UUID.randomUUID(), "minecraft:overworld", "touhou_little_maid:idle");
    }
    @Test void privateSelectionSurvivesRestartButDoesNotCreateMaterialAuthorization() throws Exception {
        var expected = choice();
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(a);
            var repository = new CarrierRepository(workspace, () -> 1000);
            var preview = repository.preview(a, expected);
            assertThrows(SecurityException.class, () -> repository.confirm(b, preview.id(), preview.digest(), expected));
            var selected = repository.confirm(a, preview.id(), preview.digest(), expected);
            assertEquals(expected, selected.choice());
            assertThrows(IllegalStateException.class, () -> repository.confirm(a, preview.id(), preview.digest(), expected));
            assertEquals(selected, new CarrierRepository(workspace, () -> 1000).selection(a));
            assertFalse(Files.exists(workspace.directory().resolve("materials/grants.json")));
        }
        try (var manager = new WorkspaceManager(directory)) {
            assertEquals(expected, new CarrierRepository(manager.getWorkspace(a), () -> 2000).selection(a).choice());
            assertNull(new CarrierRepository(manager.getWorkspace(b), () -> 2000).selection(b).choice());
        }
    }
    @Test void changedTaskDimensionExpiryAndStaleClearCannotApproveAnotherCarrier() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var now = new AtomicLong(1000);
            var repository = new CarrierRepository(manager.getWorkspace(a), now::get);
            var original = choice(); var preview = repository.preview(a, original);
            assertThrows(IllegalStateException.class, () -> repository.confirm(a, preview.id(), preview.digest(),
                    new CarrierRepository.Choice(original.maidId(), original.dimension(), "touhou_little_maid:farm")));
            assertThrows(IllegalStateException.class, () -> repository.confirm(a, preview.id(), preview.digest(),
                    new CarrierRepository.Choice(original.maidId(), "minecraft:the_nether", original.nativeTaskId())));
            now.set(preview.expiresAt() + 1);
            assertThrows(IllegalStateException.class, () -> repository.confirm(a, preview.id(), preview.digest(), original));
            var fresh = repository.preview(a, original);
            var selection = repository.confirm(a, fresh.id(), fresh.digest(), original);
            assertThrows(IllegalStateException.class, () -> repository.clear(a, selection.revision() - 1));
            assertNull(repository.clear(a, selection.revision()).choice());
        }
    }
    @Test void inFlightConfirmationCannotRecreatePrivateSelectionAfterErase() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(a);
            var repository = new CarrierRepository(workspace, () -> 1000);
            var original = choice(); var preview = repository.preview(a, original);
            workspace.conversation().erase(workspace.conversation().revision());
            assertThrows(IllegalStateException.class, () -> repository.confirm(a, preview.id(), preview.digest(), original));
            assertFalse(Files.exists(workspace.directory().resolve("carrier.json")));
        }
    }
}
