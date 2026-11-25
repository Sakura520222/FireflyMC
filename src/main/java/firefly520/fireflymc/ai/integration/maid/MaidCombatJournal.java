package firefly520.fireflymc.ai.integration.maid;

import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Per-strike before-intent, immutable native observations, and positive canonical write proofs. */
final class MaidCombatJournal {
    enum State { PREPARED, HIT_INTENT, OBSERVED, SAVED, REJECTED, RECOVERY_REQUIRED, RECONCILED_CURRENT_STATE }
    record Hit(int index, MaidMeleeKernel.Snapshot before, MaidMeleeKernel.Snapshot observedBefore, MaidMeleeKernel.Snapshot after, MaidMeleeKernel.Target targetAfter,
            boolean entered, boolean attempted, boolean verified, boolean killed, List<MaidCombatScope.Drop> groundDrops,
            List<String> experience, String reason, String nativeProof) {
        Hit { groundDrops = List.copyOf(groundDrops); experience = List.copyOf(experience); }
    }
    record Operation(int version, MaidCombatPlan plan, State state, List<Hit> hits, boolean finished, String reason,
            String currentStateProof, long revision) {
        Operation { hits = List.copyOf(hits); }
        boolean settled() { return state == State.REJECTED || state == State.SAVED || state == State.RECONCILED_CURRENT_STATE; }
        boolean killed() { return state == State.SAVED && hits.stream().anyMatch(Hit::killed); }
        boolean entered() { return hits.stream().anyMatch(Hit::entered); }
    }
    private final Workspace workspace;
    private final long epoch;
    private final ConversationStore journal;
    private final Map<UUID, Operation> operations = new LinkedHashMap<>(), published = new ConcurrentHashMap<>();
    private volatile boolean broken;
    MaidCombatJournal(Workspace workspace) throws IOException {
        this.workspace = workspace;
        synchronized (workspace.conversation()) {
            epoch = workspace.conversation().privacyEpoch(); Path dir = workspace.directory().resolve("maid-combat"), file = dir.resolve("journal.jsonl");
            if (Files.isSymbolicLink(dir) || Files.isSymbolicLink(file)) throw new IOException("MAID_COMBAT_UNSAFE_PATH");
            journal = new ConversationStore(file);
            for (var event : journal.getSnapshot(0).events()) {
                if (!event.kind().equals("maid_combat_operation")) throw new IOException("MAID_COMBAT_JOURNAL_EVENT");
                var op = AiJson.GSON.fromJson(event.data(), Operation.class); validate(op); var old = operations.get(op.plan().id());
                if (op.revision() != (old == null ? 1 : old.revision() + 1) || old != null && !old.plan().equals(op.plan()))
                    throw new IOException("MAID_COMBAT_JOURNAL_IDENTITY");
                operations.put(op.plan().id(), op);
            }
            for (var op : List.copyOf(operations.values())) if (!op.settled()) {
                if (op.state() == State.PREPARED && op.hits().isEmpty()) persist(copy(op, State.REJECTED, op.hits(), true, "RESTART_BEFORE_NATIVE_INTENT", ""));
                else persist(copy(op, State.RECOVERY_REQUIRED, op.hits(), true, "RESTART_REQUIRES_NATIVE_COMBAT_REVIEW", ""));
            }
            published.putAll(operations);
        }
    }
    synchronized Operation prepare(Actor actor, MaidCombatPlan plan) throws IOException {
        access(actor); plan.requireStorageIdentity(workspace, actor); var old = operations.get(plan.id());
        if (old != null) { if (!old.plan().equals(plan)) throw new SecurityException("MAID_COMBAT_OPERATION_REUSED"); return old; }
        if (operations.values().stream().anyMatch(op -> !op.settled())) throw new IllegalStateException("MAID_COMBAT_REVIEW_REQUIRED");
        return persist(new Operation(1, plan, State.PREPARED, List.of(), false, "WAITING_NATIVE_NAVIGATION_AND_COOLDOWN", "", 1));
    }
    synchronized int beginHit(Actor actor, UUID id, MaidMeleeKernel.Snapshot before) throws IOException {
        var op = get(actor, id);
        if (op.finished() || op.settled() || op.state() == State.HIT_INTENT || op.state() == State.RECOVERY_REQUIRED || op.hits().size() >= op.plan().maximumHits())
            throw new IllegalStateException("MAID_COMBAT_STRIKE_BUDGET_OR_STATE");
        int index = op.hits().size(); var hits = new ArrayList<>(op.hits());
        hits.add(new Hit(index, before, null, null, null, false, false, false, false, List.of(), List.of(), "NATIVE_INTENT", ""));
        persist(copy(op, State.HIT_INTENT, hits, false, "NATIVE_INTENT", "")); return index;
    }
    synchronized Operation observed(Actor actor, UUID id, int index, MaidMeleeKernel.Outcome outcome) throws IOException {
        var op = get(actor, id);
        if (op.state() != State.HIT_INTENT || index != op.hits().size() - 1 || !op.hits().get(index).reason().equals("NATIVE_INTENT"))
            throw new IllegalStateException("MAID_COMBAT_STRIKE_ALREADY_RECORDED");
        var old = op.hits().get(index); var hits = new ArrayList<>(op.hits());
        hits.set(index, new Hit(index, old.before(), outcome.before(), outcome.after(), outcome.targetAfter(), outcome.entered(), outcome.attempted(), outcome.verified() && outcome.save() != null,
                outcome.killed(), outcome.loot(), outcome.experience(), outcome.reason(), ""));
        boolean uncertain = outcome.entered() && (!outcome.verified() || outcome.save() == null);
        return persist(copy(op, uncertain ? State.RECOVERY_REQUIRED : State.OBSERVED, hits, uncertain, outcome.reason(), ""));
    }
    synchronized Operation notDispatched(Actor actor, UUID id, int index, String reason) throws IOException {
        var op = get(actor, id);
        if (op.state() != State.HIT_INTENT || index != op.hits().size() - 1 || !op.hits().get(index).reason().equals("NATIVE_INTENT"))
            throw new IllegalStateException("MAID_COMBAT_STRIKE_ALREADY_RECORDED");
        var old = op.hits().get(index); var hits = new ArrayList<>(op.hits());
        hits.set(index, new Hit(index, old.before(), null, old.before(), old.before().target(), false, false, false, false, List.of(), List.of(), reason, ""));
        return persist(copy(op, State.OBSERVED, hits, false, reason, ""));
    }
    synchronized Operation saved(Actor actor, UUID id, int index, String proof) throws IOException {
        var op = get(actor, id);
        if (proof == null || !proof.matches("[0-9a-f]{64}") || index < 0 || index >= op.hits().size()) throw new IllegalStateException("MAID_COMBAT_POSITIVE_PROOF_REQUIRED");
        var hit = op.hits().get(index);
        if (!hit.entered() || !hit.verified()) throw new IllegalStateException("MAID_COMBAT_OBSERVATION_REQUIRED");
        if (!hit.nativeProof().isEmpty()) { if (!hit.nativeProof().equals(proof)) throw new IllegalStateException("MAID_COMBAT_PROOF_CHANGED"); return op; }
        var hits = new ArrayList<>(op.hits());
        hits.set(index, new Hit(index, hit.before(), hit.observedBefore(), hit.after(), hit.targetAfter(), hit.entered(), hit.attempted(), hit.verified(), hit.killed(), hit.groundDrops(), hit.experience(), hit.reason(), proof));
        var next = copy(op, op.state(), hits, op.finished(), op.reason(), op.currentStateProof());
        return persist(next.state() == State.RECOVERY_REQUIRED || next.state() == State.RECONCILED_CURRENT_STATE ? next : settle(next));
    }
    synchronized Operation finish(Actor actor, UUID id, String reason) throws IOException {
        var op = get(actor, id); if (op.settled() || op.state() == State.RECOVERY_REQUIRED) return op;
        if (op.state() == State.HIT_INTENT) return unknown(actor, id, "MAID_COMBAT_NATIVE_RESULT_UNCERTAIN");
        return persist(settle(copy(op, op.entered() ? State.OBSERVED : State.REJECTED, op.hits(), true, reason, "")));
    }
    synchronized Operation unknown(Actor actor, UUID id, String reason) throws IOException {
        var op = get(actor, id); if (op.settled()) return op;
        return persist(copy(op, State.RECOVERY_REQUIRED, op.hits(), true, reason, op.currentStateProof()));
    }
    synchronized Operation reconciled(Actor actor, MaidCombatReconcile review, String proof) throws IOException {
        var op = get(actor, review.operationId());
        if (op.settled() || op.revision() != review.operationRevision() || !AiJson.digest(AiJson.canonical(op)).equals(review.operationDigest())
                || op.state() == State.HIT_INTENT || !reviewable(op)
                || proof == null || !proof.matches("[0-9a-f]{64}")) throw new IllegalStateException("MAID_COMBAT_RECONCILE_CHANGED_OR_UNKNOWN_EFFECT");
        return persist(copy(op, State.RECONCILED_CURRENT_STATE, op.hits(), true, "CURRENT_STATE_ONLY_NO_REPLAY_OR_REWARD:" + review.hash(), proof));
    }
    static boolean reviewable(Operation op) {
        return op.state() != State.HIT_INTENT && op.hits().stream().noneMatch(hit -> hit.reason().equals("NATIVE_INTENT") || hit.entered() && !hit.verified());
    }
    private static Operation settle(Operation op) {
        if (op.finished() && op.hits().stream().allMatch(hit -> !hit.entered() || hit.verified() && !hit.nativeProof().isEmpty()))
            return new Operation(op.version(), op.plan(), op.entered() ? State.SAVED : State.REJECTED, op.hits(), true, op.reason(), op.currentStateProof(), op.revision());
        return op;
    }
    synchronized Operation get(Actor actor, UUID id) { access(actor); var value = operations.get(id); if (value == null) throw new IllegalArgumentException("MAID_COMBAT_OPERATION_NOT_FOUND"); return value; }
    Operation published(Actor actor, UUID id) { access(actor); return published.get(id); }
    List<Operation> snapshot(Actor actor) { access(actor); return List.copyOf(published.values()); }
    boolean settled(Actor actor) { access(actor); return !broken && published.values().stream().allMatch(Operation::settled); }
    boolean taskSettled(Actor actor, UUID task) { access(actor); return !broken && published.values().stream().filter(op -> op.plan().taskId().equals(task)).allMatch(Operation::settled); }
    boolean operationSettled(Actor actor, UUID id) { access(actor); var op = published.get(id); return !broken && op != null && op.settled(); }
    boolean forgettable(Actor actor, long nextEpoch) { actor.requireAccess(workspace.owner()); return nextEpoch > epoch && !broken && published.values().stream().allMatch(Operation::settled); }
    String export(Actor actor) { access(actor); return AiJson.GSON.toJson(journal.getSnapshot(0).events()); }
    private Operation persist(Operation op) throws IOException {
        if (broken) throw new IOException("MAID_COMBAT_JOURNAL_UNAVAILABLE"); validate(op);
        synchronized (workspace.conversation()) {
            access(new Actor(workspace.owner(), true, false, 0)); var data = AiJson.GSON.toJsonTree(op).getAsJsonObject();
            if (data.toString().length() > 4 * 1024 * 1024) throw new IOException("MAID_COMBAT_EVIDENCE_TOO_LARGE");
            try {
                journal.append(List.of(new Draft(op.plan().taskId(), "maid_combat_operation", null, data, op.plan().id().toString())));
                operations.put(op.plan().id(), op);
                workspace.conversation().append(List.of(new Draft(op.plan().taskId(), "maid_combat_evidence", null, data, op.plan().id().toString())));
                published.put(op.plan().id(), op); return op;
            } catch (IOException | RuntimeException failure) { broken = true; throw failure; }
        }
    }
    private void access(Actor actor) { actor.requireAccess(workspace.owner()); if (workspace.conversation().privacyEpoch() != epoch) throw new IllegalStateException("WORKSPACE_PRIVACY_EPOCH_CHANGED"); }
    private void validate(Operation op) throws IOException {
        if (op == null || op.version() != 1 || op.plan() == null || !op.plan().intact() || !op.plan().owner().equals(workspace.owner())
                || op.plan().privacyEpoch() != epoch || op.state() == null || op.reason() == null || op.currentStateProof() == null || op.revision() < 1
                || op.hits().size() > op.plan().maximumHits()) throw new IOException("MAID_COMBAT_JOURNAL_INVALID");
        for (int i = 0; i < op.hits().size(); i++) {
            var hit = op.hits().get(i);
            if (hit.index() != i || hit.before() == null || hit.nativeProof() == null || hit.reason() == null || hit.groundDrops().size() > 128
                    || !hit.nativeProof().isEmpty() && !hit.nativeProof().matches("[0-9a-f]{64}")
                    || !hit.nativeProof().isEmpty() && (!hit.entered() || !hit.verified())
                    || hit.verified() && (hit.observedBefore() == null || hit.after() == null || hit.targetAfter() == null)) throw new IOException("MAID_COMBAT_HIT_INVALID");
        }
        if (op.state() == State.SAVED && (!op.finished() || !op.entered() || op.hits().stream().anyMatch(hit -> hit.entered() && (!hit.verified() || hit.nativeProof().isEmpty()))))
            throw new IOException("MAID_COMBAT_SAVED_WITHOUT_PROOF");
        if (op.state() == State.REJECTED && (!op.finished() || op.entered() || !reviewable(op))
                || op.state() == State.RECONCILED_CURRENT_STATE && (!op.finished() || !reviewable(op) || !op.currentStateProof().matches("[0-9a-f]{64}")))
            throw new IOException("MAID_COMBAT_SETTLED_WITHOUT_EVIDENCE");
    }
    private static Operation copy(Operation op, State state, List<Hit> hits, boolean finished, String reason, String proof) {
        return new Operation(op.version(), op.plan(), state, hits, finished, reason, proof, op.revision() + 1);
    }
}
