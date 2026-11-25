package firefly520.fireflymc.ai.integration.maid;

import firefly520.fireflymc.ai.material.MaterialStack;
import firefly520.fireflymc.ai.material.NativeSaveBarrier;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Native action bookkeeping only; actual seeds/crops/behavior are tested in the dedicated fixture. */
class MaidFarmJournalTest {
    @TempDir Path root;
    final Actor actor = Actor.player(UUID.randomUUID(), true, true, 0);
    final MaterialStack seed = new MaterialStack("{\"id\":\"minecraft:wheat_seeds\",\"count\":1}", 2);
    MaidFarmPlan plan(WorkspaceManager.Workspace workspace) {
        var maid = new MaidControlPlan.NativeState(UUID.randomUUID(), "minecraft:overworld", "touhou_little_maid:idle", "native.TaskIdle", 1,
                false, false, true, false, "DAY", 0, 0, 0, 8, false, false, false, false, 0, true);
        return new MaidFarmPlan(1, UUID.randomUUID(), actor.owner(), UUID.randomUUID(), workspace.metadata().serverInstanceId(), workspace.primaryConversationId(),
                workspace.conversation().privacyEpoch(), UUID.randomUUID(), 1, 100000, MaidFarmPlan.Kind.PLANT, 0, 65, 0,
                UUID.randomUUID(), 1, AiJson.digest("confirmed-own-maid-source"), 0, maid,
                new MaidFarmPlan.BlockView("minecraft:air", Map.of()), new MaidFarmPlan.BlockView("minecraft:farmland", Map.of("moisture", "7")),
                List.of(seed, MaterialStack.EMPTY, MaterialStack.EMPTY, MaterialStack.EMPTY, MaterialStack.EMPTY, MaterialStack.EMPTY), MaterialStack.EMPTY, MaterialStack.EMPTY, "").signed();
    }
    MaidFarmNative.Outcome planted(MaidFarmPlan plan, boolean covered) {
        var after = new ArrayList<>(plan.inventory()); after.set(0, seed.withCount(1));
        return new MaidFarmNative.Outcome(true, true, true, new MaidFarmPlan.BlockView("minecraft:wheat", Map.of("age", "0")), plan.soil(), after,
                MaterialStack.EMPTY, MaterialStack.EMPTY, Set.of(plan.cropPos()), "", covered ? new NativeSaveBarrier().changed(List.of(new Object())) : null);
    }
    @Test void preparedNavigationHasNoCropDebtButAnEnteredCallbackNeedsItsOwnNativeSaveAndCannotReplay() throws Exception {
        try (var manager = new WorkspaceManager(root)) {
            var workspace = manager.getWorkspace(actor); var journal = new MaidFarmJournal(workspace); var plan = plan(workspace);
            assertTrue(journal.prepare(actor, plan).settled()); journal.begin(actor, plan.id()); assertFalse(journal.settled(actor));
            assertThrows(IllegalStateException.class, () -> journal.begin(actor, plan.id()));
            var after = journal.observed(actor, plan.id(), planted(plan, true)); assertEquals(MaidFarmJournal.State.OBSERVED, after.state());
            assertFalse(after.settled()); assertEquals(1, after.evidence().inventory().getFirst().count());
            journal.saved(actor, plan.id(), AiJson.digest("synthetic-native-store-proof"));
            assertTrue(journal.get(actor, plan.id()).settled()); assertTrue(journal.taskSettled(actor, plan.taskId()));
            assertThrows(IllegalStateException.class, () -> journal.begin(actor, plan.id()));
            assertThrows(IllegalStateException.class, () -> journal.observed(actor, plan.id(), planted(plan, true)));
            assertEquals(1, journal.snapshot(actor).size());
        }
    }
    @Test void missingSaveCoverageAndLateKnownOutcomesNeverClearAnUncertainWorldBarrier() throws Exception {
        try (var manager = new WorkspaceManager(root)) {
            var workspace = manager.getWorkspace(actor); var journal = new MaidFarmJournal(workspace);
            var missing = plan(workspace); journal.prepare(actor, missing); journal.begin(actor, missing.id());
            assertEquals(MaidFarmJournal.State.RECOVERY_REQUIRED, journal.observed(actor, missing.id(), planted(missing, false)).state());
            var late = plan(workspace); journal.prepare(actor, late); journal.begin(actor, late.id()); journal.unknown(actor, late.id(), "NATIVE_RETURN_PENDING_AT_TIMEOUT");
            var actual = journal.observed(actor, late.id(), planted(late, true));
            assertEquals(MaidFarmJournal.State.RECOVERY_REQUIRED, actual.state()); assertEquals(1, actual.evidence().inventory().getFirst().count());
            journal.saved(actor, late.id(), AiJson.digest("late-positive-native-proof"));
            assertEquals(MaidFarmJournal.State.RECOVERY_REQUIRED, journal.get(actor, late.id()).state()); assertFalse(journal.settled(actor));
        }
    }
    @Test void originalCropAndMaterialVersionsStayFixedAcrossRestartAndOtherPlayersCannotReadThem() throws Exception {
        try (var manager = new WorkspaceManager(root)) {
            var workspace = manager.getWorkspace(actor); var journal = new MaidFarmJournal(workspace); var plan = plan(workspace);
            journal.prepare(actor, plan); journal.begin(actor, plan.id()); journal.observed(actor, plan.id(), planted(plan, true));
            var reloaded = new MaidFarmJournal(workspace); var op = reloaded.get(actor, plan.id());
            assertEquals(plan, op.plan()); assertEquals(MaidFarmJournal.State.RECOVERY_REQUIRED, op.state());
            assertEquals(1, op.evidence().inventory().getFirst().count()); assertThrows(IllegalStateException.class, () -> reloaded.begin(actor, plan.id()));
            var other = Actor.player(UUID.randomUUID(), true, true, 4);
            assertThrows(SecurityException.class, () -> reloaded.snapshot(other));
            assertThrows(SecurityException.class, () -> reloaded.saved(other, plan.id(), AiJson.digest("foreign")));
        }
    }
    @Test void publishedWorldSettlementDoesNotAcquireItsJournalMonitorFromThePrimaryConversation() throws Exception {
        try (var manager = new WorkspaceManager(root)) {
            var workspace = manager.getWorkspace(actor); var journal = new MaidFarmJournal(workspace); var plan = plan(workspace);
            journal.prepare(actor, plan); journal.rejectBeforeNative(actor, plan.id(), "CANCELLED_BEFORE_NATIVE_WORK");
            synchronized (journal) {
                var result = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    synchronized (workspace.conversation()) { return journal.taskSettled(actor, plan.taskId()); }
                }).get(2, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(result);
            }
        }
    }
}
