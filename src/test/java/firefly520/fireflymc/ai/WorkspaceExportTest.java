package firefly520.fireflymc.ai;

import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.workspace.*;
import firefly520.fireflymc.ai.workspace.WorkspaceExportService.*;
import firefly520.fireflymc.client.ai.ExportReceiver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceExportTest {
    @TempDir Path directory;
    @Test void hiddenCompassArtifactCannotBypassNativePolicyThroughPrivateZipAndOriginalStaysSigned() throws Exception {
        var actor = Actor.player(UUID.randomUUID(), true, true, 0);
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor);
            var plan = new firefly520.fireflymc.ai.integration.exploration.CompassSearchPlan(1, UUID.randomUUID(), actor.owner(),
                    workspace.metadata().serverInstanceId(), workspace.primaryConversationId(), workspace.conversation().privacyEpoch(),
                    UUID.randomUUID(), UUID.randomUUID(), 1, System.currentTimeMillis() + 60000,
                    firefly520.fireflymc.ai.integration.exploration.CompassSearchPlan.Kind.STRUCTURE, "minecraft:village_plains", "MAIN_HAND",
                    "explorerscompass:explorerscompass", firefly520.fireflymc.ai.storage.AiJson.digest("native-location-bearing-item"),
                    "minecraft:overworld", 173002, 64, -89374, firefly520.fireflymc.ai.storage.AiJson.digest("config"), 0, false,
                    new firefly520.fireflymc.ai.integration.exploration.CompassSearchBudget(512, 16, 5000), "").signed();
            var path = workspace.artifact(plan.id()); firefly520.fireflymc.ai.storage.AiJson.write(path, plan);
            var indexed = new com.google.gson.JsonObject(); indexed.addProperty("planId", plan.id().toString()); indexed.addProperty("hash", plan.hash());
            workspace.conversation().append(List.of(new Draft(plan.taskId(), "compass_search_plan", null, indexed, null)));
            var exported = new WorkspaceExportService(workspace).prepare(actor, UUID.randomUUID());
            try (var zip = new ZipFile(workspace.directory().resolve("exports").resolve(exported.id() + ".zip").toFile())) {
                var text = new String(zip.getInputStream(zip.getEntry("artifacts/" + plan.id() + ".json")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                var projected = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
                assertTrue(projected.get("coordinatesHidden").getAsBoolean()); assertTrue(projected.get("exportProjectionOnly").getAsBoolean());
                assertFalse(text.contains("173002")); assertFalse(text.contains("-89374"));
                assertFalse(projected.has("itemFingerprint")); assertFalse(projected.has("hash"));
            }
            var original = firefly520.fireflymc.ai.storage.AiJson.GSON.fromJson(Files.readString(path), plan.getClass());
            assertTrue(original.intact()); assertEquals(plan, original);
        }
    }
    @Test void nativeJournalSnapshotsAreCompleteOwnerOnlyAndCannotChooseArbitraryZipPaths() throws Exception {
        Actor owner = Actor.player(UUID.randomUUID(), true, true, 0), stranger = Actor.player(UUID.randomUUID(), true, true, 4);
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner);
            String travel = "[{\"revision\":1,\"state\":\"RECOVERY_REQUIRED\"},{\"revision\":2,\"state\":\"FAILED\"}]";
            String maid = "[{\"revision\":1,\"state\":\"RECONCILED_CURRENT_STATE\"}]";
            var service = new WorkspaceExportService(workspace, null, null, Map.of("travel-journal.json", () -> travel, "maid-control-journal.json", () -> maid));
            assertThrows(SecurityException.class, () -> service.prepare(stranger, UUID.randomUUID()));
            assertThrows(IllegalArgumentException.class, () -> new WorkspaceExportService(workspace, null, null, Map.of("../private.json", () -> travel)));
            var manifest = service.prepare(owner, UUID.randomUUID());
            try (var zip = new ZipFile(workspace.directory().resolve("exports").resolve(manifest.id() + ".zip").toFile())) {
                assertEquals(travel, new String(zip.getInputStream(zip.getEntry("travel-journal.json")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                assertEquals(maid, new String(zip.getInputStream(zip.getEntry("maid-control-journal.json")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
    }
    @Test void completePrivateExportIncludesFluidGrantsWithoutConfirmationTokensOrOtherPlayers() throws Exception {
        Actor actor = Actor.player(UUID.randomUUID(), true, true, 0), other = Actor.player(UUID.randomUUID(), true, true, 0);
        try (var manager = new WorkspaceManager(directory.resolve("server"))) {
            var workspace = manager.getWorkspace(actor);
            var grants = new firefly520.fireflymc.ai.material.MaterialSourceGrants(workspace, () -> 1000,
                    firefly520.fireflymc.ai.material.MaterialSourceGrants.Domain.FLUIDS);
            var source = new firefly520.fireflymc.ai.material.MaterialSourceGrants.Candidate(
                    firefly520.fireflymc.ai.material.MaterialSourceGrants.Kind.BACKPACK_ITEM, "fluid-item|inventory/0|" + UUID.randomUUID() + "|minecraft:overworld",
                    "private selected tank", List.of(), "host", true, true);
            var accepted = grants.preview(actor, source); grants.confirm(actor, accepted.id(), accepted.digest(), source);
            var pending = grants.preview(actor, source); manager.getWorkspace(other).saveArtifact(Map.of("private", "stranger-content"));
            var service = new WorkspaceExportService(workspace); var manifest = service.prepare(actor, UUID.randomUUID());
            try (ZipFile zip = new ZipFile(workspace.directory().resolve("exports").resolve(manifest.id() + ".zip").toFile())) {
                String exported = new String(zip.getInputStream(zip.getEntry("fluid-sources.json")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(exported.contains("private selected tank")); assertFalse(exported.contains(pending.id().toString()));
                assertFalse(exported.contains("stranger-content")); assertFalse(exported.contains("expiresAt"));
            }
        }
    }
    @Test void privateMaterialExportIncludesLosslessEncodingInstructionsAndEveryEvent() throws Exception {
        Actor actor = Actor.player(UUID.randomUUID(), true, true, 0);
        try (var manager = new WorkspaceManager(directory.resolve("server"))) {
            var workspace = manager.getWorkspace(actor);
            String events = "[{\"kind\":\"material_operation_delta\",\"data\":{\"privateEvidence\":\"retained verbatim\"}}]";
            var service = new WorkspaceExportService(workspace, () -> events); var manifest = service.prepare(actor, UUID.randomUUID());
            try (ZipFile zip = new ZipFile(workspace.directory().resolve("exports").resolve(manifest.id() + ".zip").toFile())) {
                assertEquals(events, new String(zip.getInputStream(zip.getEntry("material-journal.json")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                String format = new String(zip.getInputStream(zip.getEntry("material-journal-format.txt")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(format.contains("baseRevision")); assertTrue(format.contains("No historical result is discarded"));
            }
        }
    }
    @Test void mixedFluidExportPreservesBothJournalsOnlyForAuthenticatedOwner() throws Exception {
        Actor actor = Actor.player(UUID.randomUUID(), true, true, 0), other = Actor.player(UUID.randomUUID(), true, true, 4);
        try (var manager = new WorkspaceManager(directory.resolve("server"))) {
            var workspace = manager.getWorkspace(actor);
            String journals = "{\"fluids\":[{\"witness\":\"exact-native-result\",\"amount\":600}],\"mixed\":[{\"phase\":\"RETURNING\"}]}";
            var service = new WorkspaceExportService(workspace, () -> "[]", () -> journals);
            assertThrows(SecurityException.class, () -> service.prepare(other, UUID.randomUUID()));
            var manifest = service.prepare(actor, UUID.randomUUID());
            try (var zip = new ZipFile(workspace.directory().resolve("exports").resolve(manifest.id() + ".zip").toFile())) {
                assertEquals(journals, new String(zip.getInputStream(zip.getEntry("mixed-fluid-journals.json")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
    }
    @Test void completePrivateExportResumesAfterDisconnectAndRejectsOtherOwnersWithoutTruncatingHistory() throws Exception {
        Actor actor = Actor.player(UUID.randomUUID(), true, true, 0);
        Actor other = Actor.player(UUID.randomUUID(), true, true, 4);
        try (var manager = new WorkspaceManager(directory.resolve("server"))) {
            var workspace = manager.getWorkspace(actor);
            var otherWorkspace = manager.getWorkspace(other);
            otherWorkspace.saveArtifact(Map.of("private", "other-player-secret"));
            byte[] noise = new byte[60000]; new Random(4).nextBytes(noise);
            UUID artifact = workspace.saveArtifact(Map.of("complete", Base64.getEncoder().encodeToString(noise)));
            workspace.conversation().append(List.of(Draft.message(null, ModelMessage.text("user", "early complete message"), null)));
            Files.writeString(workspace.directory().resolve("approvals.json"), "{\"token\":\"must-not-export\"}");
            Files.writeString(workspace.directory().resolve("old-secret.json"), "leave unrelated file");
            var service = new WorkspaceExportService(workspace);
            UUID request = UUID.randomUUID();
            Manifest manifest = service.prepare(actor, request);
            assertTrue(manifest.length() > 8192);
            assertEquals(manifest, service.prepare(actor, request));
            assertThrows(SecurityException.class, () -> service.chunk(other, request, 0));
            assertThrows(java.io.IOException.class, () -> new WorkspaceExportService(otherWorkspace).manifest(other, request));
            Path client = directory.resolve("client");
            var receiver = new ExportReceiver(client, manifest, manifest.serverInstanceId().toString(), actor.owner());
            Chunk first = service.chunk(actor, request, 0);
            receiver.accept(first);
            receiver.accept(first);
            assertEquals(first.nextOffset(), receiver.offset());
            receiver = new ExportReceiver(client, manifest, manifest.serverInstanceId().toString(), actor.owner());
            assertEquals(first.nextOffset(), receiver.offset());
            while (receiver.offset() < manifest.length()) receiver.accept(service.chunk(actor, request, receiver.offset()));
            assertEquals(manifest.sha256(), WorkspaceExportService.digest(receiver.completedPath()));
            try (ZipFile zip = new ZipFile(receiver.completedPath().toFile())) {
                String timeline = new String(zip.getInputStream(zip.getEntry("timeline.jsonl")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(timeline.contains("early complete message"));
                assertFalse(timeline.contains("other-player-secret"));
                assertNotNull(zip.getEntry("artifacts/" + artifact + ".json"));
                assertNull(zip.getEntry("approvals.json"));
                assertNull(zip.getEntry("old-secret.json"));
            }
            assertEquals(1, workspace.conversation().revision());
            assertFalse(Files.exists(client.resolve("pending.json")));
        }
    }

    @Test void downloadsRejectWrongIdentityOutOfOrderAndCorruptedPayloads() throws Exception {
        UUID id = UUID.randomUUID(), server = UUID.randomUUID();
        String owner = UUID.randomUUID().toString();
        Manifest manifest = new Manifest(1, id, server, owner, UUID.randomUUID(), 1, 3,
                firefly520.fireflymc.ai.storage.AiJson.digest("abc"), 1);
        assertThrows(java.io.IOException.class, () -> new ExportReceiver(directory, manifest, server.toString(), UUID.randomUUID().toString()));
        var receiver = new ExportReceiver(directory, manifest, server.toString(), owner);
        assertThrows(java.io.IOException.class, () -> receiver.accept(new Chunk(id, 1, 3, 3, "YmM=")));
        assertThrows(java.io.IOException.class, () -> receiver.accept(new Chunk(id, 0, 3, 3, "YmFk")));
        assertFalse(Files.exists(directory.resolve(id + ".zip")));
    }
}
