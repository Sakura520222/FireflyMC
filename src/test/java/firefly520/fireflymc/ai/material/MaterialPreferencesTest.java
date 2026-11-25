package firefly520.fireflymc.ai.material;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.workspace.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MaterialPreferencesTest {
    @TempDir Path root;
    private final Actor alice = Actor.player(UUID.randomUUID(), true, true, 0), bob = Actor.player(UUID.randomUUID(), true, true, 0);
    @Test void protectedItemsPersistPerWorkspaceAndCannotBeEditedByAnotherOwner() throws Exception {
        try (var manager = new WorkspaceManager(root)) {
            var workspace = manager.getWorkspace(alice); var preferences = new MaterialPreferences(workspace);
            assertFalse(preferences.protects("minecraft:diamond"));
            preferences.save(alice, 0, Set.of("minecraft:diamond", "minecraft:netherite_ingot"));
            assertTrue(new MaterialPreferences(workspace).protects("minecraft:diamond"));
            assertFalse(new MaterialPreferences(manager.getWorkspace(bob)).protects("minecraft:diamond"));
            assertThrows(SecurityException.class, () -> preferences.save(bob, 1, Set.of()));
            assertThrows(IllegalStateException.class, () -> preferences.save(alice, 0, Set.of()));
            assertEquals(1, preferences.view(alice).revision());
        }
    }
    @Test void explicitPrivacyErasureRemovesRulesAndFencesLateSavedSettings() throws Exception {
        try (var manager = new WorkspaceManager(root)) {
            var workspace = manager.getWorkspace(alice); var preferences = new MaterialPreferences(workspace);
            preferences.save(alice, 0, Set.of("minecraft:diamond"));
            var privacy = new WorkspacePrivacyService(System::currentTimeMillis, ignored -> true);
            var preview = privacy.preview(alice, workspace); privacy.confirm(alice, workspace, preview.token());
            assertThrows(IllegalStateException.class, () -> preferences.save(alice, 1, Set.of("minecraft:emerald")));
            assertTrue(new MaterialPreferences(workspace).view(alice).protectedItems().isEmpty());
        }
    }
}
