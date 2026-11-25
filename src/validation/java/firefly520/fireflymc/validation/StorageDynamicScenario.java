package firefly520.fireflymc.validation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import firefly520.fireflymc.ai.AiServerService;
import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.processing.VirtualProcessingService;
import firefly520.fireflymc.ai.processing.advanced.AdvancedProcessingPlan;
import firefly520.fireflymc.ai.processing.advanced.AdvancedProcessingService;
import firefly520.fireflymc.ai.processing.advanced.StorageDynamicNativeScope;
import firefly520.fireflymc.ai.processing.advanced.StorageDynamicRecipes;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.security.ToolPolicyEngine;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.AgentTask;
import firefly520.fireflymc.ai.task.TaskControl;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Actual installed recipes through the production inventory, ledger, tool bridge and native save pump. */
public final class StorageDynamicScenario implements AutoCloseable {
    private static final String BACKPACK_WRAPPER = "net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper";
    private static final String STORAGE_WRAPPER = "net.p3pp3rf1y.sophisticatedstorage.item.StackStorageWrapper";
    private static final String STORAGE_STORE = "net.p3pp3rf1y.sophisticatedstorage.block.ItemContentsStorage";
    private final MinecraftServer server;
    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private final List<TaskControl> controls = new CopyOnWriteArrayList<>();
    private final List<String> passed = new CopyOnWriteArrayList<>();
    private final List<String> deferredWaits = new CopyOnWriteArrayList<>();
    private final JsonArray cases = new JsonArray();
    private final ServerPlayer owner;
    private final Actor actor;
    private final WorkspaceManager manager;
    private final WorkspaceManager.Workspace workspace;
    private final MaterialLedger ledger;
    private final MaterialInventoryService inventory;
    private final VirtualProcessingService processing;
    private final AdvancedProcessingService advanced;
    private final ToolRegistry tools = new ToolRegistry();
    private final GameToolBridge bridge;
    private final AtomicInteger craftedEvents = new AtomicInteger();
    private final Consumer<PlayerEvent.ItemCraftedEvent> craftedListener;
    private final CompletableFuture<JsonObject> result;
    private volatile boolean closed;
    private int ticks;

    private record Fixture(String kind, RecipeHolder<?> recipe, List<MaterialStack> inputs, UUID sourceUuid,
                           MaterialStack nested, CompoundTag beforeContents, Set<UUID> beforeStorageIds, String name,
                           int carrierInputSlot, BlockPos carrierChest, UUID carrierGrant, Map<UUID, CompoundTag> beforeStorageContents) { }

    public StorageDynamicScenario(MinecraftServer server) throws Exception {
        if (!server.isDedicatedServer()) throw new IllegalStateException("DEDICATED_STORAGE_DYNAMIC_SCENARIO_REQUIRED");
        this.server = server;
        owner = player(); actor = Actor.player(owner.getUUID(), true, true, 0);
        Path root = Files.createTempDirectory(server.getWorldPath(LevelResource.ROOT).toAbsolutePath(), "ai4-storage-dynamic-");
        manager = new WorkspaceManager(root); workspace = manager.getWorkspace(actor); ledger = new MaterialLedger(root);
        inventory = new MaterialInventoryService(server, AiServerService::authenticated, new InstalledPermissionAdapters(server));
        inventory.bind(actor.owner(), new MaterialSourceGrants(workspace, System::currentTimeMillis));
        inventory.bindPreferences(actor.owner(), new MaterialPreferences(workspace));
        processing = new VirtualProcessingService(server, inventory, ledger, () -> 1);
        advanced = new AdvancedProcessingService(server, inventory, ledger, processing, () -> 1); advanced.register(tools);
        bridge = new GameToolBridge(server, identity -> {
            try { return main(() -> {
                var current = server.getPlayerList().getPlayer(UUID.fromString(identity));
                return current == null ? new Actor(identity, false, false, 0)
                        : Actor.player(current.getUUID(), AiServerService.authenticated(current), true, 0);
            }); } catch (Exception failure) { throw new CompletionException(failure); }
        }, name -> tools.get(name) != null, () -> closed);
        craftedListener = event -> { if (event.getEntity().getUUID().equals(owner.getUUID())) craftedEvents.incrementAndGet(); };
        NeoForge.EVENT_BUS.addListener(PlayerEvent.ItemCraftedEvent.class, craftedListener);
        result = CompletableFuture.supplyAsync(() -> {
            long started = System.nanoTime(); var report = new JsonObject();
            try { run(); report.addProperty("status", "PASSED"); }
            catch (Throwable failure) {
                failure.printStackTrace(); report.addProperty("status", "FAILED"); report.addProperty("error", failure.toString());
                report.add("materialOperations", AiJson.GSON.toJsonTree(ledger.list(actor)));
            }
            report.add("cases", cases); report.add("passedChecks", AiJson.GSON.toJsonTree(passed));
            report.add("deferredWaits", AiJson.GSON.toJsonTree(deferredWaits));
            report.addProperty("durationMillis", (System.nanoTime() - started) / 1_000_000);
            report.addProperty("fixtureSaveRequests", 0); report.addProperty("usesProductionNativeSavePump", true);
            report.addProperty("craftedEvents", craftedEvents.get());
            report.addProperty("conversationId", workspace.primaryConversationId().toString());
            report.add("nativeSaveDispatch", AiJson.GSON.toJsonTree(inventory.nativeSaves().dispatchMetrics()));
            report.addProperty("scope", "Three actual installed Storage recipes through advanced plan/execute, GameToolBridge, authenticated mock network player, production material ledger and canonical native saves in an isolated dedicated world. Fixture persists actual bridge receipts in the permanent conversation. No full AgentRuntime, provider, graphical client, restart or full-modpack claim.");
            return report;
        }, action -> Thread.ofPlatform().daemon(true).name("storage-dynamic-validation").start(action));
    }

    private void run() throws Exception {
        check(main(() -> AiServerService.authenticated(owner)), "real-firefly-authenticated-survival-player");
        runCase("backpack", "BackpackUpgradeRecipe");
        runCase("shared_storage", "StorageTierUpgradeRecipe");
        runCase("vanilla_shulker", "ShulkerBoxFromVanillaShapelessRecipe");
        check(ledger.settled(actor.owner()), "all-three-operations-have-terminal-and-positive-native-save-receipts");
    }

    private void runCase(String kind, String nativeClass) throws Exception {
        long started = System.nanoTime(); var details = new JsonObject(); cases.add(details); details.addProperty("case", kind);
        Fixture fixture = main(() -> prepare(kind, nativeClass));
        details.addProperty("recipeId", fixture.recipe().id().toString());
        details.addProperty("recipeClass", fixture.recipe().value().getClass().getName());
        details.addProperty("inputLayout", StorageDynamicRecipes.profile(fixture.recipe().value()).input().name());
        details.add("storageIdsBefore", uuidList(fixture.beforeStorageIds()));
        details.addProperty("carrierSource", fixture.carrierGrant() == null ? "player" : "owner-confirmed-chest");
        if (fixture.carrierGrant() != null) details.addProperty("carrierSourceGrant", fixture.carrierGrant().toString());
        var ctx = context("Verify actual " + nativeClass + " with real nested materials");
        var beforeInventory = main(this::playerItems); int eventsBefore = craftedEvents.get();
        var planned = invoke("advanced_processing_plan", ctx, bindings(ctx, fixture), "storage-plan-" + ctx.taskId());
        requireSuccess(planned, kind + "/real-recipe-plan");
        var plan = advanced.load(workspace, actor, UUID.fromString(planned.observations().get("planId").getAsString()),
                planned.observations().get("planHash").getAsString());
        details.addProperty("planId", plan.id().toString()); details.addProperty("planHash", plan.hash());
        check(plan.intact() && plan.conversationId().equals(workspace.primaryConversationId()), kind + "/fixed-plan-in-permanent-conversation");
        check(plan.recipeId().equals(fixture.recipe().id().toString()) && plan.inputs().equals(fixture.inputs()), kind + "/actual-recipe-and-component-bound-inputs");
        if (!kind.equals("backpack")) check(plan.adapter().equals("advanced:storage-shared-native-v1")
                        && "true".equals(plan.context().get("requiresNativeAssemblyCommit")), kind + "/production-shared-native-commit-selected");
        if (!kind.equals("backpack")) details.add("storageIdsAfterPlan", uuidList(main(this::storageIds)));
        check(main(this::playerItems).equals(beforeInventory) && craftedEvents.get() == eventsBefore, kind + "/preview-does-not-consume-or-fire-crafting-event");
        if (fixture.carrierChest() != null) check(main(() -> ItemSnapshotCodec.capture(carrierChest(fixture.carrierChest()).getItem(0), owner.registryAccess()))
                        .equals(fixture.inputs().get(fixture.carrierInputSlot())), kind + "/preview-preserves-owner-authorized-carrier-slot");
        UUID outputUuid = main(() -> plannedOutput(plan).get(uuidComponent()));
        check(outputUuid != null && (fixture.sourceUuid() == null || fixture.sourceUuid().equals(outputUuid)), kind + "/planned-output-retains-or-reserves-one-native-uuid");
        details.addProperty("outputUuid", outputUuid.toString());
        if (fixture.sourceUuid() != null) check(main(() -> contents(kind, fixture.sourceUuid())).equals(fixture.beforeContents()), kind + "/preview-preserves-real-native-contents");
        if (!kind.equals("backpack")) check(main(this::storageIds).equals(fixture.beforeStorageIds()), kind + "/preview-creates-no-real-shared-entry");

        JsonObject args = execution(plan);
        var completed = invoke("advanced_processing_execute", ctx, args, "storage-execute-" + plan.id());
        requireSuccess(completed, kind + "/native-processing-delivered");
        if (!kind.equals("backpack")) details.add("storageIdsAfterService", uuidList(main(this::storageIds)));
        var operation = ledger.get(actor, plan.id());
        check(operation.state() == MaterialLedger.State.COMPLETED && operation.saved() && operation.inputsSaved()
                        && !operation.nativeSaves().isEmpty() && operation.nativeSaves().stream().allMatch(save -> save.receipt().matches("[0-9a-f]{64}")),
                kind + "/real-ledger-and-every-canonical-save-acknowledged");
        check(operation.claims().stream().mapToInt(MaterialLedger.Claim::extracted).sum()
                        == fixture.inputs().stream().mapToInt(MaterialStack::count).sum()
                        && operation.escrow().stream().allMatch(MaterialStack::empty) && operation.remainingProducts().isEmpty(),
                kind + "/exact-real-inputs-extracted-and-custody-settled");
        check(main(this::playerItems).equals(totals(plan.products())), kind + "/player-receives-only-fixed-products-and-remainders");
        if (fixture.carrierChest() != null) check(main(() -> carrierChest(fixture.carrierChest()).isEmpty()), kind + "/owner-authorized-carrier-chest-is-really-extracted-empty");
        check(craftedEvents.get() == eventsBefore + 1, kind + "/one-actual-item-crafted-event");
        check(main(() -> nestedOutput(kind, outputUuid).equals(List.of(fixture.nested()))), kind + "/native-output-retains-seven-diamonds-and-components");
        if (!kind.equals("backpack")) details.add("storageIdsAfterReadOnlyInspection", uuidList(main(this::storageIds)));
        if (!fixture.name().isEmpty()) check(main(() -> output(outputUuid).getHoverName().getString().equals(fixture.name())), kind + "/carrier-name-preserved");
        Set<UUID> expectedIds = new HashSet<>(fixture.beforeStorageIds());
        if (!kind.equals("backpack")) {
            UUID reserved = StorageDynamicNativeScope.reserved(plan.id());
            int nativeEntries = Integer.parseInt(plan.context().get("sharedEntries"));
            int sourceEntries = fixture.sourceUuid() == null ? 0 : 1;
            check(nativeEntries == sourceEntries || nativeEntries == sourceEntries + 1, kind + "/native-write-set-has-at-most-one-reserved-new-entry");
            if (nativeEntries == sourceEntries + 1) expectedIds.add(reserved);
            if (kind.equals("vanilla_shulker")) check(outputUuid.equals(reserved) && nativeEntries == 1, kind + "/migration-output-is-its-one-bound-reserved-entry");
            details.addProperty("operationReservedUuid", reserved.toString()); details.addProperty("predictedSharedEntryCount", nativeEntries);
            details.add("storageIdsExpected", uuidList(expectedIds));
            check(main(this::storageIds).equals(expectedIds), kind + "/native-migration-creates-exactly-its-bound-entry-set");
            check(main(() -> unrelatedStorageUnchanged(fixture)), kind + "/native-migration-preserves-every-unrelated-existing-entry");
        }
        var deliveredInventory = main(this::playerItems); var deliveredContents = main(() -> contents(kind, outputUuid));
        long revision = operation.revision();
        var duplicate = invoke("advanced_processing_execute", ctx, args, "storage-duplicate-" + plan.id());
        check(!duplicate.success() && duplicate.errorCode().equals("ADVANCED_ALREADY_RECORDED"), kind + "/duplicate-execution-is-recorded-without-replay");
        check(main(this::playerItems).equals(deliveredInventory) && craftedEvents.get() == eventsBefore + 1
                        && main(() -> contents(kind, outputUuid)).equals(deliveredContents) && ledger.get(actor, plan.id()).revision() == revision,
                kind + "/duplicate-keeps-materials-uuid-contents-and-event-count");
        if (!kind.equals("backpack")) check(main(this::storageIds).equals(expectedIds), kind + "/duplicate-creates-no-second-shared-inventory");
        if (!kind.equals("backpack")) check(main(() -> unrelatedStorageUnchanged(fixture)), kind + "/duplicate-keeps-unrelated-storage-entries");
        if (fixture.carrierChest() != null) check(main(() -> carrierChest(fixture.carrierChest()).isEmpty()), kind + "/duplicate-leaves-authorized-source-empty");
        Path conversationFile = workspace.directory().resolve("conversation").resolve(workspace.primaryConversationId() + ".jsonl");
        var persisted = new ConversationStore(conversationFile).getSnapshot(0).events();
        check(persisted.stream().anyMatch(event -> ctx.taskId().equals(event.taskId()) && "advanced_processing_plan".equals(event.kind())
                        && plan.id().toString().equals(event.data().get("planId").getAsString()))
                        && persisted.stream().anyMatch(event -> ctx.taskId().equals(event.taskId()) && "tool_result".equals(event.kind())
                        && ("storage-execute-" + plan.id()).equals(event.toolTransactionId())
                        && AiJson.GSON.toJson(completed).equals(event.data().get("fullResult").getAsString())),
                kind + "/actual-completion-receipt-read-back-from-permanent-main-conversation");
        var task = workspace.conversation().task(ctx.taskId());
        workspace.conversation().append(List.of(Draft.checkpoint(task.update(AgentTask.State.COMPLETED, "STORAGE_DYNAMIC_VALIDATED"))));
        details.addProperty("ledgerState", operation.state().name()); details.addProperty("ledgerSaved", operation.saved());
        details.add("nativeSaves", AiJson.GSON.toJsonTree(operation.nativeSaves()));
        details.addProperty("craftedEventDelta", craftedEvents.get() - eventsBefore); details.addProperty("status", "PASSED");
        details.addProperty("durationMillis", (System.nanoTime() - started) / 1_000_000);
    }

    private Fixture prepare(String kind, String simpleName) throws Exception {
        owner.getInventory().clearContent();
        var recipe = server.getRecipeManager().getRecipes().stream()
                .filter(holder -> holder.value().getClass().getSimpleName().equals(simpleName) && StorageDynamicRecipes.supports(holder.value()))
                .filter(holder -> !kind.equals("shared_storage") || holder.id().toString().equals("sophisticatedstorage:copper_shulker_box"))
                .sorted(Comparator.comparing(holder -> holder.id().toString())).findFirst()
                .orElseThrow(() -> new IllegalStateException("ACTUAL_STORAGE_RECIPE_MISSING:" + simpleName));
        var inputs = nativeInputs(recipe);
        var source = inputs.stream().filter(stack -> !stack.isEmpty() && source(kind, stack)).findFirst().orElseThrow();
        int carrierInputSlot = inputs.indexOf(source);
        if (kind.equals("shared_storage") && !source.getItem().getClass().getName().equals("net.p3pp3rf1y.sophisticatedstorage.item.ShulkerBoxItem"))
            throw new IllegalStateException("ACTUAL_SHARED_STORAGE_SHULKER_SOURCE_REQUIRED");
        var diamond = new ItemStack(Items.DIAMOND, 7); diamond.set(DataComponents.CUSTOM_NAME, Component.literal("same seven native diamonds"));
        String name = kind.equals("vanilla_shulker") ? "" : "owned native " + kind;
        if (!name.isEmpty()) source.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        UUID sourceUuid = null;
        if (kind.equals("vanilla_shulker")) source.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(diamond)));
        else {
            if (kind.equals("backpack")) source.set(uuidComponent(), UUID.randomUUID());
            Object wrapper = wrapper(kind, source);
            if (!kind.equals("backpack")) call(wrapper, "ensureContentsUuid", new Class<?>[0]);
            IItemHandler handler = (IItemHandler) call(wrapper, "getInventoryHandler", new Class<?>[0]);
            if (!handler.insertItem(0, diamond, false).isEmpty()) throw new IllegalStateException("NATIVE_NESTED_DIAMOND_INSERT_FAILED");
            sourceUuid = source.get(uuidComponent());
            if (sourceUuid == null) throw new IllegalStateException("NATIVE_SOURCE_UUID_REQUIRED");
        }
        BlockPos carrierChest = null; UUID carrierGrant = null;
        if (kind.equals("backpack")) {
            // Own-inventory backpack hosts are deliberately protected. This fixture models the
            // owner explicitly authorizing a real container that holds the intended recipe input.
            carrierChest = owner.blockPosition().offset(2, 1, 0).immutable();
            if (!owner.serverLevel().isEmptyBlock(carrierChest) || owner.serverLevel().getBlockEntity(carrierChest) != null
                    || !owner.serverLevel().isEmptyBlock(carrierChest.above()))
                throw new IllegalStateException("ISOLATED_CARRIER_CHEST_POSITION_OCCUPIED");
            if (!owner.serverLevel().setBlock(carrierChest, Blocks.CHEST.defaultBlockState(), 3))
                throw new IllegalStateException("ACTUAL_CARRIER_CHEST_PLACEMENT_FAILED");
            var chest = carrierChest(carrierChest); chest.setItem(0, source.copy()); chest.setChanged();
            var grants = inventory.grants(actor);
            var preview = grants.preview(actor, inventory.blockCandidate(actor, carrierChest, Direction.UP, true, false, false));
            var granted = grants.confirm(actor, preview.id(), preview.digest(),
                    inventory.blockCandidate(actor, carrierChest, Direction.UP, true, false, true));
            carrierGrant = granted.id();
            check(granted.source().extract() && !granted.source().insert() && granted.source().members().size() == 1
                            && granted.source().members().getFirst().identity() != null,
                    kind + "/real-owner-confirmed-finite-container-grant");
        }
        var installedInputs = new ArrayList<MaterialStack>();
        for (int slot = 0; slot < inputs.size(); slot++) {
            boolean inChest = carrierChest != null && slot == carrierInputSlot;
            owner.getInventory().setItem(slot, inChest ? ItemStack.EMPTY : inputs.get(slot).copy());
            var actual = inChest ? carrierChest(carrierChest).getItem(0) : owner.getInventory().getItem(slot);
            var captured = ItemSnapshotCodec.capture(actual, owner.registryAccess());
            if (!captured.equals(ItemSnapshotCodec.capture(inputs.get(slot), owner.registryAccess())))
                throw new IllegalStateException("ACTUAL_STORAGE_INPUT_COMPONENTS_CHANGED_ON_INSTALL:" + kind + ":" + slot);
            installedInputs.add(captured);
        }
        owner.getInventory().setChanged();
        var beforeStorage = kind.equals("backpack") ? Map.<UUID, CompoundTag>of() : storageSnapshot();
        return new Fixture(kind, recipe, List.copyOf(installedInputs),
                sourceUuid, ItemSnapshotCodec.capture(diamond, owner.registryAccess()), sourceUuid == null ? null : contents(kind, sourceUuid),
                beforeStorage.keySet(), name, carrierInputSlot, carrierChest, carrierGrant, beforeStorage);
    }

    private ChestBlockEntity carrierChest(BlockPos pos) {
        if (!(owner.serverLevel().getBlockEntity(pos) instanceof ChestBlockEntity chest))
            throw new IllegalStateException("ACTUAL_CARRIER_CHEST_MISSING");
        return chest;
    }

    private static List<ItemStack> nativeInputs(RecipeHolder<?> holder) {
        var profile = Objects.requireNonNull(StorageDynamicRecipes.profile(holder.value()));
        var inputs = new ArrayList<>(Collections.nCopies(profile.inputSize(), ItemStack.EMPTY));
        var ingredients = holder.value().getIngredients();
        if (holder.value() instanceof ShapedRecipe shaped) {
            for (int y = 0; y < shaped.getHeight(); y++) for (int x = 0; x < shaped.getWidth(); x++)
                inputs.set(y * 3 + x, ingredient(ingredients.get(y * shaped.getWidth() + x)));
        } else {
            if (ingredients.size() > inputs.size()) throw new IllegalStateException("ACTUAL_STORAGE_INPUT_LAYOUT_TOO_LARGE");
            for (int slot = 0; slot < ingredients.size(); slot++) inputs.set(slot, ingredient(ingredients.get(slot)));
        }
        return inputs;
    }
    private static ItemStack ingredient(Ingredient ingredient) {
        if (ingredient.isEmpty()) return ItemStack.EMPTY;
        if (ingredient.getItems().length == 0) throw new IllegalStateException("ACTUAL_STORAGE_INGREDIENT_HAS_NO_INSTALLED_CANDIDATE");
        return ingredient.getItems()[0].copyWithCount(1);
    }
    private static boolean source(String kind, ItemStack stack) {
        if (kind.equals("vanilla_shulker")) return stack.getItem() instanceof BlockItem block && block.getBlock() instanceof ShulkerBoxBlock;
        try { return Class.forName(kind.equals("backpack") ? "net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem"
                : "net.p3pp3rf1y.sophisticatedstorage.item.StorageBlockItem").isInstance(stack.getItem()); }
        catch (ClassNotFoundException failure) { throw new IllegalStateException("ACTUAL_STORAGE_SOURCE_CLASS_MISSING", failure); }
    }
    private JsonObject bindings(AgentTool.Context ctx, Fixture fixture) throws Exception {
        var snapshot = ctx.control().await(inventory.scan(actor), 30000); var used = new HashMap<String, Integer>();
        var args = new JsonObject(); args.addProperty("recipeId", fixture.recipe().id().toString()); var inputs = new JsonArray();
        for (int slot = 0; slot < fixture.inputs().size(); slot++) {
            MaterialStack expected = fixture.inputs().get(slot); if (expected.empty()) continue;
            boolean inChest = fixture.carrierGrant() != null && slot == fixture.carrierInputSlot();
            String sourceId = inChest ? fixture.carrierGrant().toString() : "player";
            int nativeSlot = inChest ? 0 : slot;
            var found = snapshot.rows().stream().filter(row -> row.sourceId().equals(sourceId) && row.slot() == nativeSlot
                    && row.stack().equals(expected) && row.extractableCount() - used.getOrDefault(row.key(), 0) >= expected.count()).findFirst();
            if (found.isEmpty()) {
                var diagnostic = new JsonObject(); diagnostic.addProperty("case", fixture.kind()); diagnostic.addProperty("roleSlot", slot);
                diagnostic.addProperty("sourceId", sourceId); diagnostic.addProperty("nativeSlot", nativeSlot);
                diagnostic.add("expected", AiJson.GSON.toJsonTree(expected));
                diagnostic.add("actualSlot", AiJson.GSON.toJsonTree(main(() -> ItemSnapshotCodec.capture(inChest
                        ? carrierChest(fixture.carrierChest()).getItem(0) : owner.getInventory().getItem(nativeSlot), owner.registryAccess()))));
                diagnostic.add("scanRows", AiJson.GSON.toJsonTree(snapshot.rows())); diagnostic.add("scanRefusals", AiJson.GSON.toJsonTree(snapshot.refusals()));
                throw new IllegalStateException("REAL_AUTHORIZED_INGREDIENT_NOT_AVAILABLE:" + diagnostic);
            }
            var stock = found.get();
            used.merge(stock.key(), expected.count(), Math::addExact);
            var bind = new JsonObject(); bind.addProperty("slot", slot); bind.addProperty("sourceKey", stock.key()); bind.addProperty("count", expected.count()); inputs.add(bind);
        }
        args.add("inputs", inputs); return args;
    }

    private AgentTool.Result invoke(String name, AgentTool.Context ctx, JsonObject args, String callId) throws Exception {
        AgentTool original = tools.get(name), current = original;
        var call = new ModelMessage.ToolCall(callId, name, args.toString());
        String approval = original.descriptor().risk().requiresApproval() ? ToolPolicyEngine.digest(actor.owner(), call) : "";
        if (original.descriptor().risk().requiresApproval()) check(new ToolPolicyEngine().check(actor, actor.owner(), original.descriptor(), true, call, "").decision()
                == ToolPolicyEngine.Decision.APPROVAL, callId + "/fixed-call-requires-approval");
        var start = new JsonObject(); start.addProperty("name", name); start.addProperty("risk", original.descriptor().risk().name());
        start.addProperty("inputDigest", ToolPolicyEngine.digest(actor.owner(), call));
        workspace.conversation().append(List.of(new Draft(ctx.taskId(), "tool_start", null, start, callId)));
        for (int phase = 0; phase < 24; phase++) {
            ctx.control().check();
            try {
                var actual = bridge.execute(current, ctx, args, approval, call).get(10, TimeUnit.SECONDS);
                var journal = new JsonObject(); journal.addProperty("name", name); journal.addProperty("success", actual.success());
                journal.addProperty("errorCode", actual.errorCode()); journal.addProperty("finishedAt", System.currentTimeMillis());
                String full = AiJson.GSON.toJson(actual); journal.addProperty("fullResult", full);
                workspace.conversation().append(List.of(Draft.message(ctx.taskId(), ModelMessage.result(callId, full), callId),
                        new Draft(ctx.taskId(), "tool_result", null, journal, callId)));
                return actual;
            } catch (ExecutionException failed) {
                if (!(failed.getCause() instanceof AgentTool.Deferred waiting)) throw failed;
                deferredWaits.add(callId + ":" + waiting.reason());
                // The global AiServerEvents Post tick pumps the existing NativeSaveObserver.
                // This fixture neither requests a save nor replaces the accepted call's control/deadline.
                while (!waiting.readiness().toCompletableFuture().isDone()) { ctx.control().check(); Thread.sleep(20); }
                ctx.control().check(); waiting.readiness().toCompletableFuture().get(10, TimeUnit.SECONDS);
                current = new DefinedTool(original.descriptor(), (fresh, ignored) -> {
                    if (fresh.control() != ctx.control() || !Objects.equals(fresh.taskId(), ctx.taskId())) throw new IllegalStateException("STORAGE_VALIDATION_ACCEPTED_CONTROL_CHANGED");
                    return waiting.continuation().resume(fresh);
                });
            }
        }
        throw new IllegalStateException("STORAGE_DYNAMIC_DEFERRED_PHASE_BUDGET");
    }

    private AgentTool.Context context(String prompt) throws Exception {
        var task = AgentTask.create(actor.owner(), UUID.randomUUID(), prompt);
        workspace.conversation().append(List.of(Draft.checkpoint(task)));
        var control = new TaskControl(120000); controls.add(control); return new AgentTool.Context(workspace, actor, control, task.id());
    }
    private static JsonObject execution(AdvancedProcessingPlan plan) {
        var args = new JsonObject(); args.addProperty("planId", plan.id().toString()); args.addProperty("planHash", plan.hash()); return args;
    }
    private ItemStack plannedOutput(AdvancedProcessingPlan plan) {
        return plan.products().stream().map(stack -> ItemSnapshotCodec.restore(stack, owner.registryAccess()))
                .filter(stack -> stack.has(uuidComponent())).findFirst().orElseThrow();
    }
    private ItemStack output(UUID uuid) {
        return owner.getInventory().items.stream().filter(stack -> uuid.equals(stack.get(uuidComponent()))).findFirst().orElseThrow();
    }
    private List<MaterialStack> nestedOutput(String kind, UUID uuid) throws Exception {
        // Decode a copied native payload. Reopening an already-cached live StackStorageWrapper can
        // allocate a fresh UUID while loadData resets its fields; a fixture inspection must not do that.
        CompoundTag stored = contents(kind, uuid);
        CompoundTag payload = kind.equals("backpack") ? stored : stored.getCompound("storageWrapper").getCompound("contents");
        var handler = new ItemStackHandler(); handler.deserializeNBT(owner.registryAccess(), payload.getCompound("inventory").copy());
        var nested = new ArrayList<MaterialStack>();
        for (int slot = 0; slot < handler.getSlots(); slot++) if (!handler.getStackInSlot(slot).isEmpty())
            nested.add(ItemSnapshotCodec.capture(handler.getStackInSlot(slot), owner.registryAccess()));
        return List.copyOf(nested);
    }
    private Object wrapper(String kind, ItemStack stack) throws Exception {
        return kind.equals("backpack") ? callStatic(BACKPACK_WRAPPER, "fromStack", new Class<?>[]{ItemStack.class}, stack)
                : callStatic(STORAGE_WRAPPER, "fromStack", new Class<?>[]{HolderLookup.Provider.class, ItemStack.class}, owner.registryAccess(), stack);
    }
    private CompoundTag contents(String kind, UUID uuid) throws Exception {
        Object store = callStatic(kind.equals("backpack") ? "net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage" : STORAGE_STORE, "get", new Class<?>[0]);
        if (!kind.equals("backpack") && !(boolean) call(store, "has", new Class<?>[]{UUID.class}, uuid)) throw new IllegalStateException("ACTUAL_NATIVE_STORAGE_ENTRY_MISSING");
        return ((CompoundTag) call(store, kind.equals("backpack") ? "getOrCreateBackpackContents" : "getOrCreateStorageContents", new Class<?>[]{UUID.class}, uuid)).copy();
    }
    @SuppressWarnings("unchecked") private Set<UUID> storageIds() throws Exception {
        return storageSnapshot().keySet();
    }
    private static JsonArray uuidList(Collection<UUID> ids) {
        var values = new JsonArray(); ids.stream().map(UUID::toString).sorted().forEach(values::add); return values;
    }
    @SuppressWarnings("unchecked") private Map<UUID, CompoundTag> storageSnapshot() throws Exception {
        Object store = callStatic(STORAGE_STORE, "get", new Class<?>[0]);
        var field = store.getClass().getDeclaredField("storageContents");
        if (!field.trySetAccessible()) throw new IllegalStateException("ACTUAL_STORAGE_CONTENTS_FIELD_UNAVAILABLE");
        var snapshot = new HashMap<UUID, CompoundTag>();
        ((Map<UUID, CompoundTag>) field.get(store)).forEach((uuid, contents) -> snapshot.put(uuid, contents.copy()));
        return Map.copyOf(snapshot);
    }
    private boolean unrelatedStorageUnchanged(Fixture fixture) throws Exception {
        var current = storageSnapshot();
        return fixture.beforeStorageContents().entrySet().stream()
                .filter(entry -> !entry.getKey().equals(fixture.sourceUuid()))
                .allMatch(entry -> entry.getValue().equals(current.get(entry.getKey())));
    }
    @SuppressWarnings("unchecked") private static DataComponentType<UUID> uuidComponent() {
        try { return (DataComponentType<UUID>) ((Supplier<?>) Class.forName("net.p3pp3rf1y.sophisticatedcore.init.ModCoreDataComponents").getField("STORAGE_UUID").get(null)).get(); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("ACTUAL_STORAGE_UUID_COMPONENT_MISSING", failure); }
    }
    private static Object callStatic(String type, String method, Class<?>[] parameters, Object... arguments) throws Exception {
        return Class.forName(type).getMethod(method, parameters).invoke(null, arguments);
    }
    private static Object call(Object target, String method, Class<?>[] parameters, Object... arguments) throws Exception {
        return target.getClass().getMethod(method, parameters).invoke(target, arguments);
    }
    private Map<String, Long> playerItems() {
        var items = new ArrayList<MaterialStack>();
        for (int slot = 0; slot < owner.getInventory().getContainerSize(); slot++) items.add(ItemSnapshotCodec.capture(owner.getInventory().getItem(slot), owner.registryAccess()));
        return totals(items);
    }
    private static Map<String, Long> totals(List<MaterialStack> stacks) {
        var totals = new TreeMap<String, Long>();
        for (var stack : stacks) if (!stack.empty()) totals.merge(stack.itemJson(), (long) stack.count(), Math::addExact);
        return Map.copyOf(totals);
    }
    private void requireSuccess(AgentTool.Result actual, String name) {
        if (!actual.success()) throw new IllegalStateException(name + ":" + actual.errorCode() + ":" + actual.observations()); check(true, name);
    }
    private void check(boolean condition, String name) {
        if (!condition) throw new IllegalStateException(name); passed.add(name);
    }
    private <T> T main(Callable<T> work) throws Exception {
        var future = new CompletableFuture<T>();
        server.execute(() -> { try { future.complete(work.call()); } catch (Throwable failure) { future.completeExceptionally(failure); } });
        return future.get(10, TimeUnit.SECONDS);
    }
    private ServerPlayer player() {
        UUID id = UUID.randomUUID();
        var cookie = new CommonListenerCookie(new GameProfile(id, "storage_" + id.toString().substring(0, 8)), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
        var player = new ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND); channels.add(new EmbeddedChannel(connection)); NetworkRegistry.configureMockConnection(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie); ValidationHandshake.reply(player); ValidationHandshake.authenticateFixture(player);
        player.setGameMode(GameType.SURVIVAL); player.getInventory().clearContent(); player.setNoGravity(true); return player;
    }
    public JsonObject tick() {
        inventory.tick(); processing.tick(); advanced.tick(); ticks++;
        if (!result.isDone()) return null;
        var report = result.join(); report.addProperty("ticks", ticks); return report;
    }
    @Override public void close() throws Exception {
        closed = true; controls.forEach(control -> control.stop("VALIDATION_CLOSING")); advanced.close(); inventory.close(); processing.close();
        try {
            if (!processing.awaitEvidenceStopped(10000)) throw new IllegalStateException("STORAGE_DYNAMIC_EVIDENCE_SHUTDOWN_TIMEOUT");
        } finally {
            NeoForge.EVENT_BUS.unregister(craftedListener); server.getPlayerList().remove(owner);
            try { IOUtilities.waitUntilIOWorkerComplete(); }
            finally { try { channels.forEach(EmbeddedChannel::finishAndReleaseAll); } finally { manager.close(); } }
        }
    }
}
