package firefly520.fireflymc.ai.integration.exploration;

import com.google.gson.*;
import firefly520.fireflymc.ai.storage.AiJson;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Public/model/export projections only; none of these fixtures claims native generation or disk-save validation. */
class CompassPrivacyProjectionTest {
    private final UUID id = UUID.randomUUID(), task = UUID.randomUUID(), epoch = UUID.randomUUID();
    private final String owner = UUID.randomUUID().toString();
    private CompassPreparationJournal.Operation preparation() {
        return new CompassPreparationJournal.Operation(1, id, owner, task, UUID.randomUUID(), UUID.randomUUID(), 0, epoch, 1,
                AiJson.digest("private-search-commitment"), AiJson.digest("private-preparation-commitment"), "minecraft:overworld", "minecraft:village_plains",
                6400, -8000, 512, new CompassPreparationBudget(2, 5000), 5, true,
                List.of(new CompassPreparationJournal.Candidate(UUID.randomUUID(), 400, -500, CompassPreparationJournal.State.RECOVERY_REQUIRED,
                        "CANCELLED", true, AiJson.digest("private-canonical-proof"))));
    }
    private CompassSearchJournal.Operation search(boolean visible) {
        var before = new CompassSearchJournal.Observation(AiJson.digest("private-native-stack-before"), "SEARCHING", 0, false, visible);
        var after = new CompassSearchJournal.Observation(AiJson.digest("private-native-stack-with-found-coordinates"), "FOUND", 1, false, visible);
        var result = new CompassSearchJournal.ResultEvidence(true, visible, visible ? 6408 : null, visible ? -7992 : null, null,
                "SERVER_BOUNDED_NATIVE_STRUCTURE_START", 2, 2, 1000, 0);
        return new CompassSearchJournal.Operation(1, id, owner, task, AiJson.digest("plan-with-private-native-stack"), epoch,
                CompassSearchJournal.State.RECOVERY_REQUIRED, "CANCELLED", 4, true, true, CompassSearchJournal.State.FOUND,
                6, 4, before, after, result, AiJson.digest("private-start-proof"), AiJson.digest("private-terminal-proof"));
    }
    @Test void hiddenPreparationKeepsBudgetsAndRealStatesWithoutCoordinatesOrEnumerableCommitments() {
        var original = preparation(); String privateBefore = AiJson.canonical(original);
        var result = CompassPrivacyProjection.preparation(original, false);
        assertAbsent(result, Set.of("x", "z", "originX", "originZ", "attempt", "saveProof", "searchHash", "preparationHash", "executionEpoch"));
        assertTrue(result.get("coordinatesHidden").getAsBoolean()); assertEquals(512, result.get("radiusBlocks").getAsInt());
        assertEquals(2, result.getAsJsonObject("budget").get("maximumChunks").getAsInt());
        assertEquals("RECOVERY_REQUIRED", result.getAsJsonArray("candidates").get(0).getAsJsonObject().get("state").getAsString());
        assertTrue(result.getAsJsonArray("candidates").get(0).getAsJsonObject().get("saveConfirmed").getAsBoolean());
        assertEquals(privateBefore, AiJson.canonical(original)); assertTrue(privateBefore.contains("\"x\":400"));
    }
    @Test void nativeHiddenAndLaterServerHiddenBothRemoveResultPositionsAndNativeFingerprints() {
        var alreadyHidden = search(false); assertFalse(CompassPrivacyProjection.coordinatesVisible(alreadyHidden));
        var historicalVisible = search(true); String privateBefore = AiJson.canonical(historicalVisible);
        var exported = CompassPrivacyProjection.search(historicalVisible, false);
        assertAbsent(exported, Set.of("x", "z", "sampleY", "itemFingerprint", "startSave", "terminalSave", "planHash", "executionEpoch"));
        assertFalse(exported.getAsJsonObject("result").get("coordinatesVisible").getAsBoolean());
        var displayed = CompassPrivacyProjection.result(CompassSearchService.view(historicalVisible), false);
        assertAbsent(displayed, Set.of("x", "z", "sampleY")); assertFalse(displayed.getAsJsonObject("result").get("coordinatesVisible").getAsBoolean());
        assertEquals(privateBefore, AiJson.canonical(historicalVisible)); assertEquals(6408, historicalVisible.result().x());
    }
    @Test void everyExportHistoryVersionAndSignedRecoveryArtifactCanBeRedactedWithoutChangingPrivateEvidence() {
        var original = preparation(); var history = List.of(original, original);
        var projected = new JsonArray(); history.forEach(value -> projected.add(CompassPrivacyProjection.preparation(value, false)));
        assertEquals(2, projected.size()); assertAbsent(projected, Set.of("x", "z", "attempt", "saveProof", "searchHash", "preparationHash"));
        var allowed = CompassPrivacyProjection.preparation(original, true);
        assertEquals(400, allowed.getAsJsonArray("candidates").get(0).getAsJsonObject().get("x").getAsInt());
        assertEquals(original.candidates().getFirst().saveProof(), allowed.getAsJsonArray("candidates").get(0).getAsJsonObject().get("saveProof").getAsString());
        assertFalse(allowed.get("coordinatesHidden").getAsBoolean());
        var evidence = search(false);
        var review = new CompassReconciliation(1, UUID.randomUUID(), id, owner, task, original.serverInstanceId(), original.conversationId(),
                0, epoch, evidence, original, "").signed();
        String raw = AiJson.canonical(review);
        var artifact = CompassPrivacyProjection.artifact(AiJson.GSON.toJsonTree(review).getAsJsonObject(), "compass_reconcile_preview", false);
        assertAbsent(artifact, Set.of("x", "z", "originX", "originZ", "attempt", "saveProof", "searchHash", "preparationHash", "itemFingerprint", "hash", "executionEpoch"));
        assertTrue(artifact.get("exportProjectionOnly").getAsBoolean()); assertEquals(raw, AiJson.canonical(review));
    }
    private static void assertAbsent(JsonElement value, Set<String> fields) {
        if (value.isJsonObject()) for (var entry : value.getAsJsonObject().entrySet()) {
            assertFalse(fields.contains(entry.getKey()), "leaked hidden field: " + entry.getKey()); assertAbsent(entry.getValue(), fields);
        } else if (value.isJsonArray()) value.getAsJsonArray().forEach(element -> assertAbsent(element, fields));
    }
}
