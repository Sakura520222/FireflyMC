package firefly520.fireflymc.ai.processing.advanced;

import firefly520.fireflymc.ai.material.MaterialLedger;
import firefly520.fireflymc.ai.material.MaterialStack;
import firefly520.fireflymc.ai.processing.VerifiedTransformation;
import firefly520.fireflymc.ai.storage.AiJson;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Immutable one-operation approval: concrete sources and role quantities, native context and outcome bound together. */
public record AdvancedProcessingPlan(int schema, UUID id, UUID serverInstanceId, UUID conversationId,
        String owner, UUID taskId, UUID executionEpoch, long privacyEpoch, String dimension, long recipeGeneration,
        String recipeId, String mechanismId, int choice, int initializationSeed, String adapter,
        List<MaterialStack> inputs, List<Integer> consumed, List<MaterialStack> products,
        Map<String, String> context, List<VerifiedTransformation.RecipeReference> backingRecipes,
        List<String> callbacks, boolean freshSampling, List<MaterialLedger.Stock> sources,
        List<MaterialLedger.Need> needs, String hash) {
    public AdvancedProcessingPlan {
        Objects.requireNonNull(id); Objects.requireNonNull(serverInstanceId); Objects.requireNonNull(conversationId);
        Objects.requireNonNull(taskId); Objects.requireNonNull(executionEpoch); UUID.fromString(owner);
        inputs = List.copyOf(inputs); consumed = List.copyOf(consumed); products = List.copyOf(products);
        context = Map.copyOf(context); backingRecipes = List.copyOf(backingRecipes); callbacks = List.copyOf(callbacks);
        sources = List.copyOf(sources); needs = List.copyOf(needs);
        if (schema != 1 || privacyEpoch < 0 || recipeGeneration < 0 || choice < 0 || choice > 2
                || recipeId == null || mechanismId == null || recipeId.isBlank() == mechanismId.isBlank()
                || dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || adapter == null || adapter.isBlank()
                || inputs.isEmpty() || inputs.size() > 9 || inputs.size() != consumed.size() || products.size() > 128
                || sources.isEmpty() || sources.size() > 81 || needs.isEmpty() || needs.size() > 81 || backingRecipes.size() > 64
                || context.size() > 128 || callbacks.size() > 16 || hash == null || !hash.isEmpty() && !hash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("ADVANCED_PLAN_INVALID");
        Map<String, Integer> logical = new TreeMap<>(), physical = new TreeMap<>();
        for (int slot = 0; slot < inputs.size(); slot++) {
            MaterialStack input = inputs.get(slot); int amount = consumed.get(slot);
            if (amount < 0 || amount > 64 || input.count() != amount) throw new IllegalArgumentException("ADVANCED_ROLE_QUANTITY_CHANGED");
            if (amount != 0) logical.merge(input.itemJson(), amount, Math::addExact);
        }
        var unique = new HashMap<String, MaterialLedger.Stock>();
        for (var source : sources) if (unique.putIfAbsent(source.key(), source) != null) throw new IllegalArgumentException("ADVANCED_DUPLICATE_SOURCE");
        if (needs.stream().map(MaterialLedger.Need::stockKey).distinct().count() != needs.size()) throw new IllegalArgumentException("ADVANCED_DUPLICATE_NEED");
        Map<String, Long> usage = new HashMap<>();
        for (var need : needs) {
            var source = unique.get(need.stockKey());
            if (source == null || need.count() > source.extractableCount()) throw new IllegalArgumentException("ADVANCED_SOURCE_CHANGED");
            physical.merge(source.stack().itemJson(), need.count(), Math::addExact);
            long units = usage.merge(source.poolId(), Math.multiplyExact((long) need.count(), source.unitsPerItem()), Math::addExact);
            if (units > source.poolUnits()) throw new IllegalArgumentException("ADVANCED_POOL_OVERALLOCATED");
        }
        if (!logical.equals(physical) || logical.values().stream().mapToInt(Integer::intValue).sum() > 512)
            throw new IllegalArgumentException("ADVANCED_MATERIAL_MISMATCH");
    }
    public AdvancedProcessingPlan signed() { return withHash(AiJson.digest(AiJson.canonical(withHash("")))); }
    public boolean intact() { return hash.equals(AiJson.digest(AiJson.canonical(withHash("")))); }
    private AdvancedProcessingPlan withHash(String value) {
        return new AdvancedProcessingPlan(schema, id, serverInstanceId, conversationId, owner, taskId, executionEpoch, privacyEpoch,
                dimension, recipeGeneration, recipeId, mechanismId, choice, initializationSeed, adapter, inputs, consumed, products,
                context, backingRecipes, callbacks, freshSampling, sources, needs, value);
    }
}
