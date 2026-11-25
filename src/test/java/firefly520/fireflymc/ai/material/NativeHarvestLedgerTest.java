package firefly520.fireflymc.ai.material;

import firefly520.fireflymc.ai.blueprint.*;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeHarvestLedgerTest {
    @TempDir Path directory;
    final Actor owner = Actor.player(UUID.randomUUID(), true, true, 0), other = Actor.player(UUID.randomUUID(), true, true, 4);
    final BlueprintDocument.Position pos = new BlueprintDocument.Position(4, 64, 7);
    final BlueprintDocument.State stone = new BlueprintDocument.State("minecraft:stone", Map.of()), air = new BlueprintDocument.State("minecraft:air", Map.of());
    final MaterialStack tool = new MaterialStack("{\"id\":\"minecraft:iron_pickaxe\"}", 1), product = new MaterialStack("{\"id\":\"minecraft:cobblestone\"}", 3);
    RemovalJournal.Step removed(RemovalJournal journal, boolean custody, boolean outputs) throws Exception {
        var input = new RemovalJournal.Input(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), AiJson.digest("native approved world removal"),
                "minecraft:overworld", RemovalJournal.Mode.DEMOLITION, List.of(), List.of(new BuildJournal.Cell(pos, stone, air)), tool);
        journal.prepare(owner, input); var start = journal.begin(owner, input.operationId(), RemovalJournal.Action.START);
        journal.observed(owner, input.operationId(), start.attempt(), new RemovalJournal.Evidence("READY", "", 1, tool, 0,
                List.of(new BuildJournal.ObservedCell(pos, stone)), List.of()));
        var finish = journal.begin(owner, input.operationId(), RemovalJournal.Action.REMOVE);
        return journal.observed(owner, input.operationId(), finish.attempt(), new RemovalJournal.Evidence("REMOVED", custody ? RemovalJournal.CUSTODY_EVIDENCE : "", 1,
                tool, .005f, List.of(new BuildJournal.ObservedCell(pos, air)), outputs ? List.of(new RemovalJournal.Drop(UUID.randomUUID(), product)) : List.of()));
    }
    void saved(RemovalJournal journal, UUID id) throws Exception {
        for (var save : journal.get(owner, id).saves()) journal.nativeSaved(owner, id, save.attempt(), AiJson.digest("actual native writer " + save.attempt()));
    }
    @Test void onlyVerifiedPrivateCustodyCanEnterMaterialAccountsAndRepeatsNeverDuplicateLoot() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var journal = new RemovalJournal(manager.getWorkspace(owner), UUID.randomUUID()); var ledger = new MaterialLedger(directory);
            var physical = removed(journal, false, true);
            assertThrows(IllegalStateException.class, () -> ledger.adoptHarvest(owner, journal, physical.input().operationId()));
            var held = removed(journal, true, true); UUID id = held.input().operationId();
            assertThrows(SecurityException.class, () -> ledger.adoptHarvest(other, journal, id));
            var adopted = ledger.adoptHarvest(owner, journal, id);
            assertEquals(List.of(product), adopted.remainingProducts()); assertEquals(MaterialLedger.State.CALLBACK_DONE, adopted.state());
            assertEquals(RemovalJournal.State.ACCOUNTED, journal.get(owner, id).state()); assertFalse(adopted.saved());
            assertEquals(adopted, ledger.adoptHarvest(owner, journal, id)); assertEquals(1, ledger.list(owner).size());
            saved(journal, id); assertTrue(ledger.harvestSaved(owner, journal.get(owner, id)).saved());
        }
    }
    @Test void aRealZeroDropRemovalStillNeedsNativeSaveAndDoesNotCreateARefund() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var journal = new RemovalJournal(manager.getWorkspace(owner), UUID.randomUUID()); var ledger = new MaterialLedger(directory);
            var removed = removed(journal, true, false); UUID id = removed.input().operationId();
            var zero = ledger.adoptHarvest(owner, journal, id);
            assertEquals(MaterialLedger.State.COMPLETED, zero.state()); assertTrue(zero.products().isEmpty()); assertFalse(zero.saved());
            var restored = new MaterialLedger(directory);
            assertEquals(MaterialLedger.State.RECOVERY_REQUIRED, restored.get(owner, id).state());
            assertTrue(restored.recoveryBlocksWorld("minecraft:overworld", pos.x(), pos.y(), pos.z()));
            assertFalse(restored.recoveryBlocksWorld("minecraft:overworld", pos.x() + 1, pos.y(), pos.z()));
            var inventory = new MaterialLedger.Stock("player", 1, "player:" + owner.owner(), "player:" + owner.owner() + ":slot:0", 0, product, 1, 3, 3);
            assertEquals(0L, restored.available(List.of(inventory)).get(inventory.key()));
            saved(journal, id); restored.harvestSaved(owner, journal.get(owner, id));
            assertEquals(MaterialLedger.State.RECOVERY_REQUIRED, restored.get(owner, id).state());
            var review = restored.recoveryReview(owner, id); restored.reconcile(owner, id, review.revision(), review.reviewHash());
            assertFalse(restored.recoveryBlocksWorld("minecraft:overworld", pos.x(), pos.y(), pos.z()));
            assertTrue(restored.get(owner, id).remainingProducts().isEmpty());
        }
    }
    @Test void custodyCannotBeErasedBetweenWorldEvidenceAndMaterialLedgerCommit() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var journal = new RemovalJournal(workspace, UUID.randomUUID()); var ledger = new MaterialLedger(directory);
            var held = removed(journal, true, true); UUID id = held.input().operationId(); saved(journal, id);
            var privacy = new WorkspacePrivacyService(System::currentTimeMillis, ws -> ledger.settled(ws.owner()));
            assertFalse(journal.get(owner, id).settled());
            assertThrows(IllegalStateException.class, () -> privacy.preview(owner, workspace));
            var adopted = ledger.adoptHarvest(owner, journal, id);
            assertTrue(journal.get(owner, id).settled()); assertTrue(adopted.saved());
            assertThrows(IllegalStateException.class, () -> privacy.preview(owner, workspace));
            ledger.beginDelivery(owner, id, 0, 3, "actual-player-delivery", MaterialStack.EMPTY); ledger.delivered(owner, id, 3, true);
            ledger.nativeSaved(owner, id, "deliver", "actual-player-delivery", AiJson.digest("actual native player delivery save"));
            assertTrue(ledger.settled(owner.owner())); assertNotNull(privacy.preview(owner, workspace));
        }
    }
    @Test void uncertainEvidenceAndExistingMaterialOperationIdentityCannotBeAdoptedAsFreshLoot() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var journal = new RemovalJournal(manager.getWorkspace(owner), UUID.randomUUID()); var ledger = new MaterialLedger(directory);
            var held = removed(journal, true, true); UUID id = held.input().operationId(); journal.uncertain(owner, id, "OTHER_NATIVE_EFFECT_UNPROVEN");
            assertThrows(IllegalStateException.class, () -> ledger.adoptHarvest(owner, journal, id));
            var second = removed(journal, true, true); UUID secondId = second.input().operationId();
            var source = new MaterialLedger.Stock("player", 1, "player:" + owner.owner(), "slot", 0, product, 1, 3, 3);
            ledger.reserve(owner, second.input().taskId(), secondId, second.input().planHash(), List.of(source), List.of(new MaterialLedger.Need(source.key(), 1)));
            assertThrows(SecurityException.class, () -> ledger.adoptHarvest(owner, journal, secondId));
            assertEquals(RemovalJournal.State.REMOVED, journal.get(owner, secondId).state());
            assertTrue(ledger.get(owner, secondId).products().isEmpty());
        }
    }
    @Test void uncertaintyBeforeAdoptionQuarantinesWorldAndPreservesLootWithoutAllowingGenericRecovery() throws Exception {
        UUID id;
        try (var manager = new WorkspaceManager(directory)) {
            var journal = new RemovalJournal(manager.getWorkspace(owner), UUID.randomUUID()); var ledger = new MaterialLedger(directory);
            var held = removed(journal, true, true); id = held.input().operationId();
            var unknown = journal.uncertain(owner, id, "UNADAPTED_NATIVE_CALLBACK");
            assertThrows(SecurityException.class, () -> ledger.quarantineRemoval(other, unknown));
            var op = ledger.quarantineRemoval(owner, unknown);
            assertEquals(List.of(product), op.remainingProducts()); assertTrue(op.uncertain());
            assertTrue(ledger.recoveryBlocksWorld("minecraft:overworld", pos.x(), pos.y(), pos.z()));
            assertTrue(ledger.recoveryBlocksPlayer(owner.owner())); assertFalse(ledger.recoveryBlocksPlayer(other.owner()));
            assertThrows(IllegalStateException.class, () -> ledger.recoveryReview(owner, unknown.input().operationId()));
            ledger.quarantineRemoval(owner, unknown);
            assertEquals(1, ledger.list(owner).size()); assertEquals(List.of(product), ledger.get(owner, id).products());
        }
        var reopened = new MaterialLedger(directory);
        assertTrue(reopened.recoveryBlocksWorld("minecraft:overworld", pos.x(), pos.y(), pos.z()));
        assertEquals("NATIVE_REMOVAL_UNCONFIRMED", reopened.get(owner, id).reason());
        assertThrows(IllegalStateException.class, () -> reopened.recoveryReview(owner, id));
    }
    @Test void pendingCustodyCapacityIsCheckedBeforeAnotherIrreversibleBreakAndReleasedByDelivery() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var journal = new RemovalJournal(manager.getWorkspace(owner), UUID.randomUUID()); var ledger = new MaterialLedger(directory); UUID first = null;
            for (int i = 0; i < 32; i++) {
                ledger.requireHarvestCapacity(owner); var step = removed(journal, true, true);
                if (first == null) first = step.input().operationId(); ledger.adoptHarvest(owner, journal, step.input().operationId());
            }
            assertEquals("MATERIAL_PENDING_LIMIT", assertThrows(IllegalStateException.class, () -> ledger.requireHarvestCapacity(owner)).getMessage());
            assertDoesNotThrow(() -> ledger.requireHarvestCapacity(other));
            ledger.beginDelivery(owner, first, 0, 3, "actual-capacity-delivery", MaterialStack.EMPTY);
            ledger.delivered(owner, first, 3, true);
            assertDoesNotThrow(() -> ledger.requireHarvestCapacity(owner));
            assertFalse(ledger.get(owner, first).saved(), "Freeing custody capacity is distinct from proving a native disk write");
            assertEquals(32, ledger.list(owner).size());
        }
    }
}
