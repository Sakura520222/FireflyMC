package firefly520.fireflymc.ai.integration.maid;

import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Actual native mode receipts. A saved receipt never licenses replay of a mode callback. */
final class MaidControlJournal {
    enum State { INTENT, OBSERVED, SAVED, NO_CHANGE, REJECTED, RECOVERY_REQUIRED, RECONCILED_CURRENT_STATE }
    record Operation(int version, String owner, UUID id, UUID taskId, long privacyEpoch, String planHash,
            MaidControlPlan.Mode mode, MaidControlPlan.NativeState before, MaidControlPlan.NativeState after,
            State state, boolean entered, boolean changed, boolean verified, String nativeProof, String reason, long revision) {
        boolean settled() { return state == State.SAVED && verified && nativeProof.matches("[0-9a-f]{64}")
                || state == State.NO_CHANGE && verified && !entered || state == State.REJECTED && !entered
                || state == State.RECONCILED_CURRENT_STATE && after != null && nativeProof.matches("[0-9a-f]{64}"); }
    }
    private final Workspace workspace;
    private final ConversationStore journal;
    private final long privacyEpoch;
    private final Map<UUID, Operation> operations = new LinkedHashMap<>();
    private volatile boolean broken;
    private final Map<UUID, Operation> published = new java.util.concurrent.ConcurrentHashMap<>();

    MaidControlJournal(Workspace workspace) throws IOException {
        this.workspace = workspace;
        synchronized (workspace.conversation()) {
            privacyEpoch = workspace.conversation().privacyEpoch();
            Path folder = workspace.directory().resolve("maid-control"), file = folder.resolve("journal.jsonl");
            if (Files.isSymbolicLink(folder) || Files.isSymbolicLink(file)) throw new IOException("MAID_CONTROL_UNSAFE_PATH");
            journal = new ConversationStore(file);
            for (var event : journal.getSnapshot(0).events()) {
                if (!event.kind().equals("maid_control_operation")) throw new IOException("MAID_CONTROL_JOURNAL_EVENT");
                var value = AiJson.GSON.fromJson(event.data(), Operation.class); validate(value);
                if (value.privacyEpoch() != privacyEpoch) throw new IOException("MAID_CONTROL_PRIVACY_EPOCH_MISMATCH");
                var before = operations.get(value.id());
                if (value.revision() != (before == null ? 1 : before.revision() + 1) || before != null && !sameIntent(before, value))
                    throw new IOException("MAID_CONTROL_JOURNAL_IDENTITY");
                operations.put(value.id(), value);
            }
            for (var value : List.copyOf(operations.values())) if (!value.settled() && value.state() != State.RECOVERY_REQUIRED)
                persist(copy(value, State.RECOVERY_REQUIRED, value.entered(), value.changed(), value.verified(), value.after(), value.nativeProof(), "RESTART_REQUIRES_NATIVE_MODE_REVIEW"));
            published.putAll(operations);
        }
    }
    synchronized Operation get(Actor actor, UUID id) {
        access(actor); var value = operations.get(id);
        if (value == null) throw new IllegalArgumentException("MAID_CONTROL_OPERATION_NOT_FOUND"); return value;
    }
    synchronized Optional<Operation> find(Actor actor, UUID id) { access(actor); return Optional.ofNullable(operations.get(id)); }
    synchronized List<Operation> list(Actor actor) { access(actor); return List.copyOf(operations.values()); }
    List<Operation> snapshot(Actor actor) { access(actor); return List.copyOf(published.values()); }
    Operation publishedOperation(Actor actor, UUID id) { access(actor); return published.get(id); }
    synchronized Operation intent(Actor actor, MaidControlPlan plan) throws IOException {
        access(actor); actor.requireAccess(plan.owner());
        var duplicate = operations.get(plan.id());
        if (duplicate != null) {
            if (!duplicate.taskId().equals(plan.taskId()) || !duplicate.planHash().equals(plan.hash())) throw new SecurityException("MAID_CONTROL_OPERATION_REUSED");
            throw new IllegalStateException("MAID_CONTROL_ALREADY_RECORDED");
        }
        if (operations.values().stream().filter(value -> !value.settled()).count() >= 16) throw new IllegalStateException("MAID_CONTROL_PENDING_LIMIT");
        return persist(new Operation(1, actor.owner(), plan.id(), plan.taskId(), privacyEpoch, plan.hash(), plan.mode(), plan.before(), null,
                State.INTENT, false, false, false, "", "", 1));
    }
    synchronized Operation observed(Actor actor, UUID id, MaidControlNative.Outcome effect) throws IOException {
        var op = get(actor, id);
        if (op.state() == State.RECONCILED_CURRENT_STATE) {
            var data = new com.google.gson.JsonObject(); data.addProperty("entered", effect.entered()); data.addProperty("changed", effect.changed());
            data.addProperty("verified", effect.verified()); data.addProperty("error", effect.error()); data.add("observed", AiJson.GSON.toJsonTree(effect.after()));
            note(op, "maid_control_late_native_effect", data.toString()); return op;
        }
        if (op.state() != State.INTENT && op.state() != State.RECOVERY_REQUIRED || op.entered() || op.after() != null)
            throw new IllegalStateException("MAID_CONTROL_EVIDENCE_ALREADY_RECORDED");
        boolean covered = effect.verified() && (effect.changed() ? effect.entered() && effect.save() != null
                : !effect.entered() && op.before().equals(effect.after()))
                && effect.after() != null && effect.after().modeObserved(op.mode());
        String reason = effect.verified() && !covered ? "MAID_NATIVE_SAVE_OR_MODE_UNCOVERED" : effect.error();
        boolean reconciling = op.reason().startsWith("CURRENT_STATE_RECONCILE_PENDING:");
        State state = reconciling ? State.RECOVERY_REQUIRED : covered ? effect.changed() ? State.OBSERVED : State.NO_CHANGE
                : effect.entered() || effect.changed() || effect.verified() ? State.RECOVERY_REQUIRED : State.REJECTED;
        return persist(copy(op, state, effect.entered(), effect.changed(), covered, effect.after(), "", reconciling ? op.reason() : reason));
    }
    synchronized Operation rejected(Actor actor, UUID id, String reason) throws IOException {
        var op = get(actor, id);
        if (op.state() == State.RECONCILED_CURRENT_STATE || op.reason().startsWith("CURRENT_STATE_RECONCILE_PENDING:")) {
            note(op, "maid_control_late_non_dispatch", reason); return op;
        }
        if (op.state() != State.INTENT || op.entered()) throw new IllegalStateException("MAID_CONTROL_NATIVE_RESULT_UNCERTAIN");
        return persist(copy(op, State.REJECTED, false, false, false, null, "", reason));
    }
    synchronized Operation uncertain(Actor actor, UUID id, String reason) throws IOException {
        var op = get(actor, id);
        if (op.settled()) return op;
        return persist(copy(op, State.RECOVERY_REQUIRED, op.entered(), op.changed(), op.verified(), op.after(), op.nativeProof(), reason));
    }
    synchronized Operation saved(Actor actor, UUID id, String proof) throws IOException {
        var op = get(actor, id);
        if (op.state() == State.RECONCILED_CURRENT_STATE) {
            if (proof == null || !proof.matches("[0-9a-f]{64}")) throw new IllegalStateException("MAID_CONTROL_NATIVE_PROOF_INVALID");
            note(op, "maid_control_late_native_proof", proof); return op;
        }
        if (proof == null || !proof.matches("[0-9a-f]{64}") || !op.entered()
                || !Set.of(State.OBSERVED, State.SAVED, State.RECOVERY_REQUIRED).contains(op.state())) throw new IllegalStateException("MAID_CONTROL_NATIVE_PROOF_INVALID");
        if (!op.nativeProof().isEmpty()) {
            if (!op.nativeProof().equals(proof)) throw new IllegalStateException("MAID_CONTROL_NATIVE_PROOF_CHANGED"); return op;
        }
        return persist(copy(op, op.state() == State.OBSERVED && op.verified() ? State.SAVED : State.RECOVERY_REQUIRED,
                true, op.changed(), op.verified(), op.after(), proof, op.reason()));
    }
    synchronized Operation beginReconciliation(Actor actor, MaidControlReconcile review) throws IOException {
        var op = get(actor, review.operationId());
        if (op.settled() || op.revision() != review.operationRevision() || !AiJson.digest(AiJson.canonical(op)).equals(review.operationDigest())
                || !op.taskId().equals(review.taskId()) || !op.before().maidId().equals(review.observed().maidId()))
            throw new IllegalStateException("MAID_RECONCILE_PREVIEW_CHANGED");
        return persist(copy(op, State.RECOVERY_REQUIRED, op.entered(), op.changed(), op.verified(), op.after(), op.nativeProof(),
                "CURRENT_STATE_RECONCILE_PENDING:" + review.hash()));
    }
    synchronized void requireReconciliation(Actor actor, MaidControlReconcile review) {
        var op = get(actor, review.operationId());
        if (op.state() != State.RECOVERY_REQUIRED || !op.reason().equals("CURRENT_STATE_RECONCILE_PENDING:" + review.hash()))
            throw new IllegalStateException("MAID_RECONCILE_SUPERSEDED");
    }
    synchronized Operation reconciled(Actor actor, MaidControlReconcile review, String currentProof) throws IOException {
        requireReconciliation(actor, review); var op = get(actor, review.operationId());
        if (currentProof == null || !currentProof.matches("[0-9a-f]{64}")) throw new IllegalStateException("MAID_RECONCILE_POSITIVE_PROOF_REQUIRED");
        return persist(copy(op, State.RECONCILED_CURRENT_STATE, op.entered(), op.changed(), false, review.observed(), currentProof,
                "CURRENT_STATE_RECONCILED:" + review.hash() + ";ORIGINAL_MODE_SUCCESS_NOT_CLAIMED"));
    }
    @FunctionalInterface interface Retirement { void retire(Operation committed) throws Exception; }
    synchronized Operation reconcileAndRetire(Actor actor, MaidControlReconcile review, String currentProof, Retirement retirement) throws Exception {
        var committed = reconciled(actor, review, currentProof);
        retirement.retire(committed);
        return committed;
    }
    synchronized void currentProof(Actor actor, MaidControlReconcile review, String proof) throws IOException {
        requireReconciliation(actor, review);
        if (proof == null || !proof.matches("[0-9a-f]{64}")) throw new IllegalStateException("MAID_RECONCILE_POSITIVE_PROOF_REQUIRED");
        var data = new com.google.gson.JsonObject(); data.addProperty("reviewId", review.id().toString()); data.addProperty("reviewHash", review.hash());
        data.addProperty("operationId", review.operationId().toString()); data.addProperty("canonicalCurrentStateProof", proof);
        workspace.conversation().append(List.of(new Draft(review.taskId(), "maid_control_current_state_proof", null, data, review.id().toString())));
    }
    private void note(Operation op, String kind, String value) throws IOException {
        var data = new com.google.gson.JsonObject(); data.addProperty("operationId", op.id().toString()); data.addProperty("evidence", value);
        data.addProperty("originalModeSuccessClaimed", false);
        workspace.conversation().append(List.of(new Draft(op.taskId(), kind, null, data, op.id().toString())));
    }
    boolean settled(Actor actor) { access(actor); return !broken && published.values().stream().allMatch(Operation::settled); }
    boolean forgettableAfterEpochAdvance(Actor actor, long currentEpoch) {
        actor.requireAccess(workspace.owner());
        return currentEpoch > privacyEpoch && !broken && published.values().stream().allMatch(Operation::settled);
    }
    Set<UUID> erasedOperationIds(Actor actor, long currentEpoch) {
        if (!forgettableAfterEpochAdvance(actor, currentEpoch)) throw new IllegalStateException("MAID_CONTROL_PRIVACY_SETTLEMENT_REQUIRED");
        return Set.copyOf(published.keySet());
    }
    synchronized String export(Actor actor) { access(actor); return AiJson.GSON.toJson(journal.getSnapshot(0).events()); }
    boolean operationSettled(Actor actor, UUID id) { access(actor); var op = published.get(id); return !broken && op != null && op.settled(); }
    boolean requiresRecovery(Actor actor, UUID task) {
        access(actor); return broken || published.values().stream().anyMatch(value -> value.taskId().equals(task) && !value.settled());
    }
    private Operation persist(Operation value) throws IOException {
        if (broken) throw new IOException("MAID_CONTROL_JOURNAL_UNAVAILABLE"); validate(value);
        synchronized (workspace.conversation()) {
            if (workspace.conversation().privacyEpoch() != privacyEpoch) throw new IllegalStateException("WORKSPACE_PRIVACY_EPOCH_CHANGED");
            var data = AiJson.GSON.toJsonTree(value).getAsJsonObject();
            try {
                journal.append(List.of(new Draft(value.taskId(), "maid_control_operation", null, data, value.id().toString())));
                operations.put(value.id(), value);
                workspace.conversation().append(List.of(new Draft(value.taskId(), "maid_control_evidence", null, data, value.id().toString())));
                published.put(value.id(), value);
                return value;
            } catch (IOException | RuntimeException failure) { broken = true; throw failure; }
        }
    }
    private void access(Actor actor) {
        actor.requireAccess(workspace.owner());
        if (workspace.conversation().privacyEpoch() != privacyEpoch) throw new IllegalStateException("WORKSPACE_PRIVACY_EPOCH_CHANGED");
    }
    private void validate(Operation value) throws IOException {
        if (value == null || value.version() != 1 || !workspace.owner().equals(value.owner()) || value.id() == null || value.taskId() == null
                || value.privacyEpoch() < 0 || value.planHash() == null || !value.planHash().matches("[0-9a-f]{64}")
                || value.mode() == null || value.before() == null || value.state() == null || value.nativeProof() == null
                || !value.nativeProof().isEmpty() && !value.nativeProof().matches("[0-9a-f]{64}") || value.reason() == null || value.revision() < 1
                || value.after() != null && !value.before().maidId().equals(value.after().maidId())
                || value.changed() && !value.entered()
                || value.verified() && (value.after() == null || !value.after().modeObserved(value.mode()))
                || !value.nativeProof().isEmpty() && !value.entered() && value.state() != State.RECONCILED_CURRENT_STATE
                || value.state() == State.SAVED && (!value.entered() || !value.changed() || !value.verified() || value.nativeProof().isEmpty())
                || value.state() == State.NO_CHANGE && (value.entered() || value.changed() || !value.verified())
                || value.state() == State.REJECTED && (value.entered() || value.changed() || value.verified())
                || value.state() == State.INTENT && (value.entered() || value.changed() || value.verified() || value.after() != null || !value.nativeProof().isEmpty()))
            throw new IOException("MAID_CONTROL_JOURNAL_INVALID");
    }
    private static boolean sameIntent(Operation a, Operation b) {
        return a.id().equals(b.id()) && a.owner().equals(b.owner()) && a.taskId().equals(b.taskId())
                && a.privacyEpoch() == b.privacyEpoch() && a.planHash().equals(b.planHash()) && a.mode() == b.mode() && a.before().equals(b.before());
    }
    private static Operation copy(Operation op, State state, boolean entered, boolean changed, boolean verified,
                                  MaidControlPlan.NativeState after, String proof, String reason) {
        return new Operation(op.version(), op.owner(), op.id(), op.taskId(), op.privacyEpoch(), op.planHash(), op.mode(), op.before(), after,
                state, entered, changed, verified, proof, reason, op.revision() + 1);
    }
}
