package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.material.MaterialStack;
import firefly520.fireflymc.ai.storage.AiJson;
import java.util.*;

/** Fixed survival removal approval; the source of an undo and every intermediate footprint are signed. */
public record RemovalPlan(int schema, UUID id, UUID serverInstanceId, UUID conversationId, String owner, UUID taskId,
                          UUID executionEpoch, String dimension, long registryGeneration, RemovalJournal.Mode mode,
                          UUID sourcePlanId, String sourcePlanHash, long regionRevision, String regionHash,
                          MaterialStack hand, int handSlot, List<BuildJournal.Cell> initialWorld, List<Step> steps, String hash) {
    public record Step(UUID id, BlueprintDocument.Position anchor, List<BuildJournal.Cell> cells, List<UUID> sourceSteps) {
        public Step {
            Objects.requireNonNull(id); Objects.requireNonNull(anchor); cells = List.copyOf(cells); sourceSteps = List.copyOf(sourceSteps);
            if (cells.isEmpty() || cells.size() > 128 || cells.stream().map(BuildJournal.Cell::position).distinct().count() != cells.size()
                    || cells.stream().noneMatch(cell -> cell.position().equals(anchor) && cell.target() != null)
                    || sourceSteps.stream().distinct().count() != sourceSteps.size()) throw new IllegalArgumentException("REMOVAL_STEP_INVALID");
        }
    }
    public RemovalPlan {
        Objects.requireNonNull(id); Objects.requireNonNull(serverInstanceId); Objects.requireNonNull(conversationId); UUID.fromString(owner);
        Objects.requireNonNull(taskId); Objects.requireNonNull(executionEpoch); Objects.requireNonNull(mode); Objects.requireNonNull(hand);
        initialWorld = List.copyOf(initialWorld); steps = List.copyOf(steps);
        if (schema != 1 || registryGeneration < 0 || dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")
                || regionRevision < 0 || regionHash == null || !regionHash.isEmpty() && !digest(regionHash) || sourcePlanHash == null
                || mode == RemovalJournal.Mode.UNDO && (sourcePlanId == null || !digest(sourcePlanHash))
                || mode == RemovalJournal.Mode.DEMOLITION && (sourcePlanId != null || !sourcePlanHash.isEmpty())
                || handSlot < 0 || handSlot > 8 || initialWorld.isEmpty() || initialWorld.size() > 16384 || steps.isEmpty() || steps.size() > 512
                || steps.stream().map(Step::id).distinct().count() != steps.size() || hash == null || !hash.isEmpty() && !digest(hash))
            throw new IllegalArgumentException("REMOVAL_PLAN_INVALID");
        var actual = new HashMap<BlueprintDocument.Position, BlueprintDocument.State>();
        for (var cell : initialWorld) if (actual.put(cell.position(), cell.before()) != null) throw new IllegalArgumentException("REMOVAL_DUPLICATE_POSITION");
        var removed = new HashSet<BlueprintDocument.Position>(); var sourceSteps = new HashSet<UUID>();
        for (var step : steps) {
            if (mode == RemovalJournal.Mode.UNDO && step.sourceSteps().isEmpty() && !RemovalTransitions.partialOnly(step.cells())
                    || mode == RemovalJournal.Mode.DEMOLITION && !step.sourceSteps().isEmpty())
                throw new IllegalArgumentException("REMOVAL_SOURCE_MODE_CHANGED");
            for (var source : step.sourceSteps()) if (!sourceSteps.add(source)) throw new IllegalArgumentException("REMOVAL_SOURCE_STEP_REUSED");
            for (var cell : step.cells()) {
                if (!cell.before().equals(actual.get(cell.position()))) throw new IllegalArgumentException("REMOVAL_INTERMEDIATE_WORLD_CHANGED");
                if (!RemovalTransitions.valid(cell) || cell.target() != null && (removed.contains(cell.position())
                        || RemovalTransitions.air(cell.target()) && !removed.add(cell.position())))
                    throw new IllegalArgumentException("REMOVAL_TARGET_INVALID");
            }
            step.cells().stream().filter(cell -> cell.target() != null).forEach(cell -> actual.put(cell.position(), cell.target()));
        }
        if (initialWorld.stream().anyMatch(cell -> cell.target() != null && !cell.target().equals(actual.get(cell.position()))))
            throw new IllegalArgumentException("REMOVAL_FINAL_TARGET_INCOMPLETE");
    }
    public RemovalPlan signed() { return withHash(calculatedHash()); }
    public boolean intact() { return hash.equals(calculatedHash()); }
    private String calculatedHash() { var value = AiJson.GSON.toJsonTree(this).getAsJsonObject(); value.remove("hash"); return AiJson.digest(AiJson.canonical(value)); }
    private RemovalPlan withHash(String hash) {
        return new RemovalPlan(schema, id, serverInstanceId, conversationId, owner, taskId, executionEpoch, dimension, registryGeneration, mode,
                sourcePlanId, sourcePlanHash, regionRevision, regionHash, hand, handSlot, initialWorld, steps, hash);
    }
    private static boolean digest(String text) { return text != null && text.matches("[0-9a-f]{64}"); }
}
