package firefly520.fireflymc.ai.integration.exploration;

import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Durable preparation facts only. Current world presence never substitutes for original generation/save evidence. */
public final class CompassPreparationJournal {
    public enum State { INTENT, DISPATCHED, READY, SAVED, NOT_ENTERED, RECOVERY_REQUIRED }
    public record Candidate(UUID attempt, int x, int z, State state, String reason, boolean nativeReady, String saveProof) {
        public Candidate {
            Objects.requireNonNull(attempt); Objects.requireNonNull(state);
            if (Math.abs((long) x) > 1_875_000 || Math.abs((long) z) > 1_875_000
                    || reason == null || reason.length() > 160 || saveProof == null
                    || !saveProof.isEmpty() && !saveProof.matches("[0-9a-f]{64}")
                    || !saveProof.isEmpty() && !nativeReady || state == State.SAVED && saveProof.isEmpty()
                    || Set.of(State.INTENT, State.DISPATCHED, State.NOT_ENTERED).contains(state) && (nativeReady || !saveProof.isEmpty())
                    || state == State.READY && (!nativeReady || !saveProof.isEmpty()))
                throw new IllegalArgumentException("COMPASS_PREPARATION_CANDIDATE_INVALID");
        }
        public boolean settled() { return state == State.NOT_ENTERED || state == State.SAVED; }
    }
    public record Operation(int version, UUID id, String owner, UUID taskId, UUID conversationId, UUID serverInstanceId,
                            long privacyEpoch, UUID executionEpoch, long registryGeneration, String searchHash, String preparationHash,
                            String dimension, String targetId, int originX, int originZ, int radiusBlocks, CompassPreparationBudget budget,
                            long revision, boolean admissionClosed, List<Candidate> candidates) {
        public Operation { candidates = List.copyOf(candidates); }
        public boolean settled() { return admissionClosed && candidates.stream().allMatch(Candidate::settled); }
    }

    private final Workspace workspace;
    private final long privacy;
    private final ConversationStore journal;
    private final Map<UUID, Operation> operations = new LinkedHashMap<>();
    private volatile List<Operation> published = List.of();
    private volatile boolean broken;

    public CompassPreparationJournal(Workspace workspace) throws IOException {
        this.workspace = workspace; privacy = workspace.conversation().privacyEpoch();
        Path namespace = workspace.directory().resolve("exploration"), folder = namespace.resolve("epoch-" + privacy),
                path = folder.resolve("preparation-journal.jsonl");
        if (Files.isSymbolicLink(namespace) || Files.isSymbolicLink(folder) || Files.isSymbolicLink(path))
            throw new IOException("COMPASS_PREPARATION_UNSAFE_PATH");
        journal = new ConversationStore(path);
        for (var event : journal.getSnapshot(0).events()) {
            if (!event.kind().equals("compass_preparation_operation")) throw new IOException("COMPASS_PREPARATION_EVENT_INVALID");
            Operation op;
            try { op = AiJson.GSON.fromJson(event.data(), Operation.class); validate(op); }
            catch (RuntimeException invalid) { throw new IOException("COMPASS_PREPARATION_JOURNAL_INVALID", invalid); }
            var prior = operations.get(op.id());
            if (op.revision() != (prior == null ? 1 : prior.revision() + 1)
                    || prior != null && !sameBinding(prior, op)) throw new IOException("COMPASS_PREPARATION_IDENTITY_CHANGED");
            operations.put(op.id(), op);
        }
        published = List.copyOf(operations.values());
        for (var op : List.copyOf(operations.values())) if (!op.settled()) {
            var next = op.candidates().stream().map(candidate -> candidate.settled() ? candidate
                    : candidate.state() == State.INTENT ? candidate(candidate, State.NOT_ENTERED, "RESTART_BEFORE_DISPATCH", false, "")
                    : candidate(candidate, State.RECOVERY_REQUIRED, "RESTART_NO_GENERATION_REPLAY", candidate.nativeReady(), candidate.saveProof())).toList();
            persist(copy(op, true, next));
        }
    }

    public synchronized Operation create(Actor actor, CompassPreparationPlan plan) throws IOException {
        access(actor); var search = plan.search(); var budget = plan.budget();
        plan.requireIdentity(workspace, actor, search.executionEpoch(), search.registryGeneration(), System.currentTimeMillis());
        if (operations.containsKey(search.id())) throw new IllegalStateException("COMPASS_PREPARATION_ALREADY_RECORDED");
        if (operations.values().stream().filter(op -> !op.settled()).count() >= 32) throw new IllegalStateException("COMPASS_PREPARATION_OPERATION_LIMIT");
        return persist(new Operation(1, search.id(), actor.owner(), search.taskId(), search.conversationId(), search.serverInstanceId(),
                privacy, search.executionEpoch(), search.registryGeneration(), search.hash(), plan.hash(), search.dimension(), search.targetId(),
                search.originX(), search.originZ(), search.budget().radiusBlocks(), budget, 1, false, List.of()));
    }

    public synchronized Operation intend(Actor actor, UUID id, UUID attempt, int x, int z) throws IOException {
        var op = get(actor, id);
        if (op.admissionClosed()) throw new IllegalStateException("COMPASS_PREPARATION_ADMISSION_CLOSED");
        if (op.candidates().size() >= op.budget().maximumChunks()) throw new IllegalStateException("COMPASS_PREPARATION_CHUNK_LIMIT");
        if (op.candidates().stream().anyMatch(candidate -> candidate.attempt().equals(attempt) || candidate.x() == x && candidate.z() == z))
            throw new IllegalStateException("COMPASS_PREPARATION_DUPLICATE_CANDIDATE");
        if (!within(op, x, z)) throw new IllegalArgumentException("COMPASS_PREPARATION_OUTSIDE_APPROVAL");
        var candidates = new ArrayList<>(op.candidates()); candidates.add(new Candidate(attempt, x, z, State.INTENT, "NATIVE_DISPATCH_NOT_ENTERED", false, ""));
        return persist(copy(op, false, candidates));
    }
    public synchronized Operation dispatched(Actor actor, UUID id, UUID attempt) throws IOException {
        if (get(actor, id).admissionClosed()) throw new IllegalStateException("COMPASS_PREPARATION_ADMISSION_CLOSED");
        return update(actor, id, attempt, before -> {
            if (before.state() != State.INTENT) throw new IllegalStateException("COMPASS_PREPARATION_DISPATCH_REPLAY_FORBIDDEN");
            return candidate(before, State.DISPATCHED, "NATIVE_FUTURE_PENDING", false, "");
        });
    }
    public synchronized Operation notEntered(Actor actor, UUID id, UUID attempt, String reason) throws IOException {
        return update(actor, id, attempt, before -> {
            if (before.state() != State.INTENT && before.state() != State.DISPATCHED
                    && !(before.state() == State.RECOVERY_REQUIRED && !before.nativeReady() && before.saveProof().isEmpty()))
                throw new IllegalStateException("COMPASS_PREPARATION_NATIVE_ALREADY_OBSERVED");
            return candidate(before, State.NOT_ENTERED, reason, false, "");
        });
    }
    public synchronized Operation ready(Actor actor, UUID id, UUID attempt) throws IOException {
        return ready(actor, id, attempt, "NATIVE_STRUCTURE_STARTS_OBSERVED_NOT_YET_SAVED");
    }
    public synchronized Operation ready(Actor actor, UUID id, UUID attempt, String persistenceReason) throws IOException {
        return update(actor, id, attempt, before -> {
            if (before.state() != State.DISPATCHED && before.state() != State.RECOVERY_REQUIRED || before.nativeReady())
                throw new IllegalStateException("COMPASS_PREPARATION_RESULT_ALREADY_RECORDED");
            return candidate(before, before.state() == State.RECOVERY_REQUIRED ? State.RECOVERY_REQUIRED : State.READY,
                    persistenceReason, true, "");
        });
    }
    public synchronized Operation nativeSaved(Actor actor, UUID id, UUID attempt, String proof) throws IOException {
        if (proof == null || !proof.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("COMPASS_PREPARATION_SAVE_PROOF_INVALID");
        return update(actor, id, attempt, before -> {
            if (!before.nativeReady()) throw new IllegalStateException("COMPASS_PREPARATION_READY_PROOF_REQUIRED");
            if (!before.saveProof().isEmpty()) {
                if (!before.saveProof().equals(proof)) throw new IllegalStateException("COMPASS_PREPARATION_SAVE_PROOF_CHANGED");
                return before;
            }
            return candidate(before, before.state() == State.RECOVERY_REQUIRED ? State.RECOVERY_REQUIRED : State.SAVED,
                    "CANONICAL_STRUCTURE_STARTS_SAVED", true, proof);
        });
    }
    public synchronized Operation stopAdmission(Actor actor, UUID id, String reason) throws IOException {
        var op = get(actor, id);
        var candidates = op.candidates().stream().map(before -> before.settled() ? before
                : before.state() == State.INTENT ? candidate(before, State.NOT_ENTERED, reason, false, "")
                : candidate(before, State.RECOVERY_REQUIRED, reason, before.nativeReady(), before.saveProof())).toList();
        return persist(copy(op, true, candidates));
    }
    public synchronized Operation finishAdmission(Actor actor, UUID id) throws IOException {
        var op = get(actor, id); return op.admissionClosed() ? op : persist(copy(op, true, op.candidates().stream()
                .map(before -> before.state() == State.INTENT ? candidate(before, State.NOT_ENTERED, "CLOSED_BEFORE_NATIVE", false, "") : before).toList()));
    }
    public synchronized Operation reconcile(Actor actor, UUID id, long revision, String digest) throws IOException {
        var op = get(actor, id);
        if (!op.admissionClosed() || op.revision() != revision || !AiJson.digest(AiJson.canonical(op)).equals(digest)
                || op.candidates().stream().anyMatch(candidate -> !candidate.settled() && (!candidate.nativeReady() || candidate.saveProof().isEmpty())))
            throw new IllegalStateException("COMPASS_PREPARATION_POSITIVE_RECONCILIATION_REQUIRED");
        return persist(copy(op, true, op.candidates().stream().map(before -> before.settled() ? before
                : candidate(before, State.SAVED, "RECONCILED_NATIVE_SAVE_WITHOUT_GENERATION", true, before.saveProof())).toList()));
    }
    public synchronized Operation get(Actor actor, UUID id) {
        access(actor); var op = operations.get(id); if (op == null) throw new IllegalArgumentException("COMPASS_PREPARATION_OPERATION_NOT_FOUND"); return op;
    }
    public List<Operation> snapshot(Actor actor) { access(actor); return published; }
    public String export(Actor actor) { access(actor); return AiJson.GSON.toJson(journal.getSnapshot(0).events()); }
    public List<ConversationStore.Event> history(Actor actor) { access(actor); return journal.getSnapshot(0).events(); }
    public boolean settled(Actor actor) { access(actor); return published.stream().allMatch(Operation::settled); }
    public boolean taskSettled(Actor actor, UUID task) { access(actor); return published.stream().filter(op -> op.taskId().equals(task)).allMatch(Operation::settled); }
    public Optional<Operation> find(Actor actor, UUID id) { access(actor); return published.stream().filter(op -> op.id().equals(id)).findFirst(); }
    public boolean canForgetAfterErasure(Actor actor) {
        actor.requireAccess(workspace.owner()); return !broken && workspace.conversation().privacyEpoch() > privacy && published.stream().allMatch(Operation::settled);
    }

    private Operation update(Actor actor, UUID id, UUID attempt, java.util.function.UnaryOperator<Candidate> change) throws IOException {
        var op = get(actor, id); var candidates = new ArrayList<>(op.candidates()); int index = -1;
        for (int i = 0; i < candidates.size(); i++) if (candidates.get(i).attempt().equals(attempt)) { index = i; break; }
        if (index < 0) throw new IllegalArgumentException("COMPASS_PREPARATION_ATTEMPT_NOT_FOUND");
        var before = candidates.get(index); var after = change.apply(before); if (before == after) return op;
        candidates.set(index, after); return persist(copy(op, op.admissionClosed(), candidates));
    }
    private static Candidate candidate(Candidate before, State state, String reason, boolean ready, String proof) {
        return new Candidate(before.attempt(), before.x(), before.z(), state, reason, ready, proof);
    }
    private static Operation copy(Operation before, boolean closed, List<Candidate> candidates) {
        return new Operation(before.version(), before.id(), before.owner(), before.taskId(), before.conversationId(), before.serverInstanceId(),
                before.privacyEpoch(), before.executionEpoch(), before.registryGeneration(), before.searchHash(), before.preparationHash(), before.dimension(), before.targetId(),
                before.originX(), before.originZ(), before.radiusBlocks(), before.budget(),
                before.revision() + 1, closed, candidates);
    }
    private static boolean sameBinding(Operation a, Operation b) {
        return a.id().equals(b.id()) && a.owner().equals(b.owner()) && a.taskId().equals(b.taskId())
                && a.conversationId().equals(b.conversationId()) && a.serverInstanceId().equals(b.serverInstanceId()) && a.privacyEpoch() == b.privacyEpoch()
                && a.executionEpoch().equals(b.executionEpoch()) && a.registryGeneration() == b.registryGeneration() && a.searchHash().equals(b.searchHash()) && a.preparationHash().equals(b.preparationHash())
                && a.dimension().equals(b.dimension()) && a.targetId().equals(b.targetId()) && a.budget().equals(b.budget())
                && a.originX() == b.originX() && a.originZ() == b.originZ() && a.radiusBlocks() == b.radiusBlocks();
    }
    private void access(Actor actor) {
        actor.requireAccess(workspace.owner());
        if (broken || privacy != workspace.conversation().privacyEpoch()) throw new IllegalStateException("COMPASS_PREPARATION_JOURNAL_REQUIRES_REVIEW");
    }
    private void validate(Operation op) {
        if (op == null || op.version() != 1 || !workspace.owner().equals(op.owner()) || op.id() == null || op.taskId() == null || op.executionEpoch() == null
                || !workspace.primaryConversationId().equals(op.conversationId()) || !workspace.metadata().serverInstanceId().equals(op.serverInstanceId())
                || privacy != op.privacyEpoch() || op.registryGeneration() < 0 || op.revision() < 1 || op.searchHash() == null || !op.searchHash().matches("[0-9a-f]{64}")
                || op.preparationHash() == null || !op.preparationHash().matches("[0-9a-f]{64}")
                || Math.abs((long) op.originX()) > 30_000_000 || Math.abs((long) op.originZ()) > 30_000_000 || op.radiusBlocks() < 1 || op.radiusBlocks() > 2048
                || op.dimension() == null || !op.dimension().matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || op.targetId() == null || !op.targetId().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                || op.budget() == null || !op.budget().enabled() || op.candidates().size() > op.budget().maximumChunks()
                || op.candidates().stream().map(Candidate::attempt).distinct().count() != op.candidates().size()
                || op.candidates().stream().map(candidate -> candidate.x() + ":" + candidate.z()).distinct().count() != op.candidates().size()
                || op.candidates().stream().anyMatch(candidate -> !within(op, candidate.x(), candidate.z())))
            throw new IllegalArgumentException("COMPASS_PREPARATION_OPERATION_INVALID");
    }
    private static boolean within(Operation operation, int x, int z) {
        long blockX = (long) x * 16 + 8, blockZ = (long) z * 16 + 8;
        return Math.abs(blockX) <= 30_000_000 && Math.abs(blockZ) <= 30_000_000
                && Math.abs(blockX - operation.originX()) <= operation.radiusBlocks() && Math.abs(blockZ - operation.originZ()) <= operation.radiusBlocks();
    }
    private Operation persist(Operation op) throws IOException {
        if (broken) throw new IOException("COMPASS_PREPARATION_JOURNAL_UNAVAILABLE"); validate(op);
        synchronized (workspace.conversation()) {
            if (privacy != workspace.conversation().privacyEpoch()) throw new IllegalStateException("WORKSPACE_PRIVACY_EPOCH_CHANGED");
            try {
                journal.append(List.of(new ConversationStore.Draft(op.taskId(), "compass_preparation_operation", null, AiJson.GSON.toJsonTree(op).getAsJsonObject(), op.id().toString())));
                operations.put(op.id(), op); published = List.copyOf(operations.values()); return op;
            } catch (IOException | RuntimeException failed) { broken = true; throw failed; }
        }
    }
}
