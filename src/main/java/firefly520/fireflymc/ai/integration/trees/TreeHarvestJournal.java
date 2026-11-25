package firefly520.fireflymc.ai.integration.trees;

import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Separate durable intent and accepted-result records. Unfinished histories never re-enter native code. */
public final class TreeHarvestJournal {
    public record Observation(TreeHarvestNative.State state, String reason, int serverTick, double progress,
                              firefly520.fireflymc.ai.material.MaterialStack hand, float exhaustion,
                              List<TreeHarvestNative.Cell> changed, List<UUID> fallingEntities, List<TreeHarvestNative.Drop> drops, List<TreeHarvestNative.Pickup> pickups,
                              List<TreeHarvestNative.RootTickRecord> scheduledRootTicks, List<UUID> completedRootTicks) {
        public Observation { changed = List.copyOf(changed); fallingEntities = List.copyOf(fallingEntities); drops = List.copyOf(drops); pickups = List.copyOf(pickups);
            scheduledRootTicks = scheduledRootTicks == null ? List.of() : List.copyOf(scheduledRootTicks);
            completedRootTicks = completedRootTicks == null ? List.of() : List.copyOf(completedRootTicks); }
        public boolean rootTicksSettled() { return scheduledRootTicks.stream().allMatch(root -> completedRootTicks.contains(root.id()))
                && completedRootTicks.size() == completedRootTicks.stream().distinct().count()
                && completedRootTicks.stream().allMatch(id -> scheduledRootTicks.stream().anyMatch(root -> root.id().equals(id))); }
        static Observation of(TreeHarvestNative.Evidence evidence) { return new Observation(evidence.state(), evidence.reason(), evidence.serverTick(), evidence.progress(),
                evidence.hand(), evidence.exhaustion(), evidence.changed(), evidence.fallingEntities(), evidence.drops(), evidence.pickups(), evidence.scheduledRootTicks(), evidence.completedRootTicks()); }
    }
    public record Operation(TreeHarvestPlan plan, TreeHarvestNative.State state, String reason, long revision, UUID intent,
                            Observation observation, Map<UUID, String> saves) {
        public Operation { saves = Map.copyOf(saves); }
        public boolean terminal() { return Set.of(TreeHarvestNative.State.COMPLETED, TreeHarvestNative.State.WORLD_REMAINDER, TreeHarvestNative.State.CANCELLED, TreeHarvestNative.State.REJECTED).contains(state); }
        public boolean settled() { return terminal() && observation != null && observation.rootTicksSettled() && intent == null && !saves.isEmpty()
                && saves.values().stream().allMatch(proof -> proof.matches("[0-9a-f]{64}")); }
        public boolean savedKnownTerminal() { return observation != null && observation.rootTicksSettled() && intent == null && !saves.isEmpty()
                && Set.of(TreeHarvestNative.State.COMPLETED, TreeHarvestNative.State.WORLD_REMAINDER, TreeHarvestNative.State.REJECTED, TreeHarvestNative.State.CANCELLED).contains(observation.state())
                && saves.values().stream().allMatch(proof -> proof.matches("[0-9a-f]{64}")); }
    }
    private final Workspace workspace;
    private final long privacy;
    private final ConversationStore store;
    private final Map<UUID, Operation> operations = new LinkedHashMap<>();
    private volatile List<Operation> published = List.of();
    private volatile boolean broken;
    public TreeHarvestJournal(Workspace workspace) throws IOException {
        this.workspace = workspace; privacy = workspace.conversation().privacyEpoch();
        Path namespace = workspace.directory().resolve("tree-harvest"), folder = namespace.resolve("epoch-" + privacy), path = folder.resolve("journal.jsonl");
        if (Files.isSymbolicLink(namespace) || Files.isSymbolicLink(folder) || Files.isSymbolicLink(path)) throw new IOException("TREE_UNSAFE_PATH");
        store = new ConversationStore(path);
        for (var event : store.getSnapshot(0).events()) {
            if (!Set.of("tree_harvest_created", "tree_harvest_state").contains(event.kind())) throw new IOException("TREE_JOURNAL_EVENT_INVALID");
            var data = event.data(); UUID id = UUID.fromString(data.get("operationId").getAsString());
            Operation previous = operations.get(id);
            TreeHarvestPlan plan = previous == null ? AiJson.GSON.fromJson(data.get("plan"), TreeHarvestPlan.class) : previous.plan();
            if (previous == null && !event.kind().equals("tree_harvest_created") || previous != null && !event.kind().equals("tree_harvest_state")) throw new IOException("TREE_JOURNAL_ORDER_INVALID");
            var op = new Operation(plan, TreeHarvestNative.State.valueOf(data.get("state").getAsString()), data.get("reason").getAsString(),
                    data.get("revision").getAsLong(), data.has("intent") ? UUID.fromString(data.get("intent").getAsString()) : null,
                    data.has("observation") ? AiJson.GSON.fromJson(data.get("observation"), Observation.class) : null, readSaves(data.getAsJsonObject("saves")));
            validate(op);
            if (!id.equals(plan.id()) || op.revision() != (previous == null ? 1 : previous.revision() + 1)) throw new IOException("TREE_JOURNAL_IDENTITY_INVALID");
            operations.put(id, op);
        }
        published = List.copyOf(operations.values());
        for (var op : List.copyOf(operations.values())) if (!op.settled() && op.state() != TreeHarvestNative.State.RECOVERY_REQUIRED)
            persist(new Operation(op.plan(), TreeHarvestNative.State.RECOVERY_REQUIRED, "TREE_RESTART_NO_NATIVE_REPLAY", op.revision() + 1, op.intent(), op.observation(), op.saves()), false);
    }
    public synchronized Operation get(Actor actor, UUID id) { access(actor); var op = operations.get(id); if (op == null) throw new IllegalArgumentException("TREE_OPERATION_NOT_FOUND"); return op; }
    public synchronized Optional<Operation> find(Actor actor, UUID id) { access(actor); return Optional.ofNullable(operations.get(id)); }
    public List<Operation> snapshot(Actor actor) { access(actor); return published; }
    public synchronized Operation create(Actor actor, TreeHarvestPlan plan) throws IOException {
        access(actor); actor.requireAccess(plan.owner());
        if (operations.containsKey(plan.id())) throw new IllegalStateException("TREE_OPERATION_ALREADY_RECORDED");
        if (operations.values().stream().filter(op -> !op.settled()).count() >= 16) throw new IllegalStateException("TREE_PENDING_LIMIT");
        return persist(new Operation(plan, TreeHarvestNative.State.PREPARED, "TREE_NATIVE_NOT_STARTED", 1, null, null, Map.of()), true);
    }
    public synchronized UUID intent(Actor actor, UUID id) throws IOException {
        return intent(actor, id, null);
    }
    public synchronized UUID intent(Actor actor, UUID id, TreeHarvestNative.RootTickRecord callback) throws IOException {
        var op = get(actor, id);
        if (op.terminal() || op.state() == TreeHarvestNative.State.RECOVERY_REQUIRED || op.intent() != null) throw new IllegalStateException("TREE_NATIVE_INTENT_CLOSED");
        if (callback != null && (op.observation() == null || !op.plan().root().equals(callback.position())
                || op.observation().scheduledRootTicks().stream().noneMatch(record -> record.equals(callback))
                || op.observation().completedRootTicks().contains(callback.id()))) throw new IllegalStateException("TREE_ROOT_TICK_INTENT_UNAPPROVEN");
        UUID attempt = UUID.randomUUID(); persist(new Operation(op.plan(), op.state(), "TREE_NATIVE_STAGE_PENDING", op.revision() + 1, attempt, op.observation(), op.saves()), false, callback); return attempt;
    }
    public synchronized Operation observed(Actor actor, UUID id, UUID attempt, TreeHarvestNative.Evidence evidence) throws IOException {
        var op = get(actor, id); if (!Objects.equals(op.intent(), attempt)) throw new IllegalStateException("TREE_NATIVE_ATTEMPT_CHANGED");
        var saves = new LinkedHashMap<>(op.saves()); if (evidence.save() != null) saves.put(attempt, "");
        boolean missingSave = evidence.save() == null;
        var next = op.state() == TreeHarvestNative.State.RECOVERY_REQUIRED || missingSave ? TreeHarvestNative.State.RECOVERY_REQUIRED : evidence.state();
        return persist(new Operation(op.plan(), next, missingSave ? "TREE_NATIVE_SAVE_COVERAGE_MISSING" : evidence.reason(), op.revision() + 1, null, Observation.of(evidence), saves), false);
    }
    public synchronized Operation notDispatched(Actor actor, UUID id, UUID attempt) throws IOException {
        var op = get(actor, id); if (!Objects.equals(op.intent(), attempt)) throw new IllegalStateException("TREE_NATIVE_ATTEMPT_CHANGED");
        return persist(new Operation(op.plan(), op.state(), "TREE_STAGE_NOT_DISPATCHED", op.revision() + 1, null, op.observation(), op.saves()), false);
    }
    public synchronized Operation saved(Actor actor, UUID id, UUID attempt, String proof) throws IOException {
        var op = get(actor, id); if (!proof.matches("[0-9a-f]{64}") || !op.saves().containsKey(attempt)) throw new IllegalArgumentException("TREE_SAVE_PROOF_INVALID");
        var saves = new LinkedHashMap<>(op.saves()); String previous = saves.put(attempt, proof);
        if (!previous.isEmpty() && !previous.equals(proof)) throw new IllegalStateException("TREE_SAVE_PROOF_CHANGED");
        return persist(new Operation(op.plan(), op.state(), op.reason(), op.revision() + 1, op.intent(), op.observation(), saves), false);
    }
    public synchronized Operation uncertain(Actor actor, UUID id, String reason) throws IOException {
        var op = get(actor, id); return persist(new Operation(op.plan(), TreeHarvestNative.State.RECOVERY_REQUIRED, reason, op.revision() + 1, op.intent(), op.observation(), op.saves()), false);
    }
    public synchronized Operation acknowledgeSavedTerminal(Actor actor, UUID id, long revision, String reviewHash) throws IOException {
        var op = get(actor, id);
        if (op.state() != TreeHarvestNative.State.RECOVERY_REQUIRED || op.revision() != revision || !op.savedKnownTerminal()
                || reviewHash == null || !reviewHash.matches("[0-9a-f]{64}")) throw new IllegalStateException("TREE_POSITIVE_SAVED_RESULT_REQUIRED");
        return persist(new Operation(op.plan(), op.observation().state(), "TREE_RECONCILED_SAVED_RESULT:" + reviewHash, op.revision() + 1, null, op.observation(), op.saves()), false);
    }
    public boolean settled(Actor actor) { access(actor); return !broken && published.stream().allMatch(Operation::settled); }
    public boolean taskSettled(Actor actor, UUID task) { access(actor); return !broken && published.stream().filter(op -> op.plan().taskId().equals(task)).allMatch(Operation::settled); }
    public boolean canForgetAfterErasure(Actor actor) {
        actor.requireAccess(workspace.owner()); return !broken && workspace.conversation().privacyEpoch() > privacy && published.stream().allMatch(Operation::settled);
    }
    public synchronized String export(Actor actor) { access(actor); return AiJson.GSON.toJson(store.getSnapshot(0).events()); }
    private Operation persist(Operation op, boolean first) throws IOException {
        return persist(op, first, null);
    }
    private Operation persist(Operation op, boolean first, TreeHarvestNative.RootTickRecord callback) throws IOException {
        validate(op);
        synchronized (workspace.conversation()) {
            access(new Actor(workspace.owner(), true, false, 0));
            var data = new com.google.gson.JsonObject(); data.addProperty("operationId", op.plan().id().toString());
            if (first) data.add("plan", AiJson.GSON.toJsonTree(op.plan()));
            data.addProperty("state", op.state().name()); data.addProperty("reason", op.reason()); data.addProperty("revision", op.revision());
            if (op.intent() != null) data.addProperty("intent", op.intent().toString());
            if (callback != null) data.add("nativeRootTickIntent", AiJson.GSON.toJsonTree(callback));
            if (op.observation() != null) data.add("observation", AiJson.GSON.toJsonTree(op.observation()));
            data.add("saves", AiJson.GSON.toJsonTree(op.saves()));
            try {
                store.append(List.of(new ConversationStore.Draft(op.plan().taskId(), first ? "tree_harvest_created" : "tree_harvest_state", null, data, op.plan().id().toString())));
                operations.put(op.plan().id(), op); published = List.copyOf(operations.values()); return op;
            } catch (IOException | RuntimeException failure) { broken = true; throw failure; }
        }
    }
    private void validate(Operation op) throws IOException {
        if (op == null || op.plan() == null || !workspace.owner().equals(op.plan().owner()) || op.plan().privacyEpoch() != privacy
                || !workspace.metadata().serverInstanceId().equals(op.plan().server()) || !workspace.primaryConversationId().equals(op.plan().conversation())
                || !op.plan().signed().hash().equals(op.plan().hash()) || op.state() == null || op.revision() < 1 || op.reason() == null
                || op.saves().size() > 4096 || op.saves().values().stream().anyMatch(value -> !value.isEmpty() && !value.matches("[0-9a-f]{64}"))) throw new IOException("TREE_JOURNAL_INVALID");
    }
    private void access(Actor actor) {
        actor.requireAccess(workspace.owner());
        if (broken || workspace.conversation().privacyEpoch() != privacy) throw new IllegalStateException("TREE_JOURNAL_REQUIRES_REVIEW");
    }
    private static Map<UUID, String> readSaves(com.google.gson.JsonObject value) {
        var saves = new LinkedHashMap<UUID, String>(); value.entrySet().forEach(entry -> saves.put(UUID.fromString(entry.getKey()), entry.getValue().getAsString())); return saves;
    }
}
