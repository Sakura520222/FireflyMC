package firefly520.fireflymc.ai.integration.maid;

import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.material.MaterialStack;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Native crop and real-inventory receipts. No output is computed or credited by this journal. */
final class MaidFarmJournal {
    enum State { PREPARED, NATIVE_INTENT, OBSERVED, SAVED, REJECTED, RECOVERY_REQUIRED, RECONCILED_CURRENT_STATE }
    record Position(int x, int y, int z) {}
    record Evidence(boolean verified, boolean achieved, MaidFarmPlan.BlockView crop, MaidFarmPlan.BlockView soil,
                    List<MaterialStack> inventory, MaterialStack hand, MaterialStack offHand,
                    List<Position> changed, String reason) {
        Evidence { inventory = List.copyOf(inventory); changed = List.copyOf(changed); }
    }
    record Operation(int version, MaidFarmPlan plan, State state, boolean entered, Evidence evidence,
                     String nativeProof, String reason, long revision) {
        boolean settled() { return state == State.PREPARED && !entered || state == State.REJECTED && !entered
                || state == State.SAVED && entered && evidence != null && evidence.verified() && nativeProof.matches("[0-9a-f]{64}")
                || state == State.RECONCILED_CURRENT_STATE && evidence != null && nativeProof.matches("[0-9a-f]{64}"); }
    }
    private final Workspace workspace;
    private final long epoch;
    private final ConversationStore journal;
    private final Map<UUID, Operation> operations = new LinkedHashMap<>(), published = new ConcurrentHashMap<>();
    private volatile boolean broken;
    MaidFarmJournal(Workspace workspace) throws IOException {
        this.workspace = workspace;
        synchronized (workspace.conversation()) {
            epoch = workspace.conversation().privacyEpoch(); Path dir = workspace.directory().resolve("maid-farm"), file = dir.resolve("journal.jsonl");
            if (Files.isSymbolicLink(dir) || Files.isSymbolicLink(file)) throw new IOException("MAID_FARM_UNSAFE_PATH");
            journal = new ConversationStore(file);
            for (var event : journal.getSnapshot(0).events()) {
                if (!event.kind().equals("maid_farm_operation")) throw new IOException("MAID_FARM_JOURNAL_EVENT");
                var value = AiJson.GSON.fromJson(event.data(), Operation.class); validate(value);
                var old = operations.get(value.plan().id());
                if (value.revision() != (old == null ? 1 : old.revision() + 1) || old != null && !old.plan().equals(value.plan()))
                    throw new IOException("MAID_FARM_JOURNAL_IDENTITY");
                operations.put(value.plan().id(), value);
            }
            for (var op : List.copyOf(operations.values())) if (!op.settled() && op.state() != State.RECOVERY_REQUIRED)
                persist(copy(op, State.RECOVERY_REQUIRED, op.entered(), op.evidence(), op.nativeProof(), "RESTART_REQUIRES_NATIVE_CROP_REVIEW"));
            published.putAll(operations);
        }
    }
    synchronized Operation prepare(Actor actor, MaidFarmPlan plan) throws IOException {
        access(actor); actor.requireAccess(plan.owner());
        var old = operations.get(plan.id());
        if (old != null) {
            if (!old.plan().equals(plan)) throw new SecurityException("MAID_FARM_OPERATION_REUSED"); return old;
        }
        if (operations.values().stream().filter(op -> !op.settled()).count() >= 16) throw new IllegalStateException("MAID_FARM_PENDING_LIMIT");
        return persist(new Operation(1, plan, State.PREPARED, false, null, "", "WAITING_NATIVE_WORK_RATE", 1));
    }
    synchronized Operation begin(Actor actor, UUID id) throws IOException {
        var op = get(actor, id); if (op.state() != State.PREPARED || op.entered()) throw new IllegalStateException("MAID_FARM_ALREADY_RECORDED");
        return persist(copy(op, State.NATIVE_INTENT, false, null, "", ""));
    }
    synchronized Operation observed(Actor actor, UUID id, MaidFarmNative.Outcome effect) throws IOException {
        var op = get(actor, id);
        if (op.state() != State.NATIVE_INTENT && op.state() != State.RECOVERY_REQUIRED || op.evidence() != null)
            throw new IllegalStateException("MAID_FARM_NATIVE_EVIDENCE_ALREADY_RECORDED");
        var evidence = new Evidence(effect.verified() && effect.save() != null, effect.achieved(), effect.crop(), effect.soil(), effect.inventory(), effect.hand(), effect.offHand(),
                effect.changedPositions().stream().map(pos -> new Position(pos.getX(), pos.getY(), pos.getZ())).sorted(Comparator.comparingInt(Position::y)
                    .thenComparingInt(Position::x).thenComparingInt(Position::z)).toList(), effect.reason());
        State state = !effect.entered() ? State.REJECTED : evidence.verified() && op.state() != State.RECOVERY_REQUIRED ? State.OBSERVED : State.RECOVERY_REQUIRED;
        return persist(copy(op, state, effect.entered(), evidence, "", effect.reason()));
    }
    synchronized Operation rejectBeforeNative(Actor actor, UUID id, String reason) throws IOException {
        var op = get(actor, id);
        if (op.state() != State.PREPARED && op.state() != State.NATIVE_INTENT || op.entered()) throw new IllegalStateException("MAID_FARM_NATIVE_RESULT_UNCERTAIN");
        return persist(copy(op, State.REJECTED, false, null, "", reason));
    }
    synchronized Operation unknown(Actor actor, UUID id, String reason) throws IOException {
        var op = get(actor, id); if (op.settled()) return op;
        return persist(copy(op, State.RECOVERY_REQUIRED, op.entered(), op.evidence(), op.nativeProof(), reason));
    }
    synchronized void saved(Actor actor, UUID id, String proof) throws IOException {
        var op = get(actor, id);
        if (op.state() == State.RECONCILED_CURRENT_STATE) {
            var data = new com.google.gson.JsonObject(); data.addProperty("operationId", id.toString()); data.addProperty("proof", proof); data.addProperty("originalGoalSuccessClaimed", false);
            workspace.conversation().append(List.of(new Draft(op.plan().taskId(), "maid_farm_late_proof", null, data, id.toString()))); return;
        }
        if (proof == null || !proof.matches("[0-9a-f]{64}") || !op.entered() || op.evidence() == null)
            throw new IllegalStateException("MAID_FARM_POSITIVE_PROOF_REQUIRED");
        if (!op.nativeProof().isEmpty()) { if (!op.nativeProof().equals(proof)) throw new IllegalStateException("MAID_FARM_PROOF_CHANGED"); return; }
        persist(copy(op, op.state() == State.OBSERVED && op.evidence().verified() ? State.SAVED : State.RECOVERY_REQUIRED,
                true, op.evidence(), proof, op.reason()));
    }
    synchronized void beginReconcile(Actor actor, MaidFarmReconcile review) throws IOException {
        var op = get(actor, review.operationId());
        if (op.settled() || op.evidence() == null || !op.evidence().verified() || op.revision() != review.operationRevision()
                || !AiJson.digest(AiJson.canonical(op)).equals(review.operationDigest()) || !op.plan().taskId().equals(review.taskId()))
            throw new IllegalStateException("MAID_FARM_RECONCILE_VERSION_OR_UNKNOWN_EFFECT");
        persist(copy(op, State.RECOVERY_REQUIRED, op.entered(), op.evidence(), op.nativeProof(), "CURRENT_STATE_RECONCILE_PENDING:" + review.hash()));
    }
    synchronized void requireReconcile(Actor actor, MaidFarmReconcile review) {
        var op = get(actor, review.operationId());
        if (op.state() != State.RECOVERY_REQUIRED || !op.reason().equals("CURRENT_STATE_RECONCILE_PENDING:" + review.hash()))
            throw new IllegalStateException("MAID_FARM_RECONCILE_SUPERSEDED");
    }
    synchronized void currentProof(Actor actor, MaidFarmReconcile review, String proof) throws IOException {
        requireReconcile(actor, review);
        if (proof == null || !proof.matches("[0-9a-f]{64}")) throw new IllegalStateException("MAID_FARM_CURRENT_PROOF_REQUIRED");
        var data = new com.google.gson.JsonObject(); data.addProperty("reviewId", review.id().toString()); data.addProperty("hash", review.hash());
        data.addProperty("operationId", review.operationId().toString()); data.addProperty("currentProof", proof);
        workspace.conversation().append(List.of(new Draft(review.taskId(), "maid_farm_current_state_proof", null, data, review.id().toString())));
    }
    synchronized Operation reconciled(Actor actor, MaidFarmReconcile review, String proof) throws IOException {
        requireReconcile(actor, review); if (proof == null || !proof.matches("[0-9a-f]{64}")) throw new IllegalStateException("MAID_FARM_CURRENT_PROOF_REQUIRED");
        var op = get(actor, review.operationId());
        var current = new Evidence(false, false, review.crop(), review.soil(), review.inventory(), review.hand(), review.offHand(), List.of(), "CURRENT_STATE_ONLY_NO_REPLAY_OR_REFUND");
        return persist(copy(op, State.RECONCILED_CURRENT_STATE, op.entered(), current, proof, "CURRENT_STATE_RECONCILED:" + review.hash()));
    }
    synchronized Operation get(Actor actor, UUID id) {
        access(actor); var value = operations.get(id); if (value == null) throw new IllegalArgumentException("MAID_FARM_OPERATION_NOT_FOUND"); return value;
    }
    Operation published(Actor actor, UUID id) { access(actor); return published.get(id); }
    List<Operation> snapshot(Actor actor) { access(actor); return List.copyOf(published.values()); }
    boolean settled(Actor actor) { access(actor); return !broken && published.values().stream().allMatch(Operation::settled); }
    boolean taskSettled(Actor actor, UUID task) { access(actor); return !broken && published.values().stream().filter(op -> op.plan().taskId().equals(task)).allMatch(Operation::settled); }
    boolean operationSettled(Actor actor, UUID id) { access(actor); var op = published.get(id); return !broken && op != null && op.settled(); }
    boolean forgettable(Actor actor, long currentEpoch) { actor.requireAccess(workspace.owner()); return currentEpoch > epoch && !broken && published.values().stream().allMatch(Operation::settled); }
    Set<UUID> erasedOperationIds(Actor actor, long currentEpoch) {
        if (!forgettable(actor, currentEpoch)) throw new IllegalStateException("MAID_FARM_PRIVACY_SETTLEMENT_REQUIRED"); return Set.copyOf(published.keySet());
    }
    synchronized String export(Actor actor) { access(actor); return AiJson.GSON.toJson(journal.getSnapshot(0).events()); }
    private Operation persist(Operation value) throws IOException {
        if (broken) throw new IOException("MAID_FARM_JOURNAL_UNAVAILABLE"); validate(value);
        synchronized (workspace.conversation()) {
            if (workspace.conversation().privacyEpoch() != epoch) throw new IllegalStateException("WORKSPACE_PRIVACY_EPOCH_CHANGED");
            var data = AiJson.GSON.toJsonTree(value).getAsJsonObject();
            if (data.toString().length() > 4 * 1024 * 1024) throw new IOException("MAID_FARM_EVIDENCE_TOO_LARGE");
            try {
                journal.append(List.of(new Draft(value.plan().taskId(), "maid_farm_operation", null, data, value.plan().id().toString())));
                operations.put(value.plan().id(), value);
                workspace.conversation().append(List.of(new Draft(value.plan().taskId(), "maid_farm_evidence", null, data, value.plan().id().toString())));
                published.put(value.plan().id(), value); return value;
            } catch (IOException | RuntimeException failure) { broken = true; throw failure; }
        }
    }
    private void access(Actor actor) { actor.requireAccess(workspace.owner()); if (workspace.conversation().privacyEpoch() != epoch) throw new IllegalStateException("WORKSPACE_PRIVACY_EPOCH_CHANGED"); }
    private void validate(Operation op) throws IOException {
        if (op == null || op.version() != 1 || op.plan() == null || !op.plan().intact() || !op.plan().owner().equals(workspace.owner())
                || op.plan().privacyEpoch() != epoch || op.state() == null || op.nativeProof() == null || op.reason() == null || op.revision() < 1
                || !op.nativeProof().isEmpty() && !op.nativeProof().matches("[0-9a-f]{64}")
                || op.state() == State.SAVED && (!op.entered() || op.evidence() == null || !op.evidence().verified() || op.nativeProof().isEmpty()))
            throw new IOException("MAID_FARM_JOURNAL_INVALID");
    }
    private static Operation copy(Operation op, State state, boolean entered, Evidence evidence, String proof, String reason) {
        return new Operation(op.version(), op.plan(), state, entered, evidence, proof, reason, op.revision() + 1);
    }
}
