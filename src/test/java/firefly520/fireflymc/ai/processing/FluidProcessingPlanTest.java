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

class FluidProcessingPlanTest {
    @TempDir Path temp;
    static final MaterialStack FILLED = new MaterialStack("{\"id\":\"minecraft:water_bucket\",\"count\":1}", 1);
    static FluidProcessingPlan plan(UUID server, UUID conversation, String owner, long privacy) {
        var stock = stock("player", "player/slot0", BUCKET, 1, 1);
        var tank = source("backend", new FluidTankSnapshot.Tank(2000, WATER));
        var evaluation = new FluidProcessingAdapters.Evaluation("mechanism", FluidProcessingAdapters.BUCKET_FILL,
                "minecraft@1.21.1/neoforge-fluid-bucket@1", List.of(BUCKET), List.of(WATER), List.of(FILLED), List.of());
        return new FluidProcessingPlan(1, UUID.randomUUID(), server, conversation, owner, UUID.randomUUID(), UUID.randomUUID(), privacy,
                "minecraft:overworld", 1, "", FluidProcessingAdapters.BUCKET_FILL, "minecraft:water", "minecraft:bucket", 1,
                "", "", List.of(BUCKET), List.of(evaluation), List.of(stock), List.of(new MaterialLedger.Need(stock.key(), 1)),
                List.of(new FluidProcessingPlan.Claim(UUID.randomUUID(), tank, WATER)), List.of(), "").signed();
    }
    private static void manifest(WorkspaceManager.Workspace workspace, FluidProcessingPlan plan) throws Exception {
        var data = new JsonObject(); data.addProperty("planId", plan.id().toString()); data.addProperty("hash", plan.hash());
        workspace.conversation().append(List.of(new Draft(plan.taskId(), "fluid_processing_plan", null, data, null)));
    }
    @Test void serializedHashBindsAllIdentityGenerationAndExactNativeEvidence() {
        var plan = plan(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString(), 0);
        var roundTrip = AiJson.GSON.fromJson(AiJson.GSON.toJson(plan), FluidProcessingPlan.class);
        assertEquals(plan, roundTrip); assertTrue(roundTrip.intact()); assertEquals("MATERIALS_ONLY@1", plan.policy());
        for (String field : List.of("owner", "serverInstanceId", "conversationId", "taskId", "executionEpoch")) {
            var changed = AiJson.GSON.toJsonTree(plan).getAsJsonObject(); changed.addProperty(field, UUID.randomUUID().toString());
            assertFalse(AiJson.GSON.fromJson(changed, FluidProcessingPlan.class).intact(), field);
        }
        for (String field : List.of("privacyEpoch", "recipeGeneration")) {
            var changed = AiJson.GSON.toJsonTree(plan).getAsJsonObject(); changed.addProperty(field, 99);
            assertFalse(AiJson.GSON.fromJson(changed, FluidProcessingPlan.class).intact(), field);
        }
        assertThrows(UnsupportedOperationException.class, () -> plan.inputs().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.evaluations().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.evaluations().getFirst().consumedItems().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.evaluations().getFirst().consumedFluids().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.evaluations().getFirst().producedItems().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.sources().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.needs().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.fluidClaims().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.fluidClaims().getFirst().source().snapshot().tanks().clear());
        assertEquals(List.of(WATER), plan.fluidInputs()); assertEquals(List.of(FILLED), plan.itemOutputs());
    }
    @Test void constructorRejectsMissingRawInputsAndDuplicateOperationIds() {
        var plan = plan(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString(), 0);
        var changed = AiJson.GSON.toJsonTree(plan).getAsJsonObject(); changed.getAsJsonArray("fluidClaims").remove(0);
        assertThrows(RuntimeException.class, () -> AiJson.GSON.fromJson(changed, FluidProcessingPlan.class));
        var duplicate = AiJson.GSON.toJsonTree(plan).getAsJsonObject();
        duplicate.getAsJsonArray("fluidClaims").add(duplicate.getAsJsonArray("fluidClaims").get(0).deepCopy());
        assertThrows(RuntimeException.class, () -> AiJson.GSON.fromJson(duplicate, FluidProcessingPlan.class));
    }
    @Test void itemCountCannotBeDetachedFromExactSourceWithdrawals() {
        var plan = plan(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString(), 0);
        var changed = AiJson.GSON.toJsonTree(plan).getAsJsonObject();
        changed.getAsJsonArray("needs").get(0).getAsJsonObject().addProperty("count", 2);
        assertThrows(RuntimeException.class, () -> AiJson.GSON.fromJson(changed, FluidProcessingPlan.class));
    }
    @Test void ownedLoadRequiresAuditAndRejectsCrossOwnerChangedFileAndPrivacyErasure() throws Exception {
        try (var manager = new WorkspaceManager(temp)) {
            var actor = Actor.player(UUID.randomUUID(), true, true, 0); var workspace = manager.getWorkspace(actor);
            var plan = plan(workspace.metadata().serverInstanceId(), workspace.primaryConversationId(), actor.owner(), 0);
            AiJson.write(workspace.artifact(plan.id()), plan);
            assertThrows(IllegalArgumentException.class, () -> FluidProcessingPlanner.readOwned(workspace, actor, plan.id(), plan.hash()));
            manifest(workspace, plan);
            assertEquals(plan, FluidProcessingPlanner.readOwned(workspace, actor, plan.id(), plan.hash()));
            assertThrows(SecurityException.class, () -> FluidProcessingPlanner.readOwned(workspace,
                    Actor.player(UUID.randomUUID(), true, true, 4), plan.id(), plan.hash()));
            var changed = AiJson.GSON.toJsonTree(plan).getAsJsonObject(); changed.addProperty("recipeGeneration", 2);
            AiJson.write(workspace.artifact(plan.id()), changed);
            assertThrows(SecurityException.class, () -> FluidProcessingPlanner.readOwned(workspace, actor, plan.id(), plan.hash()));
            AiJson.write(workspace.artifact(plan.id()), plan);
            var wrongWorld = new WorkspaceManager.Workspace(new WorkspaceManager.Metadata(1, UUID.randomUUID(), actor.owner(), workspace.primaryConversationId()),
                    workspace.directory(), workspace.conversation());
            assertThrows(SecurityException.class, () -> FluidProcessingPlanner.readOwned(wrongWorld, actor, plan.id(), plan.hash()));
            workspace.conversation().erase(workspace.conversation().revision());
            assertThrows(IllegalArgumentException.class, () -> FluidProcessingPlanner.readOwned(workspace, actor, plan.id(), plan.hash()));
        }
    }
    @Test void outputAmountMustHaveACompleteFixedDestinationEvenIfNativeInputsAreValid() {
        var tank = source("out", new FluidTankSnapshot.Tank(2000, FluidAmount.EMPTY));
        var stock = stock("player", "slot0", FILLED, 1, 1);
        var evaluation = new FluidProcessingAdapters.Evaluation("mechanism", FluidProcessingAdapters.BUCKET_EMPTY, "native@1",
                List.of(FILLED), List.of(), List.of(BUCKET), List.of(WATER));
        assertThrows(IllegalArgumentException.class, () -> new FluidProcessingPlan(1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID().toString(), UUID.randomUUID(), UUID.randomUUID(), 0, "minecraft:overworld", 1, "", FluidProcessingAdapters.BUCKET_EMPTY,
                "", "minecraft:water_bucket", 1, "", "", List.of(FILLED), List.of(evaluation), List.of(stock),
                List.of(new MaterialLedger.Need(stock.key(), 1)), List.of(), List.of(new FluidProcessingPlan.OutputTarget(0, tank, WATER.withAmount(500))), ""));
    }
}
