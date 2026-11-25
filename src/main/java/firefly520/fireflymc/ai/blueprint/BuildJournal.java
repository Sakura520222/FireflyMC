package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.material.MaterialStack;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.util.*;

/** Durable world-step evidence in the owner's existing single-writer journal, never a second conversation. */
public final class BuildJournal {
    public enum State { PREPARED, DISPATCHING, OBSERVED, VERIFIED, UNDONE, REJECTED, RECOVERY_REQUIRED, RECONCILED }
    public record Cell(BlueprintDocument.Position position, BlueprintDocument.State before, BlueprintDocument.State target) {
        public Cell { Objects.requireNonNull(position); Objects.requireNonNull(before); BlueprintDocument.checkWorld(position); }
    }
    public record ObservedCell(BlueprintDocument.Position position, BlueprintDocument.State state) {
        public ObservedCell { Objects.requireNonNull(position); Objects.requireNonNull(state); }
    }
    public record Observation(boolean success, boolean mutationEntered, boolean uncertain, String code,
                              MaterialStack remaining, List<MaterialStack> otherItems, List<ObservedCell> cells) {
        public Observation {
            Objects.requireNonNull(code); Objects.requireNonNull(remaining);
            otherItems = List.copyOf(otherItems); cells = List.copyOf(cells);
            if (otherItems.size() > 128 || cells.size() > 256) throw new IllegalArgumentException("BUILD_OBSERVATION_LIMIT");
        }
    }
    public record Step(int schema, UUID operationId, UUID executionEpoch, String owner, UUID taskId, UUID planId, String planHash,
                       UUID materialOperationId, int escrowIndex, String dimension, State state, List<Cell> cells, MaterialStack input,
                       Observation observation, String reason, String receipt, long version) {
        public Step { cells = List.copyOf(cells); }
        public boolean settled() { return state == State.VERIFIED || state == State.UNDONE || state == State.REJECTED || state == State.RECONCILED; }
    }
    private final Workspace workspace;
    private final UUID executionEpoch;
    private final long privacyEpoch;
    private final Map<UUID, Step> projection = new LinkedHashMap<>();
    private long seen;
    public BuildJournal(Workspace workspace, UUID executionEpoch) {
        this.workspace = workspace; this.executionEpoch = Objects.requireNonNull(executionEpoch);
        privacyEpoch = workspace.conversation().privacyEpoch();
    }
    private void access(Actor actor) {
        actor.requireAccess(workspace.owner());
        if (workspace.conversation().privacyEpoch() != privacyEpoch) throw new IllegalStateException("BUILD_PRIVACY_CHANGED");
    }
    public List<Step> list(Actor actor) {
        synchronized (workspace.conversation()) {
            refresh(actor);
            return List.copyOf(projection.values());
        }
    }
    private void refresh(Actor actor) {
        access(actor);
        var snapshot = workspace.conversation().getSnapshot(Math.max(seen, privacyEpoch));
        for (var event : snapshot.events()) if (event.kind().equals("build_step")) {
            var step = AiJson.GSON.fromJson(event.data(), Step.class);
            if (step.schema() != 1 || !step.owner().equals(actor.owner())) throw new IllegalStateException("BUILD_JOURNAL_INVALID");
            projection.put(step.operationId(), step);
        }
        seen = snapshot.revision();
    }
    public Step get(Actor actor, UUID id) {
        synchronized (workspace.conversation()) {
            refresh(actor); Step step = projection.get(id);
            if (step == null) throw new IllegalArgumentException("BUILD_STEP_NOT_FOUND"); return step;
        }
    }
    public Step prepare(Actor actor, UUID operation, UUID task, UUID plan, String planHash, UUID materialOperation, int escrowIndex,
                        String dimension, List<Cell> cells, MaterialStack input) throws IOException {
        synchronized (workspace.conversation()) {
            access(actor);
            if (operation == null || task == null || plan == null || materialOperation == null || escrowIndex < 0
                    || planHash == null || !planHash.matches("[0-9a-f]{64}") || dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || cells == null || cells.isEmpty() || cells.size() > 128 || input == null || input.empty() || input.count() != 1)
                throw new IllegalArgumentException("BUILD_STEP_INVALID");
            if (cells.stream().map(Cell::position).distinct().count() != cells.size() || cells.stream().noneMatch(c -> c.target() != null))
                throw new IllegalArgumentException("BUILD_FOOTPRINT_INVALID");
            var checkpoint = workspace.conversation().task(task);
            if (checkpoint == null || checkpoint.state().terminal()) throw new IllegalStateException("BUILD_TASK_ENDED");
            refresh(actor); var previous = projection.get(operation);
            if (previous != null) {
                if (!previous.taskId().equals(task) || !previous.planId().equals(plan) || !previous.planHash().equals(planHash)
                        || !previous.materialOperationId().equals(materialOperation) || previous.escrowIndex() != escrowIndex
                        || !previous.dimension().equals(dimension) || !previous.cells().equals(cells) || !previous.input().equals(input))
                    throw new SecurityException("BUILD_OPERATION_REUSED");
                return previous;
            }
            if (projection.values().stream().filter(step -> !step.settled()).count() >= 8192) throw new IllegalStateException("BUILD_PENDING_LIMIT");
            return append(new Step(1, operation, executionEpoch, actor.owner(), task, plan, planHash, materialOperation, escrowIndex,
                    dimension, State.PREPARED, cells, input, null, "", "", 1));
        }
    }
    public Step begin(Actor actor, UUID id) throws IOException {
        synchronized (workspace.conversation()) {
            Step step = get(actor, id); require(step, State.PREPARED);
            if (!step.executionEpoch().equals(executionEpoch)) throw new IllegalStateException("BUILD_PLAN_REVALIDATION_REQUIRED");
            return append(copy(step, State.DISPATCHING, null, "", ""));
        }
    }
    public Step observed(Actor actor, UUID id, Observation observation) throws IOException {
        synchronized (workspace.conversation()) {
            Step step = get(actor, id); Objects.requireNonNull(observation);
            boolean retainedBarrier = step.state() == State.RECOVERY_REQUIRED;
            if (step.state() != State.DISPATCHING && !retainedBarrier || step.observation() != null)
                throw new IllegalStateException("BUILD_OBSERVATION_ALREADY_RECORDED_OR_CHANGED");
            Map<BlueprintDocument.Position, BlueprintDocument.State> actual = new HashMap<>();
            for (var cell : observation.cells()) if (actual.put(cell.position(), cell.state()) != null) throw new IllegalArgumentException("BUILD_DUPLICATE_OBSERVATION");
            Set<BlueprintDocument.Position> footprint = new HashSet<>(); step.cells().forEach(cell -> footprint.add(cell.position()));
            boolean complete = actual.keySet().equals(footprint);
            boolean unchanged = complete && step.cells().stream().allMatch(cell -> cell.before().equals(actual.get(cell.position())));
            boolean exact = complete && step.cells().stream().allMatch(cell -> (cell.target() == null ? cell.before() : cell.target()).equals(actual.get(cell.position())));
            if (retainedBarrier) return append(copy(step, State.RECOVERY_REQUIRED, observation, "LATE_WORLD_EVIDENCE_RECORDED", step.receipt()));
            if (observation.uncertain() || !observation.otherItems().isEmpty())
                return append(copy(step, State.RECOVERY_REQUIRED, observation, "NATIVE_EFFECT_UNCERTAIN", ""));
            if (!observation.success() && unchanged && observation.remaining().equals(step.input()))
                return append(copy(step, State.REJECTED, observation, observation.code(), ""));
            if (!observation.success() || !observation.mutationEntered() || !exact || !observation.remaining().empty())
                return append(copy(step, State.RECOVERY_REQUIRED, observation, "WORLD_OR_MATERIAL_MISMATCH", ""));
            return append(copy(step, State.OBSERVED, observation, "AWAITING_MATERIAL_RECEIPT_AND_WORLD_VERIFICATION", ""));
        }
    }
    /** Native server verifier supplies the current footprint and the durable material/world receipt; clients never do. */
    public Step verified(Actor actor, UUID id, List<ObservedCell> current, String receipt) throws IOException {
        synchronized (workspace.conversation()) {
            Step step = get(actor, id); require(step, State.OBSERVED);
            if (!step.executionEpoch().equals(executionEpoch) || receipt == null || !receipt.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("BUILD_VERIFICATION_PROOF_REQUIRED");
            if (!exactCurrent(step, current)) return append(copy(step, State.RECOVERY_REQUIRED, step.observation(), "WORLD_CHANGED_BEFORE_VERIFICATION", receipt));
            return append(copy(step, State.VERIFIED, step.observation(), "", receipt));
        }
    }
    public Step uncertain(Actor actor, UUID id, String reason) throws IOException {
        synchronized (workspace.conversation()) {
            Step step = get(actor, id);
            return append(copy(step, State.RECOVERY_REQUIRED, step.observation(), reason, step.receipt()));
        }
    }
    public void recover(Actor actor) throws IOException {
        synchronized (workspace.conversation()) {
            for (Step step : list(actor)) if (!step.executionEpoch().equals(executionEpoch)
                    && Set.of(State.DISPATCHING, State.OBSERVED).contains(step.state()))
                append(copy(step, State.RECOVERY_REQUIRED, step.observation(), "RESTART_NATIVE_EFFECT_REQUIRES_RECONCILIATION", step.receipt()));
        }
    }
    /** New approval settles only recorded, canonically saved outcomes; it neither places nor restores ownership for undo. */
    public void reconcileSaved(Actor actor, SavedPlacementProofs.Review review, firefly520.fireflymc.ai.material.MaterialLedger.Operation material) throws IOException {
        synchronized (workspace.conversation()) {
            review.requireCurrent(actor, material, list(actor));
            for (var proof : review.steps()) {
                var step = get(actor, proof.operationId());
                if (step.state() != State.VERIFIED && step.state() != State.REJECTED && step.state() != State.RECONCILED)
                    append(copy(step, State.RECONCILED, step.observation(), "SAVED_NATIVE_HISTORY_RECONCILED_NO_UNDO_OWNERSHIP", proof.receipt()));
            }
            rejectUnstarted(actor, review.operationId());
        }
    }
    /** An owning recovery controller may retire only steps that never reached their dispatch intent. */
    public void rejectUnstarted(Actor actor, UUID materialOperation) throws IOException {
        synchronized (workspace.conversation()) {
            for (var step : list(actor)) if (step.materialOperationId().equals(materialOperation)
                    && step.state() == State.PREPARED && step.observation() == null)
                append(copy(step, State.REJECTED, null, "UNSTARTED_WORLD_STEP_CANCELLED_NO_NATIVE_REPLAY", ""));
        }
    }
    public boolean undoCandidate(Actor actor, UUID id, List<ObservedCell> current, String liveReceipt) {
        synchronized (workspace.conversation()) {
            Step step = get(actor, id);
            return step.state() == State.VERIFIED && step.executionEpoch().equals(executionEpoch)
                    && !step.receipt().isEmpty() && step.receipt().equals(liveReceipt) && exactCurrent(step, current);
        }
    }
    /** Preserve the original debit/receipt; native harvest is a new transaction, never an invented refund. */
    public Step undone(Actor actor, UUID id, RemovalJournal.Step removal) throws IOException {
        synchronized (workspace.conversation()) {
            var step = get(actor, id); String receipt = RemovalJournal.harvestReceipt(removal);
            String reason = "NATIVE_UNDO:" + removal.input().operationId() + ":" + receipt;
            if (!step.owner().equals(removal.owner()) || !step.executionEpoch().equals(executionEpoch) || !removal.executionEpoch().equals(executionEpoch)
                    || removal.input().mode() != RemovalJournal.Mode.UNDO || !removal.input().sourceSteps().contains(id)
                    || !step.dimension().equals(removal.input().dimension()) || removal.state() != RemovalJournal.State.ACCOUNTED)
                throw new IllegalStateException("UNDO_SOURCE_RECEIPT_MISMATCH");
            var restored = new HashMap<BlueprintDocument.Position, BlueprintDocument.State>();
            removal.input().cells().stream().filter(cell -> cell.target() != null).forEach(cell -> restored.put(cell.position(), cell.target()));
            if (step.cells().stream().filter(cell -> cell.target() != null).anyMatch(cell -> !restored.containsKey(cell.position())
                    || !RemovalTransitions.air(restored.get(cell.position())) && !cell.before().equals(restored.get(cell.position()))))
                throw new IllegalStateException("UNDO_SOURCE_FOOTPRINT_MISMATCH");
            if (step.state() == State.UNDONE && step.reason().equals(reason)) return step;
            require(step, State.VERIFIED);
            return append(copy(step, State.UNDONE, step.observation(), reason, step.receipt()));
        }
    }
    private static boolean exactCurrent(Step step, List<ObservedCell> current) {
        var actual = new HashMap<BlueprintDocument.Position, BlueprintDocument.State>();
        for (var cell : current) if (actual.put(cell.position(), cell.state()) != null) return false;
        return current.size() == step.cells().size() && step.cells().stream()
                .allMatch(cell -> (cell.target() == null ? cell.before() : cell.target()).equals(actual.get(cell.position())));
    }
    public static boolean hasUnresolvedWorldEffects(Workspace workspace) {
        var journal = new BuildJournal(workspace, UUID.randomUUID());
        var actor = Actor.player(UUID.fromString(workspace.owner()), true, true, 0);
        return journal.list(actor).stream().anyMatch(step -> Set.of(State.DISPATCHING, State.OBSERVED, State.RECOVERY_REQUIRED).contains(step.state()))
                || RemovalJournal.hasUnresolvedWorldEffects(workspace);
    }
    private Step append(Step step) throws IOException {
        workspace.conversation().append(List.of(new Draft(step.taskId(), "build_step", null, AiJson.GSON.toJsonTree(step).getAsJsonObject(), null)));
        return step;
    }
    private static void require(Step step, State state) { if (step.state() != state) throw new IllegalStateException("BUILD_PHASE_" + step.state()); }
    private static Step copy(Step s, State state, Observation observation, String reason, String receipt) {
        return new Step(s.schema(), s.operationId(), s.executionEpoch(), s.owner(), s.taskId(), s.planId(), s.planHash(), s.materialOperationId(),
                s.escrowIndex(), s.dimension(), state, s.cells(), s.input(), observation, reason, receipt, s.version() + 1);
    }
}
