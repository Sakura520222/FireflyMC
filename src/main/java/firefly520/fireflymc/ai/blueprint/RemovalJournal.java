package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.material.MaterialStack;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.util.*;

/** Durable native mining stages. Unknown outcomes are never inferred from matching block counts. */
public final class RemovalJournal {
    public enum Mode { DEMOLITION, UNDO }
    public static final String CUSTODY_EVIDENCE = "NATIVE_LOOT_CUSTODY";
    public enum State { PREPARED, MINING, READY, REMOVED, ACCOUNTED, REJECTED, CANCELLED, RECOVERY_REQUIRED }
    public enum Action { START, REMOVE, ABORT }
    public record Input(UUID operationId, UUID taskId, UUID planId, String planHash, String dimension, Mode mode,
                        List<UUID> sourceSteps, List<BuildJournal.Cell> cells, MaterialStack hand) {
        public Input {
            sourceSteps = List.copyOf(sourceSteps); cells = List.copyOf(cells);
            if (operationId == null || taskId == null || planId == null || mode == null || hand == null
                    || planHash == null || !planHash.matches("[0-9a-f]{64}") || dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")
                    || cells.isEmpty() || cells.size() > 128 || sourceSteps.size() > 128 || mode == Mode.UNDO && sourceSteps.isEmpty() && !RemovalTransitions.partialOnly(cells)
                    || new HashSet<>(sourceSteps).size() != sourceSteps.size() || cells.stream().map(BuildJournal.Cell::position).distinct().count() != cells.size()
                    || cells.stream().noneMatch(cell -> cell.target() != null) || cells.stream().anyMatch(cell -> !RemovalTransitions.valid(cell)))
                throw new IllegalArgumentException("REMOVAL_JOURNAL_INPUT");
        }
    }
    public record Drop(UUID nativeDropId, MaterialStack stack) {
        public Drop { if (nativeDropId == null || stack == null || stack.empty()) throw new IllegalArgumentException("REMOVAL_DROP_INVALID"); }
    }
    public record Evidence(String nativeState, String reason, double progress, MaterialStack hand, float exhaustion,
                           List<BuildJournal.ObservedCell> world, List<Drop> drops) {
        public Evidence {
            world = List.copyOf(world); drops = List.copyOf(drops);
            if (nativeState == null || reason == null || hand == null || !Double.isFinite(progress) || progress < 0 || progress > 1
                    || !Float.isFinite(exhaustion) || exhaustion < 0 || exhaustion > 40 || world.size() > 128 || drops.size() > 128
                    || world.stream().map(BuildJournal.ObservedCell::position).distinct().count() != world.size()
                    || drops.stream().map(Drop::nativeDropId).distinct().count() != drops.size()) throw new IllegalArgumentException("REMOVAL_EVIDENCE_INVALID");
        }
    }
    public record Save(Action action, UUID attempt, String proof) {
        public Save { if (action == null || attempt == null || proof == null || !proof.isEmpty() && !proof.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("REMOVAL_SAVE_INVALID"); }
    }
    public record Step(int version, String owner, UUID executionEpoch, Input input, State state, Action intent,
                       UUID attempt, Evidence evidence, List<Save> saves, String reason, long revision) {
        public Step { saves = List.copyOf(saves); }
        public boolean saved() { return saves.stream().allMatch(save -> save.proof() != null && save.proof().matches("[0-9a-f]{64}")); }
        public boolean settled() {
            return intent == null && saved() && (Set.of(State.ACCOUNTED, State.REJECTED, State.CANCELLED).contains(state)
                    || state == State.REMOVED && evidence != null && !CUSTODY_EVIDENCE.equals(evidence.reason()));
        }
    }
    private final Workspace workspace;
    private final UUID epoch;
    private final long privacyEpoch;
    public RemovalJournal(Workspace workspace, UUID epoch) {
        this.workspace = workspace; this.epoch = Objects.requireNonNull(epoch); privacyEpoch = workspace.conversation().privacyEpoch();
    }
    public List<Step> list(Actor actor) {
        synchronized (workspace.conversation()) {
            access(actor); var values = new LinkedHashMap<UUID, Step>();
            for (var event : workspace.conversation().getSnapshot(privacyEpoch).events()) if (event.kind().equals("build_removal_step")) {
                var step = AiJson.GSON.fromJson(event.data(), Step.class);
                if (step.version() != 1 || !step.owner().equals(actor.owner())) throw new IllegalStateException("REMOVAL_JOURNAL_INVALID");
                values.put(step.input().operationId(), step);
            }
            return List.copyOf(values.values());
        }
    }
    public Step get(Actor actor, UUID id) { return list(actor).stream().filter(step -> step.input().operationId().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("REMOVAL_STEP_NOT_FOUND")); }
    public Step prepare(Actor actor, Input input) throws IOException {
        synchronized (workspace.conversation()) {
            var previous = list(actor).stream().filter(step -> step.input().operationId().equals(input.operationId())).findFirst();
            if (previous.isPresent()) {
                if (!previous.get().input().equals(input)) throw new SecurityException("REMOVAL_OPERATION_REUSED");
                return previous.get();
            }
            if (list(actor).stream().filter(step -> !step.settled()).count() >= 256) throw new IllegalStateException("REMOVAL_PENDING_LIMIT");
            return append(new Step(1, actor.owner(), epoch, input, State.PREPARED, null, null, null, List.of(), "", 1));
        }
    }
    public Step begin(Actor actor, UUID id, Action action) throws IOException {
        synchronized (workspace.conversation()) {
            Step step = get(actor, id);
            if (!step.executionEpoch().equals(epoch) || step.intent() != null || !(action == Action.START && step.state() == State.PREPARED
                    || action == Action.REMOVE && step.state() == State.READY || action == Action.ABORT && Set.of(State.MINING, State.READY).contains(step.state())))
                throw new IllegalStateException("REMOVAL_PHASE_CHANGED");
            UUID attempt = UUID.randomUUID(); var saves = new ArrayList<>(step.saves()); saves.add(new Save(action, attempt, ""));
            return append(copy(step, step.state(), action, attempt, step.evidence(), saves, "NATIVE_STAGE_PENDING"));
        }
    }
    public Step ready(Actor actor, UUID id, Evidence evidence) throws IOException {
        synchronized (workspace.conversation()) {
            var step = get(actor, id);
            if (!step.executionEpoch().equals(epoch) || step.intent() != null || !Set.of(State.MINING, State.READY).contains(step.state())
                    || !evidence.nativeState().equals("READY") || evidence.progress() < 1 || !unchanged(step, evidence)) throw new IllegalStateException("REMOVAL_NOT_READY");
            return append(copy(step, State.READY, null, null, evidence, step.saves(), "NATIVE_MINING_TIME_PAID"));
        }
    }
    public Step observed(Actor actor, UUID id, UUID attempt, Evidence evidence) throws IOException {
        synchronized (workspace.conversation()) {
            var step = get(actor, id); requireAttempt(step, attempt); boolean barrier = step.state() == State.RECOVERY_REQUIRED;
            State next = State.RECOVERY_REQUIRED; var saves = step.saves();
            if (step.intent() == Action.START && sameWorld(step, evidence, false) && evidence.drops().isEmpty()) {
                if (evidence.nativeState().equals("MINING")) next = State.MINING;
                else if (evidence.nativeState().equals("READY") && evidence.progress() >= 1) next = State.READY;
            } else if (step.intent() == Action.REMOVE) {
                if (evidence.nativeState().equals("REMOVED") && sameWorld(step, evidence, true)) next = State.REMOVED;
                else if (evidence.nativeState().equals("MINING") && evidence.reason().equals("MINING_RATE_CHANGED") && unchanged(step, evidence)) {
                    next = State.MINING; saves = step.saves().stream().filter(save -> !save.attempt().equals(attempt)).toList();
                }
            } else if (step.intent() == Action.ABORT && evidence.nativeState().equals("CANCELLED") && unchanged(step, evidence)) next = State.CANCELLED;
            if (evidence.nativeState().equals("REJECTED") && unchanged(step, evidence)) next = State.REJECTED;
            if (barrier) next = State.RECOVERY_REQUIRED;
            return append(copy(step, next, null, null, evidence, saves, next == State.RECOVERY_REQUIRED ? "REMOVAL_NATIVE_OUTCOME_REQUIRES_RECONCILIATION" : evidence.reason()));
        }
    }
    public Step notDispatched(Actor actor, UUID id, UUID attempt) throws IOException {
        synchronized (workspace.conversation()) {
            var step = get(actor, id); requireAttempt(step, attempt);
            return append(copy(step, step.state(), null, null, step.evidence(), step.saves().stream().filter(save -> !save.attempt().equals(attempt)).toList(), "NATIVE_STAGE_NOT_DISPATCHED"));
        }
    }
    public Step cancelPrepared(Actor actor, UUID id) throws IOException {
        synchronized (workspace.conversation()) {
            var step = get(actor, id);
            if (step.state() != State.PREPARED || step.intent() != null || !step.saves().isEmpty()) throw new IllegalStateException("REMOVAL_NATIVE_STAGE_ALREADY_ENTERED");
            return append(copy(step, State.CANCELLED, null, null, null, List.of(), "CANCELLED_BEFORE_NATIVE_START"));
        }
    }
    @FunctionalInterface public interface HarvestCommit<T> { T commit(Step step, String sourceReceipt) throws IOException; }
    /** The workspace lock also protects privacy deletion; native code must never run in this IO callback. */
    public <T> T accountHarvest(Actor actor, UUID id, HarvestCommit<T> commit) throws IOException {
        synchronized (workspace.conversation()) {
            var step = get(actor, id); String receipt = harvestReceipt(step);
            T value = commit.commit(step, receipt);
            if (step.state() != State.ACCOUNTED) append(copy(step, State.ACCOUNTED, null, null, step.evidence(), step.saves(), "NATIVE_LOOT_ACCOUNTED:" + receipt));
            return value;
        }
    }
    public static String harvestReceipt(Step step) {
        if (!Set.of(State.REMOVED, State.ACCOUNTED).contains(step.state()) || step.intent() != null || step.evidence() == null
                || !step.evidence().nativeState().equals("REMOVED") || !CUSTODY_EVIDENCE.equals(step.evidence().reason())
                || !sameWorld(step, step.evidence(), true) || step.saves().stream().noneMatch(save -> save.action() == Action.START)
                || step.saves().stream().noneMatch(save -> save.action() == Action.REMOVE))
            throw new IllegalStateException("VERIFIED_NATIVE_LOOT_CUSTODY_REQUIRED");
        return AiJson.digest(step.owner() + ":" + AiJson.canonical(step.input()) + ":" + AiJson.canonical(step.evidence()));
    }
    public Step nativeSaved(Actor actor, UUID id, UUID attempt, String proof) throws IOException {
        synchronized (workspace.conversation()) {
            var step = get(actor, id); if (proof == null || !proof.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("REMOVAL_SAVE_PROOF_INVALID");
            var saves = new ArrayList<>(step.saves()); int found = -1;
            for (int i = 0; i < saves.size(); i++) if (saves.get(i).attempt().equals(attempt)) { found = i; break; }
            if (found < 0) throw new IllegalStateException("REMOVAL_SAVE_ATTEMPT_UNKNOWN");
            if (!saves.get(found).proof().isEmpty()) return step;
            saves.set(found, new Save(saves.get(found).action(), attempt, proof));
            return append(copy(step, step.state(), step.intent(), step.attempt(), step.evidence(), saves, step.reason()));
        }
    }
    public Step uncertain(Actor actor, UUID id, String reason) throws IOException {
        synchronized (workspace.conversation()) { var step = get(actor, id); return append(copy(step, State.RECOVERY_REQUIRED, step.intent(), step.attempt(), step.evidence(), step.saves(), reason)); }
    }
    public Step uncertain(Actor actor, UUID id, String reason, Evidence observed) throws IOException {
        synchronized (workspace.conversation()) {
            var step = get(actor, id);
            if (step.evidence() != null && !step.evidence().drops().isEmpty() && !step.evidence().drops().equals(observed.drops()))
                throw new IllegalStateException("REMOVAL_CUSTODY_EVIDENCE_CANNOT_BE_DROPPED");
            return append(copy(step, State.RECOVERY_REQUIRED, step.intent(), step.attempt(), observed, step.saves(), reason));
        }
    }
    public void recover(Actor actor) throws IOException {
        synchronized (workspace.conversation()) {
            for (var step : list(actor)) if (!step.executionEpoch().equals(epoch) && step.state() != State.RECOVERY_REQUIRED
                    && (step.intent() != null || step.state() != State.PREPARED && !step.settled())) uncertain(actor, step.input().operationId(), "RESTART_MINING_REQUIRES_RECONCILIATION");
        }
    }
    public static boolean hasUnresolvedWorldEffects(Workspace workspace) {
        var actor = Actor.player(UUID.fromString(workspace.owner()), true, false, 0);
        return new RemovalJournal(workspace, UUID.randomUUID()).list(actor).stream().anyMatch(step -> step.intent() != null
                || step.state() != State.PREPARED && !step.settled());
    }
    public static boolean requiresRecovery(Workspace workspace, UUID task) {
        var actor = Actor.player(UUID.fromString(workspace.owner()), true, false, 0);
        return new RemovalJournal(workspace, UUID.randomUUID()).list(actor).stream().anyMatch(step -> step.input().taskId().equals(task)
                && (step.intent() != null || step.state() == State.RECOVERY_REQUIRED));
    }
    private static boolean unchanged(Step step, Evidence evidence) {
        return evidence.hand().equals(step.evidence() == null ? step.input().hand() : step.evidence().hand()) && evidence.drops().isEmpty() && sameWorld(step, evidence, false);
    }
    private static boolean sameWorld(Step step, Evidence evidence, boolean removed) {
        var actual = new HashMap<BlueprintDocument.Position, BlueprintDocument.State>(); evidence.world().forEach(cell -> actual.put(cell.position(), cell.state()));
        return actual.size() == step.input().cells().size() && step.input().cells().stream().allMatch(cell ->
                (removed && cell.target() != null ? cell.target() : cell.before()).equals(actual.get(cell.position())));
    }
    private static void requireAttempt(Step step, UUID attempt) {
        if (step.intent() == null || !Objects.equals(step.attempt(), attempt)) throw new IllegalStateException("REMOVAL_ATTEMPT_ALREADY_RECORDED_OR_CHANGED");
    }
    private void access(Actor actor) {
        actor.requireAccess(workspace.owner()); if (workspace.conversation().privacyEpoch() != privacyEpoch) throw new IllegalStateException("WORKSPACE_PRIVACY_EPOCH_CHANGED");
    }
    private Step append(Step step) throws IOException {
        workspace.conversation().append(List.of(new Draft(step.input().taskId(), "build_removal_step", null, AiJson.GSON.toJsonTree(step).getAsJsonObject(), step.input().operationId().toString()))); return step;
    }
    private static Step copy(Step step, State state, Action intent, UUID attempt, Evidence evidence, List<Save> saves, String reason) {
        return new Step(step.version(), step.owner(), step.executionEpoch(), step.input(), state, intent, attempt, evidence, saves, reason, step.revision() + 1);
    }
}
