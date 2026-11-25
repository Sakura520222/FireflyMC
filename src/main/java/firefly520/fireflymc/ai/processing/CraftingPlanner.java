package firefly520.fireflymc.ai.processing;

import firefly520.fireflymc.ai.material.MaterialStack;
import java.util.*;

/** Bounded component-exact search. Native recipe queries are supplied by the server dispatcher. */
public final class CraftingPlanner {
    public record Stock(String key, MaterialStack stack, int available, String pool, long weight) {
        public Stock {
            if (key == null || stack == null || stack.empty() || available < 0 || pool == null || weight < 1)
                throw new IllegalArgumentException("PLANNER_STOCK_INVALID");
        }
    }
    public record Ingredient(int slot, int count, List<MaterialStack> alternatives) {
        public Ingredient {
            alternatives = List.copyOf(alternatives);
            if (slot < 0 || slot >= 81 || count < 1 || count > 64 || alternatives.isEmpty() || alternatives.size() > 256
                    || alternatives.stream().anyMatch(s -> s.empty() || s.count() != 1))
                throw new IllegalArgumentException("PLANNER_INGREDIENT_INVALID");
        }
    }
    public record Route(String recipeId, int inputSize, List<Ingredient> ingredients) {
        public Route {
            ingredients = List.copyOf(ingredients);
            if (recipeId == null || recipeId.isBlank() || inputSize < 1 || inputSize > 81 || ingredients.isEmpty()
                    || ingredients.size() > inputSize || ingredients.stream().anyMatch(i -> i.slot() >= inputSize)
                    || ingredients.stream().map(Ingredient::slot).distinct().count() != ingredients.size())
                throw new IllegalArgumentException("PLANNER_ROUTE_INVALID");
        }
    }
    public record Step(String recipeId, List<MaterialStack> inputs, List<MaterialStack> products) {
        public Step { inputs = List.copyOf(inputs); products = List.copyOf(products); }
    }
    public record Result(Map<String, Integer> physicalInputs, List<Step> steps, List<MaterialStack> products, int visitedNodes) {
        public Result { physicalInputs = Map.copyOf(physicalInputs); steps = List.copyOf(steps); products = List.copyOf(products); }
    }
    public record Limits(int depth, int nodes, int steps, int units) {
        public Limits {
            if (depth < 1 || depth > 32 || nodes < 1 || nodes > 100000 || steps < 1 || steps > 64 || units < 1 || units > 512)
                throw new IllegalArgumentException("PLANNER_LIMIT_INVALID");
        }
        public static Limits standard() { return new Limits(16, 12000, 64, 512); }
    }
    public interface Catalog {
        List<Route> producers(MaterialStack wanted) throws Exception;
        /** Must verify the exact recipe/inputs and return deterministic, native products plus remainders. */
        List<MaterialStack> verify(Route route, List<MaterialStack> inputs) throws Exception;
        void check() throws Exception;
    }
    private interface Next { boolean accept(State state, MaterialStack taken) throws Exception; }
    private static final class State {
        final Map<String, Integer> used, virtual;
        final Map<String, Long> spentPools;
        final List<Step> steps;
        int units;
        State() {
            used = new LinkedHashMap<>(); virtual = new LinkedHashMap<>();
            spentPools = new HashMap<>(); steps = new ArrayList<>();
        }
        State(State other) {
            used = new LinkedHashMap<>(other.used);
            virtual = new LinkedHashMap<>(other.virtual); spentPools = new HashMap<>(other.spentPools);
            steps = new ArrayList<>(other.steps); units = other.units;
        }
    }
    private final Map<String, List<Stock>> stocksByItem = new HashMap<>();
    private final Map<String, Long> availablePools;
    private final Catalog catalog;
    private final Limits limits;
    private final Map<String, List<Route>> routes = new HashMap<>();
    private int nodes;
    private boolean depthLimited, stepLimited;
    private Result result;
    private CraftingPlanner(List<Stock> stocks, Map<String, Long> pools, Catalog catalog, Limits limits) {
        this.availablePools = Map.copyOf(pools); this.catalog = catalog; this.limits = limits;
        var keys = new HashSet<String>();
        for (var stock : stocks) {
            if (!keys.add(stock.key())) throw new IllegalArgumentException("PLANNER_DUPLICATE_SOURCE");
            stocksByItem.computeIfAbsent(stock.stack().itemJson(), ignored -> new ArrayList<>()).add(stock);
        }
    }
    public static Result solve(List<Stock> stocks, Map<String, Long> pools, List<MaterialStack> targets, int count,
                               Catalog catalog, Limits limits) throws Exception {
        if (stocks.size() > 16384 || targets.isEmpty() || targets.size() > 256 || count < 1 || count > 64)
            throw new IllegalArgumentException("PLANNER_INPUT_LIMIT");
        var planner = new CraftingPlanner(stocks, pools, catalog, limits);
        var target = new Ingredient(0, 1, targets.stream().map(s -> s.withCount(1)).distinct().toList());
        boolean found = planner.goals(target, count, new State(), new ArrayList<>());
        if (!found) throw new IllegalStateException(planner.depthLimited ? "PLANNING_DEPTH_LIMIT"
                : planner.stepLimited ? "PLANNING_STEP_LIMIT" : "MATERIAL_DEPENDENCY_UNAVAILABLE");
        return planner.result;
    }
    private void check() throws Exception {
        catalog.check();
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("PLANNING_CANCELLED");
        if (++nodes > limits.nodes()) throw new IllegalStateException("PLANNING_NODE_LIMIT");
    }
    private boolean goals(Ingredient goal, int left, State state, List<MaterialStack> held) throws Exception {
        check();
        if (left == 0) {
            var products = new LinkedHashMap<>(state.virtual);
            for (var stack : held) products.merge(stack.itemJson(), stack.count(), Math::addExact);
            result = new Result(state.used, state.steps, stacks(products), nodes); return true;
        }
        return take(goal, state, new HashSet<>(), 0, (next, taken) -> {
            var targets = new ArrayList<>(held); targets.add(taken);
            return goals(goal, left - 1, next, targets);
        });
    }
    private boolean take(Ingredient ingredient, State state, Set<String> ancestry, int depth, Next next) throws Exception {
        check();
        // Existing inventory precedes every production route, including alternative component variants.
        for (MaterialStack item : ingredient.alternatives())
            if (takeUnits(item, ingredient.count(), state, (value, ignored) -> next.accept(value, item.withCount(ingredient.count())))) return true;
        if (depth >= limits.depth()) { depthLimited = true; return false; }
        if (state.steps.size() >= limits.steps()) { stepLimited = true; return false; }
        for (MaterialStack item : ingredient.alternatives()) {
            if (ancestry.contains(item.itemJson())) continue;
            List<Route> producers = routes.get(item.itemJson());
            if (producers == null) {
                producers = List.copyOf(catalog.producers(item));
                if (producers.size() > 128) throw new IllegalStateException("PLANNING_ROUTE_LIMIT");
                routes.put(item.itemJson(), producers);
            }
            for (Route route : producers) {
                var path = new HashSet<>(ancestry); path.add(item.itemJson());
                var ordered = route.ingredients().stream().sorted(Comparator.comparingInt(i -> i.alternatives().size())).toList();
                var inputs = new ArrayList<>(Collections.nCopies(route.inputSize(), MaterialStack.EMPTY));
                if (assign(route, ordered, 0, state, inputs, path, depth + 1, (prepared, ignored) -> {
                    check();
                    List<MaterialStack> outputs = List.copyOf(catalog.verify(route, inputs));
                    if (outputs.size() > 128 || outputs.stream().anyMatch(MaterialStack::empty))
                        throw new IllegalStateException("PLANNING_OUTPUT_INVALID");
                    if (outputs.stream().noneMatch(o -> o.itemJson().equals(item.itemJson()))) return false;
                    var produced = new State(prepared);
                    if (produced.steps.size() >= limits.steps()) { stepLimited = true; return false; }
                    produced.steps.add(new Step(route.recipeId(), inputs, outputs));
                    for (var output : outputs) produced.virtual.merge(output.itemJson(), output.count(), Math::addExact);
                    if (produced.virtual.values().stream().mapToLong(Integer::longValue).sum() > limits.units())
                        throw new IllegalStateException("PLANNING_MATERIAL_LIMIT");
                    // Removing this route's ancestry allows repeated batches, but their real inputs stay spent.
                    return take(new Ingredient(ingredient.slot(), ingredient.count(), List.of(item)), produced, ancestry, depth, next);
                })) return true;
            }
        }
        return false;
    }
    private boolean assign(Route route, List<Ingredient> ordered, int index, State state, List<MaterialStack> inputs,
                           Set<String> ancestry, int depth, Next next) throws Exception {
        check();
        if (index == ordered.size()) return next.accept(state, MaterialStack.EMPTY);
        Ingredient ingredient = ordered.get(index);
        return take(ingredient, state, ancestry, depth, (prepared, taken) -> {
            inputs.set(ingredient.slot(), taken);
            return assign(route, ordered, index + 1, prepared, inputs, ancestry, depth, next);
        });
    }
    private boolean takeUnits(MaterialStack item, int count, State state, Next next) throws Exception {
        check();
        if (count == 0) return next.accept(state, MaterialStack.EMPTY);
        if (state.units >= limits.units()) throw new IllegalStateException("PLANNING_MATERIAL_LIMIT");
        int virtual = state.virtual.getOrDefault(item.itemJson(), 0);
        if (virtual > 0) {
            var taken = new State(state); taken.units++;
            if (virtual == 1) taken.virtual.remove(item.itemJson()); else taken.virtual.put(item.itemJson(), virtual - 1);
            if (takeUnits(item, count - 1, taken, next)) return true;
        }
        for (Stock stock : stocksByItem.getOrDefault(item.itemJson(), List.of())) {
            if (stock.available() - state.used.getOrDefault(stock.key(), 0) < 1
                    || availablePools.getOrDefault(stock.pool(), 0L) - state.spentPools.getOrDefault(stock.pool(), 0L) < stock.weight()) continue;
            var taken = new State(state); taken.units++;
            taken.used.merge(stock.key(), 1, Integer::sum);
            taken.spentPools.merge(stock.pool(), stock.weight(), Long::sum);
            if (takeUnits(item, count - 1, taken, next)) return true;
        }
        return false;
    }
    private static List<MaterialStack> stacks(Map<String, Integer> values) {
        return values.entrySet().stream().filter(e -> e.getValue() > 0).map(e -> new MaterialStack(e.getKey(), e.getValue())).toList();
    }
}
