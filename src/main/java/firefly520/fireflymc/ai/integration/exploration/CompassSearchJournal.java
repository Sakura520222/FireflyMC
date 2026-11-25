package firefly520.fireflymc.ai.integration.exploration;

import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** No native object/compass NBT is serialized, including when native coordinate display is disabled. */
public final class CompassSearchJournal {
    public enum State { START_INTENT, RUNNING, FOUND, NOT_FOUND, STOPPED, RECOVERY_REQUIRED }
    public record Observation(String itemFingerprint, String nativeState, int damage, boolean broken, boolean coordinatesVisible) {
        public Observation {
            if (itemFingerprint == null || !itemFingerprint.matches("[0-9a-f]{64}") || nativeState == null || nativeState.length() > 32 || damage < 0)
                throw new IllegalArgumentException("COMPASS_OBSERVATION_INVALID");
        }
    }
    public record ResultEvidence(boolean verified, boolean coordinatesVisible, Integer x, Integer z, Integer sampleY,
                                 String verification, int checks, int frames, long maximumSliceNanos, int sliceOverruns) {
        public ResultEvidence {
            if (!coordinatesVisible && (x != null || z != null || sampleY != null) || (x == null) != (z == null)
                    || x != null && (Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000)
                    || checks < 0 || frames < 0 || maximumSliceNanos < 0 || sliceOverruns < 0 || verification == null || verification.length() > 160)
                throw new IllegalArgumentException("COMPASS_RESULT_INVALID");
        }
    }
    public record Operation(int version, UUID id, String owner, UUID taskId, String planHash, UUID executionEpoch,
                            State state, String reason, long revision, boolean entered, boolean terminalKnown, State terminalState,
                            int levelsBefore, int levelsAfterStart, Observation before, Observation after,
                            ResultEvidence result, String startSave, String terminalSave) {
        public boolean saved() { return terminalKnown && (!entered || terminalSave != null && terminalSave.matches("[0-9a-f]{64}")); }
        public boolean terminal() { return Set.of(State.FOUND, State.NOT_FOUND, State.STOPPED).contains(state); }
    }
    private final Workspace workspace;
    private final long privacy;
    private final ConversationStore journal;
    private final Map<UUID, Operation> operations = new LinkedHashMap<>();
    private volatile List<Operation> published = List.of();
    private volatile boolean broken;
    public CompassSearchJournal(Workspace workspace) throws IOException {
        this.workspace = workspace; privacy = workspace.conversation().privacyEpoch();
        Path namespace = workspace.directory().resolve("exploration"), folder = namespace.resolve("epoch-" + privacy), path = folder.resolve("journal.jsonl");
        if (Files.isSymbolicLink(namespace) || Files.isSymbolicLink(folder) || Files.isSymbolicLink(path)) throw new IOException("COMPASS_UNSAFE_PATH");
        journal = new ConversationStore(path);
        for (var event : journal.getSnapshot(0).events()) {
            if (!event.kind().equals("compass_search_operation")) throw new IOException("COMPASS_JOURNAL_EVENT_INVALID");
            var op = AiJson.GSON.fromJson(event.data(), Operation.class); validate(op);
            var before = operations.get(op.id());
            if (op.revision() != (before == null ? 1 : before.revision() + 1) || before != null
                    && (!before.taskId().equals(op.taskId()) || !before.planHash().equals(op.planHash()) || !before.executionEpoch().equals(op.executionEpoch())))
                throw new IOException("COMPASS_JOURNAL_IDENTITY_CHANGED");
            operations.put(op.id(), op);
        }
        published = List.copyOf(operations.values());
        for (var op : List.copyOf(operations.values())) if (op.state() != State.RECOVERY_REQUIRED && (!op.terminal() || !op.saved()))
            persist(copy(op, State.RECOVERY_REQUIRED, "RESTART_NO_NATIVE_REPLAY", op.entered(), op.terminalKnown(), op.levelsBefore(), op.levelsAfterStart(), op.after(), op.result(), op.startSave(), op.terminalSave()));
    }
    public synchronized Operation get(Actor actor, UUID id) {
        access(actor); var value = operations.get(id); if (value == null) throw new IllegalArgumentException("COMPASS_OPERATION_NOT_FOUND"); return value;
    }
    public synchronized Optional<Operation> find(Actor actor, UUID id) { access(actor); return Optional.ofNullable(operations.get(id)); }
    public synchronized List<Operation> list(Actor actor) { access(actor); return List.copyOf(operations.values()); }
    public synchronized Operation create(Actor actor, CompassSearchPlan plan, Observation before, int levels) throws IOException {
        access(actor); actor.requireAccess(plan.owner());
        if (operations.containsKey(plan.id())) throw new IllegalStateException("COMPASS_OPERATION_ALREADY_RECORDED");
        if (operations.values().stream().filter(op -> !op.terminal() || !op.saved()).count() >= 32) throw new IllegalStateException("COMPASS_OPERATION_LIMIT");
        return persist(new Operation(1, plan.id(), actor.owner(), plan.taskId(), plan.hash(), plan.executionEpoch(), State.START_INTENT, "NATIVE_START_PENDING", 1,
                false, false, null, levels, levels, before, before, null, "", ""));
    }
    public synchronized Operation started(Actor actor, UUID id, Observation after, int levels) throws IOException {
        var op = get(actor, id); if (op.state() != State.START_INTENT) throw new IllegalStateException("COMPASS_START_ALREADY_RECORDED");
        return persist(copy(op, State.RUNNING, "NATIVE_SEARCH_RUNNING", true, false, op.levelsBefore(), levels, after, null, op.startSave(), ""));
    }
    public synchronized Operation finished(Actor actor, UUID id, State state, String reason, boolean entered, boolean known,
                                           Observation after, int levelsAfterStart, ResultEvidence result) throws IOException {
        var op = get(actor, id);
        if (op.terminalKnown() || !Set.of(State.START_INTENT, State.RUNNING, State.RECOVERY_REQUIRED).contains(op.state()))
            throw new IllegalStateException("COMPASS_RESULT_ALREADY_RECORDED");
        if (!Set.of(State.FOUND, State.NOT_FOUND, State.STOPPED, State.RECOVERY_REQUIRED).contains(state)
                || state == State.FOUND && (result == null || !result.verified()) || state != State.RECOVERY_REQUIRED && !known)
            throw new IllegalArgumentException("COMPASS_RESULT_STATE_INVALID");
        return persist(new Operation(op.version(), op.id(), op.owner(), op.taskId(), op.planHash(), op.executionEpoch(),
                op.state() == State.RECOVERY_REQUIRED ? State.RECOVERY_REQUIRED : state, reason, op.revision() + 1,
                entered, known, known && state != State.RECOVERY_REQUIRED ? state : null,
                op.levelsBefore(), levelsAfterStart, op.before(), after, result, op.startSave(), ""));
    }
    public synchronized Operation requireRecovery(Actor actor, UUID id, String reason) throws IOException {
        var op = get(actor, id);
        return persist(copy(op, State.RECOVERY_REQUIRED, reason, op.entered(), op.terminalKnown(), op.levelsBefore(),
                op.levelsAfterStart(), op.after(), op.result(), op.startSave(), op.terminalSave()));
    }
    public synchronized void nativeSaved(Actor actor, UUID id, boolean terminal, String proof) throws IOException {
        // Explicit erasure may finish after a combined terminal proof but before a redundant start-proof callback.
        // The old epoch is already fully settled; never append into it after erasure or fabricate a new epoch record.
        actor.requireAccess(workspace.owner());
        var obsolete = operations.get(id);
        if (!broken && workspace.conversation().privacyEpoch() > privacy && obsolete != null && obsolete.terminal() && obsolete.saved()
                && published.stream().allMatch(value -> value.terminal() && value.saved())) return;
        var op = get(actor, id);
        if (proof == null || !proof.matches("[0-9a-f]{64}") || terminal && !op.terminalKnown()) throw new IllegalArgumentException("COMPASS_SAVE_PROOF_INVALID");
        String previous = terminal ? op.terminalSave() : op.startSave();
        if (!previous.isEmpty()) { if (!previous.equals(proof)) throw new IllegalStateException("COMPASS_SAVE_PROOF_CHANGED"); return; }
        persist(copy(op, op.state(), op.reason(), op.entered(), op.terminalKnown(), op.levelsBefore(), op.levelsAfterStart(), op.after(), op.result(),
                terminal ? op.startSave() : proof, terminal ? proof : op.terminalSave()));
    }
    public synchronized Operation acknowledgeSavedTerminal(Actor actor, UUID id, long revision) throws IOException {
        var op = get(actor, id);
        if (op.state() != State.RECOVERY_REQUIRED || op.revision() != revision || !op.saved()) throw new IllegalStateException("COMPASS_POSITIVE_TERMINAL_PROOF_REQUIRED");
        return persist(copy(op, op.terminalState(), "RECONCILED_SAVED_RESULT_WITHOUT_REPLAY", op.entered(), true,
                op.levelsBefore(), op.levelsAfterStart(), op.after(), op.result(), op.startSave(), op.terminalSave()));
    }
    /** Privacy/ACK callers can already hold the primary-conversation monitor; never take this journal's monitor here. */
    public List<Operation> snapshot(Actor actor) { access(actor); return published; }
    public long privacyEpoch() { return privacy; }
    public boolean canForgetAfterErasure(Actor actor) {
        actor.requireAccess(workspace.owner());
        return !broken && workspace.conversation().privacyEpoch() > privacy && published.stream().allMatch(op -> op.terminal() && op.saved());
    }
    public boolean settled(Actor actor) { access(actor); return !broken && published.stream().allMatch(op -> op.terminal() && op.saved()); }
    public boolean settledForTask(Actor actor, UUID task) { access(actor); return !broken && published.stream().filter(op -> op.taskId().equals(task)).allMatch(op -> op.terminal() && op.saved()); }
    /** No journal monitor or artifact filesystem lookup: the original immutable manifest is in the primary timeline. */
    public boolean settledCall(Actor actor, UUID task, ModelMessage.ToolCall call) {
        try {
            access(actor);
            if (!Set.of("compass_search_execute", "compass_search_reconcile").contains(call.name())) return false;
            var args = AiJson.object(call.arguments(), 65536, 24);
            if (call.name().equals("compass_search_reconcile")) {
                if (!args.keySet().equals(Set.of("reviewId", "reviewHash"))) return false;
                UUID reviewId = UUID.fromString(args.get("reviewId").getAsString()); String reviewHash = args.get("reviewHash").getAsString();
                for (var event : workspace.conversation().getSnapshot(0).events()) if (task.equals(event.taskId()) && event.kind().equals("compass_reconcile_preview")
                        && event.data() != null && event.data().has("reviewId") && event.data().has("hash") && event.data().has("operationId")
                        && event.data().has("originalPlanHash") && event.data().has("operationRevision")
                        && reviewId.toString().equals(event.data().get("reviewId").getAsString()) && reviewHash.equals(event.data().get("hash").getAsString())) {
                    var manifest = event.data();
                    return published.stream().anyMatch(op -> op.taskId().equals(task) && op.id().toString().equals(manifest.get("operationId").getAsString())
                            && op.planHash().equals(manifest.get("originalPlanHash").getAsString()) && op.revision() > manifest.get("operationRevision").getAsLong()
                            && op.reason().equals("RECONCILED_SAVED_RESULT_WITHOUT_REPLAY") && op.terminal() && op.saved());
                }
                return false;
            }
            if (!args.keySet().equals(Set.of("planId", "planHash"))) return false;
            UUID id = UUID.fromString(args.get("planId").getAsString()); String hash = args.get("planHash").getAsString();
            var op = published.stream().filter(value -> value.id().equals(id) && value.taskId().equals(task) && value.planHash().equals(hash)).findFirst().orElse(null);
            if (op == null || !op.terminal() || !op.saved()) return false;
            return workspace.conversation().getSnapshot(0).events().stream().anyMatch(event -> task.equals(event.taskId())
                    && event.kind().equals("compass_search_plan") && event.data() != null && event.data().has("planId") && event.data().has("hash")
                    && id.toString().equals(event.data().get("planId").getAsString()) && hash.equals(event.data().get("hash").getAsString()));
        } catch (RuntimeException unknown) { return false; }
    }
    public synchronized String export(Actor actor) { access(actor); return AiJson.GSON.toJson(journal.getSnapshot(0).events()); }
    private Operation copy(Operation op, State state, String reason, boolean entered, boolean known, int beforeLevels, int afterLevels,
                           Observation after, ResultEvidence result, String startSave, String terminalSave) {
        return new Operation(op.version(), op.id(), op.owner(), op.taskId(), op.planHash(), op.executionEpoch(), state, reason, op.revision() + 1,
                entered, known, known ? op.terminalState() : null, beforeLevels, afterLevels, op.before(), after, result, startSave, terminalSave);
    }
    private void access(Actor actor) {
        actor.requireAccess(workspace.owner());
        if (broken || privacy != workspace.conversation().privacyEpoch()) throw new IllegalStateException("COMPASS_JOURNAL_REQUIRES_REVIEW");
    }
    private void validate(Operation op) throws IOException {
        if (op == null || op.version() != 1 || !workspace.owner().equals(op.owner()) || op.id() == null || op.taskId() == null
                || op.executionEpoch() == null || op.revision() < 1 || op.state() == null || op.before() == null || op.after() == null
                || op.terminalKnown() && (op.terminalState() == null || !Set.of(State.FOUND, State.NOT_FOUND, State.STOPPED).contains(op.terminalState()))
                || !op.terminalKnown() && op.terminalState() != null
                || op.reason() == null || !op.planHash().matches("[0-9a-f]{64}") || op.startSave() == null || op.terminalSave() == null
                || !op.startSave().isEmpty() && !op.startSave().matches("[0-9a-f]{64}")
                || !op.terminalSave().isEmpty() && (!op.terminalSave().matches("[0-9a-f]{64}") || !op.terminalKnown())) throw new IOException("COMPASS_JOURNAL_INVALID");
    }
    private Operation persist(Operation op) throws IOException {
        if (broken) throw new IOException("COMPASS_JOURNAL_UNAVAILABLE"); validate(op);
        synchronized (workspace.conversation()) {
            if (privacy != workspace.conversation().privacyEpoch()) throw new IllegalStateException("WORKSPACE_PRIVACY_EPOCH_CHANGED");
            try {
                journal.append(List.of(new ConversationStore.Draft(op.taskId(), "compass_search_operation", null, AiJson.GSON.toJsonTree(op).getAsJsonObject(), op.id().toString())));
                operations.put(op.id(), op); published = List.copyOf(operations.values()); return op;
            } catch (IOException | RuntimeException failure) { broken = true; throw failure; }
        }
    }
}
