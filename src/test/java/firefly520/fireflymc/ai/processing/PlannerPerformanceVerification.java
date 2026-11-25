package firefly520.fireflymc.ai.processing;

import firefly520.fireflymc.ai.material.MaterialStack;
import firefly520.fireflymc.ai.storage.AiJson;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** Opt-in worker-only measurements, explicitly not Minecraft MSPT or client frame timings. */
public final class PlannerPerformanceVerification {
    private static MaterialStack item(String id, int count) { return new MaterialStack("{\"id\":\"minecraft:" + id + "\"}", count); }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("REPORT_PATH_REQUIRED");
        long started = System.nanoTime();
        var reports = new ArrayList<Map<String, Object>>();
        for (int slots : List.of(36, 16384)) {
            var stocks = new ArrayList<CraftingPlanner.Stock>(); var pools = new HashMap<String, Long>();
            for (int i = 0; i < slots; i++) {
                String key = "source-" + i;
                stocks.add(new CraftingPlanner.Stock(key, item(i == slots - 1 ? "cobblestone" : "dirt", 64), 64, key, 1)); pools.put(key, 64L);
            }
            CraftingPlanner.Catalog catalog = new CraftingPlanner.Catalog() {
                @Override public List<CraftingPlanner.Route> producers(MaterialStack wanted) {
                    if (!wanted.itemJson().equals(item("stone", 1).itemJson())) return List.of();
                    return List.of(new CraftingPlanner.Route("minecraft:stone", 1, List.of(new CraftingPlanner.Ingredient(0, 1, List.of(item("cobblestone", 1))))));
                }
                @Override public List<MaterialStack> verify(CraftingPlanner.Route route, List<MaterialStack> inputs) { return List.of(item("stone", 1)); }
                @Override public void check() {}
            };
            for (int i = 0; i < 20; i++) CraftingPlanner.solve(stocks, pools, List.of(item("stone", 1)), 64, catalog, CraftingPlanner.Limits.standard());
            double[] elapsed = new double[100]; int nodes = 0;
            for (int i = 0; i < elapsed.length; i++) {
                long begin = System.nanoTime();
                var plan = CraftingPlanner.solve(stocks, pools, List.of(item("stone", 1)), 64, catalog, CraftingPlanner.Limits.standard());
                elapsed[i] = (System.nanoTime() - begin) / 1_000_000.0; nodes = plan.visitedNodes();
                if (plan.steps().size() != 64 || !plan.products().equals(List.of(item("stone", 64)))) throw new AssertionError("Invalid measured result");
            }
            Arrays.sort(elapsed);
            reports.add(Map.of("sourceSlots", slots, "recipeSteps", 64, "warmupRuns", 20, "measuredRuns", elapsed.length,
                    "visitedNodes", nodes, "p50Millis", elapsed[49], "p95Millis", elapsed[94], "p99Millis", elapsed[98], "maxMillis", elapsed[99]));
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("recordedAt", Instant.now().toString()); report.put("java", System.getProperty("java.version"));
        report.put("vm", System.getProperty("java.vm.name")); report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        report.put("availableProcessors", Runtime.getRuntime().availableProcessors()); report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        report.put("scope", "Pure bounded dependency search with synthetic recipe oracle; no native predicates, HTTP, file IO, Minecraft MSPT or client rendering inside measurements");
        report.put("durationSeconds", (System.nanoTime() - started) / 1_000_000_000.0); report.put("cases", reports);
        AiJson.write(Path.of(args[0]), report);
        System.out.println(AiJson.GSON.toJson(report));
    }
}
