package firefly520.fireflymc.ai.integration.maid;

import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** State-machine regressions only. These proofs are test inputs, not a native combat acceptance. */
class MaidCombatJournalTest {
    @TempDir Path root;
    final Actor actor = Actor.player(UUID.randomUUID(), true, true, 0);
    final MaterialStack sword = new MaterialStack("{\"id\":\"minecraft:iron_sword\",\"count\":1}", 1);
    MaidCombatPlan plan(WorkspaceManager.Workspace workspace, int hits) {
        var maid = new MaidControlPlan.NativeState(UUID.randomUUID(), "minecraft:overworld", "touhou_little_maid:idle", "native.TaskIdle", 1,
                false, false, true, false, "DAY", 0, 0, 0, 8, false, false, false, false, 0, true);
        var before = new MaidMeleeKernel.Snapshot(maid, "touhou_little_maid:empty", Collections.nCopies(6, MaterialStack.EMPTY), sword, MaterialStack.EMPTY,
                Collections.nCopies(4, MaterialStack.EMPTY), Collections.nCopies(3, MaterialStack.EMPTY), 20, 0,
                new MaidMeleeKernel.Target(UUID.randomUUID(), "minecraft:zombie", 20, 0, true, false, 0, 65, 0));
        return new MaidCombatPlan(1, UUID.randomUUID(), actor.owner(), UUID.randomUUID(), workspace.metadata().serverInstanceId(), workspace.primaryConversationId(),
                workspace.conversation().privacyEpoch(), UUID.randomUUID(), 1, 100000, hits, 200, before, "").signed();
    }
    MaidMeleeKernel.Outcome effect(MaidCombatPlan plan, boolean killed, boolean verified, boolean save) {
        var before = plan.before();
        var target = new MaidMeleeKernel.Target(plan.targetId(), before.target().type(), killed ? 0 : 10, 0, !killed, false, 0, 65, 0);
        var after = new MaidMeleeKernel.Snapshot(before.maid(), before.backpackId(), before.inventory(), before.hand(), before.offHand(), before.armour(), before.baubles(), 20, 0, target);
        return new MaidMeleeKernel.Outcome(true, true, verified, killed, before, after, target,
                List.of(new MaidCombatScope.Drop(UUID.randomUUID(), new MaterialStack("{\"id\":\"minecraft:rotten_flesh\",\"count\":1}", 1))),
                List.of("actual-native-orb-observation"), verified ? "" : "UNADAPTED_NATIVE_EFFECT", save ? new NativeSaveBarrier().changed(List.of(new Object())) : null);
    }
    MaidCombatReconcile review(WorkspaceManager.Workspace workspace, MaidCombatJournal.Operation op) {
        return new MaidCombatReconcile(1, UUID.randomUUID(), actor.owner(), op.plan().taskId(), workspace.metadata().serverInstanceId(), workspace.primaryConversationId(),
                workspace.conversation().privacyEpoch(), op.plan().executionEpoch(), 100000, op.plan().id(), op.revision(), AiJson.digest(AiJson.canonical(op)), op.plan().before(), "").signed();
    }
    @Test void eachStrikeNeedsItsOwnProofAndTheNativeIntentCannotBeReplayed() throws Exception {
        try (var manager = new WorkspaceManager(root)) {
            var workspace = manager.getWorkspace(actor); var journal = new MaidCombatJournal(workspace); var plan = plan(workspace, 2);
            journal.prepare(actor, plan); assertEquals(0, journal.beginHit(actor, plan.id(), plan.before()));
            assertThrows(IllegalStateException.class, () -> journal.beginHit(actor, plan.id(), plan.before()));
            var first = effect(plan, false, true, true); journal.observed(actor, plan.id(), 0, first);
            assertEquals(1, journal.beginHit(actor, plan.id(), first.after())); journal.observed(actor, plan.id(), 1, effect(plan, true, true, true));
            journal.finish(actor, plan.id(), "TARGET_KILLED");
            assertFalse(journal.get(actor, plan.id()).settled()); journal.saved(actor, plan.id(), 1, AiJson.digest("second-native-write"));
            assertFalse(journal.get(actor, plan.id()).settled()); journal.saved(actor, plan.id(), 0, AiJson.digest("first-native-write"));
            var op = journal.get(actor, plan.id()); assertTrue(op.settled()); assertTrue(op.killed());
            assertThrows(IllegalStateException.class, () -> journal.beginHit(actor, plan.id(), first.after()));
            assertThrows(IllegalStateException.class, () -> journal.observed(actor, plan.id(), 1, first));
        }
    }
    @Test void restartNeverTreatsAnUnreturnedNativeIntentAsSafeNoDispatch() throws Exception {
        try (var manager = new WorkspaceManager(root)) {
            var workspace = manager.getWorkspace(actor); var journal = new MaidCombatJournal(workspace); var plan = plan(workspace, 1);
            journal.prepare(actor, plan); journal.beginHit(actor, plan.id(), plan.before());
            var reopened = new MaidCombatJournal(workspace); var op = reopened.get(actor, plan.id());
            assertEquals(MaidCombatJournal.State.RECOVERY_REQUIRED, op.state()); assertFalse(op.settled()); assertFalse(MaidCombatJournal.reviewable(op));
            assertThrows(IllegalStateException.class, () -> reopened.reconciled(actor, review(workspace, op), AiJson.digest("current-only-proof")));
            assertThrows(IllegalStateException.class, () -> reopened.beginHit(actor, plan.id(), plan.before()));
        }
    }
    @Test void unknownEffectsRetainActualGroundLootAndExperienceWithoutPermittingAReplayOrReconciliation() throws Exception {
        try (var manager = new WorkspaceManager(root)) {
            var workspace = manager.getWorkspace(actor); var journal = new MaidCombatJournal(workspace); var plan = plan(workspace, 1);
            journal.prepare(actor, plan); journal.beginHit(actor, plan.id(), plan.before()); var effect = effect(plan, true, false, false);
            var op = journal.observed(actor, plan.id(), 0, effect);
            assertEquals(MaidCombatJournal.State.RECOVERY_REQUIRED, op.state()); assertEquals(effect.loot(), op.hits().getFirst().groundDrops());
            assertEquals(effect.experience(), op.hits().getFirst().experience()); assertFalse(op.killed()); assertFalse(MaidCombatJournal.reviewable(op));
            assertThrows(IllegalStateException.class, () -> journal.reconciled(actor, review(workspace, op), AiJson.digest("not-a-native-proof")));
        }
    }
    @Test void acceptedCurrentStatePreservesHistoricalHitsAndLateProofsNeverClaimTheOldGoalSucceeded() throws Exception {
        try (var manager = new WorkspaceManager(root)) {
            var workspace = manager.getWorkspace(actor); var journal = new MaidCombatJournal(workspace); var plan = plan(workspace, 1);
            journal.prepare(actor, plan); journal.beginHit(actor, plan.id(), plan.before()); journal.observed(actor, plan.id(), 0, effect(plan, true, true, true));
            journal.unknown(actor, plan.id(), "ORIGINAL_SAVE_NOT_CONFIRMED"); var op = journal.get(actor, plan.id()); assertTrue(MaidCombatJournal.reviewable(op));
            var history = op.hits(); journal.reconciled(actor, review(workspace, op), AiJson.digest("new-approved-current-state-native-write"));
            var current = journal.get(actor, plan.id()); assertTrue(current.settled()); assertFalse(current.killed()); assertEquals(history, current.hits());
            journal.saved(actor, plan.id(), 0, AiJson.digest("late-original-write")); current = journal.get(actor, plan.id());
            assertEquals(MaidCombatJournal.State.RECONCILED_CURRENT_STATE, current.state()); assertFalse(current.killed()); assertFalse(current.hits().getFirst().nativeProof().isEmpty());
        }
    }
    @Test void onlyProvenNoDispatchCanCloseAnIntentAndPublishedSettlementDoesNotTakeTheJournalMonitor() throws Exception {
        try (var manager = new WorkspaceManager(root)) {
            var workspace = manager.getWorkspace(actor); var journal = new MaidCombatJournal(workspace); var plan = plan(workspace, 1);
            journal.prepare(actor, plan); journal.beginHit(actor, plan.id(), plan.before()); journal.notDispatched(actor, plan.id(), 0, "REJECTED_BEFORE_NATIVE_INVOCATION");
            var op = journal.finish(actor, plan.id(), "CANCELLED_BEFORE_NATIVE_ATTACK"); assertTrue(op.settled()); assertFalse(op.entered());
            synchronized (journal) {
                assertTrue(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    synchronized (workspace.conversation()) { return journal.taskSettled(actor, plan.taskId()); }
                }).get(2, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThrows(SecurityException.class, () -> journal.snapshot(Actor.player(UUID.randomUUID(), true, true, 4)));
        }
    }
}
