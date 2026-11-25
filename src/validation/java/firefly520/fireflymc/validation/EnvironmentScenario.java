package firefly520.fireflymc.validation;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import firefly520.fireflymc.ai.integration.environment.EnvironmentFactsService;
import firefly520.fireflymc.ai.material.ItemSnapshotCodec;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import java.util.*;

/** Native API fixtures in an isolated dedicated process; never invokes a model or a graphical client. */
public final class EnvironmentScenario implements AutoCloseable {
    private final MinecraftServer server;
    private final firefly520.fireflymc.ai.knowledge.ServerKnowledgeService knowledge;
    private EnvironmentFactsService lastFacts;
    private final JsonArray checks = new JsonArray();
    private final JsonObject evidence = new JsonObject();
    private final long started = System.nanoTime();
    private int phase;
    private String failure = "";
    public EnvironmentScenario(MinecraftServer server) {
        this.server = server; knowledge = new firefly520.fireflymc.ai.knowledge.ServerKnowledgeService(server);
        knowledge.processingSupport(holder -> firefly520.fireflymc.ai.processing.VanillaProcessingKernel.supports(holder.value())
                ? "VANILLA_MATERIALS_ONLY_V1" : "NO_EXECUTION_ADAPTER");
        knowledge.integrationEvidence(() -> lastFacts == null ? List.of() : lastFacts.adapterEvidence());
    }
    public JsonObject tick() {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        knowledge.tick();
        try {
            switch (phase++) {
                case 0 -> ownEnvironment();
                case 1 -> seasons();
                case 2 -> compasses();
                case 3 -> waystones();
                case 4 -> {
                    if (!knowledge.envelope().get("indexState").getAsString().equals("READY")) { phase--; return null; }
                    integrationCatalog();
                }
                default -> { return report(); }
            }
            return null;
        } catch (Exception | LinkageError error) {
            error.printStackTrace(); failure = error.getClass().getSimpleName() + ": " + error.getMessage(); return report();
        }
    }
    private void ownEnvironment() throws Exception {
        try (var first = new Session(); var other = new Session()) {
            var service = service(first, other); first.player.moveTo(3, 83, 3, 0, 0); other.player.moveTo(7, 83, 7, 0, 0);
            var firstPosition = first.player.position(); long time = first.player.level().getDayTime();
            JsonObject a = service.environment(first.actor(), new JsonObject()), b = service.environment(other.actor(), new JsonObject());
            require(a.getAsJsonObject("position").get("x").getAsInt() == 3 && b.getAsJsonObject("position").get("x").getAsInt() == 7, "PLAYER_POSITION_ISOLATION");
            require(firstPosition.equals(first.player.position()) && time == first.player.level().getDayTime(), "READ_MUTATED_PLAYER_OR_TIME");
            check("own_position_weather_biome_queries_leave_world_and_player_unchanged");
            try { new EnvironmentFactsService(server, p -> false).environment(first.actor(), new JsonObject()); throw new IllegalStateException("AUTH_BYPASS"); }
            catch (SecurityException expected) { require("PLAYER_NOT_AUTHENTICATED".equals(expected.getMessage()), "WRONG_AUTH_REFUSAL"); }
            check("query_rechecks_live_authentication");
            Throwable asynchronous = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { service.environment(first.actor(), new JsonObject()); return null; } catch (Throwable caught) { return caught; }
            }).get();
            require(asynchronous instanceof IllegalStateException && "SERVER_THREAD_REQUIRED".equals(asynchronous.getMessage()), "ASYNC_STATE_ACCESS");
            check("minecraft_state_query_refuses_async_thread");
            evidence.add("ownEnvironment", a);
        }
    }
    private void seasons() throws Exception {
        try (var session = new Session()) {
            var service = service(session); var level = session.player.serverLevel(); var result = service.season(session.actor(), new JsonObject());
            if (!ModList.get().isLoaded("sereneseasons")) { require(state(result).equals("ABSENT"), "SEASON_ABSENCE_MISREPORTED"); check("missing_season_mod_is_explicit"); return; }
            require(state(result).equals("RUNTIME_READY"), "SEASON_API_NOT_READY_" + result);
            Object nativeState = Class.forName("sereneseasons.api.season.SeasonHelper").getMethod("getSeasonState", Level.class).invoke(null, level);
            Class<?> contract = Class.forName("sereneseasons.api.season.ISeasonState");
            require(result.get("subSeason").getAsString().equals(((Enum<?>) contract.getMethod("getSubSeason").invoke(nativeState)).name()), "SEASON_MISMATCH");
            require(result.get("cycleTicks").getAsInt() == (int) contract.getMethod("getSeasonCycleTicks").invoke(nativeState), "SEASON_TIME_MISMATCH");
            check("season_and_cycle_ticks_match_actual_mod_state");
            BlockPos crop = new BlockPos(20, 82, 20); level.getChunkAt(crop);
            var before = level.getBlockState(crop); var floor = level.getBlockState(crop.below());
            try {
                level.setBlockAndUpdate(crop.below(), Blocks.FARMLAND.defaultBlockState()); level.setBlockAndUpdate(crop, Blocks.WHEAT.defaultBlockState());
                session.player.moveTo(20.5, 83, 20.5, 0, 90);
                JsonObject fertility = service.season(session.actor(), new JsonObject());
                require(fertility.has("lookedAtCrop"), "LOOKED_AT_CROP_NOT_REPORTED");
                boolean nativeFertile = (boolean) Class.forName("sereneseasons.init.ModFertility")
                        .getMethod("isCropFertile", String.class, Level.class, BlockPos.class).invoke(null, "minecraft:wheat", level, crop);
                require(fertility.getAsJsonObject("lookedAtCrop").get("seasonallyFertile").getAsBoolean() == nativeFertile, "FERTILITY_MISMATCH");
                check("crop_fertility_uses_native_dimension_biome_and_season_rule"); evidence.add("season", fertility);
            } finally { level.setBlockAndUpdate(crop, before); level.setBlockAndUpdate(crop.below(), floor); }
        }
    }
    private void compasses() throws Exception {
        try (var session = new Session(); var other = new Session()) {
            var service = service(session, other);
            for (String mod : List.of("naturescompass", "explorerscompass")) {
                if (!ModList.get().isLoaded(mod)) { check(mod + "_not_installed_no_record"); continue; }
                ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(mod, mod)));
                require(!stack.isEmpty(), "COMPASS_ITEM_MISSING"); Object item = stack.getItem(); var type = item.getClass();
                session.player.getInventory().setItem(session.player.getInventory().selected, stack);
                var initial = ItemSnapshotCodec.capture(stack, session.player.registryAccess());
                Object nativeState = type.getMethod("getState", ItemStack.class).invoke(item, stack);
                JsonObject initialView = service.compasses(session.actor(), new JsonObject()).getAsJsonArray("heldCompasses").get(0).getAsJsonObject();
                require(state(initialView).equals("RUNTIME_READY") && initialView.get("state").getAsString()
                        .equals(nativeState == null ? "UNINITIALIZED" : ((Enum<?>) nativeState).name()), "NEW_COMPASS_NATIVE_DEFAULT_MISMATCH");
                require(initial.equals(ItemSnapshotCodec.capture(stack, session.player.registryAccess()))
                        && !initialView.has("recordedX") && !initialView.has("recordedZ"), "NEW_COMPASS_QUERY_INITIALIZED_OR_EXPOSED_TARGET");
                check(mod + "_new_native_compass_state_is_read_without_initialization");
                if (mod.equals("naturescompass")) {
                    type.getMethod("setBiomeKey", ItemStack.class, ResourceLocation.class, Player.class).invoke(item, stack, ResourceLocation.parse("minecraft:plains"), session.player);
                    type.getMethod("setFound", ItemStack.class, int.class, int.class, int.class, Player.class).invoke(item, stack, 144, 233, 128, session.player);
                } else type.getMethod("setFound", ItemStack.class, ResourceLocation.class, boolean.class, int.class, int.class, int.class)
                        .invoke(item, stack, ResourceLocation.parse("minecraft:fortress"), false, 144, 233, 128);
                session.player.getInventory().setItem(session.player.getInventory().selected, stack);
                type.getMethod("setDisplayCoordinates", ItemStack.class, boolean.class).invoke(item, stack, false);
                var before = ItemSnapshotCodec.capture(stack, session.player.registryAccess());
                JsonObject hidden = service.compasses(session.actor(), new JsonObject()).getAsJsonArray("heldCompasses").get(0).getAsJsonObject();
                require(state(hidden).equals("RUNTIME_READY"), "COMPASS_API_NOT_READY_" + hidden);
                require(!hidden.has("recordedX") && !hidden.has("recordedZ") && !hidden.get("coordinatesVisible").getAsBoolean(), "HIDDEN_COORDINATES_LEAK");
                require(before.equals(ItemSnapshotCodec.capture(stack, session.player.registryAccess())), "COMPASS_QUERY_MUTATED_COMPONENTS");
                require(service.compasses(other.actor(), new JsonObject()).getAsJsonArray("heldCompasses").isEmpty(), "OTHER_PLAYER_COMPASS_LEAK");
                check(mod + "_private_read_preserves_components_and_hidden_coordinates");
                type.getMethod("setDisplayCoordinates", ItemStack.class, boolean.class).invoke(item, stack, true);
                JsonObject visible = service.compasses(session.actor(), new JsonObject()).getAsJsonArray("heldCompasses").get(0).getAsJsonObject();
                require(visible.get("recordedX").getAsInt() == 144 && visible.get("recordedZ").getAsInt() == 233
                        && !visible.get("dimensionVerified").getAsBoolean(), "COMPASS_RECORD_MISMATCH");
                check(mod + "_visible_existing_target_is_not_new_search_or_dimension_proof"); evidence.add(mod, visible);
            }
        }
    }
    private void waystones() throws Exception {
        try (var session = new Session(); var other = new Session()) {
            var service = service(session, other);
            JsonObject before = service.waystones(session.actor(), new JsonObject());
            if (!ModList.get().isLoaded("waystones")) { require(state(before).equals("ABSENT"), "WAYSTONE_ABSENCE_MISREPORTED"); check("missing_waystone_mod_is_explicit"); return; }
            require(state(before).equals("RUNTIME_READY"), "WAYSTONE_API_NOT_READY_" + before);
            var api = Class.forName("net.blay09.mods.waystones.api.WaystonesAPI"); var contract = Class.forName("net.blay09.mods.waystones.api.Waystone");
            var styleType = Class.forName("net.blay09.mods.waystones.api.WaystoneStyle");
            Object style = styleType.getConstructor(ResourceLocation.class).newInstance(ResourceLocation.parse("waystones:waystone"));
            BlockPos origin = new BlockPos(512 + Math.floorMod(session.player.getUUID().hashCode(), 1000) * 4, 82, 32);
            var level = session.player.serverLevel(); level.getChunkAt(origin); level.getChunkAt(origin.offset(4, 0, 0));
            Object own = ((Optional<?>) api.getMethod("placeWaystone", Level.class, BlockPos.class, styleType).invoke(null, level, origin, style)).orElseThrow();
            Object foreign = ((Optional<?>) api.getMethod("placeWaystone", Level.class, BlockPos.class, styleType).invoke(null, level, origin.offset(4, 0, 0), style)).orElseThrow();
            try {
                api.getMethod("activateWaystone", ServerPlayer.class, contract).invoke(null, session.player, own);
                api.getMethod("activateWaystone", ServerPlayer.class, contract).invoke(null, other.player, foreign);
                UUID ownId = (UUID) contract.getMethod("getWaystoneUid").invoke(own), otherId = (UUID) contract.getMethod("getWaystoneUid").invoke(foreign);
                var position = session.player.position(); int xp = session.player.totalExperience;
                JsonObject known = service.waystones(session.actor(), new JsonObject()); String rows = known.getAsJsonArray("activated").toString();
                require(rows.contains(ownId.toString()) && !rows.contains(otherId.toString()), "FOREIGN_WAYSTONE_LEAK");
                require(known.get("total").getAsInt() == 1, "WAYSTONE_SCOPE_WIDENED");
                require(session.player.position().equals(position) && session.player.totalExperience == xp, "QUERY_TELEPORTED_OR_CHARGED_PLAYER");
                check("only_own_native_activated_waystones_no_global_database_or_teleport"); evidence.add("waystones", known);
                JsonObject cursor = new JsonObject(); cursor.addProperty("offset", 1);
                require(service.waystones(session.actor(), cursor).getAsJsonArray("activated").isEmpty(), "WAYSTONE_CURSOR_REPEAT");
                api.getMethod("deactivateWaystone", ServerPlayer.class, contract).invoke(null, session.player, own);
                require(service.waystones(session.actor(), new JsonObject()).getAsJsonArray("activated").isEmpty(), "WAYSTONE_REVOCATION_STALE");
                check("native_deactivation_is_immediate_and_paging_does_not_repeat");
            } finally {
                api.getMethod("removeWaystoneFromDatabase", MinecraftServer.class, contract).invoke(null, server, own);
                api.getMethod("removeWaystoneFromDatabase", MinecraftServer.class, contract).invoke(null, server, foreign);
                for (BlockPos pos : List.of(origin, origin.above(), origin.offset(4, 0, 0), origin.offset(4, 1, 0))) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
        }
    }
    private EnvironmentFactsService service(Session... sessions) {
        lastFacts = new EnvironmentFactsService(server, p -> Arrays.stream(sessions).anyMatch(s -> s.player == p)); return lastFacts;
    }
    private void integrationCatalog() {
        var args = new JsonObject(); args.addProperty("query", "waystones");
        var result = knowledge.integrationView(args);
        if (ModList.get().isLoaded("waystones")) {
            require(result.getAsJsonArray("entries").size() == 1, "INTEGRATION_MOD_SCOPE");
            var row = result.getAsJsonArray("entries").get(0).getAsJsonObject();
            require(row.get("version").getAsString().equals("21.1.37") && row.get("discoveryState").getAsString().equals("DISCOVERED"), "INTEGRATION_VERSION_PROOF");
            require(row.getAsJsonArray("capabilities").get(0).getAsJsonObject().get("state").getAsString().equals("RUNTIME_READY"), "INTEGRATION_NATIVE_QUERY_EVIDENCE_MISSING");
            require(!result.toString().contains("knownPosition") && !result.toString().contains("activated\"") && !result.toString().contains("recordedX"), "INTEGRATION_PRIVATE_DATA_LEAK");
            check("integration_catalog_separates_loaded_version_from_called_interface_without_private_records");
        }
        args = new JsonObject(); args.addProperty("query", "minecraft");
        result = knowledge.integrationView(args); require(result.toString().contains("VANILLA_MATERIALS_ONLY_V1"), "INTEGRATION_ACTIVE_RECIPES_MISSING");
        check("integration_recipe_counts_come_from_live_indexed_holders");
        knowledge.invalidate(); result = knowledge.integrationView(args);
        require(result.get("indexState").getAsString().equals("INDEXING") && result.getAsJsonArray("entries").get(0).getAsJsonObject()
                .getAsJsonObject("recipes").get("indexedRecipeCount").getAsInt() == 0, "INTEGRATION_RELOAD_STALE");
        check("reload_clears_old_executable_recipe_evidence_immediately");
    }
    private static String state(JsonObject data) { return data.getAsJsonObject("adapter").get("state").getAsString(); }
    private static void require(boolean condition, String error) { if (!condition) throw new IllegalStateException(error); }
    private void check(String name) { checks.add(name); }
    private JsonObject report() {
        JsonObject report = new JsonObject(); report.addProperty("status", failure.isEmpty() ? "PASSED" : "FAILED"); report.addProperty("error", failure);
        report.add("checks", checks); report.add("nativeEvidence", evidence); report.addProperty("elapsedMillis", (System.nanoTime() - started) / 1_000_000);
        report.add("loadedMods", AiJson.GSON.toJsonTree(ModList.get().getMods().stream().map(mod -> Map.of("id", mod.getModId(), "version", mod.getVersion().toString())).toList()));
        report.addProperty("scope", "Read-only environment APIs, actual Mod components and activated-waystone records with isolated player fixtures; no searches, travel, map rendering, GUI clients or model requests."); return report;
    }
    @Override public void close() { }
    private final class Session implements AutoCloseable {
        final ServerPlayer player; final EmbeddedChannel channel;
        Session() {
            UUID id = UUID.randomUUID(); var cookie = new CommonListenerCookie(new GameProfile(id, "env-" + id.toString().substring(0, 8)),
                    0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
            player = new ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND); channel = new EmbeddedChannel(connection);
            NetworkRegistry.configureMockConnection(connection); server.getPlayerList().placeNewPlayer(connection, player, cookie); ValidationHandshake.reply(player);
            player.setGameMode(GameType.SURVIVAL); player.getInventory().clearContent();
        }
        Actor actor() { return Actor.player(player.getUUID(), true, true, 0); }
        @Override public void close() { server.getPlayerList().remove(player); net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete(); channel.finishAndReleaseAll(); }
    }
}
