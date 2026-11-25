package firefly520.fireflymc.validation;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import firefly520.fireflymc.ai.AiServerService;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.processing.VirtualProcessingService;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.task.TaskControl;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Runs the actual discovery tool on a dedicated server, including expired UI cursors, without a provider call. */
public final class CraftableDiscoveryScenario implements AutoCloseable {
    private final MinecraftServer server;
    private final WorkspaceManager workspaces;
    private final WorkspaceManager.Workspace workspace;
    private final MaterialLedger ledger;
    private final MaterialInventoryService inventory;
    private final VirtualProcessingService processing;
    private final ToolRegistry tools = new ToolRegistry();
    private final ServerPlayer player;
    private final Actor actor;
    private final EmbeddedChannel channel;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final GameToolBridge bridge;
    private final CompletableFuture<JsonObject> result;
    private final Set<UUID> expired = new HashSet<>();
    private int calls;

    public CraftableDiscoveryScenario(MinecraftServer server) throws Exception {
        this.server = server;
        Path root = Files.createTempDirectory(server.getWorldPath(LevelResource.ROOT), "craftable-query-");
        workspaces = new WorkspaceManager(root); ledger = new MaterialLedger(root);
        inventory = new MaterialInventoryService(server, AiServerService::authenticated, new InstalledPermissionAdapters(server));
        processing = new VirtualProcessingService(server, inventory, ledger, () -> 1); processing.register(tools);
        UUID id = UUID.randomUUID();
        var cookie = new CommonListenerCookie(new GameProfile(id, "craft_" + id.toString().substring(0, 8)), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
        player = new ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND); channel = new EmbeddedChannel(connection);
        NetworkRegistry.configureMockConnection(connection); server.getPlayerList().placeNewPlayer(connection, player, cookie);
        ValidationHandshake.reply(player); ValidationHandshake.authenticateFixture(player);
        player.setGameMode(GameType.SURVIVAL); player.setNoGravity(true); player.getInventory().clearContent();
        player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 6)); player.getInventory().setItem(1, new ItemStack(Items.BIRCH_LOG, 7));
        player.getInventory().setItem(2, new ItemStack(Items.BREAD, 27)); player.getInventory().setItem(3, new ItemStack(Items.TORCH, 64));
        actor = Actor.player(id, true, true, 0); workspace = workspaces.getWorkspace(actor);
        inventory.bind(actor.owner(), new MaterialSourceGrants(workspace, System::currentTimeMillis));
        inventory.bindPreferences(actor.owner(), new MaterialPreferences(workspace));
        bridge = new GameToolBridge(server, owner -> actor, name -> tools.get(name) != null, () -> false, io);
        result = CompletableFuture.supplyAsync(this::measure, action -> Thread.ofPlatform().daemon(true).name("craftable-driver").start(action));
    }

    private AgentTool.Result query() throws Exception {
        calls++;
        var call = new ModelMessage.ToolCall("query-" + calls, "crafting_discover", "{}");
        return bridge.execute(tools.get(call.name()), new AgentTool.Context(workspace, actor, new TaskControl(120000), UUID.randomUUID()),
                new JsonObject(), "", call).get(120, TimeUnit.SECONDS);
    }
    private JsonObject measure() {
        var report = new JsonObject();
        try {
            long started = System.nanoTime(); var first = query();
            report.addProperty("screenshotQueryMillis", (System.nanoTime() - started) / 1_000_000.0);
            require(first.success(), first.errorCode());
            var data = first.observations(); var outputs = new HashSet<String>();
            data.getAsJsonArray("craftable").forEach(value -> outputs.add(value.getAsJsonObject().get("outputId").getAsString()));
            require(outputs.containsAll(Set.of("minecraft:oak_planks", "minecraft:birch_planks")), "SCREENSHOT_PLANKS_MISSING");
            require(data.get("displayReport").getAsString().contains("橡木木板") && data.get("displayReport").getAsString().contains("白桦木板"), "CHINESE_CATALOG_LABELS_MISSING");
            require(!outputs.contains("minecraft:crafting_table"), "DISCOVERY_INVENTED_INTERMEDIATE_MATERIALS");
            require(data.get("complete").getAsBoolean() && data.get("scannedRecipes").equals(data.get("catalogRecipes")), "CATALOG_NOT_COMPLETE");
            require(!data.has("nextOffset") && !data.has("snapshotId"), "MODEL_PAGINATION_REMAINED");
            report.add("screenshotQuery", data);
            main(() -> {
                require(player.getInventory().getItem(0).getCount() == 6 && player.getInventory().getItem(1).getCount() == 7, "QUERY_CONSUMED_LOGS");
                player.getInventory().clearContent();
                Item[] materials = {Items.OAK_PLANKS, Items.COBBLESTONE, Items.STICK, Items.IRON_INGOT, Items.GOLD_INGOT,
                        Items.DIAMOND, Items.REDSTONE, Items.COAL, Items.GLASS, Items.LEATHER, Items.STRING, Items.WHITE_WOOL};
                for (int i = 0; i < materials.length; i++) player.getInventory().setItem(i, new ItemStack(materials[i], 64));
                return true;
            });
            WorkspacePreferences.defaults().update(JsonParser.parseString("{\"yoloMode\":true}").getAsJsonObject()).save(workspace, actor);
            started = System.nanoTime(); var second = query();
            report.addProperty("manyResultsQueryMillis", (System.nanoTime() - started) / 1_000_000.0);
            require(second.success(), second.errorCode()); require(second.observations().get("craftableCount").getAsInt() > 12, "OLD_RESULT_LIMIT_REMAINED");
            require(second.observations().get("scannedRecipes").equals(second.observations().get("catalogRecipes")), "SECOND_CATALOG_NOT_COMPLETE");
            require(main(() -> {
                for (int i = 0; i < 12; i++) if (player.getInventory().getItem(i).getCount() != 64) return false;
                return true;
            }), "QUERY_CONSUMED_MATERIALS");
            require(ledger.list(actor).isEmpty(), "READ_ONLY_QUERY_CREATED_EXECUTION");
            report.add("manyResultsQuery", second.observations()); report.addProperty("toolCalls", calls); report.addProperty("status", "PASSED");
        } catch (Throwable failure) { failure.printStackTrace(); report.addProperty("status", "FAILED"); report.addProperty("error", failure.toString()); }
        return report;
    }
    private static void require(boolean value, String error) { if (!value) throw new IllegalStateException(error); }
    private <T> T main(Callable<T> work) throws Exception {
        var future = new CompletableFuture<T>();
        server.execute(() -> { try { future.complete(work.call()); } catch (Throwable failure) { future.completeExceptionally(failure); } });
        return future.get(30, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    public JsonObject tick() throws Exception {
        inventory.tick();
        var field = MaterialInventoryService.class.getDeclaredField("snapshots"); field.setAccessible(true);
        var snapshots = (Map<UUID, MaterialInventoryService.Snapshot>) field.get(inventory);
        snapshots.replaceAll((id, value) -> expired.add(id) ? new MaterialInventoryService.Snapshot(id, value.owner(),
                System.currentTimeMillis() - 120000, value.rows(), value.refusals()) : value);
        processing.tick();
        if (!result.isDone()) return null;
        var report = result.join(); report.addProperty("forcedExpiredUiCursors", expired.size());
        if (expired.size() < 2) report.addProperty("status", "FAILED");
        return report;
    }
    @Override public void close() throws Exception {
        processing.close(); require(processing.awaitEvidenceStopped(10000), "WRITER_NOT_STOPPED"); inventory.close();
        io.shutdown(); require(io.awaitTermination(10000, TimeUnit.MILLISECONDS), "TOOL_NOT_STOPPED");
        server.getPlayerList().remove(player); channel.finishAndReleaseAll(); workspaces.close();
    }
}
