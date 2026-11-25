package firefly520.fireflymc.ai.integration.maid.transport;

import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.material.MaterialStack;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Durable finite intent and actual observations. Uncertain effects and custody never license replay. */
public final class MaidTransportJournal {
    public enum State { INTENT, ARMED, OBSERVED, SAVED, REJECTED, RECOVERY_REQUIRED }
    public record Evidence(boolean verified, boolean achieved, String reason, List<MaterialStack> source,
                           List<MaterialStack> destination, List<MaterialStack> custody, List<MaterialStack> returned, long elapsedNanos) {
        public Evidence(boolean verified, boolean achieved, String reason, List<MaterialStack> source,
                List<MaterialStack> destination, List<MaterialStack> custody, long elapsedNanos) {
            this(verified, achieved, reason, source, destination, custody, List.of(), elapsedNanos);
        }
        public Evidence {
            source = List.copyOf(source); destination = List.copyOf(destination); custody = List.copyOf(custody); returned = List.copyOf(returned);
            if (reason == null || reason.length() > 100 || source.size() > 130 || destination.size() > 130 || custody.size() > 128 || returned.size() > 256 || elapsedNanos < 0)
                throw new IllegalArgumentException("MAID_TRANSPORT_EVIDENCE_INVALID");
        }
    }
    public record Operation(int version, MaidTransportPlan plan, List<String> backendIds, State state, boolean entered, Evidence evidence,
                            String nativeProof, String reason, long revision) {
        public Operation { backendIds = List.copyOf(backendIds); }
        public boolean settled() { return state == State.REJECTED && !entered || state == State.SAVED && entered
                && evidence != null && evidence.verified() && evidence.custody().isEmpty() && nativeProof.matches("[0-9a-f]{64}"); }
        public boolean reconcilable() { return state == State.RECOVERY_REQUIRED && entered && evidence != null && evidence.verified()
                && evidence.custody().isEmpty() && nativeProof.matches("[0-9a-f]{64}") && !reason.startsWith("RETURN_INTENT:"); }
    }
    private final Workspace workspace;
    private final long epoch;
    private final ConversationStore journal;
    private final Map<UUID, Operation> operations = new LinkedHashMap<>();
    private volatile List<Operation> published = List.of();
    private volatile boolean broken;
    /** Server maintenance metadata only; contains no conversation, item components or authentication assertion. */
    public record ResourceObligation(UUID owner, UUID operationId, List<String> backendIds) {
        public ResourceObligation { backendIds = List.copyOf(backendIds); }
    }
    public List<ResourceObligation> resourceObligations() {
        if (broken) throw new IllegalStateException("MAID_TRANSPORT_JOURNAL_UNAVAILABLE");
        return published.stream().filter(op -> !op.settled()).map(op -> new ResourceObligation(UUID.fromString(workspace.owner()), op.plan().id(), op.backendIds())).toList();
    }
    public MaidTransportJournal(Workspace workspace) throws IOException {
        this.workspace = workspace;
        synchronized (workspace.conversation()) {
            epoch = workspace.conversation().privacyEpoch(); Path dir = workspace.directory().resolve("maid-transport").resolve("epoch-" + epoch);
            Path file = dir.resolve("journal.jsonl");
            if (Files.isSymbolicLink(dir.getParent()) || Files.isSymbolicLink(dir) || Files.isSymbolicLink(file)) throw new IOException("MAID_TRANSPORT_UNSAFE_PATH");
            journal = new ConversationStore(file);
            for (var event : journal.getSnapshot(0).events()) {
                if (!event.kind().equals("maid_transport_operation")) throw new IOException("MAID_TRANSPORT_JOURNAL_EVENT");
                var op = AiJson.GSON.fromJson(event.data(), Operation.class); validate(op);
                var old = operations.get(op.plan().id());
                if (op.revision() != (old == null ? 1 : old.revision() + 1) || old != null
                        && (!old.plan().equals(op.plan()) || !op.backendIds().containsAll(old.backendIds())))
                    throw new IOException("MAID_TRANSPORT_JOURNAL_IDENTITY");
                operations.put(op.plan().id(), op);
            }
            for (var op : List.copyOf(operations.values())) if (!op.settled() && op.state() != State.RECOVERY_REQUIRED)
                persist(copy(op, State.RECOVERY_REQUIRED, op.entered(), op.evidence(), op.nativeProof(), "RESTART_NO_NATIVE_REPLAY"));
            published = List.copyOf(operations.values());
        }
    }
    public synchronized Operation intent(Actor actor, MaidTransportPlan plan) throws IOException {
        access(actor); plan.requireStoredIdentity(workspace, actor);
        if (operations.containsKey(plan.id())) throw new IllegalStateException("MAID_TRANSPORT_ALREADY_RECORDED");
        if (operations.values().stream().filter(op -> !op.settled()).count() >= 16) throw new IllegalStateException("MAID_TRANSPORT_PENDING_LIMIT");
        return persist(new Operation(1, plan, plan.backendIds(), State.INTENT, false, null, "", "WAITING_REAL_NATIVE_TICK", 1));
    }
    public synchronized void armed(Actor actor, UUID id) throws IOException {
        var op = get(actor, id); if (op.state() != State.INTENT) throw new IllegalStateException("MAID_TRANSPORT_ALREADY_RECORDED");
        persist(copy(op, State.ARMED, false, null, "", "WAITING_REAL_NATIVE_TICK"));
    }
    public synchronized Operation observed(Actor actor, UUID id, boolean entered, Evidence evidence) throws IOException {
        var op = get(actor, id);
        if (op.evidence() != null || op.settled()) throw new IllegalStateException("MAID_TRANSPORT_RESULT_ALREADY_RECORDED");
        boolean covered = entered && evidence.verified() && evidence.custody().isEmpty();
        return persist(copy(op, !entered ? State.REJECTED : covered && op.state() != State.RECOVERY_REQUIRED ? State.OBSERVED : State.RECOVERY_REQUIRED,
                entered, evidence, "", evidence.reason()));
    }
    public synchronized void saved(Actor actor, UUID id, String proof) throws IOException {
        saved(actor, id, proof, "");
    }
    public synchronized void saved(Actor actor, UUID id, String proof, String expectedEvidence) throws IOException {
        var op = get(actor, id);
        if (!op.entered() || proof == null || !proof.matches("[0-9a-f]{64}") || op.evidence() == null)
            throw new IllegalStateException("MAID_TRANSPORT_POSITIVE_PROOF_REQUIRED");
        if (!expectedEvidence.isEmpty() && !expectedEvidence.equals(AiJson.digest(AiJson.canonical(op.evidence()))))
            throw new IllegalStateException("MAID_TRANSPORT_STALE_NATIVE_PROOF");
        if (!op.nativeProof().isEmpty()) {
            if (!op.nativeProof().equals(proof)) throw new IllegalStateException("MAID_TRANSPORT_PROOF_CHANGED"); return;
        }
        persist(copy(op, op.state() == State.OBSERVED && op.evidence().verified() && op.evidence().custody().isEmpty() ? State.SAVED : State.RECOVERY_REQUIRED,
                true, op.evidence(), proof, op.reason()));
    }
    synchronized Operation beginReturn(Actor actor, UUID id, long revision, String digest, UUID returnId) throws IOException {
        return beginReturn(actor, id, revision, digest, returnId, List.of());
    }
    synchronized Operation beginReturn(Actor actor, UUID id, long revision, String digest, UUID returnId, Collection<String> destinationScopes) throws IOException {
        var op = get(actor, id);
        if (op.revision() != revision || !AiJson.digest(AiJson.canonical(op)).equals(digest) || !op.entered() || op.evidence() == null
                || !op.evidence().verified() || op.evidence().custody().isEmpty() || !op.nativeProof().matches("[0-9a-f]{64}")
                || op.reason().startsWith("RETURN_INTENT:")) throw new IllegalStateException("MAID_TRANSPORT_RETURN_CHANGED");
        var scopes = new TreeSet<>(op.backendIds()); scopes.addAll(destinationScopes);
        if (scopes.size() > 32) throw new IllegalStateException("MAID_TRANSPORT_BACKEND_LIMIT");
        return persist(new Operation(op.version(), op.plan(), List.copyOf(scopes), State.RECOVERY_REQUIRED, true, op.evidence(), "", "RETURN_INTENT:" + returnId, op.revision() + 1));
    }
    synchronized Operation returned(Actor actor, UUID id, UUID returnId, int index, MaterialStack product,
            int accepted, boolean verified, String reason) throws IOException {
        var op = get(actor, id);
        if (!op.reason().equals("RETURN_INTENT:" + returnId) || op.evidence() == null || index < 0 || index >= op.evidence().custody().size())
            throw new IllegalStateException("MAID_TRANSPORT_RETURN_CHANGED");
        var held = op.evidence().custody().get(index);
        if (accepted < 0 || accepted > product.count() || !held.itemJson().equals(product.itemJson()) || product.count() > held.count())
            throw new IllegalStateException("MAID_TRANSPORT_RETURN_EVIDENCE_INVALID");
        var custody = new ArrayList<>(op.evidence().custody()); var returned = new ArrayList<>(op.evidence().returned());
        if (verified && accepted > 0) {
            if (accepted == held.count()) custody.remove(index); else custody.set(index, held.withCount(held.count() - accepted));
            returned.add(product.withCount(accepted));
        }
        var next = new Evidence(verified && op.evidence().verified(), false, reason, op.evidence().source(), op.evidence().destination(), custody, returned, op.evidence().elapsedNanos());
        return persist(copy(op, verified && custody.isEmpty() ? State.OBSERVED : State.RECOVERY_REQUIRED, true, next, "", reason));
    }
    synchronized void returnRejected(Actor actor, MaidTransportReturn plan, String reason) throws IOException {
        var op = get(actor, plan.operationId());
        if (!op.reason().equals("RETURN_INTENT:" + plan.id()) || op.evidence() == null || !op.evidence().verified()
                || !op.plan().owner().equals(plan.owner()) || !op.plan().taskId().equals(plan.taskId())) throw new IllegalStateException("MAID_TRANSPORT_RETURN_CHANGED");
        persist(copy(op, State.RECOVERY_REQUIRED, true, op.evidence(), plan.previousNativeProof(), reason));
    }
    public synchronized void uncertain(Actor actor, UUID id, String reason) throws IOException {
        var op = get(actor, id); if (!op.settled()) persist(copy(op, State.RECOVERY_REQUIRED, op.entered(), op.evidence(), op.nativeProof(), reason));
    }
    public synchronized Operation reconcile(Actor actor, UUID id, long revision, String digest) throws IOException {
        var op = get(actor, id);
        if (!op.reconcilable() || op.revision() != revision || !AiJson.digest(AiJson.canonical(op)).equals(digest))
            throw new IllegalStateException("MAID_TRANSPORT_RECONCILE_CHANGED");
        return persist(copy(op, State.SAVED, true, op.evidence(), op.nativeProof(), op.reason()));
    }
    public synchronized Operation get(Actor actor, UUID id) {
        access(actor); var op = operations.get(id); if (op == null) throw new IllegalArgumentException("MAID_TRANSPORT_OPERATION_NOT_FOUND"); return op;
    }
    public List<Operation> snapshot(Actor actor) { access(actor); return published; }
    public boolean settled(Actor actor) { access(actor); return !broken && published.stream().allMatch(Operation::settled); }
    public boolean taskSettled(Actor actor, UUID task) { access(actor); return !broken && published.stream().filter(op -> op.plan().taskId().equals(task)).allMatch(Operation::settled); }
    public boolean forgettable(Actor actor) { actor.requireAccess(workspace.owner()); return !broken && workspace.conversation().privacyEpoch() > epoch && published.stream().allMatch(Operation::settled); }
    /** Every transaction version in the current authorized epoch, never old erased-epoch contents. */
    public synchronized String exportJournal(Actor actor) throws IOException {
        access(actor); var data = new com.google.gson.JsonObject(); data.addProperty("version", 1);
        data.addProperty("owner", workspace.owner()); data.addProperty("privacyEpoch", epoch);
        data.add("events", AiJson.GSON.toJsonTree(journal.getSnapshot(0).events()));
        var excluded = new com.google.gson.JsonArray(); Path folder = workspace.directory().resolve("maid-transport");
        if (Files.isSymbolicLink(folder)) throw new IOException("MAID_TRANSPORT_UNSAFE_PATH");
        if (Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) try (var entries = Files.newDirectoryStream(folder, "epoch-*")) {
            for (var entry : entries) {
                String name = entry.getFileName().toString();
                if (name.matches("epoch-[0-9]{1,19}") && !name.equals("epoch-" + epoch)) excluded.add(name);
            }
        }
        data.add("excludedEpochDirectories", excluded); data.addProperty("excludedEpochContentRead", false);
        return AiJson.GSON.toJson(data);
    }
    private void access(Actor actor) {
        actor.requireAccess(workspace.owner());
        if (epoch != workspace.conversation().privacyEpoch()) throw new IllegalStateException("WORKSPACE_PRIVACY_EPOCH_CHANGED");
    }
    private Operation persist(Operation op) throws IOException {
        if (broken) throw new IOException("MAID_TRANSPORT_JOURNAL_UNAVAILABLE"); validate(op);
        synchronized (workspace.conversation()) {
            if (epoch != workspace.conversation().privacyEpoch()) throw new IllegalStateException("WORKSPACE_PRIVACY_EPOCH_CHANGED");
            var data = AiJson.GSON.toJsonTree(op).getAsJsonObject();
            if (data.toString().length() > 2 * 1024 * 1024) throw new IOException("MAID_TRANSPORT_EVIDENCE_TOO_LARGE");
            try {
                journal.append(List.of(new Draft(op.plan().taskId(), "maid_transport_operation", null, data, op.plan().id().toString())));
                operations.put(op.plan().id(), op);
                workspace.conversation().append(List.of(new Draft(op.plan().taskId(), "maid_transport_evidence", null, data, op.plan().id().toString())));
                published = List.copyOf(operations.values()); return op;
            } catch (IOException | RuntimeException failure) { broken = true; throw failure; }
        }
    }
    private void validate(Operation op) throws IOException {
        if (op == null || op.version() != 1 || op.plan() == null || !op.plan().intact() || !op.plan().owner().equals(workspace.owner())
                || op.plan().privacyEpoch() != epoch || op.state() == null || op.nativeProof() == null || op.reason() == null || op.revision() < 1
                || op.backendIds().isEmpty() || op.backendIds().size() > 32 || !op.backendIds().containsAll(op.plan().backendIds())
                || op.backendIds().stream().anyMatch(scope -> scope == null || scope.isBlank() || scope.length() > 1024)
                || !op.nativeProof().isEmpty() && !op.nativeProof().matches("[0-9a-f]{64}") || !op.nativeProof().isEmpty() && !op.entered()
                || op.state() == State.REJECTED && op.entered() || op.state() == State.SAVED && !op.settled())
            throw new IOException("MAID_TRANSPORT_JOURNAL_INVALID");
        if (op.evidence() != null && op.evidence().verified()) {
            var accounted = new ArrayList<>(op.evidence().custody()); accounted.addAll(op.evidence().returned());
            try { MaidTransportConservation.require(op.plan().source(), op.plan().destination(), op.evidence().source(), op.evidence().destination(), accounted); }
            catch (RuntimeException invalid) { throw new IOException("MAID_TRANSPORT_EVIDENCE_NOT_CONSERVED"); }
        }
    }
    private static Operation copy(Operation op, State state, boolean entered, Evidence evidence, String proof, String reason) {
        return new Operation(op.version(), op.plan(), op.backendIds(), state, entered, evidence, proof, reason, op.revision() + 1);
    }
}
