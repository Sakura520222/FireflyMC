package firefly520.fireflymc.validation;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import firefly520.fireflymc.ai.AiServerService;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.integration.maid.transport.*;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.processing.VirtualProcessingService;
import firefly520.fireflymc.ai.security.*;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.*;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.items.*;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.common.IOUtilities;

/** One real finite native transport and one native-cancel refusal; no graphical/full-pack claim. */
public final class MaidTransportScenario implements AutoCloseable {
    private static final String PREFIX = "com.github.tartaricacid.touhoulittlemaid.";
    private final MinecraftServer server;
    private final ServerPlayer player, other;
    private final Actor actor, foreign;
    private final WorkspaceManager manager;
    private final WorkspaceManager.Workspace workspace;
    private final MaterialInventoryService inventory;
    private final VirtualProcessingService dispatcher;
    private final MaidTransportService transport;
    private final ToolRegistry tools = new ToolRegistry();
    private final GameToolBridge bridge;
    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private final List<String> passed = new ArrayList<>();
    private final TamableAnimal maid;
    private final BlockPos target;
    private final ItemStack bauble;
    private final CompletableFuture<JsonObject> result;
    private final Consumer<Event> nativeEvent;
    private volatile boolean cancelNative;
    private volatile int nativeEvents, ownedNativeEvents, cancelledNativeEvents;
    private volatile String lastWait = "";
    private MaterialSourceGrants.Grant maidGrant, blockGrant;
    @SuppressWarnings("unchecked")
    public MaidTransportScenario(MinecraftServer server) throws Exception {
        this.server = server;
        if (!server.isDedicatedServer()) throw new IllegalStateException("DEDICATED_MAID_TRANSPORT_REQUIRED");
        player = player(); other = player(); actor = Actor.player(player.getUUID(), true, true, 0); foreign = Actor.player(other.getUUID(), true, true, 0);
        Path root = Files.createTempDirectory(server.getWorldPath(LevelResource.ROOT).toAbsolutePath(), "ai4-maid-transport-");
        manager = new WorkspaceManager(root); workspace = manager.getWorkspace(actor);
        var permissions = new InstalledPermissionAdapters(server); inventory = new MaterialInventoryService(server, AiServerService::authenticated, permissions);
        inventory.bind(actor.owner(), new MaterialSourceGrants(workspace, System::currentTimeMillis)); inventory.bindPreferences(actor.owner(), new MaterialPreferences(workspace));
        var otherWorkspace = manager.getWorkspace(foreign); inventory.bind(foreign.owner(), new MaterialSourceGrants(otherWorkspace, System::currentTimeMillis));
        inventory.bindPreferences(foreign.owner(), new MaterialPreferences(otherWorkspace));
        var materials = new MaterialLedger(root); dispatcher = new VirtualProcessingService(server, inventory, materials, () -> 1);
        transport = new MaidTransportService(server, inventory, permissions, dispatcher, () -> 1, () -> true, materials); transport.register(tools);
        var entity = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("touhou_little_maid:maid")).create(server.overworld());
        if (!(entity instanceof TamableAnimal pet)) throw new IllegalStateException("ACTUAL_NATIVE_MAID_REQUIRED");
        maid = pet; maid.tame(player);
        int y = server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);
        BlockPos fresh = null;
        for (int x = 2; x <= 14 && fresh == null; x++) for (int z = 0; z <= 14; z++) {
            var candidate = new BlockPos(x, y, z);
            if (server.overworld().isEmptyBlock(candidate) && server.overworld().getBlockEntity(candidate) == null) { fresh = candidate; break; }
        }
        if (fresh == null) throw new IllegalStateException("MAID_TRANSPORT_FRESH_FIXTURE_CELL_UNAVAILABLE");
        target = fresh;
        player.moveTo(target.getX() - 1.5, y, target.getZ() + .5); other.moveTo(target.getX() - 1.5, y, target.getZ() + 2.5);
        maid.moveTo(target.getX() - .5, y, target.getZ() + .5);
        if (!server.overworld().addFreshEntity(maid)) throw new IllegalStateException("ACTUAL_MAID_JOIN_REQUIRED");
        Object schedule = maid.getClass().getMethod("getSchedulePos").invoke(maid);
        schedule.getClass().getMethod("setHomeModeEnable", maid.getClass(), BlockPos.class).invoke(schedule, maid, maid.blockPosition());
        maid.getClass().getMethod("setHomeModeEnable", boolean.class).invoke(maid, true);
        Object idle = Class.forName(PREFIX + "entity.task.TaskManager").getMethod("getIdleTask").invoke(null);
        maid.getClass().getMethod("setTask", Class.forName(PREFIX + "api.task.IMaidTask")).invoke(maid, idle);
        if (inventory.maids().bindOwned(player, player.getUUID(), maid.getUUID()).binding() == null) throw new IllegalStateException("ACTUAL_MAID_BINDING_REQUIRED");
        server.overworld().setBlockAndUpdate(target, Blocks.BARREL.defaultBlockState());
        Item item = BuiltInRegistries.ITEM.stream().filter(value -> value.getClass().getName().equals(PREFIX + "item.ItemWirelessIO")).findFirst().orElseThrow();
        bauble = new ItemStack(item); Class<?> api = item.getClass();
        api.getMethod("setMode", ItemStack.class, boolean.class).invoke(null, bauble, true);
        api.getMethod("setFilterMode", ItemStack.class, boolean.class).invoke(null, bauble, true);
        api.getMethod("setBindingPos", ItemStack.class, BlockPos.class).invoke(null, bauble, target);
        var excluded = new ArrayList<>(Collections.nCopies(38, false)); excluded.set(36, true); excluded.set(37, true);
        api.getMethod("setSlotConfig", ItemStack.class, List.class).invoke(null, bauble, excluded);
        ((IItemHandlerModifiable) maid.getClass().getMethod("getMaidBauble").invoke(maid)).setStackInSlot(0, bauble);
        // Inert cargo avoids the native hunger task legitimately consuming a food fixture before
        // its next 100-tick IO chance. Native AI and all normal costs remain enabled.
        var input = new ItemStack(Items.COBBLESTONE, 4); input.set(DataComponents.CUSTOM_NAME, Component.literal("owned-transport-components"));
        ((IItemHandlerModifiable) inventory.maids().backpackSource(player, player.getUUID(), maid.getUUID()).inventory()).setStackInSlot(0, input);
        nativeEvent = event -> {
            try {
                Object observed = event.getClass().getMethod("getMaid").invoke(event);
                if (observed == maid) {
                    nativeEvents++; boolean owned = NativeMaidTransportScope.active(); if (owned) ownedNativeEvents++;
                    if (cancelNative || !owned) { cancelledNativeEvents++; ((ICancellableEvent) event).setCanceled(true); }
                }
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("ACTUAL_WIRELESS_IO_EVENT_API_CHANGED", failure); }
        };
        for (String direction : List.of("MaidToChest", "ChestToMaid")) {
            Class<Event> eventClass = (Class<Event>) Class.forName(PREFIX + "api.event.MaidWirelessIOEvent$" + direction);
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, eventClass, nativeEvent);
        }
        bridge = new GameToolBridge(server, owner -> {
            try { return main(() -> {
                var actual = server.getPlayerList().getPlayer(UUID.fromString(owner));
                return actual == null ? new Actor(owner, false, false, 0) : Actor.player(actual.getUUID(), AiServerService.authenticated(actual), true, 0);
            }); } catch (Exception failed) { throw new CompletionException(failed); }
        }, name -> tools.get(name) != null, () -> false);
        result = CompletableFuture.supplyAsync(() -> {
            var report = new JsonObject(); long start = System.nanoTime();
            try { run(); report.addProperty("status", "PASSED"); }
            catch (Throwable failure) {
                failure.printStackTrace(); report.addProperty("status", "FAILED"); report.addProperty("error", failure.toString());
                try { report.add("nativeDiagnostics", main(this::diagnostics)); }
                catch (Exception unavailable) { report.addProperty("diagnosticFailure", unavailable.getClass().getSimpleName()); }
            }
            report.add("passedChecks", AiJson.GSON.toJsonTree(passed)); report.addProperty("durationMillis", (System.nanoTime() - start) / 1_000_000);
            report.addProperty("scope", "Actual installed maid/entity/worn bauble/native100tick/event and authenticated mock dedicated players. One barrel export plus native-event cancellation. No graphical clients, model, other storage/native additions or full-modpack claim.");
            return report;
        }, action -> Thread.ofPlatform().daemon(true).name("maid-wireless-validation").start(action));
    }
    private void run() throws Exception {
        transport.prepare(workspace);
        var selection = main(() -> inventory.selectMaidBackpack(actor, maid.getUUID())); var grants = inventory.grants(actor);
        var preview = grants.preview(actor, selection.candidate());
        maidGrant = grants.confirm(actor, preview.id(), preview.digest(), main(() -> inventory.confirmSelection(actor, selection)));
        // Independently select and approve insertion only for this exact bound target, not every nearby chest.
        var candidate = main(() -> inventory.blockCandidate(actor, target, Direction.UP, false, true, true));
        var blockPreview = grants.preview(actor, candidate);
        blockGrant = grants.confirm(actor, blockPreview.id(), blockPreview.digest(), main(() -> inventory.blockCandidate(actor, target, Direction.UP, false, true, true)));
        main(() -> { bauble.getItem().getClass().getMethod("setMode", ItemStack.class, boolean.class).invoke(null, bauble, false); return true; });
        try {
            var wrongDirection = context("An insertion-only box grant cannot become a source");
            expect(() -> transport.plan(wrongDirection, maid.getUUID(), maidGrant.id(), maidGrant.revision(), blockGrant.id(), blockGrant.revision()),
                    "source-and-destination-grants-are-independent-and-direction-bound");
        } finally { main(() -> { bauble.getItem().getClass().getMethod("setMode", ItemStack.class, boolean.class).invoke(null, bauble, true); return true; }); }
        var ctx = context("Move exactly the approved native filtered component-bearing cobblestone");
        var plan = transport.plan(ctx, maid.getUUID(), maidGrant.id(), maidGrant.revision(), blockGrant.id(), blockGrant.revision());
        check(plan.matchingSlots().equals(List.of(0)) && plan.moves().size() == 1, "complete-finite-native-item-filter-preserves-the-component-variant");
        var args = arguments(plan); var tool = tools.get("maid_transport_execute"); var call = new ModelMessage.ToolCall("maid-transport-" + plan.id(), tool.descriptor().name(), args.toString());
        check(new ToolPolicyEngine().check(actor, actor.owner(), tool.descriptor(), true, call, "").decision() == ToolPolicyEngine.Decision.APPROVAL,
                "native-transport-requires-fixed-workbench-approval");
        var actual = executeSaved(ctx, plan);
        check(actual.success() && actual.observations().get("settled").getAsBoolean(), "actual-native100tick-component-conservation-and-native-save");
        check(main(() -> inventory.maids().backpackSource(player, player.getUUID(), maid.getUUID()).inventory().getStackInSlot(0).isEmpty()
                && ((net.minecraft.world.Container) server.overworld().getBlockEntity(target)).getItem(0).getCount() == 4
                && ((net.minecraft.world.Container) server.overworld().getBlockEntity(target)).getItem(0).getHoverName().getString().equals("owned-transport-components")),
                "real-source-was-debited-and-destination-retains-exact-name-component");
        var duplicate = transport.execute(ctx, plan); check(!duplicate.success() && duplicate.errorCode().equals("MAID_TRANSPORT_ALREADY_RECORDED"), "duplicate-never-repeats-native-transfer");
        var foreignWorkspace = manager.getWorkspace(foreign); transport.prepare(foreignWorkspace);
        var foreignCtx = new AgentTool.Context(foreignWorkspace, foreign, new TaskControl(120000), UUID.randomUUID());
        expect(() -> transport.plan(foreignCtx, maid.getUUID(), maidGrant.id(), maidGrant.revision(), blockGrant.id(), blockGrant.revision()), "foreign-player-cannot-borrow-transport-inventory-or-grants");
        main(() -> { var handler = (IItemHandlerModifiable) inventory.maids().backpackSource(player, player.getUUID(), maid.getUUID()).inventory(); handler.setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 2)); return true; });
        var cancelledCtx = context("The native cancellation event must prevent physical transport");
        var cancelled = transport.plan(cancelledCtx, maid.getUUID(), maidGrant.id(), maidGrant.revision(), blockGrant.id(), blockGrant.revision());
        cancelNative = true; var refusal = executeSaved(cancelledCtx, cancelled); cancelNative = false;
        check(!refusal.success() && refusal.observations().get("settled").getAsBoolean()
                && main(() -> inventory.maids().backpackSource(player, player.getUUID(), maid.getUUID()).inventory().getStackInSlot(0).getCount() == 2),
                "real-native-cancellation-event-blocks-helper-without-fake-movement");
        check(transport.resourcesSettled(workspace, actor), "all-accepted-transport-effects-have-real-save-or-explicit-no-dispatch-evidence");
    }
    private AgentTool.Result executeSaved(AgentTool.Context initial, MaidTransportPlan plan) throws Exception {
        var args = arguments(plan); var original = tools.get("maid_transport_execute"); AgentTool current = original; var context = initial;
        var call = new ModelMessage.ToolCall("maid-transport-" + plan.id(), original.descriptor().name(), args.toString());
        for (int phase = 0; phase < 6; phase++) {
            try { return context.control().await(bridge.execute(current, context, args, ToolPolicyEngine.digest(actor.owner(), call), call), 20000); }
            catch (AgentTool.Deferred deferred) {
                lastWait = deferred.reason();
                if (deferred.reason().equals("WAITING_MAID_TRANSPORT_NATIVE_SAVE")) save();
                deferred.readiness().toCompletableFuture().get(20, TimeUnit.SECONDS);
                context = new AgentTool.Context(workspace, actor, new TaskControl(120000), initial.taskId());
                current = new AgentTool() {
                    @Override public Descriptor descriptor() { return original.descriptor(); }
                    @Override public Result execute(Context fresh, JsonObject ignored) throws Exception { return deferred.continuation().resume(fresh); }
                };
            }
        }
        throw new IllegalStateException("MAID_TRANSPORT_DEFERRED_LIMIT");
    }
    private void save() throws Exception {
        main(() -> { for (var level : server.getAllLevels()) level.save(null, true, false); server.getPlayerList().saveAll(); return true; });
        IOUtilities.waitUntilIOWorkerComplete();
    }
    private AgentTool.Context context(String text) throws Exception {
        var task = AgentTask.create(actor.owner(), UUID.randomUUID(), text); workspace.conversation().append(List.of(Draft.checkpoint(task)));
        return new AgentTool.Context(workspace, actor, new TaskControl(120000), task.id());
    }
    private JsonObject arguments(MaidTransportPlan plan) { var args = new JsonObject(); args.addProperty("planId", plan.id().toString()); args.addProperty("planHash", plan.hash()); return args; }
    private JsonObject diagnostics() {
        var data = new JsonObject(); data.addProperty("maidTicks", maid.tickCount); data.addProperty("serverTicks", server.getTickCount());
        data.addProperty("nativeEvents", nativeEvents); data.addProperty("ownedNativeEvents", ownedNativeEvents); data.addProperty("cancelledNativeEvents", cancelledNativeEvents);
        data.addProperty("lastWait", lastWait); var saves = inventory.nativeSaves(); data.addProperty("pendingReceipts", saves.pendingCount());
        data.addProperty("pendingPlayer", saves.pending(player)); data.addProperty("pendingStats", saves.pending(player.getStats()));
        data.addProperty("pendingAdvancements", saves.pending(player.getAdvancements())); data.addProperty("pendingMaid", saves.pending(maid));
        var block = server.overworld().getBlockEntity(target); var chunk = server.overworld().getChunkAt(target);
        data.addProperty("pendingContainer", block != null && saves.pending(block)); data.addProperty("pendingChunk", saves.pending(chunk));
        data.add("operations", AiJson.GSON.toJsonTree(transport.operations(workspace, actor).stream().map(op -> Map.of(
                "id", op.plan().id().toString(), "state", op.state().name(), "reason", op.reason(), "saved", !op.nativeProof().isEmpty())).toList()));
        return data;
    }
    private <T> T main(Callable<T> action) throws Exception {
        if (server.isSameThread()) return action.call(); var result = new CompletableFuture<T>();
        server.execute(() -> { try { result.complete(action.call()); } catch (Throwable failure) { result.completeExceptionally(failure); } }); return result.get(10, TimeUnit.SECONDS);
    }
    private ServerPlayer player() {
        var cookie = new CommonListenerCookie(new GameProfile(UUID.randomUUID(), "maidio_" + UUID.randomUUID().toString().substring(0, 7)), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
        var player = new ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND); channels.add(new EmbeddedChannel(connection)); NetworkRegistry.configureMockConnection(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie); ValidationHandshake.reply(player); ValidationHandshake.authenticateFixture(player);
        player.setGameMode(GameType.SURVIVAL); player.getInventory().clearContent(); player.setNoGravity(true); return player;
    }
    private void expect(Callable<?> action, String name) throws Exception {
        try { action.call(); } catch (Exception expected) { passed.add(name); return; } throw new IllegalStateException("EXPECTED_REFUSAL:" + name);
    }
    private void check(boolean value, String name) { if (!value) throw new IllegalStateException(name); passed.add(name); }
    public JsonObject tick() { inventory.tick(); dispatcher.tick(); transport.tick(); return result.isDone() ? result.join() : null; }
    @Override public void close() throws Exception {
        transport.close(); dispatcher.close(); transport.awaitStopped(10000); dispatcher.awaitEvidenceStopped(10000);
        NeoForge.EVENT_BUS.unregister(nativeEvent); inventory.close(); maid.discard(); server.getPlayerList().remove(player); server.getPlayerList().remove(other);
        channels.forEach(EmbeddedChannel::finishAndReleaseAll); manager.close();
    }
}
