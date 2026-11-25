package firefly520.fireflymc.ai.integration.environment;

import com.google.gson.*;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.tools.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.neoforged.fml.ModList;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Predicate;

/** Explicit private queries. No search jobs, teleports, chunk generation, season setters, or chat/event collection. */
public final class EnvironmentFactsService {
    private final MinecraftServer server;
    private final Predicate<ServerPlayer> authenticated;
    private final Map<String, JsonObject> observations = new HashMap<>();
    public EnvironmentFactsService(MinecraftServer server, Predicate<ServerPlayer> authenticated) {
        if (!server.isDedicatedServer()) throw new IllegalStateException("DEDICATED_SERVER_REQUIRED");
        this.server = server; this.authenticated = authenticated;
    }
    public void register(ToolRegistry tools) {
        tool(tools, "environment_current", "读取本人当前位置的实际维度、生物群系、天气、时间及视线方块；不生成区块，不扫描其他玩家或收集普通聊天。", "{}", this::environment);
        tool(tools, "season_current", "通过实装 Serene Seasons 读取当前季节、维度是否启用和视线作物的原生成熟季节限制；不改季节或加速生长。", "{}", this::season);
        tool(tools, "compass_records", "读取本人主手/副手自然罗盘与探险者指南针已有结果。遵守原生隐藏坐标设置；不发起新区块搜索、不消耗耐久或伪造发现。", "{}", this::compasses);
        tool(tools, "waystones_known", "只列本人原生已激活的指路石与已知位置，不能查询任意 UUID 或全服私人石碑。列表不代表传送授权，本工具不会移动玩家、豁免费用或加载目标区块。",
                "{\"offset\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":4096}}", this::waystones);
    }
    public JsonObject environment(Actor actor, JsonObject args) {
        ServerPlayer player = player(actor); var level = player.serverLevel(); var data = base(player);
        data.addProperty("biome", level.getBiome(player.blockPosition()).unwrapKey().map(key -> key.location().toString()).orElse("unregistered"));
        data.addProperty("dayTime", level.getDayTime()); data.addProperty("raining", level.isRaining()); data.addProperty("thundering", level.isThundering());
        data.add("position", position(player.blockPosition()));
        var hit = player.pick(player.blockInteractionRange(), 1, false);
        if (hit instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK && level.hasChunkAt(block.getBlockPos())) {
            var observed = new JsonObject(); observed.add("position", position(block.getBlockPos()));
            observed.addProperty("block", BuiltInRegistries.BLOCK.getKey(level.getBlockState(block.getBlockPos()).getBlock()).toString()); data.add("lookedAt", observed);
        }
        return data;
    }
    public JsonObject season(Actor actor, JsonObject args) {
        var player = player(actor); var level = player.serverLevel(); var data = base(player); var adapter = adapter("sereneseasons", "10.1.0.3"); data.add("adapter", adapter);
        if (!matched(adapter)) return data;
        try {
            Object seasons = type("sereneseasons.init.ModConfig").getField("seasons").get(null);
            boolean active = (boolean) type("sereneseasons.config.SeasonsConfig").getMethod("isDimensionWhitelisted", ResourceKey.class).invoke(seasons, level.dimension());
            data.addProperty("activeInDimension", active);
            Object state = type("sereneseasons.api.season.SeasonHelper").getMethod("getSeasonState", Level.class).invoke(null, level);
            var contract = type("sereneseasons.api.season.ISeasonState");
            data.addProperty("season", ((Enum<?>) contract.getMethod("getSeason").invoke(state)).name());
            data.addProperty("subSeason", ((Enum<?>) contract.getMethod("getSubSeason").invoke(state)).name());
            data.addProperty("tropicalSeason", ((Enum<?>) contract.getMethod("getTropicalSeason").invoke(state)).name());
            data.addProperty("day", (int) contract.getMethod("getDay").invoke(state));
            data.addProperty("cycleTicks", (int) contract.getMethod("getSeasonCycleTicks").invoke(state));
            data.addProperty("dayDurationTicks", (int) contract.getMethod("getDayDuration").invoke(state));
            data.addProperty("tropicalBiome", (boolean) type("sereneseasons.api.season.SeasonHelper").getMethod("usesTropicalSeasons", Holder.class).invoke(null, level.getBiome(player.blockPosition())));
            Object fertility = type("sereneseasons.init.ModConfig").getField("fertility").get(null);
            data.addProperty("seasonalCropRules", type("sereneseasons.config.FertilityConfig").getField("seasonalCrops").getBoolean(fertility));
            var hit = player.pick(player.blockInteractionRange(), 1, false);
            if (hit instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK && level.hasChunkAt(block.getBlockPos())) {
                var target = level.getBlockState(block.getBlockPos()); var nativeFertility = type("sereneseasons.init.ModFertility");
                if ((boolean) nativeFertility.getMethod("isCrop", BlockState.class).invoke(null, target)) {
                    String id = BuiltInRegistries.BLOCK.getKey(target.getBlock()).toString(); var crop = new JsonObject();
                    crop.addProperty("block", id); crop.add("position", position(block.getBlockPos()));
                    crop.addProperty("seasonallyFertile", (boolean) nativeFertility.getMethod("isCropFertile", String.class, Level.class, BlockPos.class).invoke(null, id, level, block.getBlockPos()));
                    crop.addProperty("note", "这是原生季节许可；仍受光照、土壤、水分及其他 Mod 生长规则限制。"); data.add("lookedAtCrop", crop);
                }
            }
            adapter.addProperty("state", "RUNTIME_READY"); data.addProperty("source", "Serene Seasons native API/config-selected dimension and fertility rules");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { failed(adapter, unavailable); }
        observations.put("sereneseasons", adapter.deepCopy()); return data;
    }
    public JsonObject compasses(Actor actor, JsonObject args) {
        var player = player(actor); var data = base(player); var values = new JsonArray();
        for (var entry : Map.of("main_hand", player.getMainHandItem(), "off_hand", player.getOffhandItem()).entrySet()) {
            var item = entry.getValue(); if (item.isEmpty()) continue;
            String name = item.getItem().getClass().getName(); String mod, prefix;
            if (name.equals("com.chaosthedude.naturescompass.items.NaturesCompassItem")) { mod = "naturescompass"; prefix = "Biome"; }
            else if (name.equals("com.chaosthedude.explorerscompass.items.ExplorersCompassItem")) { mod = "explorerscompass"; prefix = "Structure"; }
            else continue;
            var row = new JsonObject(); row.addProperty("hand", entry.getKey()); row.addProperty("item", BuiltInRegistries.ITEM.getKey(item.getItem()).toString());
            var probe = adapter(mod, "1.21.1-3.4.0-neoforge"); row.add("adapter", probe); values.add(row);
            if (!matched(probe)) continue;
            try {
                var contract = type(name); Object instance = item.getItem();
                var stateGetter = contract.getMethod("getState", ItemStack.class); Object nativeState = stateGetter.invoke(instance, item);
                String state = nativeState == null ? "UNINITIALIZED"
                        : nativeState instanceof Enum<?> value && stateGetter.getReturnType().isInstance(value) ? value.name() : "UNKNOWN_NATIVE_STATE";
                row.addProperty("state", state);
                row.addProperty("broken", (boolean) contract.getMethod("isBroken", ItemStack.class).invoke(instance, item));
                Object key = contract.getMethod("get" + prefix + "Key", ItemStack.class).invoke(instance, item);
                if (key != null && !state.equals("INACTIVE")) row.addProperty("recordedTarget", key.toString());
                boolean coordinates = (boolean) contract.getMethod("shouldDisplayCoordinates", ItemStack.class).invoke(instance, item); row.addProperty("coordinatesVisible", coordinates);
                if (state.equals("FOUND") && coordinates) {
                    int x = (int) contract.getMethod("getFound" + prefix + "X", ItemStack.class).invoke(instance, item);
                    int z = (int) contract.getMethod("getFound" + prefix + "Z", ItemStack.class).invoke(instance, item);
                    if (Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000) throw new IllegalStateException("COMPASS_RECORD_OUTSIDE_WORLD");
                    row.addProperty("recordedX", x); row.addProperty("recordedZ", z);
                    row.addProperty("dimensionVerified", false);
                }
                row.addProperty("note", "已有物品记录，不是新搜索或当前世界存在性验证；该版本公开 getter 未提供记录所属维度。"); probe.addProperty("state", "RUNTIME_READY");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { failed(probe, unavailable); }
            observations.put(mod, probe.deepCopy());
        }
        data.add("heldCompasses", values); data.addProperty("searchStarted", false); return data;
    }
    public JsonObject waystones(Actor actor, JsonObject args) {
        var player = player(actor); var data = base(player); var probe = adapter("waystones", "21.1.37"); data.add("adapter", probe);
        if (!matched(probe)) return data;
        int offset = args.has("offset") ? args.get("offset").getAsBigDecimal().intValueExact() : 0;
        if (offset < 0 || offset > 4096) throw new IllegalArgumentException("WAYSTONE_OFFSET_INVALID");
        try {
            var api = type("net.blay09.mods.waystones.api.WaystonesAPI"); var contract = type("net.blay09.mods.waystones.api.Waystone");
            Object raw = api.getMethod("getActivatedWaystones", Player.class).invoke(null, player);
            if (!(raw instanceof Collection<?> known) || known.size() > 4096) throw new IllegalStateException("WAYSTONE_QUERY_LIMIT");
            var rows = new JsonArray(); int cursor = 0;
            for (Object waystone : known) {
                if (cursor < offset) { cursor++; continue; } if (rows.size() >= 24) break; cursor++;
                if (!(boolean) api.getMethod("isWaystoneActivated", Player.class, contract).invoke(null, player, waystone)) continue;
                var row = new JsonObject(); UUID id = (UUID) contract.getMethod("getWaystoneUid").invoke(waystone);
                var dimension = (ResourceKey<?>) contract.getMethod("getDimension").invoke(waystone); BlockPos pos = (BlockPos) contract.getMethod("getPos").invoke(waystone);
                var name = (net.minecraft.network.chat.Component) contract.getMethod("getEffectiveName").invoke(waystone);
                row.addProperty("id", id.toString()); row.addProperty("name", bounded(name.getString(), 128)); row.addProperty("dimension", dimension.location().toString());
                row.add("knownPosition", position(pos)); row.addProperty("registeredValid", (boolean) contract.getMethod("isValid").invoke(waystone));
                row.addProperty("ownedByPlayer", (boolean) contract.getMethod("isOwner", Player.class).invoke(waystone, player)); rows.add(row);
            }
            data.add("activated", rows); data.addProperty("nextOffset", Math.min(known.size(), cursor)); data.addProperty("total", known.size());
            data.addProperty("note", "本人原生激活记录；未确认目标区块当前状态，不启用传送/强制加载/费用豁免。"); probe.addProperty("state", "RUNTIME_READY");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { failed(probe, unavailable); }
        observations.put("waystones", probe.deepCopy()); return data;
    }
    /** Capability evidence contains no player's coordinates, items, discoveries or private records. */
    public List<JsonObject> adapterEvidence() {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        var versions = Map.of("sereneseasons", "10.1.0.3", "waystones", "21.1.37",
                "naturescompass", "1.21.1-3.4.0-neoforge", "explorerscompass", "1.21.1-3.4.0-neoforge");
        return versions.entrySet().stream().map(entry -> {
            var row = observations.getOrDefault(entry.getKey(), adapter(entry.getKey(), entry.getValue())).deepCopy();
            if (row.get("state").getAsString().equals("API_MATCH_PENDING")) row.addProperty("state", "DISCOVERED");
            row.addProperty("capability", switch (entry.getKey()) { case "sereneseasons" -> "本人季节与作物许可查询";
                case "waystones" -> "本人已激活指路石查询"; default -> "本人手持罗盘已有结果查询"; });
            row.addProperty("behaviorVerifiedHere", false); row.addProperty("scope", "只读查询接口；不代表搜索、传送或采集执行能力。"); return row;
        }).toList();
    }
    private ServerPlayer player(Actor actor) {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        var player = server.getPlayerList().getPlayer(UUID.fromString(actor.owner()));
        if (player == null || !authenticated.test(player)) throw new SecurityException("PLAYER_NOT_AUTHENTICATED"); actor.requireAccess(player.getUUID().toString()); return player;
    }
    private static JsonObject base(ServerPlayer player) {
        var data = new JsonObject(); data.addProperty("dimension", player.serverLevel().dimension().location().toString());
        data.addProperty("observedGameTime", player.serverLevel().getGameTime()); data.addProperty("scope", "OWN_PLAYER_CONTEXT"); return data;
    }
    private static JsonObject position(BlockPos pos) { var data = new JsonObject(); data.addProperty("x", pos.getX()); data.addProperty("y", pos.getY()); data.addProperty("z", pos.getZ()); return data; }
    private static JsonObject adapter(String id, String expected) {
        var result = new JsonObject(); result.addProperty("modId", id); result.addProperty("inspectedVersion", expected);
        String actual = ModList.get().getModContainerById(id).map(mod -> mod.getModInfo().getVersion().toString()).orElse(""); result.addProperty("loadedVersion", actual);
        result.addProperty("state", actual.isEmpty() ? "ABSENT" : actual.equals(expected) ? "API_MATCH_PENDING" : "VERSION_UNSUPPORTED");
        return result;
    }
    private static boolean matched(JsonObject probe) { return probe.get("state").getAsString().equals("API_MATCH_PENDING"); }
    private static Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name); }
    private static void failed(JsonObject probe, Throwable failure) {
        probe.addProperty("state", "API_UNAVAILABLE"); probe.addProperty("reason", failure instanceof ReflectiveOperationException && !(failure instanceof InvocationTargetException) ? "INTERFACE_MISMATCH" : "NATIVE_QUERY_FAILED");
    }
    private static String bounded(String text, int limit) { return text.length() <= limit ? text : text.substring(0, limit); }
    @FunctionalInterface private interface Query { JsonObject read(Actor actor, JsonObject args); }
    private static void tool(ToolRegistry tools, String name, String description, String properties, Query query) {
        JsonObject schema = new JsonObject(); schema.addProperty("type", "object"); schema.add("properties", JsonParser.parseString(properties)); schema.addProperty("additionalProperties", false);
        tools.register(new DefinedTool(new AgentTool.Descriptor(name, description, schema, AgentTool.Risk.READ_ONLY, "environment.own", 0,
                AgentTool.ExecutionThread.SERVER, 10000, true, ""), (ctx, args) -> {
                    ctx.actor().requireAccess(ctx.workspace().owner());
                    return new AgentTool.Result(true, "", "服务端本人环境事实与实际适配状态。", query.read(ctx.actor(), args), "server-environment-native-api", "", false);
                }));
    }
}
