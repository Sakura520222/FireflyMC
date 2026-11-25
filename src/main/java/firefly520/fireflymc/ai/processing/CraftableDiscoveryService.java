package firefly520.fireflymc.ai.processing;

import com.google.gson.*;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.TaskControl;
import firefly520.fireflymc.ai.tools.AgentTool;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.GameRules;
import java.util.*;
import java.util.function.LongSupplier;

/** One query captures inventory once and finishes the catalog internally, without model-facing scan cursors. */
public final class CraftableDiscoveryService {
    private static final int RECIPES_PER_SLICE = 128;
    private static final long SLICE_NANOS = 2_000_000;
    private record Catalog(long generation, String dimension, Object connection, long privacyEpoch, List<RecipeHolder<?>> recipes) {}
    private record Candidate(String recipeId, String adapter, String name, String outputId, String reason, JsonObject nativeResult) {}
    private record Batch(int next, List<Candidate> candidates) {}
    private final MinecraftServer server;
    private final MaterialInventoryService inventory;
    private final MaterialLedger ledger;
    private final VirtualProcessingService processing;
    private final LongSupplier generation;

    public CraftableDiscoveryService(MinecraftServer server, MaterialInventoryService inventory, MaterialLedger ledger,
                                     VirtualProcessingService processing, LongSupplier generation) {
        this.server = server; this.inventory = inventory; this.ledger = ledger; this.processing = processing; this.generation = generation;
    }

    static String request(JsonObject args) {
        if (!Set.of("query").containsAll(args.keySet())) throw new IllegalArgumentException("CRAFTABLE_QUERY_FIELDS");
        String query = args.has("query") ? args.get("query").getAsString().strip().toLowerCase(Locale.ROOT) : "";
        if (query.length() > 120) throw new IllegalArgumentException("CRAFTABLE_QUERY_LIMIT");
        return query;
    }

    public AgentTool.Result discover(AgentTool.Context ctx, JsonObject args) throws Exception {
        String query = request(args);
        try { return discover(ctx, query); }
        catch (IllegalStateException reload) {
            if (!"CRAFTABLE_RECIPE_RELOADED".equals(reload.getMessage())) throw reload;
            return discover(ctx, query);
        }
    }

    private AgentTool.Result discover(AgentTool.Context ctx, String query) throws Exception {
        long privacyEpoch = ctx.workspace().conversation().privacyEpoch();
        var snapshot = ctx.control().await(inventory.scan(ctx.actor()), 30_000);
        Catalog catalog = processing.inspectOwned(ctx, () -> {
            var player = inventory.requirePlayer(ctx.actor());
            if (ctx.workspace().conversation().privacyEpoch() != privacyEpoch) throw new IllegalStateException("WORKSPACE_PRIVACY_EPOCH_CHANGED");
            return new Catalog(generation.getAsLong(), player.serverLevel().dimension().location().toString(), player.connection,
                    privacyEpoch, List.copyOf(server.getRecipeManager().getRecipes()));
        });
        var recipes = catalog.recipes().stream().sorted(Comparator.comparing((RecipeHolder<?> h) ->
                !h.id().getNamespace().equals("minecraft")).thenComparing(h -> h.id().toString())).toList();
        var unique = new LinkedHashMap<String, MaterialLedger.Stock>();
        for (var row : snapshot.rows()) unique.merge(row.key(), row, (a, b) -> a.extractableCount() >= b.extractableCount() ? a : b);
        var availability = ledger.available(List.copyOf(unique.values()));
        var stocks = unique.values().stream().filter(row -> availability.getOrDefault(row.key(), 0L) > 0
                && !inventory.protectedByOwner(ctx.actor(), row.stack())).toList();
        boolean independentPools = stocks.stream().map(MaterialLedger.Stock::poolId).distinct().count() == stocks.size()
                && stocks.stream().allMatch(stock -> stock.unitsPerItem() == 1);
        var nativeStocks = new ArrayList<ItemStack>();
        for (int first = 0; first < stocks.size(); first += 64) {
            int begin = first, end = Math.min(first + 64, stocks.size());
            nativeStocks.addAll(processing.inspectOwned(ctx, () -> {
                guard(ctx, catalog); var player = inventory.requirePlayer(ctx.actor());
                return stocks.subList(begin, end).stream().map(stock -> ItemSnapshotCodec.restore(stock.stack().withCount(
                        (int) Math.min(Integer.MAX_VALUE, Math.min(stock.extractableCount(), availability.getOrDefault(stock.key(), 0L)))), player.registryAccess())).toList();
            }));
        }
        var nativeQuery = processing.inspectOwned(ctx, () -> new NativeRecipeBookQuery(nativeStocks));
        var entries = new JsonArray(); var exclusions = new LinkedHashMap<String, Integer>();
        int offset = 0, nativeMatches = 0, adapterPreviews = 0;
        while (offset < recipes.size()) {
            ctx.control().check(); int begin = offset;
            Batch batch = processing.inspectOwned(ctx, () -> {
                guard(ctx, catalog); int next = begin; long until = System.nanoTime() + SLICE_NANOS;
                var candidates = new ArrayList<Candidate>();
                do { candidates.add(candidate(ctx, recipes.get(next++), nativeStocks, nativeQuery, independentPools, query)); }
                while (next < recipes.size() && next - begin < RECIPES_PER_SLICE && System.nanoTime() < until);
                return new Batch(next, candidates);
            });
            offset = batch.next();
            for (var candidate : batch.candidates()) {
                if (!candidate.reason().isEmpty()) { exclusions.merge(candidate.reason(), 1, Integer::sum); continue; }
                if (candidate.nativeResult() != null) { entries.add(candidate.nativeResult()); nativeMatches++; continue; }
                adapterPreviews++;
                try {
                    var entry = processing.discoveryPreview(ctx, candidate.recipeId(), stocks);
                    entry.addProperty("displayName", candidate.name()); entry.addProperty("outputId", candidate.outputId());
                    entry.addProperty("adapter", candidate.adapter()); entries.add(entry);
                } catch (TaskControl.Stopped stopped) { throw stopped; }
                catch (Exception failure) {
                    String code = failureCode(failure);
                    if ("PROCESSING_RECIPE_STALE".equals(code)) throw new IllegalStateException("CRAFTABLE_RECIPE_RELOADED");
                    exclusions.merge(code, 1, Integer::sum);
                }
            }
        }
        processing.inspectOwned(ctx, () -> { guard(ctx, catalog); return true; });
        var data = new JsonObject(); data.addProperty("complete", true); data.addProperty("readOnly", true);
        data.addProperty("capturedAt", snapshot.createdAt()); data.addProperty("capturedAtIso", java.time.Instant.ofEpochMilli(snapshot.createdAt()).toString());
        data.addProperty("recipeGeneration", catalog.generation()); data.addProperty("query", query);
        data.addProperty("catalogRecipes", recipes.size()); data.addProperty("scannedRecipes", offset);
        data.addProperty("nativeRecipeBookMatches", nativeMatches); data.addProperty("adapterPreviews", adapterPreviews);
        data.addProperty("stockRows", unique.size()); data.addProperty("craftableCount", entries.size());
        data.addProperty("independentAlternatives", true); data.addProperty("policy", "MATERIALS_ONLY@1");
        data.add("craftable", entries); data.add("excluded", AiJson.GSON.toJsonTree(exclusions));
        data.add("sourceRefusals", AiJson.GSON.toJsonTree(snapshot.refusals()));
        data.addProperty("coverage", "当前库存直接满足的合成、烧炼、切石及已接入加工配方；使用原版配方书材料判定，特殊配料交由对应适配器。没有执行制作；实际制作时读取实时材料。");
        data.addProperty("displayReport", report(entries));
        return new AgentTool.Result(true, "", entries.isEmpty() ? "当前材料未匹配到可直接制作的配方。"
                : "已取得完整可制作清单，共 " + entries.size() + " 项；清单直接展示在工作台。", data, "native-recipe-book-and-server-inventory", "", false);
    }

    private void guard(AgentTool.Context ctx, Catalog catalog) {
        var player = inventory.requirePlayer(ctx.actor());
        if (generation.getAsLong() != catalog.generation()) throw new IllegalStateException("CRAFTABLE_RECIPE_RELOADED");
        if (ctx.workspace().conversation().privacyEpoch() != catalog.privacyEpoch()) throw new IllegalStateException("WORKSPACE_PRIVACY_EPOCH_CHANGED");
        if (player.connection != catalog.connection() || !player.serverLevel().dimension().location().toString().equals(catalog.dimension()))
            throw new IllegalStateException("CRAFTABLE_PLAYER_CONTEXT_CHANGED");
        // The pinned read-only observation is independent of the 60-second interactive UI cursor expiry.
    }

    private Candidate candidate(AgentTool.Context ctx, RecipeHolder<?> holder, List<ItemStack> stocks,
                                NativeRecipeBookQuery nativeQuery, boolean independentPools, String query) {
        String id = holder.id().toString();
        try {
            String adapter = processing.support(holder);
            if (!adapter.endsWith("MATERIALS_ONLY_V1")) return rejected(id, "OTHER_EXECUTION_ADAPTER_OR_UNSUPPORTED");
            var recipe = holder.value();
            if (ChippedProcessingAdapter.supports(recipe)) return rejected(id, "CHIPPED_REQUIRES_EXPLICIT_TARGET_ITEM");
            var player = inventory.requirePlayer(ctx.actor());
            if (recipe instanceof CraftingRecipe && !recipe.isSpecial() && player.serverLevel().getGameRules().getBoolean(GameRules.RULE_LIMITED_CRAFTING)
                    && !player.getRecipeBook().contains(holder)) return rejected(id, "RECIPE_NOT_UNLOCKED");
            ItemStack output = recipe.getResultItem(server.registryAccess());
            String outputId = output.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(output.getItem()).toString();
            String name = output.isEmpty() ? id : NativeRecipeBookQuery.displayName(output);
            if (!query.isEmpty() && !(id + " " + outputId + " " + name).toLowerCase(Locale.ROOT).contains(query)) return rejected(id, "QUERY_FILTERED");
            if (NativeRecipeBookQuery.supports(recipe)) {
                var match = nativeQuery.craftable(holder, server.registryAccess());
                if (match.isEmpty()) return rejected(id, "MATERIAL_MISSING");
                if (independentPools) return new Candidate(id, adapter, name, outputId, "", match.get());
            } else if (recipe instanceof SmithingRecipe smithing) {
                if (stocks.stream().noneMatch(smithing::isTemplateIngredient) || stocks.stream().noneMatch(smithing::isBaseIngredient)
                        || stocks.stream().noneMatch(smithing::isAdditionIngredient)) return rejected(id, "MATERIAL_MISSING");
            } else if (recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe
                    || recipe instanceof AbstractCookingRecipe || recipe instanceof StonecutterRecipe) {
                for (var ingredient : recipe.getIngredients()) if (!ingredient.isEmpty() && stocks.stream().noneMatch(ingredient::test))
                    return rejected(id, "MATERIAL_MISSING");
            }
            return new Candidate(id, adapter, name, outputId, "", null);
        } catch (RuntimeException | LinkageError failure) { return rejected(id, failureCode(failure)); }
    }
    private static Candidate rejected(String id, String reason) { return new Candidate(id, "", "", "", reason, null); }

    static String report(JsonArray entries) {
        if (entries.isEmpty()) return "当前材料没有匹配到可直接制作的物品。";
        var text = new StringBuilder("当前材料可直接制作：\n\n| 物品 | 每次产量 | 所需材料 |\n| --- | --- | --- |\n");
        for (var value : entries) {
            var entry = value.getAsJsonObject(); String name = entry.get("displayName").getAsString();
            int count = entry.has("outputCount") ? entry.get("outputCount").getAsInt() : 0;
            if (count == 0 && entry.has("productsIncludingRemainders")) for (var item : entry.getAsJsonArray("productsIncludingRemainders")) {
                var product = item.getAsJsonObject();
                if (product.has("id") && product.get("id").getAsString().equals(entry.get("outputId").getAsString())) count += product.get("count").getAsInt();
            }
            var inputs = new ArrayList<String>();
            for (var input : entry.getAsJsonArray("inputs")) {
                var item = input.getAsJsonObject().getAsJsonObject("item");
                inputs.add(cell(item.has("name") ? item.get("name").getAsString() : item.get("id").getAsString()) + " ×" + item.get("count").getAsInt());
            }
            boolean probabilistic = entry.has("probabilityOutputs") && !entry.getAsJsonArray("probabilityOutputs").isEmpty();
            text.append("| ").append(cell(name)).append(" | ").append(probabilistic ? "概率产出" : count > 0 ? Integer.toString(count) : "按配方")
                    .append(" | ").append(String.join("、", inputs)).append(" |\n");
        }
        return text.toString();
    }
    private static String cell(String value) { return value.replace('|', '｜').replace('\n', ' ').replace('\r', ' '); }
    static String failureCode(Throwable failure) {
        String message = failure.getMessage();
        return message != null && message.matches("[A-Z_]{1,100}") ? message : "ADAPTER_PREVIEW_REJECTED_OR_DYNAMIC_CONTEXT_UNSUPPORTED";
    }
}
