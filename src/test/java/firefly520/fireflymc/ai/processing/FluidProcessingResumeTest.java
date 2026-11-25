package firefly520.fireflymc.ai.processing;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static firefly520.fireflymc.ai.processing.FluidProcessingPlannerTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Approval/custody protocol tests; live holder and native inventory acceptance use dedicated scenarios. */
class FluidProcessingResumeTest {
    @TempDir Path directory;
    final Actor actor = Actor.player(UUID.randomUUID(), true, true, 0);

    private final class Fixture implements AutoCloseable {
        final WorkspaceManager manager = new WorkspaceManager(directory);
        final WorkspaceManager.Workspace workspace = manager.getWorkspace(actor);
        final FluidProcessingPlan plan = FluidProcessingPlanTest.plan(workspace.metadata().serverInstanceId(),
                workspace.primaryConversationId(), actor.owner(), workspace.conversation().privacyEpoch());
        MaterialLedger items = new MaterialLedger(directory);
        FluidLedger fluids = new FluidLedger(directory);
        MixedResourceJournal mixed = new MixedResourceJournal(workspace, items, fluids);
        Fixture(boolean itemSaved, boolean fluidSaved, int amount) throws Exception {
            this(itemSaved, fluidSaved, amount, true);
        }
        Fixture(boolean itemSaved, boolean fluidSaved, int amount, boolean takeItem) throws Exception {
            mixed.create(actor, FluidProcessingResume.definition(plan));
            items.reserve(actor, plan.taskId(), plan.id(), plan.hash(), plan.sources(), plan.needs());
            if (takeItem) {
                String itemWitness = UUID.randomUUID().toString();
                items.beginExtraction(actor, plan.id(), 0, BUCKET, 1, itemWitness);
                items.extracted(actor, plan.id(), BUCKET, 0);
                if (itemSaved) items.nativeSaved(actor, plan.id(), "extract", itemWitness, AiJson.digest("positive-item-save"));
            }
            var claim = plan.fluidClaims().getFirst();
            fluids.reserve(actor, plan.taskId(), claim.operationId(), plan.hash(), claim.source(), claim.amount());
            String fluidWitness = UUID.randomUUID().toString();
            fluids.begin(actor, claim.operationId(), fluidWitness, claim.source(), claim.amount(), false);
            var after = new FluidTankSnapshot(List.of(new FluidTankSnapshot.Tank(2000, WATER.withAmount(1000 - amount))));
            fluids.observed(actor, claim.operationId(), fluidWitness,
                    new FluidInventoryService.Outcome(true, true, WATER.withAmount(amount), after, "", null));
            if (fluidSaved) fluids.nativeSaved(actor, claim.operationId(), fluidWitness, AiJson.digest("positive-fluid-save"));
        }
        List<FluidLedger.Operation> custody() { return plan.fluidClaims().stream().map(claim -> fluids.get(actor, claim.operationId())).toList(); }
        FluidProcessingResume review() {
            return new FluidProcessingResume(1, UUID.randomUUID(), plan, UUID.randomUUID(), 9, plan.dimension(),
                    mixed.get(actor, plan.id()).revision(), items.get(actor, plan.id()), custody(), plan.evaluations(), List.of(), "").signed();
        }
        void reload() throws Exception {
            items = new MaterialLedger(directory); fluids = new FluidLedger(directory);
            mixed = new MixedResourceJournal(workspace, items, fluids);
        }
        void index(FluidProcessingResume review) throws Exception {
            var data = new JsonObject(); data.addProperty("reviewId", review.id().toString()); data.addProperty("hash", review.hash());
            workspace.conversation().append(List.of(new Draft(plan.taskId(), "fluid_resume_preview", null, data, null)));
        }
        @Override public void close() throws Exception { manager.close(); }
    }
    @Test void freshApprovalBindsTheOriginalOperationAndExactPositiveCustody() throws Exception {
        try (var f = new Fixture(true, true, 1000)) {
            var review = f.review();
            assertTrue(review.intact()); assertEquals(f.plan.id(), review.operationId());
            assertFalse(review.requiresExtraction());
            assertEquals(f.plan.taskId(), review.taskId()); assertEquals(f.plan.hash(), review.originalPlanHash());
            assertNotEquals(f.plan.executionEpoch(), review.executionEpoch());
            assertNotEquals(f.plan.recipeGeneration(), review.recipeGeneration());
            assertEquals(f.items.get(actor, f.plan.id()).revision(), review.itemRevision());
            assertEquals(f.custody().getFirst().revision(), review.fluidRevisions().get(f.custody().getFirst().id()));
            assertDoesNotThrow(() -> review.requireCurrent(f.mixed.get(actor, f.plan.id()), f.items.get(actor, f.plan.id()), f.custody()));
            var roundTrip = AiJson.GSON.fromJson(AiJson.GSON.toJson(review), FluidProcessingResume.class);
            assertEquals(review, roundTrip); assertTrue(roundTrip.intact());
            assertThrows(UnsupportedOperationException.class, () -> review.fluidCustody().clear());
            assertThrows(UnsupportedOperationException.class, () -> review.evaluations().clear());
            assertThrows(UnsupportedOperationException.class, () -> review.outputTargets().clear());
            for (String field : List.of("executionEpoch", "id")) {
                var json = AiJson.GSON.toJsonTree(review).getAsJsonObject(); json.addProperty(field, UUID.randomUUID().toString());
                assertFalse(AiJson.GSON.fromJson(json, FluidProcessingResume.class).intact(), field);
            }
            for (String field : List.of("recipeGeneration", "operationRevision")) {
                var json = AiJson.GSON.toJsonTree(review).getAsJsonObject(); json.addProperty(field, 100);
                assertFalse(AiJson.GSON.fromJson(json, FluidProcessingResume.class).intact(), field);
            }
            var moved = AiJson.GSON.toJsonTree(review).getAsJsonObject(); moved.addProperty("dimension", "minecraft:the_nether");
            assertFalse(AiJson.GSON.fromJson(moved, FluidProcessingResume.class).intact());
            var reviewedAfterMoving = AiJson.GSON.fromJson(moved, FluidProcessingResume.class).signed();
            assertEquals(f.plan.dimension(), reviewedAfterMoving.originalPlan().dimension());
            assertEquals("minecraft:the_nether", reviewedAfterMoving.dimension());
            assertEquals(f.mixed.get(actor, f.plan.id()).definition(), FluidProcessingResume.definition(reviewedAfterMoving.originalPlan()));
        }
    }
    @Test void matchingQuantitiesWithoutEveryPositiveNativeSaveCannotCreateResumeApproval() throws Exception {
        try (var f = new Fixture(false, false, 1000)) {
            assertEquals(1000, f.custody().getFirst().escrowMb());
            assertEquals(List.of(BUCKET), f.items.get(actor, f.plan.id()).escrow());
            assertEquals("FLUID_RESUME_POSITIVE_INPUT_SAVE_REQUIRED", assertThrows(IllegalStateException.class, f::review).getMessage());
            var item = f.items.get(actor, f.plan.id()); var itemSave = item.nativeSaves().getFirst();
            f.items.nativeSaved(actor, item.operationId(), itemSave.kind(), itemSave.witness(), AiJson.digest("late-item-save"));
            assertEquals("FLUID_RESUME_POSITIVE_INPUT_SAVE_REQUIRED", assertThrows(IllegalStateException.class, f::review).getMessage());
            var fluid = f.custody().getFirst();
            f.fluids.nativeSaved(actor, fluid.id(), fluid.effects().getFirst().witness(), AiJson.digest("late-fluid-save"));
            assertDoesNotThrow(f::review);
        }
    }
    @Test void savedPartialCustodyCanPreviewOnlyTheUnfulfilledOriginalClaimsWithoutProducingAnything() throws Exception {
        try (var f = new Fixture(true, true, 500)) {
            assertEquals(500, f.custody().getFirst().escrowMb());
            var review = f.review(); assertTrue(review.requiresExtraction());
            assertEquals(f.plan.fluidClaims().getFirst().amount().millibuckets() - 500,
                    review.fluidCustody().getFirst().requested().millibuckets() - review.fluidCustody().getFirst().extracted());
            review.requireCurrent(f.mixed.get(actor, f.plan.id()), f.items.get(actor, f.plan.id()), f.custody());
            assertThrows(IllegalStateException.class, () -> FluidProcessingResume.requireExactInputs(f.plan, f.items.get(actor, f.plan.id()), f.custody()));
            assertThrows(IllegalStateException.class, () -> f.mixed.prepareCommit(actor, f.plan.id(), f.items, f.fluids));
            assertEquals(1, f.custody().getFirst().effects().size());
            assertTrue(f.items.get(actor, f.plan.id()).products().isEmpty());
        }
    }
    @Test void reopeningSavedPartialInputsRestoresCustodyOnlyAndOnlyNewSavedRemainderPermitsCommit() throws Exception {
        try (var f = new Fixture(true, true, 500)) {
            var old = f.review(); var definition = f.mixed.get(actor, f.plan.id()).definition();
            f.reload();
            var recovery = f.mixed.recoveryReview(actor, f.plan.id(), f.items, f.fluids);
            f.mixed.reconcile(actor, f.plan.id(), recovery.revision(), recovery.reviewHash(), f.items, f.fluids);
            assertEquals(MixedResourceJournal.ReturnMode.NONE, f.mixed.get(actor, f.plan.id()).returnMode());
            assertEquals(MixedResourceJournal.State.INPUTS_PENDING, f.mixed.get(actor, f.plan.id()).state());
            assertTrue(f.items.get(actor, f.plan.id()).products().isEmpty()); assertEquals(0, f.custody().getFirst().consumedMb());
            assertThrows(IllegalStateException.class, () -> old.requireCurrent(f.mixed.get(actor, f.plan.id()), f.items.get(actor, f.plan.id()), f.custody()));
            var approved = f.review(); assertTrue(approved.requiresExtraction());
            var claim = f.plan.fluidClaims().getFirst(); var original = claim.source();
            var current = new FluidInventoryService.Source(original.grantId(), original.revision(), original.backend(), original.authorizationFingerprint(),
                    new FluidTankSnapshot(List.of(new FluidTankSnapshot.Tank(2000, WATER.withAmount(500)))));
            String witness = UUID.randomUUID().toString();
            assertEquals("MIXED_INPUTS_CLOSED", assertThrows(IllegalStateException.class, () ->
                    f.fluids.begin(actor, claim.operationId(), witness, current, WATER.withAmount(500), false)).getMessage());
            f.mixed.approveRemainingInputs(actor, f.plan.id(), approved.operationRevision(), approved.hash());
            assertThrows(IllegalStateException.class, () -> f.mixed.approveRemainingInputs(actor, f.plan.id(), approved.operationRevision(), approved.hash()));
            f.fluids.begin(actor, claim.operationId(), witness, current, WATER.withAmount(500), false);
            f.fluids.observed(actor, claim.operationId(), witness, new FluidInventoryService.Outcome(true, true, WATER.withAmount(500),
                    new FluidTankSnapshot(List.of(new FluidTankSnapshot.Tank(2000, FluidAmount.EMPTY))), "", null));
            assertThrows(IllegalStateException.class, f::review, "the new extraction also needs its actual save proof");
            f.fluids.nativeSaved(actor, claim.operationId(), witness, AiJson.digest("saved-only-the-missing-500-mB"));
            assertThrows(IllegalStateException.class, () -> approved.requireCurrent(f.mixed.get(actor, f.plan.id()), f.items.get(actor, f.plan.id()), f.custody()));
            FluidProcessingResume.requireExactInputs(f.plan, f.items.get(actor, f.plan.id()), f.custody());
            assertFalse(f.review().requiresExtraction());
            f.mixed.prepareCommit(actor, f.plan.id(), f.items, f.fluids); f.mixed.commit(actor, f.plan.id(), f.items, f.fluids);
            assertEquals(definition, f.mixed.get(actor, f.plan.id()).definition());
            assertEquals(2, f.custody().getFirst().effects().size()); assertEquals(1000, f.custody().getFirst().consumedMb());
            assertEquals(List.of(FluidProcessingPlanTest.FILLED), f.items.get(actor, f.plan.id()).products());
            assertThrows(IllegalStateException.class, () -> f.mixed.prepareCommit(actor, f.plan.id(), f.items, f.fluids));
        }
    }
    @Test void untouchedItemReservationIsShownAsMissingAndCannotBeCreditedAsCustody() throws Exception {
        try (var f = new Fixture(false, true, 500, false)) {
            var review = f.review(); assertTrue(review.requiresExtraction());
            assertTrue(review.itemCustody().escrow().isEmpty());
            assertEquals(1, review.itemCustody().claims().getFirst().remaining());
            assertThrows(IllegalStateException.class, () -> FluidProcessingResume.requireExactInputs(f.plan, review.itemCustody(), review.fluidCustody()));
            f.reload(); var recovery = f.mixed.recoveryReview(actor, f.plan.id(), f.items, f.fluids);
            f.mixed.reconcile(actor, f.plan.id(), recovery.revision(), recovery.reviewHash(), f.items, f.fluids);
            assertTrue(f.review().requiresExtraction()); assertTrue(f.items.get(actor, f.plan.id()).escrow().isEmpty());
        }
    }
    @Test void missingPositiveProofForPartialInputCannotOpenAResumeOrReconciliation() throws Exception {
        try (var f = new Fixture(true, false, 500)) {
            assertEquals("FLUID_RESUME_POSITIVE_INPUT_SAVE_REQUIRED", assertThrows(IllegalStateException.class, f::review).getMessage());
            f.reload(); assertThrows(IllegalStateException.class, () -> f.mixed.recoveryReview(actor, f.plan.id(), f.items, f.fluids));
            assertEquals(MixedResourceJournal.State.RECOVERY_REQUIRED, f.mixed.get(actor, f.plan.id()).state());
        }
    }
    @Test void explicitRemainderApprovalCannotReopenCancelledInputsOrAuthorizeAnotherOwner() throws Exception {
        try (var f = new Fixture(true, true, 500)) {
            var approved = f.review(); var before = f.mixed.get(actor, f.plan.id());
            var other = Actor.player(UUID.randomUUID(), true, true, 0);
            assertThrows(SecurityException.class, () -> f.mixed.approveRemainingInputs(other, f.plan.id(), before.revision(), approved.hash()));
            assertEquals(before, f.mixed.get(actor, f.plan.id()));
            f.mixed.approveRemainingInputs(actor, f.plan.id(), before.revision(), approved.hash());
            f.mixed.beginReturn(actor, f.plan.id());
            var returning = f.mixed.get(actor, f.plan.id());
            assertThrows(IllegalStateException.class, () -> f.mixed.approveRemainingInputs(actor, f.plan.id(), returning.revision(), approved.hash()));
            assertThrows(IllegalStateException.class, f::review);
            assertEquals(500, f.custody().getFirst().escrowMb());
            assertEquals(1, f.custody().getFirst().effects().size());
            assertEquals(MixedResourceJournal.ReturnMode.INPUTS, f.mixed.get(actor, f.plan.id()).returnMode());
            assertTrue(f.items.get(actor, f.plan.id()).products().stream().noneMatch(item -> item.equals(FluidProcessingPlanTest.FILLED)));
        }
    }
    @Test void unknownIntentAndCancellationCannotBeClearedByAResumeReview() throws Exception {
        try (var f = new Fixture(true, true, 1000)) {
            var approved = f.review();
            f.mixed.requireRecovery(actor, f.plan.id(), "UNKNOWN_NATIVE_EFFECT");
            assertEquals("FLUID_RESUME_RECONCILE_REQUIRED", assertThrows(IllegalStateException.class, () ->
                    approved.requireCurrent(f.mixed.get(actor, f.plan.id()), f.items.get(actor, f.plan.id()), f.custody())).getMessage());
            var review = f.mixed.recoveryReview(actor, f.plan.id(), f.items, f.fluids);
            f.mixed.reconcile(actor, f.plan.id(), review.revision(), review.reviewHash(), f.items, f.fluids);
            f.mixed.beginReturn(actor, f.plan.id());
            assertEquals("FLUID_RESUME_CANCELLED_COMPLETE_APPROVED_RETURN", assertThrows(IllegalStateException.class, () ->
                    approved.requireCurrent(f.mixed.get(actor, f.plan.id()), f.items.get(actor, f.plan.id()), f.custody())).getMessage());
            assertEquals(MixedResourceJournal.ReturnMode.INPUTS, f.mixed.get(actor, f.plan.id()).returnMode());
            assertTrue(f.items.get(actor, f.plan.id()).products().stream().noneMatch(item -> item.equals(FluidProcessingPlanTest.FILLED)));
        }
    }
    @Test void reconciliationRestoresCustodyAndFreshApprovalAllowsOneCommitWithoutAdditionalExtraction() throws Exception {
        try (var f = new Fixture(true, true, 1000)) {
            var obsolete = f.review(); var originalDefinition = f.mixed.get(actor, f.plan.id()).definition();
            int itemExtractions = f.items.get(actor, f.plan.id()).nativeSaves().size(), fluidExtractions = f.custody().getFirst().effects().size();
            f.reload();
            assertThrows(IllegalStateException.class, () -> FluidProcessingResume.requirePending(f.plan, f.mixed.get(actor, f.plan.id())));
            var recovery = f.mixed.recoveryReview(actor, f.plan.id(), f.items, f.fluids);
            f.mixed.reconcile(actor, f.plan.id(), recovery.revision(), recovery.reviewHash(), f.items, f.fluids);
            assertTrue(f.items.get(actor, f.plan.id()).products().isEmpty()); assertEquals(0, f.custody().getFirst().consumedMb());
            assertThrows(IllegalStateException.class, () -> obsolete.requireCurrent(f.mixed.get(actor, f.plan.id()), f.items.get(actor, f.plan.id()), f.custody()));
            var approved = f.review();
            approved.requireCurrent(f.mixed.get(actor, f.plan.id()), f.items.get(actor, f.plan.id()), f.custody());
            f.mixed.prepareCommit(actor, f.plan.id(), f.items, f.fluids); f.mixed.commit(actor, f.plan.id(), f.items, f.fluids);
            assertEquals(originalDefinition, f.mixed.get(actor, f.plan.id()).definition());
            assertEquals(List.of(FluidProcessingPlanTest.FILLED), f.items.get(actor, f.plan.id()).products());
            assertEquals(1000, f.custody().getFirst().consumedMb());
            assertEquals(itemExtractions, f.items.get(actor, f.plan.id()).nativeSaves().size());
            assertEquals(fluidExtractions, f.custody().getFirst().effects().size());
            assertEquals(1, f.custody().getFirst().recipeDebits().size());
            assertThrows(IllegalStateException.class, () -> approved.requireCurrent(f.mixed.get(actor, f.plan.id()), f.items.get(actor, f.plan.id()), f.custody()));
            assertThrows(IllegalStateException.class, () -> f.mixed.prepareCommit(actor, f.plan.id(), f.items, f.fluids));
            assertEquals(1, f.custody().getFirst().recipeDebits().size());
        }
    }
    @Test void recipeReloadMayUseNewGenerationOnlyWhenEveryExactEvaluationIsUnchanged() throws Exception {
        try (var f = new Fixture(true, true, 1000)) {
            var original = f.plan.evaluations().getFirst();
            for (String field : List.of("consumedFluids", "producedItems", "adapterVersion")) {
                var changed = AiJson.GSON.toJsonTree(original).getAsJsonObject();
                if (field.equals("adapterVersion")) changed.addProperty(field, "unverified-adapter@2");
                else if (field.equals("consumedFluids")) changed.getAsJsonArray(field).get(0).getAsJsonObject().addProperty("millibuckets", 999);
                else changed.getAsJsonArray(field).get(0).getAsJsonObject().addProperty("itemJson", "{\"id\":\"minecraft:water_bucket\",\"components\":{\"custom\":1}}");
                var evaluated = AiJson.GSON.fromJson(changed, FluidProcessingAdapters.Evaluation.class);
                assertEquals("FLUID_RESUME_RECIPE_CHANGED_RETURN_AND_REPLAN", assertThrows(IllegalStateException.class, () ->
                        FluidProcessingResume.requireEvaluations(f.plan, List.of(evaluated))).getMessage(), field);
            }
            assertDoesNotThrow(() -> FluidProcessingResume.requireEvaluations(f.plan, f.plan.evaluations()));
        }
    }
    @Test void outputAllocationsKeepExactIndexesComponentsAndCapacityWithoutCreditingOldDrains() {
        var tank = source("fresh-approved-target", new FluidTankSnapshot.Tank(2000, FluidAmount.EMPTY));
        var targets = List.of(new FluidProcessingPlan.OutputTarget(0, tank, WATER), new FluidProcessingPlan.OutputTarget(1, tank, WATER));
        assertDoesNotThrow(() -> FluidProcessingResume.requireTargets(List.of(WATER, WATER), targets));
        assertThrows(IllegalArgumentException.class, () -> FluidProcessingResume.requireTargets(List.of(WATER, WATER), List.of(targets.getFirst())));
        assertThrows(IllegalArgumentException.class, () -> FluidProcessingResume.requireTargets(List.of(WATER, LAVA), targets));
        var full = source("full", new FluidTankSnapshot.Tank(1000, WATER));
        assertThrows(IllegalArgumentException.class, () -> FluidProcessingResume.requireTargets(List.of(WATER),
                List.of(new FluidProcessingPlan.OutputTarget(0, full, WATER))));
    }
    @Test void persistedReviewRequiresManifestOwnerPrivacyAndTheNewExecutionEpoch() throws Exception {
        try (var f = new Fixture(true, true, 1000)) {
            var review = f.review(); AiJson.write(f.workspace.artifact(review.id()), review);
            assertThrows(IllegalArgumentException.class, () -> FluidProcessingService.readResume(f.workspace, actor, review.id(), review.hash(), review.executionEpoch()));
            f.index(review);
            assertEquals(review, FluidProcessingService.readResume(f.workspace, actor, review.id(), review.hash(), review.executionEpoch()));
            assertThrows(SecurityException.class, () -> FluidProcessingService.readResume(f.workspace,
                    Actor.player(UUID.randomUUID(), true, true, 0), review.id(), review.hash(), review.executionEpoch()));
            assertEquals("FLUID_RESUME_EXECUTION_EPOCH_CHANGED", assertThrows(IllegalStateException.class, () ->
                    FluidProcessingService.readResume(f.workspace, actor, review.id(), review.hash(), UUID.randomUUID())).getMessage());
            assertEquals(review, FluidProcessingService.readStoredResume(f.workspace, actor, review.id(), review.hash()),
                    "Historical resume references remain readable after restart; only execution requires a new epoch");
            var changed = AiJson.GSON.toJsonTree(review).getAsJsonObject(); changed.addProperty("recipeGeneration", review.recipeGeneration() + 1);
            AiJson.write(f.workspace.artifact(review.id()), changed);
            assertThrows(SecurityException.class, () -> FluidProcessingService.readResume(f.workspace, actor, review.id(), review.hash(), review.executionEpoch()));
            AiJson.write(f.workspace.artifact(review.id()), review);
            f.workspace.conversation().erase(f.workspace.conversation().revision());
            assertThrows(IllegalArgumentException.class, () -> FluidProcessingService.readResume(f.workspace, actor, review.id(), review.hash(), review.executionEpoch()));
        }
    }
    @Test void canonicalRuntimeBindingRejectsAnEvenResignedReplacement() throws Exception {
        try (var f = new Fixture(true, true, 1000)) {
            var review = f.review(); var binding = new FluidProcessingPlanner.ResumeBinding(review, null);
            var loaded = AiJson.GSON.fromJson(AiJson.GSON.toJson(review), FluidProcessingResume.class);
            assertThrows(SecurityException.class, () -> binding.requireOriginal(loaded));
            assertSame(review, binding.canonical(loaded));
            assertDoesNotThrow(() -> binding.requireOriginal(binding.canonical(loaded)));
            var changed = AiJson.GSON.toJsonTree(review).getAsJsonObject(); changed.addProperty("recipeGeneration", 100);
            var resigned = AiJson.GSON.fromJson(changed, FluidProcessingResume.class).signed();
            assertTrue(resigned.intact()); assertThrows(SecurityException.class, () -> binding.canonical(resigned));
        }
    }
}
