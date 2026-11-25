package firefly520.fireflymc.ai.material;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
class MaterialSourceGrantsTest {
    @TempDir Path directory;
    final Actor a = Actor.player(UUID.randomUUID(), true, true, 0), b = Actor.player(UUID.randomUUID(), true, true, 0);
    MaterialSourceGrants.Candidate candidate(UUID identity) {
        return new MaterialSourceGrants.Candidate(MaterialSourceGrants.Kind.BLOCK, "block|minecraft:overworld|0|up", "selected chest",
                List.of(new MaterialSourceGrants.Member("minecraft:overworld", 0, 0, 0, "minecraft:chest", identity)), "", true, true);
    }
    @Test void previewIsOwnerBoundOneUseAndRevokeInvalidatesExistingReferences() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var grants = new MaterialSourceGrants(manager.getWorkspace(a), () -> 1000);
            var preview = grants.preview(a, candidate(null));
            var actual = candidate(UUID.randomUUID());
            assertThrows(SecurityException.class, () -> grants.confirm(b, preview.id(), preview.digest(), actual));
            var grant = grants.confirm(a, preview.id(), preview.digest(), actual);
            assertThrows(IllegalStateException.class, () -> grants.confirm(a, preview.id(), preview.digest(), actual));
            assertEquals(grant, grants.require(a, grant.id(), grant.revision(), true));
            grants.revoke(a, grant.id());
            assertThrows(SecurityException.class, () -> grants.require(a, grant.id(), grant.revision(), true));
            assertTrue(new MaterialSourceGrants(manager.getWorkspace(a), () -> 1000).list(a).getFirst().revoked());
        }
    }
    @Test void containerReplacementOrExpiryRequiresAnotherReview() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var now = new AtomicLong(1000);
            var grants = new MaterialSourceGrants(manager.getWorkspace(a), now::get);
            var actual = candidate(UUID.randomUUID());
            var preview = grants.preview(a, actual);
            assertThrows(IllegalStateException.class, () -> grants.confirm(a, preview.id(), preview.digest(), candidate(UUID.randomUUID())));
            now.set(preview.expiresAt() + 1);
            assertThrows(IllegalStateException.class, () -> grants.confirm(a, preview.id(), preview.digest(), actual));
            assertTrue(grants.list(a).isEmpty());
        }
    }
    @Test void lateSourceConfirmationCannotRecreatePermissionsAfterPrivacyErasure() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(a);
            var old = new MaterialSourceGrants(workspace, () -> 1000);
            var preview = old.preview(a, candidate(null));
            workspace.conversation().erase(workspace.conversation().revision());
            assertThrows(IllegalStateException.class, () -> old.confirm(a, preview.id(), preview.digest(), candidate(UUID.randomUUID())));
            assertFalse(java.nio.file.Files.exists(workspace.directory().resolve("materials/grants.json")));
            assertTrue(new MaterialSourceGrants(workspace, () -> 1000).list(a).isEmpty());
        }
    }
    @Test void fluidAuthorizationIsIndependentFromItemsAndSurvivesInItsOwnPrivateStore() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(a);
            var items = new MaterialSourceGrants(workspace, () -> 1000);
            var fluids = new MaterialSourceGrants(workspace, () -> 1000, MaterialSourceGrants.Domain.FLUIDS);
            var source = candidate(UUID.randomUUID()); var preview = items.preview(a, source);
            var itemGrant = items.confirm(a, preview.id(), preview.digest(), source);
            assertTrue(fluids.list(a).isEmpty());
            assertThrows(SecurityException.class, () -> fluids.require(a, itemGrant.id(), itemGrant.revision(), true));
            var fluidPreview = fluids.preview(a, source);
            assertThrows(SecurityException.class, () -> fluids.confirm(b, fluidPreview.id(), fluidPreview.digest(), source));
            var fluidGrant = fluids.confirm(a, fluidPreview.id(), fluidPreview.digest(), source);
            fluids.revoke(a, fluidGrant.id());
            assertFalse(items.list(a).getFirst().revoked());
            assertTrue(new MaterialSourceGrants(workspace, () -> 1000, MaterialSourceGrants.Domain.FLUIDS).list(a).getFirst().revoked());
            assertTrue(java.nio.file.Files.exists(workspace.directory().resolve("fluids/grants.json")));
        }
    }
    @Test void explicitPrivateErasureClearsOnlyOwnedFluidGrantsAndFencesDelayedConfirmations() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(a);
            var fluids = new MaterialSourceGrants(workspace, () -> 1000, MaterialSourceGrants.Domain.FLUIDS);
            var source = candidate(UUID.randomUUID()); var preview = fluids.preview(a, source);
            fluids.confirm(a, preview.id(), preview.digest(), source);
            var delayed = fluids.preview(a, source);
            var untouched = workspace.directory().resolve("fluids/user-unrecognized.txt");
            java.nio.file.Files.writeString(untouched, "preserve user file");
            var privacy = new firefly520.fireflymc.ai.workspace.WorkspacePrivacyService(() -> 1000, ignored -> true);
            var deletion = privacy.preview(a, workspace); privacy.confirm(a, workspace, deletion.token());
            assertFalse(java.nio.file.Files.exists(workspace.directory().resolve("fluids/grants.json")));
            assertEquals("preserve user file", java.nio.file.Files.readString(untouched));
            assertThrows(IllegalStateException.class, () -> fluids.confirm(a, delayed.id(), delayed.digest(), source));
            assertTrue(new MaterialSourceGrants(workspace, () -> 1000, MaterialSourceGrants.Domain.FLUIDS).list(a).isEmpty());
        }
    }
}
