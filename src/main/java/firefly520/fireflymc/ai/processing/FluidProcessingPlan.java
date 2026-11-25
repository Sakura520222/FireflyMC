package firefly520.fireflymc.ai.processing;

import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.storage.AiJson;
import java.util.*;

/** Immutable approval of exact native item/fluid transformations and their physical sources. */
public record FluidProcessingPlan(int schema, UUID id, UUID serverInstanceId, UUID conversationId,
        String owner, UUID taskId, UUID executionEpoch, long privacyEpoch, String dimension, long recipeGeneration,
        String recipeId, String mechanismId, String fluidId, String inputItemId, int count,
        String recipeType, String serializerId, List<MaterialStack> inputs,
        List<FluidProcessingAdapters.Evaluation> evaluations, List<MaterialLedger.Stock> sources,
        List<MaterialLedger.Need> needs, List<Claim> fluidClaims, List<OutputTarget> outputTargets, String hash) {
    public record Claim(UUID operationId, FluidInventoryService.Source source, FluidAmount amount) {
        public Claim {
            Objects.requireNonNull(operationId); Objects.requireNonNull(source); Objects.requireNonNull(amount);
            if (amount.empty() || amount.millibuckets() > 1_000_000
                    || source.snapshot().amount(amount.fingerprint()) < amount.millibuckets())
                throw new IllegalArgumentException("FLUID_PLAN_CLAIM_INVALID");
        }
    }
    public record OutputTarget(int outputIndex, FluidInventoryService.Source source, FluidAmount amount) {
        public OutputTarget {
            Objects.requireNonNull(source); Objects.requireNonNull(amount);
            if (outputIndex < 0 || outputIndex >= 64 || amount.empty() || amount.millibuckets() > 1_000_000)
                throw new IllegalArgumentException("FLUID_PLAN_TARGET_INVALID");
        }
    }
    public FluidProcessingPlan {
        Objects.requireNonNull(id); Objects.requireNonNull(serverInstanceId); Objects.requireNonNull(conversationId);
        Objects.requireNonNull(taskId); Objects.requireNonNull(executionEpoch); UUID.fromString(owner);
        inputs = List.copyOf(inputs); evaluations = List.copyOf(evaluations); sources = List.copyOf(sources);
        needs = List.copyOf(needs); fluidClaims = List.copyOf(fluidClaims); outputTargets = List.copyOf(outputTargets);
        new FluidProcessingPlanner.Request(recipeId, mechanismId, fluidId, inputItemId, count, null);
        if (schema != 1 || privacyEpoch < 0 || recipeGeneration < 0 || !resource(dimension)
                || (recipeId.isEmpty() ? !"".equals(recipeType) || !"".equals(serializerId)
                    : !resource(recipeType) || !resource(serializerId)))
            throw new IllegalArgumentException("FLUID_PLAN_REFERENCE_INVALID");
        if (inputs.size() != count || evaluations.size() != count || sources.isEmpty() || sources.size() > 81
                || needs.isEmpty() || needs.size() > 81 || fluidClaims.size() > 32 || outputTargets.size() > 256
                || hash == null || !hash.isEmpty() && !hash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("FLUID_PLAN_INVALID");
        var itemInputs = new ArrayList<MaterialStack>(); var itemOutputs = new ArrayList<MaterialStack>();
        var fluidInputs = new ArrayList<FluidAmount>(); var fluidOutputs = new ArrayList<FluidAmount>();
        String adapter = evaluations.getFirst().adapterVersion();
        for (int i = 0; i < count; i++) {
            var evaluation = evaluations.get(i);
            if (inputs.get(i).count() != 1 || !evaluation.consumedItems().equals(List.of(inputs.get(i)))
                    || !inputItemId.isEmpty() && !inputItemId.equals(AiJson.object(inputs.get(i).itemJson(), 1_048_576, 32).get("id").getAsString())
                    || !evaluation.referenceKind().equals(recipeId.isEmpty() ? "mechanism" : "recipe")
                    || !evaluation.referenceId().equals(recipeId.isEmpty() ? mechanismId : recipeId)
                    || !adapter.equals(evaluation.adapterVersion())) throw new IllegalArgumentException("FLUID_PLAN_EVALUATION_CHANGED");
            itemInputs.addAll(evaluation.consumedItems()); itemOutputs.addAll(evaluation.producedItems());
            fluidInputs.addAll(evaluation.consumedFluids()); fluidOutputs.addAll(evaluation.producedFluids());
        }
        if (itemInputs.size() > 128 || itemOutputs.size() > 128 || fluidInputs.size() > 64 || fluidOutputs.size() > 64
                || itemInputs.stream().anyMatch(MaterialStack::empty) || itemOutputs.stream().anyMatch(MaterialStack::empty)
                || fluidInputs.stream().anyMatch(FluidAmount::empty) || fluidOutputs.stream().anyMatch(FluidAmount::empty)
                || itemOutputs.stream().mapToLong(MaterialStack::count).sum() > 8192
                || fluidInputs.stream().mapToLong(FluidAmount::millibuckets).sum() > 1_000_000
                || fluidOutputs.stream().mapToLong(FluidAmount::millibuckets).sum() > 1_000_000)
            throw new IllegalArgumentException("FLUID_PLAN_MATERIAL_LIMIT");
        var selected = new HashMap<String, MaterialLedger.Stock>(); var poolSizes = new HashMap<String, Long>();
        for (var source : sources) {
            if (selected.putIfAbsent(source.key(), source) != null) throw new IllegalArgumentException("FLUID_PLAN_DUPLICATE_SOURCE");
            Long prior = poolSizes.putIfAbsent(source.poolId(), source.poolUnits());
            if (prior != null && prior != source.poolUnits()) throw new IllegalArgumentException("FLUID_PLAN_POOL_CHANGED");
        }
        var physical = new ArrayList<MaterialStack>(); var used = new HashMap<String, Long>(); var keys = new HashSet<String>();
        for (var need : needs) {
            var stock = selected.get(need.stockKey());
            if (stock == null || !keys.add(need.stockKey()) || need.count() > stock.extractableCount())
                throw new IllegalArgumentException("FLUID_PLAN_ITEM_SOURCE_CHANGED");
            physical.add(stock.stack().withCount(need.count()));
            long usage = used.merge(stock.poolId(), Math.multiplyExact((long) need.count(), stock.unitsPerItem()), Math::addExact);
            if (usage > stock.poolUnits()) throw new IllegalArgumentException("FLUID_PLAN_POOL_OVERALLOCATED");
        }
        if (keys.size() != sources.size() || !itemTotals(physical).equals(itemTotals(itemInputs)))
            throw new IllegalArgumentException("FLUID_PLAN_ITEM_INPUT_MISMATCH");
        var claimIds = new HashSet<UUID>(); var sourceKinds = new HashSet<String>();
        var backendSources = new LinkedHashMap<String, FluidInventoryService.Source>();
        for (var claim : fluidClaims) {
            if (id.equals(claim.operationId()) || !claimIds.add(claim.operationId())
                    || !sourceKinds.add(claim.source().backend() + "\n" + claim.amount().unitJson()))
                throw new IllegalArgumentException("FLUID_PLAN_DUPLICATE_CLAIM");
            mergeSource(backendSources, claim.source());
        }
        if (!fluidTotals(fluidClaims.stream().map(Claim::amount).toList()).equals(fluidTotals(fluidInputs)))
            throw new IllegalArgumentException("FLUID_PLAN_RAW_INPUT_MISMATCH");
        int[] assigned = new int[fluidOutputs.size()]; var targetKeys = new HashSet<String>();
        for (var target : outputTargets) {
            if (target.outputIndex() >= fluidOutputs.size()
                    || !fluidOutputs.get(target.outputIndex()).unitJson().equals(target.amount().unitJson())
                    || !targetKeys.add(target.outputIndex() + "\n" + target.source().backend()))
                throw new IllegalArgumentException("FLUID_PLAN_OUTPUT_MISMATCH");
            assigned[target.outputIndex()] = Math.addExact(assigned[target.outputIndex()], target.amount().millibuckets());
            mergeSource(backendSources, target.source());
        }
        for (int i = 0; i < assigned.length; i++) if (assigned[i] != fluidOutputs.get(i).millibuckets())
            throw new IllegalArgumentException("FLUID_PLAN_OUTPUT_NOT_ALLOCATED");
        // Reconstruct the complete shared routing state: aliases cannot multiply capacity.
        var shadows = FluidProcessingPlanner.shadows(List.copyOf(backendSources.values()), fluidClaims);
        for (var target : outputTargets) if (FluidProcessingPlanner.fill(shadows.get(target.source().backend()), target.amount())
                != target.amount().millibuckets()) throw new IllegalArgumentException("FLUID_PLAN_OUTPUT_FULL");
    }
    public List<MaterialStack> itemInputs() { return evaluations.stream().flatMap(e -> e.consumedItems().stream()).toList(); }
    public List<MaterialStack> itemOutputs() { return evaluations.stream().flatMap(e -> e.producedItems().stream()).toList(); }
    public List<FluidAmount> fluidInputs() { return evaluations.stream().flatMap(e -> e.consumedFluids().stream()).toList(); }
    public List<FluidAmount> fluidOutputs() { return evaluations.stream().flatMap(e -> e.producedFluids().stream()).toList(); }
    public String policy() { return "MATERIALS_ONLY@1"; }
    public String evidence() {
        return "fixed-native-fluid@1:" + (recipeId.isEmpty() ? mechanismId : recipeId) + ":"
                + evaluations.getFirst().adapterVersion() + ":" + AiJson.digest(AiJson.canonical(evaluations));
    }
    public FluidProcessingPlan signed() { return withHash(AiJson.digest(AiJson.canonical(withHash("")))); }
    public boolean intact() { return hash.equals(AiJson.digest(AiJson.canonical(withHash("")))); }
    private FluidProcessingPlan withHash(String value) {
        return new FluidProcessingPlan(schema, id, serverInstanceId, conversationId, owner, taskId, executionEpoch,
                privacyEpoch, dimension, recipeGeneration, recipeId, mechanismId, fluidId, inputItemId, count, recipeType,
                serializerId, inputs, evaluations, sources, needs, fluidClaims, outputTargets, value);
    }
    static boolean resource(String value) { return value != null && value.length() <= 256 && value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"); }
    static Map<String, Long> itemTotals(List<MaterialStack> values) {
        var totals = new TreeMap<String, Long>();
        values.forEach(value -> totals.merge(value.itemJson(), (long) value.count(), Math::addExact)); return totals;
    }
    static Map<String, Long> fluidTotals(List<FluidAmount> values) {
        var totals = new TreeMap<String, Long>();
        values.forEach(value -> totals.merge(value.unitJson(), (long) value.millibuckets(), Math::addExact)); return totals;
    }
    static void mergeSource(Map<String, FluidInventoryService.Source> values, FluidInventoryService.Source source) {
        var previous = values.putIfAbsent(source.backend(), source);
        if (previous != null && !previous.equals(source)) throw new IllegalArgumentException("FLUID_PLAN_BACKEND_ALIAS_CONFLICT");
    }
}
