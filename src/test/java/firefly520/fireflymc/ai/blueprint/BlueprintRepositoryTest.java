package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BlueprintRepositoryTest {
    @TempDir Path directory;
    private final Actor a = Actor.player(UUID.randomUUID(), true, true, 0), b = Actor.player(UUID.randomUUID(), true, true, 0);
    private static BlueprintDocument document(String block) {
        return new BlueprintDocument(1, "小屋", "test", "1", "", new BlueprintDocument.Position(0, 64, 0),
                List.of(new BlueprintDocument.Block(new BlueprintDocument.Position(0, 0, 0), new BlueprintDocument.State(block, Map.of()))));
    }
    @Test void revisionsRemainPrivatePersistentAndOldVersionsStayReadable() throws Exception {
        BlueprintRepository.Entry first, second;
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(a); var repository = new BlueprintRepository(workspace);
            first = repository.save(a, UUID.randomUUID(), null, 0, "", null, "minecraft:overworld", document("minecraft:oak_planks")).entry();
            second = repository.save(a, UUID.randomUUID(), first.id(), first.revision(), first.hash(), null, "minecraft:overworld", document("minecraft:spruce_planks")).entry();
            assertEquals(2, second.revision()); assertFalse(repository.current(a, first));
            assertEquals("minecraft:oak_planks", repository.read(a, first.id(), 1, first.hash()).document().blocks().getFirst().state().blockId());
            assertThrows(SecurityException.class, () -> repository.read(b, first.id(), 1, first.hash()));
            assertTrue(new BlueprintRepository(manager.getWorkspace(b)).latest(b).isEmpty());
            assertThrows(IllegalStateException.class, () -> repository.save(a, UUID.randomUUID(), first.id(), 1, first.hash(), null, "minecraft:overworld", document("minecraft:stone")));
        }
        try (var manager = new WorkspaceManager(directory)) {
            assertEquals(second.hash(), new BlueprintRepository(manager.getWorkspace(a)).latest(a).getFirst().hash());
        }
    }
    @Test void repeatedUploadCommitIsIdempotentAndContentCannotChangeUnderItsRequestId() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var repository = new BlueprintRepository(manager.getWorkspace(a)); UUID request = UUID.randomUUID();
            var saved = repository.save(a, request, null, 0, "", null, "minecraft:overworld", document("minecraft:stone"));
            assertEquals(saved, repository.save(a, request, null, 0, "", null, "minecraft:overworld", document("minecraft:stone")));
            assertEquals(1, repository.history(a).size());
            assertThrows(SecurityException.class, () -> repository.save(a, request, null, 0, "", null, "minecraft:overworld", document("minecraft:dirt")));
        }
    }
    @Test void privacyErasureInvalidatesPendingRepositoryAndRemovesOnlyOwnedBlueprintFiles() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(a); var repository = new BlueprintRepository(workspace);
            var saved = repository.save(a, UUID.randomUUID(), null, 0, "", null, "minecraft:overworld", document("minecraft:stone"));
            Path foreign = workspace.directory().resolve("blueprints/keep-original.litematic"); Files.writeString(foreign, "original");
            var privacy = new WorkspacePrivacyService(System::currentTimeMillis, ignored -> true);
            var preview = privacy.preview(a, workspace); privacy.confirm(a, workspace, preview.token());
            assertTrue(Files.exists(foreign));
            assertFalse(Files.exists(workspace.directory().resolve("blueprints/" + saved.entry().storageId() + ".json")));
            assertThrows(IllegalStateException.class, () -> repository.save(a, UUID.randomUUID(), null, 0, "", null, "minecraft:overworld", document("minecraft:dirt")));
            assertTrue(new BlueprintRepository(workspace).latest(a).isEmpty());
        }
    }
    @Test void changingStoredContentCannotReuseItsOldApprovalHash() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(a); var repository = new BlueprintRepository(workspace);
            var saved = repository.save(a, UUID.randomUUID(), null, 0, "", null, "minecraft:overworld", document("minecraft:stone"));
            var forged = new BlueprintRepository.Revision(saved.entry(), document("minecraft:diamond_block"));
            AiJson.write(workspace.directory().resolve("blueprints/" + saved.entry().storageId() + ".json"), forged);
            assertThrows(java.io.IOException.class, () -> repository.read(a, saved.entry().id(), 1, saved.entry().hash()));
        }
    }
    @Test void nativePreviewEditInvalidatesReviewBeforeTheReplacementUploadArrives() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var repository = new BlueprintRepository(manager.getWorkspace(a));
            var entry = repository.save(a, UUID.randomUUID(), null, 0, "", null, "minecraft:overworld", document("minecraft:stone")).entry();
            String original = repository.reviewStamp(a, entry.id(), entry.hash());
            var lease = repository.reviewLease(a, entry.id(), entry.hash(), original); lease.check();
            String changed = repository.invalidateReview(a, entry.id(), entry.hash());
            assertThrows(IllegalStateException.class, lease::check);
            assertNotEquals(original, changed); assertEquals(changed, repository.reviewStamp(a, entry.id(), entry.hash()));
            assertEquals(entry.hash(), repository.read(a, entry.id(), entry.revision(), entry.hash()).entry().hash());
            assertThrows(SecurityException.class, () -> repository.invalidateReview(b, entry.id(), entry.hash()));
        }
    }
}
