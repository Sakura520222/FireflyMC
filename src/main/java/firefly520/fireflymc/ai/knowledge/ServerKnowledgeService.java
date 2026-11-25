package firefly520.fireflymc.ai.knowledge;

import com.google.gson.*;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.util.ServerLanguageLoader;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;
import java.util.*;
import java.util.function.Function;

/** Public immutable facts are indexed in bounded main-thread slices, with an explicit generation. */
public final class ServerKnowledgeService {
    private final MinecraftServer server;
    private final long started = System.nanoTime();
    private final List<JsonObject> mods;
    private final String modFingerprint;
    private final List<JsonObject> registryRows = new ArrayList<>();
    private final List<JsonObject> recipeRows = new ArrayList<>();
    private final IntegrationEvidenceIndex integrationRecipes = new IntegrationEvidenceIndex();
    private java.util.function.Supplier<List<JsonObject>> integrationEvidence = List::of;
    private final List<Iterator<JsonObject>> registrySources = new ArrayList<>();
    private Iterator<RecipeHolder<?>> recipes = Collections.emptyIterator();
    private volatile long generation;
    private boolean ready;
    private long worstSliceNanos;
    private Function<RecipeHolder<?>, String> processingSupport = ignored -> "NO_EXECUTION_ADAPTER";

    public ServerKnowledgeService(MinecraftServer server) {
        if (!server.isDedicatedServer()) throw new IllegalArgumentException("DEDICATED_SERVER_REQUIRED");
        this.server = server;
        mods = ModList.get().getMods().stream().map(mod -> {
            JsonObject row = new JsonObject();
            row.addProperty("id", mod.getModId());
            row.addProperty("name", mod.getDisplayName());
            row.addProperty("version", mod.getVersion().toString());
            String description = mod.getDescription() == null ? "" : mod.getDescription();
            row.addProperty("description", description.substring(0, Math.min(1600, description.length())));
            row.add("dependencies", AiJson.GSON.toJsonTree(mod.getDependencies().stream().map(dependency ->
                    Map.of("id", dependency.getModId(), "range", dependency.getVersionRange().toString(),
                            "type", dependency.getType().name(), "side", dependency.getSide().name())).toList()));
            row.addProperty("evidence", "SERVER_LOADED");
            return row;
        }).sorted(Comparator.comparing(row -> row.get("id").getAsString())).toList();
        modFingerprint = AiJson.digest(AiJson.GSON.toJson(mods));
        invalidate();
    }
    public long generation() { return generation; }
    public String fingerprint() { return modFingerprint; }
    public void integrationEvidence(java.util.function.Supplier<List<JsonObject>> evidence) { requireMain(); integrationEvidence = evidence; }
    public Optional<String> modVersion(String id) {
        return mods.stream().filter(m -> m.get("id").getAsString().equals(id)).findFirst()
                .map(m -> m.get("version").getAsString());
    }
    public void processingSupport(Function<RecipeHolder<?>, String> resolver) {
        requireMain(); processingSupport = resolver;
        recipeRows.clear(); integrationRecipes.clear(); recipes = server.getRecipeManager().getRecipes().iterator();
        generation++; ready = false;
    }
    public void invalidate() {
        requireMain();
        generation++;
        ready = false;
        registryRows.clear();
        recipeRows.clear();
        integrationRecipes.clear();
        registrySources.clear();
        registrySources.add(registry(BuiltInRegistries.ITEM, "item"));
        registrySources.add(registry(BuiltInRegistries.BLOCK, "block"));
        registrySources.add(registry(BuiltInRegistries.ENTITY_TYPE, "entity"));
        registrySources.add(registry(BuiltInRegistries.MOB_EFFECT, "effect"));
        registrySources.add(registry(BuiltInRegistries.FLUID, "fluid"));
        registrySources.add(registry(server.registryAccess().registryOrThrow(Registries.ENCHANTMENT), "enchantment"));
        registrySources.add(registry(server.registryAccess().registryOrThrow(Registries.BIOME), "biome"));
        registrySources.add(registry(server.registryAccess().registryOrThrow(Registries.STRUCTURE), "structure"));
        registrySources.add(server.getAdvancements().getAllAdvancements().stream().map(holder -> {
            JsonObject row = new JsonObject();
            row.addProperty("kind", "advancement"); row.addProperty("id", holder.id().toString());
            holder.value().display().ifPresent(display -> {
                row.addProperty("name", display.getTitle().getString());
                row.addProperty("description", display.getDescription().getString());
            });
            return row;
        }).iterator());
        recipes = server.getRecipeManager().getRecipes().iterator();
    }
    private <T> Iterator<JsonObject> registry(Registry<T> registry, String kind) {
        return registry.keySet().stream().map(id -> {
            JsonObject row = new JsonObject();
            row.addProperty("kind", kind);
            row.addProperty("id", id.toString());
            Object value = registry.get(id);
            registry.getHolder(id).ifPresent(holder -> row.add("tags", AiJson.GSON.toJsonTree(
                    holder.tags().limit(32).map(tag -> tag.location().toString()).toList())));
            if (value instanceof Item item) row.addProperty("name", ServerLanguageLoader.getTranslation(item.getDescriptionId()));
            if (value instanceof Block block) {
                row.addProperty("name", ServerLanguageLoader.getTranslation(block.getDescriptionId()));
                row.addProperty("defaultState", block.defaultBlockState().toString());
                JsonObject properties = new JsonObject();
                block.getStateDefinition().getProperties().forEach(p ->
                        properties.add(p.getName(), AiJson.GSON.toJsonTree(p.getPossibleValues().stream().map(Object::toString).toList())));
                row.add("properties", properties);
            }
            return row;
        }).iterator();
    }
    public void tick() {
        requireMain();
        if (ready) return;
        long start = System.nanoTime();
        int steps = 0;
        while (steps++ < 64 && System.nanoTime() - start < 1_500_000L) {
            while (!registrySources.isEmpty() && !registrySources.getFirst().hasNext()) registrySources.removeFirst();
            if (!registrySources.isEmpty()) registryRows.add(registrySources.getFirst().next());
            else if (recipes.hasNext()) {
                var row = describeRecipe(recipes.next()); recipeRows.add(row); integrationRecipes.accept(row);
            }
            else { ready = true; break; }
        }
        worstSliceNanos = Math.max(worstSliceNanos, System.nanoTime() - start);
    }
    public void register(ToolRegistry registry) {
        register(registry, "integration_status", "查询当前专服实际加载版本、已开放配方与接口状态；按机制区分发现/运行就绪/版本不支持，不把加载 JAR 或通用配方目录当成玩法验收。",
                "{\"query\":{\"type\":\"string\",\"maxLength\":160},\"offset\":{\"type\":\"integer\",\"minimum\":0}}", List.of(),
                (context, args) -> result(integrationView(args)));
        register(registry, "mod_list", "分页列出专服实际加载的 Mod ID、名称和精确版本。磁盘或客户端自报不在此列表。",
                "{\"query\":{\"type\":\"string\"},\"offset\":{\"type\":\"integer\",\"minimum\":0}}", List.of(),
                (context, args) -> result(page(mods, args)));
        register(registry, "mod_describe", "读取专服某个实际 Mod 的版本证据，不从注册 ID 推断完整机制。",
                "{\"modId\":{\"type\":\"string\"}}", List.of("modId"), (context, args) -> {
                    var found = mods.stream().filter(m -> m.get("id").getAsString().equals(args.get("modId").getAsString())).findFirst();
                    return found.isPresent() ? result(found.get()) : AgentTool.Result.error("MOD_NOT_LOADED", "专服未加载此 Mod。");
                });
        register(registry, "registry_search", "按 ID、命名空间或中文名称检索服务端物品/方块/实体/效果/流体/附魔。可用 kind 筛选。",
                "{\"query\":{\"type\":\"string\"},\"kind\":{\"type\":\"string\",\"enum\":[\"item\",\"block\",\"entity\",\"effect\",\"fluid\",\"enchantment\",\"biome\",\"structure\",\"advancement\"]},\"offset\":{\"type\":\"integer\",\"minimum\":0}}",
                List.of(), (context, args) -> result(page(registryRows.stream().filter(row -> !args.has("kind")
                        || args.get("kind").getAsString().equals(row.get("kind").getAsString())).toList(), args)));
        register(registry, "recipe_lookup", "查询服务端当前生效的配方与代次。样例输出不代表动态组件或专用配方已可执行。",
                "{\"query\":{\"type\":\"string\"},\"recipeId\":{\"type\":\"string\"},\"offset\":{\"type\":\"integer\",\"minimum\":0}}",
                List.of(), (context, args) -> {
                    if (args.has("recipeId")) {
                        var found = server.getRecipeManager().byKey(ResourceLocation.parse(args.get("recipeId").getAsString()));
                        return found.isPresent() ? result(describeRecipe(found.get()))
                                : AgentTool.Result.error("RECIPE_NOT_FOUND", "当前服务端没有此有效配方。");
                    }
                    return result(page(recipeRows, args));
                });
        register(registry, "server_public_status", "读取公开版本、人数、MSPT/TPS、维度和数据索引状态，不读取管理凭据或他人状态。",
                "{}", List.of(), (context, args) -> {
                    JsonObject status = envelope();
                    double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
                    status.addProperty("minecraft", server.getServerVersion());
                    status.addProperty("onlinePlayers", server.getPlayerList().getPlayerCount());
                    status.addProperty("mspt", mspt);
                    status.addProperty("tpsEstimate", mspt > 50 ? 1000 / mspt : 20);
                    status.addProperty("uptimeSeconds", (System.nanoTime() - started) / 1_000_000_000);
                    status.addProperty("worstIndexSliceMillis", worstSliceNanos / 1_000_000.0);
                    status.addProperty("difficulty", server.overworld().getDifficulty().name().toLowerCase(Locale.ROOT));
                    status.addProperty("dayTime", server.overworld().getDayTime());
                    status.add("enabledDataPacks", AiJson.GSON.toJsonTree(server.getPackRepository().getSelectedIds()));
                    JsonObject rules = new JsonObject();
                    var worldRules = server.overworld().getGameRules();
                    rules.addProperty("keepInventory", worldRules.getBoolean(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY));
                    rules.addProperty("doDaylightCycle", worldRules.getBoolean(net.minecraft.world.level.GameRules.RULE_DAYLIGHT));
                    rules.addProperty("doWeatherCycle", worldRules.getBoolean(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE));
                    rules.addProperty("mobGriefing", worldRules.getBoolean(net.minecraft.world.level.GameRules.RULE_MOBGRIEFING));
                    status.add("publicGameRules", rules);
                    status.add("dimensions", AiJson.GSON.toJsonTree(server.levelKeys().stream().map(k -> k.location().toString()).sorted().toList()));
                    return result(status);
                });
        register(registry, "player_inspect_self", "读取本人位置、健康和实际物品栏；查看装备不授予消耗许可。",
                "{}", List.of(), (context, args) -> {
                    ServerPlayer player = player(context);
                    JsonObject data = envelope();
                    data.addProperty("dimension", player.level().dimension().location().toString());
                    data.addProperty("x", player.getX()); data.addProperty("y", player.getY()); data.addProperty("z", player.getZ());
                    data.addProperty("health", player.getHealth()); data.addProperty("food", player.getFoodData().getFoodLevel());
                    JsonArray inventory = new JsonArray();
                    for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                        ItemStack stack = player.getInventory().getItem(i);
                        if (stack.isEmpty()) continue;
                        JsonObject item = item(stack); item.addProperty("slot", i); inventory.add(item);
                    }
                    data.add("inventory", inventory);
                    return result(data);
                }, true);
        register(registry, "world_inspect_block", "读取本人附近一个已加载方块的精确状态与方块实体类型，不读取私有内容或加载区块。",
                "{\"dx\":{\"type\":\"integer\",\"minimum\":-8,\"maximum\":8},\"dy\":{\"type\":\"integer\",\"minimum\":-8,\"maximum\":8},"
                        + "\"dz\":{\"type\":\"integer\",\"minimum\":-8,\"maximum\":8}}", List.of("dx", "dy", "dz"), (context, args) -> {
                    var player = player(context);
                    BlockPos pos = player.blockPosition().offset(args.get("dx").getAsInt(), args.get("dy").getAsInt(), args.get("dz").getAsInt());
                    if (!player.serverLevel().hasChunkAt(pos) || player.serverLevel().isOutsideBuildHeight(pos))
                        return AgentTool.Result.error("POSITION_UNAVAILABLE", "该位置当前未加载或超出建造高度。");
                    var state = player.serverLevel().getBlockState(pos);
                    JsonObject data = envelope();
                    data.addProperty("position", pos.toShortString()); data.addProperty("dimension", player.level().dimension().location().toString());
                    data.addProperty("blockId", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                    data.addProperty("state", state.toString());
                    var entity = player.serverLevel().getBlockEntity(pos);
                    if (entity != null) {
                        data.addProperty("blockEntityType", String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType())));
                        data.addProperty("privateData", "NOT_READ");
                    }
                    return result(data);
                }, true);
        register(registry, "world_scan_nearby", "有界读取本人附近已加载方块状态。不读取容器或方块实体私有数据，不加载新区块。",
                "{\"radius\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":4}}", List.of(), (context, args) -> {
                    ServerPlayer player = player(context);
                    int radius = args.has("radius") ? args.get("radius").getAsInt() : 2;
                    Map<String, Integer> counts = new TreeMap<>();
                    int unavailable = 0;
                    for (BlockPos pos : BlockPos.betweenClosed(player.blockPosition().offset(-radius, -radius, -radius),
                            player.blockPosition().offset(radius, radius, radius))) {
                        if (!player.serverLevel().hasChunkAt(pos) || player.serverLevel().isOutsideBuildHeight(pos)) { unavailable++; continue; }
                        counts.merge(player.serverLevel().getBlockState(pos).toString(), 1, Integer::sum);
                    }
                    JsonObject data = envelope();
                    data.addProperty("origin", player.blockPosition().toShortString());
                    data.addProperty("dimension", player.level().dimension().location().toString());
                    data.addProperty("unavailablePositions", unavailable);
                    data.add("counts", AiJson.GSON.toJsonTree(counts));
                    return result(data);
                }, true);
    }
    private JsonObject describeRecipe(RecipeHolder<?> holder) {
        requireMain();
        var recipe = holder.value();
        JsonObject row = envelope();
        row.remove("indexState");
        row.addProperty("recipeId", holder.id().toString());
        row.addProperty("recipeType", String.valueOf(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType())));
        row.addProperty("serializerId", String.valueOf(BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer())));
        row.addProperty("executionAdapter", processingSupport.apply(holder));
        row.addProperty("executionAvailable", row.get("executionAdapter").getAsString().endsWith("MATERIALS_ONLY_V1"));
        String family = row.get("executionAdapter").getAsString().startsWith("ADVANCED_") ? "advanced_processing"
                : row.get("executionAdapter").getAsString().startsWith("SPECIAL_") ? "special_processing"
                : row.get("executionAdapter").getAsString().startsWith("FLUID_") ? "fluid_processing" : "processing";
        if (row.get("executionAvailable").getAsBoolean()) {
            row.addProperty("planningTool", family + "_plan"); row.addProperty("executionTool", family + "_execute");
            row.addProperty("executionNote", "已注册服务端适配器；具体输入、版本、权限及物料仍须计划核验，不代表当前库存可直接执行或全部整合包运行验收通过。");
        }
        try {
            row.add("sampleOutput", item(recipe.getResultItem(server.registryAccess())));
            JsonArray ingredients = new JsonArray();
            for (Ingredient ingredient : recipe.getIngredients().stream().limit(16).toList()) {
                JsonArray alternatives = new JsonArray();
                Arrays.stream(ingredient.getItems()).limit(12).map(ServerKnowledgeService::item).forEach(alternatives::add);
                ingredients.add(alternatives);
            }
            row.add("genericIngredientHints", ingredients);
            row.addProperty("specialRecipe", recipe.isSpecial());
            row.addProperty("note", "数量、组件、返还及执行条件以对应加工适配器为准，通用提示不是物料执行计划。");
            if (firefly520.fireflymc.ai.processing.FdCuttingProcessingAdapter.supports(recipe)) {
                JsonArray outputs = new JsonArray();
                var hints = firefly520.fireflymc.ai.processing.FdCuttingProcessingAdapter.outputHints(holder, server.overworld());
                for (var hint : hints.stream().limit(16).toList()) {
                    var output = item(hint.template()); output.addProperty("chancePerUnit", hint.chance()); outputs.add(output);
                }
                row.add("nativeOutputs", outputs); row.addProperty("nativeOutputsTruncated", hints.size() > 16);
                row.addProperty("outputSemantics", "每单位独立概率，固定虚拟工具时运0；最大产量不是已拥有库存。");
            }
            if (firefly520.fireflymc.ai.processing.FdCookingProcessingAdapter.supports(recipe)) {
                row.add("outputContainer", item(firefly520.fireflymc.ai.processing.FdCookingProcessingAdapter.container(holder, server.overworld())));
                row.addProperty("containerPerOutputUnit", true);
            }
        } catch (RuntimeException e) {
            row.addProperty("note", "此配方不能通过通用只读接口解释，需要专用适配器。");
        }
        return row;
    }
    public JsonObject recipeView(JsonObject args) {
        requireMain();
        JsonObject result = envelope();
        if (args.has("recipeId")) {
            var id = ResourceLocation.parse(args.get("recipeId").getAsString());
            var holder = server.getRecipeManager().byKey(id).orElseThrow(() -> new IllegalArgumentException("RECIPE_NOT_FOUND"));
            JsonObject recipe = describeRecipe(holder);
            if (recipe.has("genericIngredientHints")) {
                var hints = recipe.getAsJsonArray("genericIngredientHints");
                while (recipe.toString().length() > 16000) {
                    boolean trimmed = false;
                    for (var hint : hints) if (hint.getAsJsonArray().size() > 1) {
                        hint.getAsJsonArray().remove(hint.getAsJsonArray().size() - 1); trimmed = true;
                    }
                    recipe.addProperty("displayHintsTruncated", true);
                    if (!trimmed) { recipe.remove("genericIngredientHints"); break; }
                }
            }
            result.add("recipe", recipe);
        } else {
            String query = args.has("query") ? args.get("query").getAsString().strip().toLowerCase(Locale.ROOT) : "";
            if (query.length() > 160) throw new IllegalArgumentException("RECIPE_QUERY_LIMIT");
            int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
            var found = recipeRows.stream().filter(row -> row.toString().toLowerCase(Locale.ROOT).contains(query)).toList();
            if (offset < 0 || offset > found.size()) throw new IllegalArgumentException("RECIPE_OFFSET");
            JsonArray entries = new JsonArray(); int end = offset;
            while (end < found.size() && entries.size() < 8) {
                var row = found.get(end).deepCopy(); row.remove("genericIngredientHints");
                if (entries.toString().length() + row.toString().length() > 18000) break;
                entries.add(row); end++;
            }
            result.add("entries", entries); result.addProperty("nextOffset", end); result.addProperty("total", found.size());
        }
        return result;
    }
    public JsonObject envelope() {
        JsonObject data = new JsonObject();
        data.addProperty("modFingerprint", modFingerprint);
        data.addProperty("recipeGeneration", generation);
        data.addProperty("indexState", ready ? "READY" : "INDEXING");
        data.addProperty("source", "SERVER_RUNTIME");
        return data;
    }
    public JsonObject integrationView(JsonObject args) {
        requireMain();
        if (!Set.of("query", "offset").containsAll(args.keySet())) throw new IllegalArgumentException("INTEGRATION_QUERY_FIELDS");
        String query = args.has("query") ? args.get("query").getAsString().strip().toLowerCase(Locale.ROOT) : "";
        if (query.length() > 160) throw new IllegalArgumentException("INTEGRATION_QUERY_LIMIT");
        int offset = args.has("offset") ? args.get("offset").getAsBigDecimal().intValueExact() : 0;
        var matching = mods.stream().filter(mod -> (mod.get("id").getAsString() + " " + mod.get("name").getAsString())
                .toLowerCase(Locale.ROOT).contains(query)).toList();
        if (offset < 0 || offset > matching.size()) throw new IllegalArgumentException("INTEGRATION_OFFSET");
        var probes = integrationEvidence.get(); var entries = new JsonArray(); int end = offset;
        while (end < matching.size() && entries.size() < 4) {
            var mod = matching.get(end); String id = mod.get("id").getAsString(); var row = new JsonObject();
            row.addProperty("modId", id); row.add("name", mod.get("name")); row.add("version", mod.get("version"));
            row.addProperty("discoveryState", "DISCOVERED"); row.addProperty("source", "SERVER_LOADED");
            row.add("recipes", integrationRecipes.view(id)); var capabilities = new JsonArray();
            probes.stream().filter(probe -> probe.get("modId").getAsString().equals(id)).limit(8).forEach(probe -> capabilities.add(probe.deepCopy()));
            row.add("capabilities", capabilities);
            if (entries.toString().length() + row.toString().length() > 17000) break;
            entries.add(row); end++;
        }
        var data = envelope(); data.add("entries", entries); data.addProperty("nextOffset", end); data.addProperty("total", matching.size());
        data.addProperty("query", query); data.addProperty("verification", "当前会话未运行完整行为验收；接口可调用和配方可规划均不等于整合包验证通过。");
        return data;
    }
    private JsonObject page(List<JsonObject> rows, JsonObject args) {
        String query = args.has("query") ? args.get("query").getAsString().toLowerCase(Locale.ROOT) : "";
        int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
        var filtered = rows.stream().filter(row -> row.toString().toLowerCase(Locale.ROOT).contains(query)).toList();
        JsonObject data = envelope();
        data.addProperty("totalIndexedMatches", filtered.size());
        int start = Math.min(offset, filtered.size()), end = Math.min(start + 12, filtered.size());
        data.add("entries", AiJson.GSON.toJsonTree(filtered.subList(start, end)));
        data.addProperty("nextOffset", end);
        return data;
    }
    private ServerPlayer player(AgentTool.Context context) {
        requireMain();
        var player = server.getPlayerList().getPlayer(UUID.fromString(context.actor().owner()));
        if (player == null) throw new IllegalStateException("PLAYER_OFFLINE");
        return player;
    }
    private static JsonObject item(ItemStack stack) {
        JsonObject data = new JsonObject();
        if (stack.isEmpty()) { data.addProperty("empty", true); return data; }
        data.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        data.addProperty("count", stack.getCount());
        data.addProperty("name", ServerLanguageLoader.getTranslation(stack.getItem().getDescriptionId()));
        data.addProperty("componentsDigest", AiJson.digest(stack.getComponents().toString()));
        return data;
    }
    private static AgentTool.Result result(JsonObject data) {
        return new AgentTool.Result(true, "", "服务端事实查询完成。", data, "server-runtime", "", false);
    }
    private void register(ToolRegistry registry, String name, String description, String properties,
                          List<String> required, DefinedTool.Action action) {
        register(registry, name, description, properties, required, action, false);
    }
    private void register(ToolRegistry registry, String name, String description, String properties,
                          List<String> required, DefinedTool.Action action, boolean needsPlayer) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", JsonParser.parseString(properties));
        schema.add("required", AiJson.GSON.toJsonTree(required));
        schema.addProperty("additionalProperties", false);
        registry.register(new DefinedTool(new AgentTool.Descriptor(name, description, schema, AgentTool.Risk.READ_ONLY,
                "knowledge.read", 0, AgentTool.ExecutionThread.SERVER, 5000, needsPlayer, ""), action));
    }
    private void requireMain() { if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED"); }
}
