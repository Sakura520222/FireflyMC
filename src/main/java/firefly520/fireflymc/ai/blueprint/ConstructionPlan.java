package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.material.MaterialLedger;
import firefly520.fireflymc.ai.material.MaterialStack;
import firefly520.fireflymc.ai.storage.AiJson;
import java.util.*;

/** Immutable approval envelope; native dispatch can only use these positions, exact inputs and observations. */
public record ConstructionPlan(int schema, UUID id, UUID serverInstanceId, UUID conversationId, String owner, UUID taskId,
                               UUID executionEpoch, UUID blueprintId, long blueprintRevision, String blueprintHash, String reviewStamp,
                               String dimension, long registryGeneration, List<BuildJournal.Cell> initialWorld,
                               List<Placement> placements, List<MaterialLedger.Stock> sources, List<MaterialLedger.Need> needs,
                               String worldDigest, String materialDigest, String hash) {
    public record Placement(UUID operationId, BlueprintDocument.Position anchor, List<BuildJournal.Cell> footprint,
                            String face, double hitX, double hitY, double hitZ, float yaw, float pitch, MaterialStack material) {
        public Placement {
            Objects.requireNonNull(operationId); Objects.requireNonNull(anchor); footprint = List.copyOf(footprint);
            if (footprint.isEmpty() || footprint.size() > 128 || footprint.stream().map(BuildJournal.Cell::position).distinct().count() != footprint.size()
                    || footprint.stream().noneMatch(cell -> cell.position().equals(anchor) && cell.target() != null)
                    || !Set.of("UP", "DOWN", "NORTH", "SOUTH", "EAST", "WEST").contains(face)
                    || !Double.isFinite(hitX) || !Double.isFinite(hitY) || !Double.isFinite(hitZ)
                    || hitX < 0 || hitX > 1 || hitY < 0 || hitY > 1 || hitZ < 0 || hitZ > 1
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch) || pitch < -90 || pitch > 90
                    || material == null || material.empty() || material.count() != 1)
                throw new IllegalArgumentException("CONSTRUCTION_PLACEMENT_INVALID");
        }
    }
    public ConstructionPlan {
        Objects.requireNonNull(id); Objects.requireNonNull(serverInstanceId); Objects.requireNonNull(conversationId);
        Objects.requireNonNull(taskId); Objects.requireNonNull(executionEpoch); Objects.requireNonNull(blueprintId);
        UUID.fromString(owner); initialWorld = List.copyOf(initialWorld); placements = List.copyOf(placements);
        sources = List.copyOf(sources); needs = List.copyOf(needs);
        if (schema != 1 || blueprintRevision < 1 || registryGeneration < 0 || dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                || !digest(blueprintHash) || !digest(reviewStamp) || !digest(worldDigest) || !digest(materialDigest)
                || hash == null || !hash.isEmpty() && !digest(hash) || initialWorld.isEmpty() || initialWorld.size() > 16384
                || placements.isEmpty() || placements.size() > 512 || sources.isEmpty() || sources.size() > 81 || needs.size() > 81)
            throw new IllegalArgumentException("CONSTRUCTION_PLAN_INVALID");
        if (initialWorld.stream().map(BuildJournal.Cell::position).distinct().count() != initialWorld.size()
                || placements.stream().map(Placement::operationId).distinct().count() != placements.size())
            throw new IllegalArgumentException("CONSTRUCTION_DUPLICATE_OPERATION");
        var positions = new HashSet<BlueprintDocument.Position>(); initialWorld.forEach(cell -> positions.add(cell.position()));
        if (placements.stream().flatMap(step -> step.footprint().stream()).anyMatch(cell -> !positions.contains(cell.position())))
            throw new IllegalArgumentException("CONSTRUCTION_OUTSIDE_SURVEY");
        if (!worldDigest.equals(AiJson.digest(AiJson.canonical(initialWorld))) || !materialDigest.equals(materialDigest(sources, needs)))
            throw new IllegalArgumentException("CONSTRUCTION_SNAPSHOT_CHANGED");
        var physical = new LinkedHashMap<String, MaterialLedger.Stock>();
        for (var source : sources) if (physical.putIfAbsent(source.key(), source) != null) throw new IllegalArgumentException("CONSTRUCTION_DUPLICATE_SOURCE");
        if (needs.stream().map(MaterialLedger.Need::stockKey).distinct().count() != needs.size()) throw new IllegalArgumentException("CONSTRUCTION_DUPLICATE_NEED");
        var capacity = new HashMap<String, Long>();
        for (var source : sources) {
            Long previous = capacity.putIfAbsent(source.poolId(), source.poolUnits());
            if (previous != null && previous != source.poolUnits()) throw new IllegalArgumentException("CONSTRUCTION_POOL_SNAPSHOT_CHANGED");
        }
        var held = new TreeMap<String, Integer>(); var pools = new HashMap<String, Long>();
        for (var need : needs) {
            var source = physical.get(need.stockKey());
            if (source == null || need.count() > source.extractableCount()) throw new IllegalArgumentException("CONSTRUCTION_SOURCE_MISMATCH");
            held.merge(source.stack().itemJson(), need.count(), Math::addExact);
            long units = pools.merge(source.poolId(), Math.multiplyExact((long) need.count(), source.unitsPerItem()), Math::addExact);
            if (units > source.poolUnits()) throw new IllegalArgumentException("CONSTRUCTION_POOL_OVERALLOCATED");
        }
        var consumed = new TreeMap<String, Integer>(); placements.forEach(step -> consumed.merge(step.material().itemJson(), 1, Math::addExact));
        if (!held.equals(consumed)) throw new IllegalArgumentException("CONSTRUCTION_MATERIAL_MISMATCH");
        var projected = new HashMap<BlueprintDocument.Position, BlueprintDocument.State>();
        initialWorld.forEach(cell -> projected.put(cell.position(), cell.before()));
        for (var placement : placements) {
            for (var cell : placement.footprint()) if (!cell.before().equals(projected.get(cell.position())))
                throw new IllegalArgumentException("CONSTRUCTION_STEP_ORDER_CHANGED");
            for (var cell : placement.footprint()) if (cell.target() != null) projected.put(cell.position(), cell.target());
        }
        if (initialWorld.stream().anyMatch(cell -> cell.target() != null && !cell.target().equals(projected.get(cell.position()))))
            throw new IllegalArgumentException("CONSTRUCTION_TARGET_NOT_COVERED");
    }
    public static String materialDigest(List<MaterialLedger.Stock> sources, List<MaterialLedger.Need> needs) {
        return AiJson.digest(AiJson.canonical(Map.of("sources", sources, "needs", needs)));
    }
    public ConstructionPlan signed() { return withHash(AiJson.digest(AiJson.canonical(withHash("")))); }
    public boolean intact() { return hash.equals(AiJson.digest(AiJson.canonical(withHash("")))); }
    private ConstructionPlan withHash(String value) {
        return new ConstructionPlan(schema, id, serverInstanceId, conversationId, owner, taskId, executionEpoch, blueprintId,
                blueprintRevision, blueprintHash, reviewStamp, dimension, registryGeneration, initialWorld, placements, sources, needs, worldDigest, materialDigest, value);
    }
    private static boolean digest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
}
