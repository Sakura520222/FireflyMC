package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.material.MaterialStack;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RemovalJournalTest {
    @TempDir Path directory;
    final Actor owner = Actor.player(UUID.randomUUID(), true, true, 0), other = Actor.player(UUID.randomUUID(), true, true, 4);
    final BlueprintDocument.Position pos = new BlueprintDocument.Position(2, 64, 3);
    final BlueprintDocument.State stone = new BlueprintDocument.State("minecraft:stone", Map.of()), air = new BlueprintDocument.State("minecraft:air", Map.of());
    final MaterialStack tool = new MaterialStack("{\"id\":\"minecraft:iron_pickaxe\"}", 1);
    final MaterialStack damaged = new MaterialStack("{\"id\":\"minecraft:iron_pickaxe\",\"components\":{\"minecraft:damage\":1}}", 1);
    RemovalJournal.Input input() {
        return new RemovalJournal.Input(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), AiJson.digest("fixed removal plan"),
                "minecraft:overworld", RemovalJournal.Mode.UNDO, List.of(UUID.randomUUID()), List.of(new BuildJournal.Cell(pos, stone, air)), tool);
    }
    RemovalJournal.Evidence evidence(String state, MaterialStack hand, BlueprintDocument.State block, double progress) {
        return new RemovalJournal.Evidence(state, "", progress, hand, .005f, List.of(new BuildJournal.ObservedCell(pos, block)), List.of());
    }
    @Test void operationIdentityAndEveryStageAreOwnerBoundWithoutOperatorBypass() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var journal = new RemovalJournal(manager.getWorkspace(owner), UUID.randomUUID()); var input = input();
            var prepared = journal.prepare(owner, input); assertEquals(prepared, journal.prepare(owner, input));
            assertThrows(SecurityException.class, () -> journal.get(other, input.operationId()));
            assertThrows(SecurityException.class, () -> journal.prepare(owner, new RemovalJournal.Input(input.operationId(), input.taskId(), input.planId(), input.planHash(),
                    input.dimension(), input.mode(), input.sourceSteps(), input.cells(), damaged)));
            assertThrows(IllegalStateException.class, () -> journal.begin(owner, input.operationId(), RemovalJournal.Action.REMOVE));
            var start = journal.begin(owner, input.operationId(), RemovalJournal.Action.START);
            assertThrows(IllegalStateException.class, () -> journal.begin(owner, input.operationId(), RemovalJournal.Action.START));
            assertThrows(IllegalStateException.class, () -> journal.observed(owner, input.operationId(), UUID.randomUUID(), evidence("READY", tool, stone, 1)));
            assertEquals(start, journal.get(owner, input.operationId()));
        }
    }
    @Test void aPartialNativeSlabReceiptIsBoundToItsExactRemainingStateAndCannotPretendTheWholeBlockWasRemoved() throws Exception {
        var doubled = new BlueprintDocument.State("minecraft:oak_slab", Map.of("type", "double", "waterlogged", "false"));
        var bottom = new BlueprintDocument.State("minecraft:oak_slab", Map.of("type", "bottom", "waterlogged", "false"));
        try (var manager = new WorkspaceManager(directory)) {
            var journal = new RemovalJournal(manager.getWorkspace(owner), UUID.randomUUID());
            for (boolean exact : List.of(true, false)) {
                UUID id = UUID.randomUUID();
                journal.prepare(owner, new RemovalJournal.Input(id, UUID.randomUUID(), UUID.randomUUID(), AiJson.digest("approved slab phases"),
                        "minecraft:overworld", RemovalJournal.Mode.UNDO, List.of(), List.of(new BuildJournal.Cell(pos, doubled, bottom)), tool));
                var start = journal.begin(owner, id, RemovalJournal.Action.START);
                journal.observed(owner, id, start.attempt(), evidence("READY", tool, doubled, 1));
                var remove = journal.begin(owner, id, RemovalJournal.Action.REMOVE);
                var receipt = journal.observed(owner, id, remove.attempt(), new RemovalJournal.Evidence("REMOVED", RemovalJournal.CUSTODY_EVIDENCE, 1, tool, 0,
                        List.of(new BuildJournal.ObservedCell(pos, exact ? bottom : air)), List.of(new RemovalJournal.Drop(UUID.randomUUID(), new MaterialStack("{\"id\":\"minecraft:oak_slab\"}", 1)))));
                assertEquals(exact ? RemovalJournal.State.REMOVED : RemovalJournal.State.RECOVERY_REQUIRED, receipt.state());
                if (exact) assertFalse(RemovalJournal.harvestReceipt(receipt).isEmpty());
                else assertThrows(IllegalStateException.class, () -> RemovalJournal.harvestReceipt(receipt));
            }
        }
    }
    @Test void actualRemovalNeedsIndependentStartAndRemovalSaveProofsAndNeverReplays() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var journal = new RemovalJournal(manager.getWorkspace(owner), UUID.randomUUID()); var input = input(); UUID id = input.operationId();
            journal.prepare(owner, input); var start = journal.begin(owner, id, RemovalJournal.Action.START);
            journal.observed(owner, id, start.attempt(), evidence("MINING", tool, stone, .2));
            assertThrows(IllegalStateException.class, () -> journal.begin(owner, id, RemovalJournal.Action.REMOVE));
            journal.ready(owner, id, evidence("READY", tool, stone, 1)); var finish = journal.begin(owner, id, RemovalJournal.Action.REMOVE);
            var observed = new RemovalJournal.Evidence("REMOVED", "", 1, damaged, .005f, List.of(new BuildJournal.ObservedCell(pos, air)),
                    List.of(new RemovalJournal.Drop(UUID.randomUUID(), new MaterialStack("{\"id\":\"minecraft:cobblestone\"}", 1))));
            var result = journal.observed(owner, id, finish.attempt(), observed);
            assertEquals(RemovalJournal.State.REMOVED, result.state()); assertEquals(damaged, result.evidence().hand()); assertFalse(result.settled());
            assertFalse(journal.nativeSaved(owner, id, start.attempt(), AiJson.digest("actual native start stores")).settled());
            assertTrue(journal.nativeSaved(owner, id, finish.attempt(), AiJson.digest("actual native removal stores")).settled());
            assertThrows(IllegalStateException.class, () -> journal.observed(owner, id, finish.attempt(), observed));
            assertThrows(IllegalStateException.class, () -> journal.begin(owner, id, RemovalJournal.Action.START));
        }
    }
    @Test void cancellationRetainsRealStartToolEffectsInsteadOfRefundingAnEarlierCopy() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var journal = new RemovalJournal(manager.getWorkspace(owner), UUID.randomUUID()); var input = input(); UUID id = input.operationId();
            journal.prepare(owner, input); var start = journal.begin(owner, id, RemovalJournal.Action.START);
            journal.observed(owner, id, start.attempt(), evidence("MINING", damaged, stone, .2));
            var abort = journal.begin(owner, id, RemovalJournal.Action.ABORT);
            var result = journal.observed(owner, id, abort.attempt(), evidence("CANCELLED", damaged, stone, .2));
            assertEquals(RemovalJournal.State.CANCELLED, result.state()); assertEquals(damaged, result.evidence().hand()); assertEquals(2, result.saves().size());
        }
    }
    @Test void lateKnownOutcomeAndEvenCompleteNativeSavesDoNotClearAnUncertainBarrier() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var journal = new RemovalJournal(manager.getWorkspace(owner), UUID.randomUUID()); var input = input(); UUID id = input.operationId();
            journal.prepare(owner, input); var start = journal.begin(owner, id, RemovalJournal.Action.START);
            journal.observed(owner, id, start.attempt(), evidence("READY", tool, stone, 1)); var finish = journal.begin(owner, id, RemovalJournal.Action.REMOVE);
            journal.uncertain(owner, id, "ACCEPTED_NATIVE_RESULT_PENDING");
            var result = journal.observed(owner, id, finish.attempt(), evidence("REMOVED", damaged, air, 1));
            assertEquals(RemovalJournal.State.RECOVERY_REQUIRED, result.state()); assertEquals(air, result.evidence().world().getFirst().state());
            for (var save : result.saves()) journal.nativeSaved(owner, id, save.attempt(), AiJson.digest("positive native save " + save.attempt()));
            assertFalse(journal.get(owner, id).settled());
            assertThrows(IllegalStateException.class, () -> journal.begin(owner, id, RemovalJournal.Action.REMOVE));
        }
    }
    @Test void reloadRetainsCompletedSavedWorkButQuarantinesIncompleteNativeMining() throws Exception {
        UUID pending, complete;
        try (var manager = new WorkspaceManager(directory)) {
            var journal = new RemovalJournal(manager.getWorkspace(owner), UUID.randomUUID()); var a = input(); pending = a.operationId();
            journal.prepare(owner, a); var start = journal.begin(owner, pending, RemovalJournal.Action.START);
            journal.observed(owner, pending, start.attempt(), evidence("MINING", tool, stone, .2));
            journal.nativeSaved(owner, pending, start.attempt(), AiJson.digest("start saved"));
            var b = input(); complete = b.operationId(); journal.prepare(owner, b); var bStart = journal.begin(owner, complete, RemovalJournal.Action.START);
            journal.observed(owner, complete, bStart.attempt(), evidence("READY", tool, stone, 1)); var finish = journal.begin(owner, complete, RemovalJournal.Action.REMOVE);
            var result = journal.observed(owner, complete, finish.attempt(), evidence("REMOVED", damaged, air, 1));
            for (var save : result.saves()) journal.nativeSaved(owner, complete, save.attempt(), AiJson.digest("saved " + save.attempt()));
        }
        try (var manager = new WorkspaceManager(directory)) {
            var journal = new RemovalJournal(manager.getWorkspace(owner), UUID.randomUUID()); journal.recover(owner);
            assertEquals(RemovalJournal.State.RECOVERY_REQUIRED, journal.get(owner, pending).state()); assertTrue(journal.get(owner, complete).settled());
            assertThrows(IllegalStateException.class, () -> journal.begin(owner, pending, RemovalJournal.Action.START));
        }
    }
    @Test void changedNativeWorldOrToolCannotTurnIntoAReadyOrSuccessfulRemoval() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var journal = new RemovalJournal(manager.getWorkspace(owner), UUID.randomUUID()); var input = input(); UUID id = input.operationId();
            journal.prepare(owner, input); var start = journal.begin(owner, id, RemovalJournal.Action.START);
            journal.observed(owner, id, start.attempt(), evidence("MINING", tool, stone, .1));
            assertThrows(IllegalStateException.class, () -> journal.ready(owner, id, evidence("READY", damaged, stone, 1)));
            assertThrows(IllegalStateException.class, () -> journal.ready(owner, id, evidence("READY", tool, air, 1)));
            journal.ready(owner, id, evidence("READY", tool, stone, 1)); var finish = journal.begin(owner, id, RemovalJournal.Action.REMOVE);
            assertEquals(RemovalJournal.State.RECOVERY_REQUIRED, journal.observed(owner, id, finish.attempt(), evidence("REMOVED", damaged, stone, 1)).state());
        }
    }
    @Test void changedRateAndProvenNonDispatchRemoveOnlyTheirOwnSaveObligation() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var journal = new RemovalJournal(manager.getWorkspace(owner), UUID.randomUUID()); var input = input(); UUID id = input.operationId();
            journal.prepare(owner, input); var start = journal.begin(owner, id, RemovalJournal.Action.START);
            journal.observed(owner, id, start.attempt(), evidence("READY", tool, stone, 1)); var finish = journal.begin(owner, id, RemovalJournal.Action.REMOVE);
            var delayed = new RemovalJournal.Evidence("MINING", "MINING_RATE_CHANGED", .05, tool, 0, List.of(new BuildJournal.ObservedCell(pos, stone)), List.of());
            var result = journal.observed(owner, id, finish.attempt(), delayed);
            assertEquals(RemovalJournal.State.MINING, result.state()); assertEquals(List.of(start.saves().getFirst()), result.saves());
            var abort = journal.begin(owner, id, RemovalJournal.Action.ABORT);
            assertEquals(result.saves(), journal.notDispatched(owner, id, abort.attempt()).saves());
            assertFalse(journal.get(owner, id).saved());
        }
    }
    @Test void privacyCannotEraseAcceptedMiningAndOldWritersCannotRecreateAnErasedWorkspace() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var journal = new RemovalJournal(workspace, UUID.randomUUID()); var input = input(); UUID id = input.operationId();
            var privacy = new firefly520.fireflymc.ai.workspace.WorkspacePrivacyService(System::currentTimeMillis, ignored -> true);
            journal.prepare(owner, input); assertFalse(RemovalJournal.hasUnresolvedWorldEffects(workspace));
            var start = journal.begin(owner, id, RemovalJournal.Action.START);
            assertTrue(RemovalJournal.requiresRecovery(workspace, input.taskId()));
            assertThrows(IllegalStateException.class, () -> privacy.preview(owner, workspace));
            assertThrows(IllegalStateException.class, () -> journal.cancelPrepared(owner, id));
            journal.notDispatched(owner, id, start.attempt()); journal.cancelPrepared(owner, id);
            assertFalse(RemovalJournal.hasUnresolvedWorldEffects(workspace));
            var preview = privacy.preview(owner, workspace); privacy.confirm(owner, workspace, preview.token());
            assertThrows(IllegalStateException.class, () -> journal.prepare(owner, input()));
            assertTrue(new RemovalJournal(workspace, UUID.randomUUID()).list(owner).isEmpty());
        }
    }
}
