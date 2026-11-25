package firefly520.fireflymc.ai.processing;

import firefly520.fireflymc.ai.material.MaterialStack;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CraftingPlannerTest {
    private static MaterialStack item(String name, int count) { return new MaterialStack("{\"id\":\"minecraft:" + name + "\"}", count); }
    private static CraftingPlanner.Ingredient ingredient(int slot, String... ids) {
        return new CraftingPlanner.Ingredient(slot, 1, Arrays.stream(ids).map(id -> item(id, 1)).toList());
    }
    private static CraftingPlanner.Route route(String name, CraftingPlanner.Ingredient... ingredients) {
        return new CraftingPlanner.Route(name, ingredients.length, List.of(ingredients));
    }
    private static CraftingPlanner.Stock stock(String key, String item, int count) {
        return new CraftingPlanner.Stock(key, item(item, count), count, key, 1);
    }
    private static class Catalog implements CraftingPlanner.Catalog {
        final Map<String, List<CraftingPlanner.Route>> routes = new HashMap<>();
        final Map<String, List<MaterialStack>> results = new HashMap<>();
        final AtomicInteger checks = new AtomicInteger();
        void add(String target, CraftingPlanner.Route route, MaterialStack... products) {
            routes.computeIfAbsent(item(target, 1).itemJson(), ignored -> new ArrayList<>()).add(route);
            results.put(route.recipeId(), List.of(products));
        }
        @Override public List<CraftingPlanner.Route> producers(MaterialStack wanted) { return routes.getOrDefault(wanted.itemJson(), List.of()); }
        @Override public List<MaterialStack> verify(CraftingPlanner.Route route, List<MaterialStack> inputs) { return results.get(route.recipeId()); }
        @Override public void check() { checks.incrementAndGet(); }
    }
    private static CraftingPlanner.Result solve(List<CraftingPlanner.Stock> stocks, String target, int count, Catalog catalog) throws Exception {
        var pools = new HashMap<String, Long>();
        stocks.forEach(s -> pools.put(s.pool(), s.available() * s.weight()));
        return CraftingPlanner.solve(stocks, pools, List.of(item(target, 1)), count, catalog, CraftingPlanner.Limits.standard());
    }
    @Test void logsBecomePlanksThenTableWithoutDuplicatingIntermediateOutput() throws Exception {
        var catalog = new Catalog();
        catalog.add("oak_planks", route("planks", ingredient(0, "oak_log")), item("oak_planks", 4));
        catalog.add("crafting_table", route("table", ingredient(0, "oak_planks"), ingredient(1, "oak_planks"),
                ingredient(2, "oak_planks"), ingredient(3, "oak_planks")), item("crafting_table", 1));
        var plan = solve(List.of(stock("log", "oak_log", 2)), "crafting_table", 1, catalog);
        assertEquals(Map.of("log", 1), plan.physicalInputs());
        assertEquals(List.of("planks", "table"), plan.steps().stream().map(CraftingPlanner.Step::recipeId).toList());
        assertEquals(List.of(item("crafting_table", 1)), plan.products());
        assertEquals(plan.products(), ProcessingChainMaterials.replay(List.of(item("oak_log", 1)), plan.steps()));
    }
    @Test void existingGoalsAreUsedBeforeRecipesEvenWhenTheyHaveOtherComponents() throws Exception {
        var catalog = new Catalog(); catalog.add("stone", route("smelt", ingredient(0, "cobblestone")), item("stone", 1));
        MaterialStack named = new MaterialStack("{\"id\":\"minecraft:stone\",\"components\":{\"minecraft:custom_name\":\"kept\"}}", 1);
        var real = new CraftingPlanner.Stock("named", named, 1, "named", 1);
        var plan = CraftingPlanner.solve(List.of(real, stock("cobble", "cobblestone", 1)), Map.of("named", 1L, "cobble", 1L),
                List.of(item("stone", 1), named), 1, catalog, CraftingPlanner.Limits.standard());
        assertTrue(plan.steps().isEmpty()); assertEquals(List.of(named), plan.products()); assertEquals(Map.of("named", 1), plan.physicalInputs());
    }
    @Test void backtracksAcrossPreviouslyChosenIngredientAndRecipe() throws Exception {
        var catalog = new Catalog();
        catalog.add("stick", route("stick_oak", ingredient(0, "oak_planks")), item("stick", 1));
        catalog.add("stick", route("stick_birch", ingredient(0, "birch_planks")), item("stick", 1));
        catalog.add("torch", route("torch", ingredient(0, "stick"), ingredient(1, "oak_planks")), item("torch", 4));
        var plan = solve(List.of(stock("oak", "oak_planks", 1), stock("birch", "birch_planks", 1)), "torch", 1, catalog);
        assertEquals(List.of("stick_birch", "torch"), plan.steps().stream().map(CraftingPlanner.Step::recipeId).toList());
        assertEquals(4, plan.products().getFirst().count());
    }
    @Test void weightedSharedPoolAliasesCannotCreateExtraResources() {
        var catalog = new Catalog();
        catalog.add("result", route("combine", ingredient(0, "iron_block"), ingredient(1, "iron_ingot")), item("result", 1));
        var block = new CraftingPlanner.Stock("block", item("iron_block", 1), 1, "drawer", 9);
        var ingot = new CraftingPlanner.Stock("ingot", item("iron_ingot", 9), 9, "drawer", 1);
        var error = assertThrows(IllegalStateException.class, () -> CraftingPlanner.solve(List.of(block, ingot), Map.of("drawer", 9L),
                List.of(item("result", 1)), 1, catalog, CraftingPlanner.Limits.standard()));
        assertEquals("MATERIAL_DEPENDENCY_UNAVAILABLE", error.getMessage());
    }
    @Test void realContainerRemainderCanSupplyTheNextStepWithoutBeingDuplicated() throws Exception {
        var catalog = new Catalog();
        catalog.add("bread", route("wet", ingredient(0, "wheat"), ingredient(1, "water_bucket")), item("bread", 1), item("water_bucket", 1));
        var plan = solve(List.of(stock("grain", "wheat", 2), stock("water", "water_bucket", 1)), "bread", 2, catalog);
        assertEquals(Map.of("grain", 2, "water", 1), plan.physicalInputs());
        assertEquals(ProcessingChainMaterials.totals(List.of(item("bread", 2), item("water_bucket", 1))), ProcessingChainMaterials.totals(plan.products()));
    }
    @Test void emptyCyclesTerminateAndRealInputCanBreakAConversionCycle() throws Exception {
        var catalog = new Catalog();
        catalog.add("a", route("a_from_b", ingredient(0, "b")), item("a", 1));
        catalog.add("b", route("b_from_a", ingredient(0, "a")), item("b", 1));
        assertEquals("MATERIAL_DEPENDENCY_UNAVAILABLE", assertThrows(IllegalStateException.class,
                () -> solve(List.of(), "a", 1, catalog)).getMessage());
        assertEquals(1, solve(List.of(stock("b", "b", 1)), "a", 1, catalog).steps().size());
    }
    @Test void limitsAndCancellationAreObservableAndNeverReturnAPartialSuccess() {
        var catalog = new Catalog(); catalog.add("a", route("make", ingredient(0, "b")), item("a", 1));
        assertEquals("PLANNING_NODE_LIMIT", assertThrows(IllegalStateException.class, () -> CraftingPlanner.solve(
                List.of(stock("b", "b", 1)), Map.of("b", 1L), List.of(item("a", 1)), 1, catalog, new CraftingPlanner.Limits(2, 1, 2, 8))).getMessage());
        Catalog cancelled = new Catalog() { @Override public void check() { throw new IllegalStateException("CANCELLED"); } };
        assertEquals("CANCELLED", assertThrows(IllegalStateException.class, () -> solve(List.of(), "a", 1, cancelled)).getMessage());
    }
    @Test void largestAllowedTargetBatchTerminatesWithCorrectCounts() throws Exception {
        var catalog = new Catalog(); catalog.add("stone", route("smelt", ingredient(0, "cobblestone")), item("stone", 1));
        var plan = solve(List.of(stock("cobble", "cobblestone", 64)), "stone", 64, catalog);
        assertEquals(64, plan.steps().size()); assertEquals(List.of(item("stone", 64)), plan.products());
    }
    @Test void accountingRejectsUsingUnownedIntermediateOrDifferentComponents() {
        var step = new CraftingPlanner.Step("bad", List.of(item("stone", 1)), List.of(item("diamond", 1)));
        assertEquals("CHAIN_INPUT_NOT_OWNED", assertThrows(IllegalStateException.class,
                () -> ProcessingChainMaterials.replay(List.of(item("cobblestone", 1)), List.of(step))).getMessage());
    }
    @Test void maximumInventoryUsesOnlySelectedSourcesWithoutLosingSearchBounds() throws Exception {
        var catalog = new Catalog(); catalog.add("stone", route("smelt", ingredient(0, "cobblestone")), item("stone", 1));
        var stocks = new ArrayList<CraftingPlanner.Stock>();
        for (int i = 0; i < 16384; i++) stocks.add(stock("source-" + i, i == 16383 ? "cobblestone" : "dirt", 64));
        var plan = solve(stocks, "stone", 64, catalog);
        assertEquals(Map.of("source-16383", 64), plan.physicalInputs());
        assertEquals(List.of(item("stone", 64)), plan.products());
        assertTrue(plan.visitedNodes() < CraftingPlanner.Limits.standard().nodes());
    }
}
