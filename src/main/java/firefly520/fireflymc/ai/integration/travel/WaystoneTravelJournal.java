package firefly520.fireflymc.ai.integration.travel;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Positive native outcome and canonical player save, with no refund/retry inference from position. */
public final class WaystoneTravelJournal {
    public enum State { DISPATCHING, RETURNED, COMPLETED, PREPARED, FAILED, REFUSED, RECOVERY_REQUIRED }
    public record RecoveryReview(UUID operationId, long revision, String reviewHash, boolean nativeSuccess, boolean feesEntered) {}
    public record Operation(int version, UUID id, String owner, UUID taskId, String planHash, long privacyEpoch, State state,
            JsonObject before, JsonObject after, JsonObject nativeResult, boolean feesEntered, boolean nativeSuccess,
            String reason, String nativeSave, long revision) {
        public Operation {
            before = before.deepCopy(); after = after.deepCopy(); nativeResult = nativeResult.deepCopy();
        }
        @Override public JsonObject before() { return before.deepCopy(); }
        @Override public JsonObject after() { return after.deepCopy(); }
        @Override public JsonObject nativeResult() { return nativeResult.deepCopy(); }
        public boolean preparation() { return before.has("operationKind") && before.get("operationKind").getAsString().equals("TARGET_PREPARATION"); }
        public boolean settled() { return state == State.REFUSED || state == State.COMPLETED || state == State.PREPARED || state == State.FAILED; }
        public boolean saved() {
            // Preparation is a bounded ephemeral loading/readiness operation, not a player/resource mutation.
            // A positive native loader return is mandatory; this never claims a chunk or player disk write.
            return state == State.REFUSED || nativeSave.matches("[0-9a-f]{64}") || preparation() && nativeResult.has("nativeLoadingObserved")
                    && nativeResult.get("nativeLoadingObserved").getAsBoolean() && nativeResult.has("noTravelDispatched") && nativeResult.get("noTravelDispatched").getAsBoolean();
        }
    }
    private final Workspace workspace;
    private final long privacyEpoch;
    private final ConversationStore journal;
    private final Map<UUID, Operation> operations = new LinkedHashMap<>();
    private volatile List<Operation> view = List.of();
    private volatile boolean broken;
    public WaystoneTravelJournal(Workspace workspace) throws IOException {
        this.workspace = workspace; privacyEpoch = workspace.conversation().privacyEpoch();
        Path folder = workspace.directory().resolve("travel"), file = folder.resolve("journal.jsonl");
        if (Files.isSymbolicLink(folder) || Files.isSymbolicLink(file)) throw new IOException("WAYSTONE_UNSAFE_PATH");
        journal = new ConversationStore(file);
        for (var event : journal.getSnapshot(0).events()) {
            if (!event.kind().equals("waystone_travel_operation")) throw new IOException("WAYSTONE_JOURNAL_EVENT");
            var op = AiJson.GSON.fromJson(event.data(), Operation.class); validate(op);
            var previous = operations.get(op.id());
            if (op.revision() != (previous == null ? 1 : previous.revision() + 1)
                    || previous != null && (!op.planHash().equals(previous.planHash()) || !op.taskId().equals(previous.taskId())))
                throw new IOException("WAYSTONE_JOURNAL_IDENTITY");
            operations.put(op.id(), op);
        }
        view = List.copyOf(operations.values());
        for (var op : List.copyOf(operations.values())) if (!op.settled()) {
            boolean beforeLoading = op.preparation() && op.after().isEmpty() && !op.nativeResult().has("nativeLoadingIntent");
            persist(copy(op, beforeLoading ? State.REFUSED : State.RECOVERY_REQUIRED, op.after(), op.nativeResult(), op.feesEntered(), op.nativeSuccess(),
                    beforeLoading ? "RESTART_BEFORE_NATIVE_LOADING_INTENT" : "RESTART_NO_NATIVE_REPLAY", op.nativeSave()));
        }
    }
    public synchronized Operation begin(Actor actor, WaystoneTravelPlan plan, JsonObject before) throws IOException {
        access(actor); actor.requireAccess(plan.owner());
        if (!plan.intact()) throw new SecurityException("WAYSTONE_TRAVEL_PLAN_CHANGED");
        if (operations.containsKey(plan.id())) throw new IllegalStateException("WAYSTONE_TRAVEL_ALREADY_RECORDED");
        if (view.stream().anyMatch(op -> !op.settled())) throw new IllegalStateException("WAYSTONE_TRAVEL_REQUIRES_REVIEW");
        if (operations.size() >= 4096) throw new IllegalStateException("WAYSTONE_TRAVEL_HISTORY_LIMIT");
        return persist(new Operation(1, plan.id(), plan.owner(), plan.taskId(), plan.hash(), privacyEpoch, State.DISPATCHING,
                before, new JsonObject(), new JsonObject(), false, false, "", "", 1));
    }
    public synchronized Operation beginPreparation(Actor actor, WaystoneTargetPreparation plan) throws IOException {
        access(actor); actor.requireAccess(plan.owner());
        require(plan.intact() && !operations.containsKey(plan.id()), "WAYSTONE_PREPARATION_ALREADY_RECORDED");
        require(view.stream().allMatch(Operation::settled), "WAYSTONE_TRAVEL_REQUIRES_REVIEW");
        var before = new JsonObject(); before.addProperty("operationKind", "TARGET_PREPARATION");
        before.add("source", AiJson.GSON.toJsonTree(plan.source())); before.add("knownTarget", AiJson.GSON.toJsonTree(plan.target()));
        before.add("approvedChunks", AiJson.GSON.toJsonTree(plan.chunks()));
        return persist(new Operation(1, plan.id(), plan.owner(), plan.taskId(), plan.hash(), privacyEpoch, State.DISPATCHING, before,
                new JsonObject(), new JsonObject(), false, false, "ONLY_NATIVE_BOUNDED_LOADING_NO_FEES_OR_MOVEMENT", "", 1));
    }
    public synchronized Operation preparationObserved(Actor actor, UUID id, JsonObject after, boolean ready, String reason) throws IOException {
        var op = get(actor, id);
        require(op.preparation() && op.nativeResult().has("nativeLoadingIntent") && op.after().isEmpty()
                && (op.state() == State.DISPATCHING || op.state() == State.RECOVERY_REQUIRED), "WAYSTONE_NATIVE_RESULT_ALREADY_RECORDED");
        var observation = new JsonObject(); observation.addProperty("nativeLoadingObserved", true); observation.addProperty("noTravelDispatched", true);
        observation.addProperty("knownNativeOutcome", true); observation.addProperty("readyAtObservedTime", ready);
        observation.addProperty("persistenceClaim", "EPHEMERAL_BOUNDED_NATIVE_PREPARATION_ONLY");
        return persist(copy(op, op.state() == State.RECOVERY_REQUIRED ? State.RECOVERY_REQUIRED : ready ? State.PREPARED : State.REFUSED,
                after, observation, false, false, reason, ""));
    }
    public synchronized Operation preparationLoadingIntent(Actor actor, UUID id) throws IOException {
        var op = get(actor, id); require(op.preparation() && op.state() == State.DISPATCHING && op.after().isEmpty()
                && !op.nativeResult().has("nativeLoadingIntent"), "WAYSTONE_PREPARATION_PHASE_CHANGED");
        var data = new JsonObject(); data.addProperty("nativeLoadingIntent", true); data.addProperty("diskPolicy", "EXISTING_FULL_ONLY");
        return persist(copy(op, State.DISPATCHING, op.after(), data, false, false, "EXISTING_FULL_CHUNKS_VERIFIED_NATIVE_LOADING_ACCEPTED", ""));
    }
    public synchronized Operation get(Actor actor, UUID id) { access(actor); var op = operations.get(id); if (op == null) throw new IllegalArgumentException("WAYSTONE_TRAVEL_NOT_FOUND"); return op; }
    public List<Operation> snapshot(Actor actor) { access(actor); return view; }
    long epoch() { return privacyEpoch; }
    List<Operation> publishedObligations() { return view; }
    boolean matches(Workspace other) {
        return workspace.owner().equals(other.owner()) && workspace.directory().equals(other.directory())
                && workspace.metadata().serverInstanceId().equals(other.metadata().serverInstanceId()) && workspace.primaryConversationId().equals(other.primaryConversationId());
    }
    public synchronized String exportJournal(Actor actor) { access(actor); return AiJson.GSON.toJson(journal.getSnapshot(0).events()); }
    public synchronized String operationHistoryDigest(Actor actor, UUID id) {
        get(actor, id);
        return AiJson.digest(AiJson.GSON.toJson(journal.getSnapshot(0).events().stream().filter(event -> id.toString().equals(event.toolTransactionId())).toList()));
    }
    public boolean settledForTask(Actor actor, UUID task) { return snapshot(actor).stream().filter(op -> op.taskId().equals(task)).allMatch(op -> op.settled() && op.saved()); }
    public boolean requiresRecovery(Actor actor, UUID task) { return snapshot(actor).stream().anyMatch(op -> op.taskId().equals(task) && !op.settled()); }
    public synchronized Operation rejectedBeforeDispatch(Actor actor, UUID id, String reason) throws IOException {
        var op = get(actor, id); require(op.state() == State.DISPATCHING || op.state() == State.RECOVERY_REQUIRED && op.after().isEmpty()
                && !op.feesEntered(), "WAYSTONE_NATIVE_RESULT_ALREADY_RECORDED");
        return persist(copy(op, State.REFUSED, op.before(), new JsonObject(), false, false, reason, ""));
    }
    public synchronized Operation observed(Actor actor, UUID id, JsonObject after, JsonObject nativeResult,
            boolean feesEntered, boolean nativeSuccess, boolean known, String reason) throws IOException {
        var op = get(actor, id);
        require(op.state() == State.DISPATCHING || op.state() == State.RECOVERY_REQUIRED && op.after().isEmpty(), "WAYSTONE_NATIVE_RESULT_ALREADY_RECORDED");
        // Even a failed native return can have charged or moved entities. Never call it a refundable refusal.
        var state = op.state() == State.RECOVERY_REQUIRED ? State.RECOVERY_REQUIRED : known && nativeSuccess ? State.RETURNED
                : !feesEntered && known && after.equals(op.before()) ? State.REFUSED : State.RECOVERY_REQUIRED;
        var recorded = nativeResult.deepCopy(); recorded.addProperty("knownNativeOutcome", known);
        return persist(copy(op, state, after, recorded, feesEntered, nativeSuccess, reason, ""));
    }
    public synchronized Operation nativeSaved(Actor actor, UUID id, String proof) throws IOException {
        var op = get(actor, id); require(proof != null && proof.matches("[0-9a-f]{64}"), "WAYSTONE_NATIVE_SAVE_INVALID");
        require(!op.preparation(), "WAYSTONE_PREPARATION_HAS_NO_PLAYER_SAVE_CLAIM");
        require(!op.after().isEmpty(), "WAYSTONE_NATIVE_RESULT_MISSING");
        if (!op.nativeSave().isEmpty()) { require(op.nativeSave().equals(proof), "WAYSTONE_NATIVE_SAVE_CHANGED"); return op; }
        return persist(copy(op, op.state() == State.RETURNED ? State.COMPLETED : op.state(), op.after(), op.nativeResult(),
                op.feesEntered(), op.nativeSuccess(), op.reason(), proof));
    }
    public synchronized Operation requireRecovery(Actor actor, UUID id, String reason) throws IOException {
        var op = get(actor, id); if (op.settled()) return op;
        return persist(copy(op, State.RECOVERY_REQUIRED, op.after(), op.nativeResult(), op.feesEntered(), op.nativeSuccess(), reason, op.nativeSave()));
    }
    public synchronized RecoveryReview recoveryReview(Actor actor, UUID id) {
        var op = get(actor, id);
        require(op.state() == State.RECOVERY_REQUIRED && op.saved() && !op.after().isEmpty()
                && op.nativeResult().has("knownNativeOutcome") && op.nativeResult().get("knownNativeOutcome").getAsBoolean(), "WAYSTONE_POSITIVE_NATIVE_EVIDENCE_REQUIRED");
        return new RecoveryReview(id, op.revision(), AiJson.digest("waystone-native-settlement:" + AiJson.canonical(op)), op.nativeSuccess(), op.feesEntered());
    }
    public synchronized Operation reconcile(Actor actor, UUID id, long revision, String hash) throws IOException {
        var review = recoveryReview(actor, id);
        require(review.revision() == revision && review.reviewHash().equals(hash), "WAYSTONE_RECOVERY_REVIEW_CHANGED");
        var op = get(actor, id);
        var accepted = op.nativeResult(); accepted.addProperty("acceptedReviewRevision", revision); accepted.addProperty("acceptedReviewHash", hash);
        return persist(copy(op, op.preparation() && op.nativeResult().get("readyAtObservedTime").getAsBoolean() ? State.PREPARED
                : op.preparation() ? State.REFUSED : op.nativeSuccess() ? State.COMPLETED : State.FAILED, op.after(), accepted, op.feesEntered(),
                op.nativeSuccess(), "ACCEPTED_NATIVE_RESULT_NO_REFUND_OR_REPLAY", op.nativeSave()));
    }
    private Operation persist(Operation value) throws IOException {
        access(new Actor(workspace.owner(), true, false, 0)); validate(value);
        if (AiJson.GSON.toJson(value).length() > 2 * 1048576) throw new IOException("WAYSTONE_EVIDENCE_LIMIT");
        try {
            journal.append(List.of(new ConversationStore.Draft(value.taskId(), "waystone_travel_operation", null, AiJson.GSON.toJsonTree(value).getAsJsonObject(), value.id().toString())));
            operations.put(value.id(), value); view = List.copyOf(operations.values()); return value;
        } catch (IOException | RuntimeException failure) { broken = true; throw failure; }
    }
    private void access(Actor actor) {
        actor.requireAccess(workspace.owner());
        if (broken || workspace.conversation().privacyEpoch() != privacyEpoch) throw new IllegalStateException("WAYSTONE_JOURNAL_REQUIRES_REVIEW");
    }
    private void validate(Operation op) throws IOException {
        if (op == null || op.version() != 1 || op.id() == null || !workspace.owner().equals(op.owner()) || op.taskId() == null
                || op.planHash() == null || !op.planHash().matches("[0-9a-f]{64}") || op.state() == null || op.reason() == null
                || op.privacyEpoch() != privacyEpoch || op.revision() < 1 || op.nativeSave() == null || !op.nativeSave().isEmpty() && !op.nativeSave().matches("[0-9a-f]{64}")
                || op.state() == State.COMPLETED && (!op.nativeSuccess() || op.nativeSave().isEmpty())
                || op.state() == State.FAILED && (op.nativeSuccess() || op.nativeSave().isEmpty())
                || op.state() == State.PREPARED && (!op.preparation() || op.feesEntered() || op.nativeSuccess() || !op.saved())
                || op.state() == State.REFUSED && op.feesEntered()) throw new IOException("WAYSTONE_JOURNAL_INVALID");
    }
    private static Operation copy(Operation op, State state, JsonObject after, JsonObject result, boolean fees, boolean success, String reason, String proof) {
        return new Operation(1, op.id(), op.owner(), op.taskId(), op.planHash(), op.privacyEpoch(), state, op.before(), after, result, fees, success, reason, proof, op.revision() + 1);
    }
    private static void require(boolean valid, String reason) { if (!valid) throw new IllegalStateException(reason); }
}
