package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.material.MaterialStack;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.AgentTask;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RemovalPlanTest {
    @TempDir Path directory;
    final Actor owner = Actor.player(UUID.randomUUID(), true, true, 0), stranger = Actor.player(UUID.randomUUID(), true, true, 4);
    final BlueprintDocument.Position a = new BlueprintDocument.Position(1, 64, 1), b = new BlueprintDocument.Position(2, 64, 1);
    final BlueprintDocument.State stone = new BlueprintDocument.State("minecraft:stone", Map.of()), air = new BlueprintDocument.State("minecraft:air", Map.of());
    final MaterialStack hand = new MaterialStack("{\"id\":\"minecraft:iron_pickaxe\"}", 1);
    RemovalPlan plan(WorkspaceManager.Workspace workspace, UUID task, List<RemovalPlan.Step> steps) {
        return new RemovalPlan(1, UUID.randomUUID(), workspace.metadata().serverInstanceId(), workspace.primaryConversationId(), owner.owner(), task,
                UUID.randomUUID(), "minecraft:overworld", 3, RemovalJournal.Mode.DEMOLITION, null, "", 2, AiJson.digest("selected bounds"),
                hand, 0, List.of(new BuildJournal.Cell(a, stone, air), new BuildJournal.Cell(b, stone, air)), steps, "").signed();
    }
    List<RemovalPlan.Step> steps() {
        return List.of(new RemovalPlan.Step(UUID.randomUUID(), a, List.of(new BuildJournal.Cell(a, stone, air), new BuildJournal.Cell(b, stone, null)), List.of()),
                new RemovalPlan.Step(UUID.randomUUID(), b, List.of(new BuildJournal.Cell(b, stone, air), new BuildJournal.Cell(a, air, null)), List.of()));
    }
    @Test void signedPlanBindsCurrentToolRegionAndEveryIntermediateWorldState() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var plan = plan(manager.getWorkspace(owner), UUID.randomUUID(), steps()); assertTrue(plan.intact());
            var edited = AiJson.GSON.toJsonTree(plan).getAsJsonObject(); edited.addProperty("handSlot", 1);
            assertFalse(AiJson.GSON.fromJson(edited, RemovalPlan.class).intact());
            edited = AiJson.GSON.toJsonTree(plan).getAsJsonObject(); edited.addProperty("regionHash", AiJson.digest("other selection"));
            assertFalse(AiJson.GSON.fromJson(edited, RemovalPlan.class).intact());
            var invalidSteps = new ArrayList<>(steps());
            invalidSteps.set(1, new RemovalPlan.Step(UUID.randomUUID(), b, List.of(new BuildJournal.Cell(b, stone, air), new BuildJournal.Cell(a, stone, null)), List.of()));
            assertThrows(IllegalArgumentException.class, () -> plan(manager.getWorkspace(owner), UUID.randomUUID(), invalidSteps));
        }
    }
    @Test void theSamePhysicalTargetAndWrongUndoSourceCannotBeChargedTwice() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var first = steps().getFirst(); var repeated = new RemovalPlan.Step(UUID.randomUUID(), a, List.of(new BuildJournal.Cell(a, air, air)), List.of());
            assertThrows(IllegalArgumentException.class, () -> plan(manager.getWorkspace(owner), UUID.randomUUID(), List.of(first, repeated)));
            var unsigned = AiJson.GSON.toJsonTree(plan(manager.getWorkspace(owner), UUID.randomUUID(), steps())).getAsJsonObject();
            unsigned.addProperty("mode", "UNDO"); unsigned.addProperty("sourcePlanId", UUID.randomUUID().toString()); unsigned.addProperty("sourcePlanHash", AiJson.digest("source plan"));
            assertThrows(RuntimeException.class, () -> AiJson.GSON.fromJson(unsigned, RemovalPlan.class));
        }
    }
    @Test void privateIndexedArtifactSurvivesRestartAndRejectsAnotherOwnerAndTampering() throws Exception {
        UUID id; String hash;
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var task = AgentTask.create(owner.owner(), UUID.randomUUID(), "review native removal");
            workspace.conversation().append(List.of(Draft.checkpoint(task))); var plan = plan(workspace, task.id(), steps());
            id = plan.id(); hash = plan.hash(); var repository = new RemovalRepository(workspace); repository.save(owner, plan);
            assertThrows(SecurityException.class, () -> repository.load(stranger, plan.id(), plan.hash()));
            assertThrows(IllegalStateException.class, () -> repository.save(owner, plan));
        }
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var repository = new RemovalRepository(workspace);
            assertTrue(repository.load(owner, id, hash).intact());
            var edited = AiJson.GSON.toJsonTree(repository.load(owner, id, hash)).getAsJsonObject(); edited.addProperty("registryGeneration", 99);
            Files.writeString(workspace.artifact(id), edited.toString());
            assertThrows(SecurityException.class, () -> repository.load(owner, id, hash));
        }
    }
    @Test void twoNativeSlabPhasesBindIntermediateStateAndAssignUndoOwnershipOnlyToTheFinalRemoval() throws Exception {
        var doubled = new BlueprintDocument.State("minecraft:oak_slab", Map.of("type", "double", "waterlogged", "false"));
        var bottom = new BlueprintDocument.State("minecraft:oak_slab", Map.of("type", "bottom", "waterlogged", "false"));
        try (var manager = new WorkspaceManager(directory)) {
            var ws = manager.getWorkspace(owner); var source = UUID.randomUUID();
            var phases = List.of(new RemovalPlan.Step(UUID.randomUUID(), a, List.of(new BuildJournal.Cell(a, doubled, bottom)), List.of()),
                    new RemovalPlan.Step(UUID.randomUUID(), a, List.of(new BuildJournal.Cell(a, bottom, air)), List.of(source)));
            var plan = new RemovalPlan(1, UUID.randomUUID(), ws.metadata().serverInstanceId(), ws.primaryConversationId(), owner.owner(), UUID.randomUUID(),
                    UUID.randomUUID(), "minecraft:overworld", 1, RemovalJournal.Mode.UNDO, UUID.randomUUID(), AiJson.digest("original two placements"),
                    0, "", hand, 0, List.of(new BuildJournal.Cell(a, doubled, air)), phases, "").signed();
            assertTrue(plan.intact()); assertTrue(RemovalTransitions.partialOnly(phases.getFirst().cells()));
            var json = AiJson.GSON.toJsonTree(plan).getAsJsonObject(); json.getAsJsonArray("steps").remove(1);
            assertThrows(RuntimeException.class, () -> AiJson.GSON.fromJson(json, RemovalPlan.class), "A half cannot satisfy the signed whole-cell target");
            assertFalse(RemovalTransitions.halfSlab(doubled, new BlueprintDocument.State("minecraft:birch_slab", bottom.properties())));
            assertFalse(RemovalTransitions.halfSlab(doubled, new BlueprintDocument.State("minecraft:oak_slab", Map.of("type", "bottom", "waterlogged", "true"))));
            assertFalse(RemovalTransitions.valid(new BuildJournal.Cell(a, bottom, doubled)), "Removal cannot generate a second half");
        }
    }
}
