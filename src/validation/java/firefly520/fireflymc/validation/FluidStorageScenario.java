package firefly520.fireflymc.validation;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import firefly520.fireflymc.ai.AiServerService;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Actual installed tank capabilities, dedicated gates and native saves; no model or graphical client. */
public final class FluidStorageScenario implements AutoCloseable {
    private final MinecraftServer server;
    private final JsonArray results = new JsonArray();
    private final List<Case> cases = new ArrayList<>();
    private int next;
    private CompletableFuture<JsonObject> running;
    private final JsonArray inputSaveChecks = new JsonArray();
    private double maximumFluidSliceMillis;
    private final long started = System.nanoTime();
    private record Case(String name, Checked action, boolean asynchronous) {
        Case(String name, Checked action) { this(name, action, false); }
    }
    private interface Checked { void run() throws Exception; }
    public FluidStorageScenario(MinecraftServer server) {
        this.server = server;
        require(server.isDedicatedServer(), "DEDICATED_FLUID_STORAGE_REQUIRED");
        require(ModList.get().isLoaded("sophisticatedbackpacks"), "ACTUAL_BACKPACK_MOD_REQUIRED");
        cases.add(new Case("separate_fluid_grant_no_implicit_item_permission", this::authorization));
        cases.add(new Case("native_partial_drain_and_single_use_return_preserve_exact_amount", this::drainReturn));
        cases.add(new Case("multiple_tanks_are_one_backend_not_duplicate_sources", this::multipleTanks));
        cases.add(new Case("same_fluid_different_components_remain_separate", this::components));
        cases.add(new Case("moving_host_invalidates_prepared_native_transfer", this::movement));
        cases.add(new Case("revocation_and_other_player_cannot_use_prepared_grant", this::revocation));
        cases.add(new Case("duplicate_uuid_and_unsafe_upgrades_refused", this::unsafe));
        cases.add(new Case("native_tank_container_automation_is_not_silently_consumed", this::automation));
        cases.add(new Case("full_tank_never_voids_returned_fluid", this::full));
        cases.add(new Case("actual_saved_data_and_player_file_both_required", this::save));
        cases.add(new Case("placed_backpack_uses_same_uuid_backend_and_native_side_capability", this::placed));
        cases.add(new Case("privacy_epoch_cancels_partly_scanned_sources", this::privacy));
        cases.add(new Case("server_authentication_and_survival_are_rechecked_before_native_call", this::gates));
        cases.add(new Case("native_fluid_custody_saved_receipts_reload_reconcile_and_exact_return", this::ledger));
        cases.add(new Case("shutdown_completes_queued_off_thread_reads_before_another_server_tick", this::queuedReadShutdown));
        cases.add(new Case("another_player_alias_cannot_drain_a_stale_cache_of_same_native_uuid", this::crossPlayerAlias));
        cases.add(new Case("actual_item_fluid_mixed_commit_and_delivery_conserves_native_bucket", this::mixedBucket, true));
        cases.add(new Case("native_bucket_decant_creates_only_its_real_fluid_and_delivers_after_fresh_authorization", this::mixedFluidOutput, true));
        cases.add(new Case("independent_item_and_fluid_grants_share_one_backpack_without_balance_changes_revoking_either", this::sharedBackpack));
    }
    public JsonObject tick() {
        if (running != null) {
            if (!running.isDone()) return null;
            results.add(running.join()); running = null; return null;
        }
        if (next < cases.size()) {
            Case test = cases.get(next++); JsonObject row = new JsonObject(); row.addProperty("name", test.name());
            long start = System.nanoTime();
            Runnable run = () -> {
                try { test.action().run(); row.addProperty("status", "PASSED"); }
                catch (Exception | LinkageError failure) {
                    failure.printStackTrace(); row.addProperty("status", "FAILED");
                    row.addProperty("error", failure.getClass().getSimpleName() + ":" + failure.getMessage());
                }
                row.addProperty("milliseconds", (System.nanoTime() - start) / 1000000.0);
            };
            if (test.asynchronous()) {
                var completion = new CompletableFuture<JsonObject>(); running = completion;
                Thread.ofVirtual().name("fireflymc-fluid-storage-validation").start(() -> { run.run(); completion.complete(row); });
            } else { run.run(); results.add(row); }
            return null;
        }
        JsonObject report = new JsonObject(); report.add("checks", results.deepCopy());
        report.addProperty("status", results.asList().stream().allMatch(row -> row.getAsJsonObject().get("status").getAsString().equals("PASSED")) ? "PASSED" : "FAILED");
        report.addProperty("durationMillis", (System.nanoTime() - started) / 1000000);
        report.addProperty("maximumFluidScanSliceMillis", maximumFluidSliceMillis);
        report.addProperty("performanceScope", "Finite one/two-tank backends, one source per scan slice; includes cold reflection/codec initialization, excludes fixture setup, not full-pack MSPT or client FPS.");
        report.addProperty("scope", "Actual Sophisticated native fluid handlers in isolated dedicated world, authenticated mock connections; no recipe integration, client visuals or power-loss claim.");
        report.add("nativeInputSaveFenceChecks", inputSaveChecks.deepCopy());
        return report;
    }
    private void authorization() throws Exception {
        try (var f = new Fixture(1)) {
            require(f.scan().sources().isEmpty(), "UNAUTHORIZED_FLUID_DISCOVERED");
            var itemGrant = new MaterialSourceGrants(f.workspace.getWorkspace(f.actor()), System::currentTimeMillis);
            refused(() -> f.service.bind(f.actor().owner(), itemGrant), "FLUID_GRANT_REQUIRED");
            var grant = f.grant(); require(f.scan().sources().size() == 1, "AUTHORIZED_FLUID_MISSING");
            require(itemGrant.list(f.actor()).isEmpty(), "FLUID_PERMISSION_GRANTED_ITEMS");
            require(grant.source().description().contains("只授权"), "FLUID_REVIEW_SCOPE_MISSING");
        }
    }
    private void drainReturn() throws Exception {
        try (var f = new Fixture(1)) {
            f.fill(new FluidStack(Fluids.WATER, 6000)); f.grant();
            SourceAndFluid source = f.source();
            var prepared = f.service.prepare(f.actor(), source.source(), source.fluid().withAmount(5000), false);
            require(prepared.amount().millibuckets() > 0 && prepared.amount().millibuckets() <= 5000, "NATIVE_TRANSFER_LIMIT_IGNORED");
            var outcome = f.service.perform(prepared);
            require(outcome.entered() && outcome.verified() && outcome.actual().equals(prepared.amount()), "REAL_FLUID_DRAIN_FAILED_" + outcome.reason());
            refused(() -> f.service.perform(prepared), "FLUID_PREPARED_ALREADY_USED");
            var grant = f.grants.list(f.actor()).getFirst();
            var current = f.service.current(f.actor(), grant.id(), grant.revision(), false);
            var back = f.service.perform(f.service.prepare(f.actor(), current, outcome.actual(), true));
            require(back.entered() && back.verified() && back.actual().equals(outcome.actual()), "FLUID_RETURN_FAILED_" + back.reason());
            require(f.amount(source.fluid().fingerprint()) == 6000, "FLUID_CONSERVATION_FAILED");
        }
    }
    private void multipleTanks() throws Exception {
        try (var f = new Fixture(2)) {
            int capacity = f.handler().getTankCapacity(0); f.fill(new FluidStack(Fluids.WATER, capacity + 1500)); f.grant();
            var source = f.source(); require(f.scan().sources().size() == 1, "MULTIPLE_TANKS_DUPLICATED_BACKEND");
            require(source.source().snapshot().kinds().size() == 1 && f.amount(source.fluid().fingerprint()) == capacity + 1500L, "TANK_TOTAL_WRONG");
            // Fixture setup targets one native tank. The public backend deliberately routes its
            // per-tank limit across later tanks, so using it here would also consume tank two.
            Map<?, ?> wrappers = (Map<?, ?>) Class.forName("net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeHandler")
                    .getMethod("getSlotWrappers").invoke(f.upgrades());
            wrappers.get(0).getClass().getMethod("drain", int.class, IFluidHandler.FluidAction.class, boolean.class)
                    .invoke(wrappers.get(0), capacity - 200, IFluidHandler.FluidAction.EXECUTE, true);
            source = f.source(); var p = f.service.prepare(f.actor(), source.source(), source.fluid().withAmount(1000), false);
            var result = f.service.perform(p);
            require(result.verified() && result.actual().millibuckets() == 1000, "CROSS_TANK_DRAIN_FAILED_" + result.reason());
            require(f.handler().getFluidInTank(0).isEmpty(), "FIRST_NATIVE_TANK_NOT_DRAINED");
        }
    }
    private void components() throws Exception {
        try (var f = new Fixture(2)) {
            var tagged = new FluidStack(Fluids.WATER, 1000); var tag = new CompoundTag(); tag.putString("fixture", "private-fluid-note");
            tagged.set(DataComponents.CUSTOM_DATA, CustomData.of(tag)); f.fill(tagged); f.fill(new FluidStack(Fluids.WATER, 1000)); f.grant();
            var current = f.scan().sources().getFirst(); require(current.snapshot().kinds().size() == 2, "FLUID_COMPONENTS_MERGED");
            var exact = FluidSnapshotCodec.capture(tagged, f.player.registryAccess());
            var result = f.service.perform(f.service.prepare(f.actor(), current, exact, false));
            require(result.verified() && result.actual().equals(exact), "FLUID_COMPONENTS_LOST");
            require(f.amount(FluidSnapshotCodec.capture(new FluidStack(Fluids.WATER, 1), f.player.registryAccess()).fingerprint()) == 1000, "OTHER_FLUID_CONSUMED");
        }
    }
    private void movement() throws Exception {
        try (var f = new Fixture(1)) {
            f.fill(new FluidStack(Fluids.WATER, 1000)); f.grant(); var source = f.source();
            var p = f.service.prepare(f.actor(), source.source(), source.fluid().withAmount(500), false);
            f.player.getInventory().setItem(1, f.backpack); f.player.getInventory().setItem(0, ItemStack.EMPTY);
            require(!f.service.perform(p).entered() && f.amount(source.fluid().fingerprint()) == 1000, "MOVED_HOST_MUTATED");
        }
    }
    private void revocation() throws Exception {
        try (var f = new Fixture(1); var other = new Fixture(1)) {
            f.fill(new FluidStack(Fluids.WATER, 1000)); var grant = f.grant(); var source = f.source();
            var p = f.service.prepare(f.actor(), source.source(), source.fluid().withAmount(500), false);
            refused(() -> f.grants.require(other.actor(), grant.id(), grant.revision(), true), null);
            f.grants.revoke(f.actor(), grant.id()); require(!f.service.perform(p).entered(), "REVOKED_FLUID_GRANT_USED");
            require(f.amount(source.fluid().fingerprint()) == 1000, "REVOKED_SOURCE_MUTATED");
        }
    }
    private void unsafe() throws Exception {
        try (var f = new Fixture(1)) {
            f.player.getInventory().setItem(1, f.backpack.copy());
            refused(f::grant, "BACKPACK_DUPLICATE_HOST"); f.player.getInventory().setItem(1, ItemStack.EMPTY);
            for (String item : List.of("void_upgrade", "infinity_upgrade")) {
                f.upgrades().setStackInSlot(1, new ItemStack(item(item)));
                refused(f::grant, null);
            }
        }
    }
    private void automation() throws Exception {
        try (var f = new Fixture(1)) {
            Object upgrades = f.upgrades();
            Map<?, ?> wrappers = (Map<?, ?>) Class.forName("net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeHandler")
                    .getMethod("getSlotWrappers").invoke(upgrades);
            Object tank = wrappers.get(0);
            var io = (IItemHandlerModifiable) tank.getClass().getMethod("getInventory").invoke(tank);
            io.setStackInSlot(0, new ItemStack(Items.WATER_BUCKET));
            refused(f::grant, "FLUID_CONTAINER_AUTOMATION_ACTIVE");
        }
    }
    private void full() throws Exception {
        try (var f = new Fixture(1)) {
            int capacity = f.handler().getTankCapacity(0); f.fill(new FluidStack(Fluids.WATER, capacity)); f.grant();
            var source = f.source();
            refused(() -> f.service.prepare(f.actor(), source.source(), source.fluid().withAmount(250), true), "FLUID_OUTPUT_FULL");
            require(f.amount(source.fluid().fingerprint()) == capacity, "FULL_TANK_DESTROYED_FLUID");
        }
    }
    private void save() throws Exception {
        try (var f = new Fixture(1)) {
            f.fill(new FluidStack(Fluids.WATER, 1000)); f.grant(); var source = f.source();
            var result = f.service.perform(f.service.prepare(f.actor(), source.source(), source.fluid().withAmount(500), false));
            require(result.verified() && result.nativeSave() != null && !result.nativeSave().saved().isDone(), "MISSING_NATIVE_FLUID_SAVE_OBLIGATION_" + result.reason());
            var field = net.minecraft.server.players.PlayerList.class.getDeclaredField("playerIo"); field.setAccessible(true);
            ((net.minecraft.world.level.storage.PlayerDataStorage) field.get(server.getPlayerList())).save(f.player);
            IOUtilities.waitUntilIOWorkerComplete();
            require(!result.nativeSave().saved().isDone(), "PLAYER_FILE_PROVED_INDEPENDENT_TANK_CONTENTS");
            server.overworld().getDataStorage().save(); IOUtilities.waitUntilIOWorkerComplete();
            require(result.nativeSave().saved().isDone() && !result.nativeSave().saved().isCompletedExceptionally(), "NATIVE_FLUID_SAVE_PROOF_MISSING");
        }
    }
    private void placed() throws Exception {
        try (var f = new Fixture(1)) {
            f.fill(new FluidStack(Fluids.WATER, 1000));
            var storage = new StorageSourceIntrospector();
            var host = f.items.equipment().requireHost(f.player, f.player.getUUID(), "inventory/0");
            var itemProbe = storage.probeItemBackpackFluids(f.player, storage.captureItemHost(f.player, "inventory/0", 0, host.inventory(), 0));
            require(itemProbe.callable(), itemProbe.reason());
            BlockPos pos = new BlockPos(112, -58, 112); server.overworld().getChunkAt(pos);
            var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("sophisticatedbackpacks:diamond_backpack"));
            require(block != Blocks.AIR, "ACTUAL_BACKPACK_BLOCK_MISSING");
            server.overworld().setBlock(pos, block.defaultBlockState(), 3);
            try {
                var entity = server.overworld().getBlockEntity(pos);
                f.player.getInventory().setItem(0, ItemStack.EMPTY);
                entity.getClass().getMethod("setBackpack", ItemStack.class).invoke(entity, f.backpack);
                var probe = storage.probeBlockFluids(f.player, server.overworld(), pos, Direction.UP);
                require(probe.callable() && probe.backendId().equals(itemProbe.backendId()), "PLACED_FLUID_BACKEND_NOT_SAME_" + probe.reason());
                require(probe.handler() == server.overworld().getCapability(Capabilities.FluidHandler.BLOCK, pos, Direction.UP), "EXTERNAL_SIDE_HANDLER_BYPASSED");
                f.player.moveTo(pos.getX() + .5, pos.getY() + 2, pos.getZ() + .5, 0, 90);
                var selection = f.service.selectLookedAt(f.actor());
                var preview = f.grants.preview(f.actor(), selection.candidate());
                var grant = f.grants.confirm(f.actor(), preview.id(), preview.digest(), f.service.confirmSelection(f.actor(), selection));
                var source = f.service.current(f.actor(), grant.id(), grant.revision(), true);
                var fluid = source.snapshot().kinds().values().iterator().next();
                var prepared = f.service.prepare(f.actor(), source, fluid.withAmount(100), false);
                var result = f.service.perform(prepared);
                require(result.entered() && result.verified() && result.actual().millibuckets() == 100, "PLACED_AUTHORIZED_FLUID_DRAIN_FAILED_" + result.reason());
                source = f.service.current(f.actor(), grant.id(), grant.revision(), true);
                prepared = f.service.prepare(f.actor(), source, fluid.withAmount(100), false);
                server.overworld().setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                require(!f.service.perform(prepared).entered(), "DESTROYED_FLUID_SOURCE_STILL_USED");
            } finally { server.overworld().setBlock(pos, Blocks.AIR.defaultBlockState(), 3); }
        }
    }
    private void privacy() throws Exception {
        try (var f = new Fixture(1)) {
            f.fill(new FluidStack(Fluids.WATER, 1000)); f.grant();
            var scan = f.service.scan(f.actor());
            f.workspace.getWorkspace(f.actor()).conversation().erase(f.workspace.getWorkspace(f.actor()).conversation().revision());
            f.service.tick(); require(scan.isCompletedExceptionally(), "OLD_PRIVACY_SCAN_PUBLISHED");
        }
    }
    private void gates() throws Exception {
        try (var f = new Fixture(1)) {
            f.fill(new FluidStack(Fluids.WATER, 1000)); f.grant(); var source = f.source();
            var prepared = f.service.prepare(f.actor(), source.source(), source.fluid().withAmount(100), false);
            f.player.setGameMode(GameType.CREATIVE);
            require(!f.service.perform(prepared).entered(), "CREATIVE_SOURCE_BYPASSED_GATE");
            f.player.setGameMode(GameType.SURVIVAL);
            prepared = f.service.prepare(f.actor(), source.source(), source.fluid().withAmount(100), false);
            firefly520.fireflymc.network.ModPayloadHandler.VERIFIED_PLAYERS.remove(f.player.getUUID());
            require(!f.service.perform(prepared).entered(), "UNVERIFIED_CONNECTION_BYPASSED_GATE");
            require(f.amount(source.fluid().fingerprint()) == 1000, "DENIED_NATIVE_OPERATION_CHANGED_FLUID");
        }
    }
    private void ledger() throws Exception {
        try (var f = new Fixture(1)) {
            f.fill(new FluidStack(Fluids.WATER, 1000)); var grant = f.grant(); var source = f.source();
            var root = f.workspace.getWorkspace(f.actor()).directory().getParent().getParent();
            var ledger = new FluidLedger(root); UUID id = UUID.randomUUID(), task = UUID.randomUUID();
            String hash = firefly520.fireflymc.ai.storage.AiJson.digest("native-fluid-custody");
            var op = ledger.reserve(f.actor(), task, id, hash, source.source(), source.fluid().withAmount(500));
            var prepared = f.service.prepare(f.actor(), source.source(), source.fluid().withAmount(500), false);
            String drain = UUID.randomUUID().toString();
            ledger.begin(f.actor(), id, drain, prepared.source(), prepared.amount(), false);
            var actual = f.service.perform(prepared); ledger.observed(f.actor(), id, drain, actual);
            require(actual.entered() && actual.verified() && ledger.get(f.actor(), id).escrowMb() == 500, "NATIVE_LEDGER_DRAIN_NOT_OWNED");
            saveAndRecord(f, ledger, id, drain, actual);
            var reloaded = new FluidLedger(root);
            require(reloaded.get(f.actor(), id).state() == FluidLedger.State.RECOVERY_REQUIRED, "RESTART_SKIPPED_FLUID_RECONCILIATION");
            var review = reloaded.recoveryReview(f.actor(), id);
            reloaded.reconcile(f.actor(), id, review.revision(), review.reviewHash());
            require(f.amount(source.fluid().fingerprint()) == 500 && reloaded.get(f.actor(), id).escrowMb() == 500, "RECONCILIATION_REPLAYED_DRAIN");
            var target = f.service.current(f.actor(), grant.id(), grant.revision(), false);
            prepared = f.service.prepare(f.actor(), target, source.fluid().withAmount(500), true);
            String fill = UUID.randomUUID().toString(); reloaded.begin(f.actor(), id, fill, prepared.source(), prepared.amount(), true);
            actual = f.service.perform(prepared); reloaded.observed(f.actor(), id, fill, actual); saveAndRecord(f, reloaded, id, fill, actual);
            require(f.amount(source.fluid().fingerprint()) == 1000 && reloaded.settled(f.actor()), "NATIVE_LEDGER_RETURN_NOT_CONSERVED");
            var reopened = new FluidLedger(root);
            op = reopened.reserve(f.actor(), task, id, hash, source.source(), source.fluid().withAmount(500));
            require(op.state() == FluidLedger.State.COMPLETED && op.effects().size() == 2 && op.escrowMb() == 0, "DUPLICATE_OPERATION_RECREATED_CUSTODY");
        }
    }
    private void saveAndRecord(Fixture f, FluidLedger ledger, UUID id, String witness, FluidInventoryService.Outcome actual) throws Exception {
        var recorded = new java.util.concurrent.CompletableFuture<Void>();
        f.service.nativeSaves().record(id, actual.nativeSave(), proof -> {
            try { ledger.nativeSaved(f.actor(), id, witness, proof); recorded.complete(null); }
            catch (java.io.IOException | RuntimeException failure) { recorded.completeExceptionally(failure); throw failure; }
        });
        var field = net.minecraft.server.players.PlayerList.class.getDeclaredField("playerIo"); field.setAccessible(true);
        ((net.minecraft.world.level.storage.PlayerDataStorage) field.get(server.getPlayerList())).save(f.player);
        server.overworld().getDataStorage().save(); IOUtilities.waitUntilIOWorkerComplete();
        recorded.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
    private void queuedReadShutdown() throws Exception {
        try (var f = new Fixture(1)) {
            var waiting = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<FluidInventoryService.Snapshot>>();
            var thread = Thread.ofVirtual().start(() -> waiting.set(f.service.scan(f.actor()))); thread.join();
            require(waiting.get() != null && !waiting.get().isDone(), "QUEUED_READ_FIXTURE_NOT_PENDING");
            f.service.close();
            require(waiting.get().isCompletedExceptionally(), "SHUTDOWN_LEFT_UNDRAINABLE_NATIVE_READ");
            require(f.service.scan(f.actor()).isCompletedExceptionally(), "CLOSED_SERVICE_ACCEPTED_READ");
        }
    }
    private void crossPlayerAlias() throws Exception {
        try (var first = new Fixture(1); var second = new Fixture(1)) {
            first.fill(new FluidStack(Fluids.WATER, 1000)); first.grant();
            ItemStack duplicate = first.backpack.copy(); second.player.getInventory().setItem(0, duplicate);
            Class<?> type = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper");
            Object wrapper = type.getMethod("fromStack", ItemStack.class).invoke(null, duplicate);
            type.getMethod("getInventoryHandler").invoke(wrapper); type.getMethod("getUpgradeHandler").invoke(wrapper);
            second.grant();
            var a = first.source(); var b = second.source();
            require(a.source().backend().equals(b.source().backend()), "CROSS_PLAYER_ALIAS_FIXTURE_NOT_SHARED");
            var pendingB = second.service.prepare(second.actor(), b.source(), b.fluid().withAmount(500), false);
            var acceptedA = first.service.perform(first.service.prepare(first.actor(), a.source(), a.fluid().withAmount(500), false));
            require(acceptedA.entered() && acceptedA.verified(), "ALIAS_FIRST_NATIVE_DRAIN_FAILED");
            var deniedB = second.service.perform(pendingB);
            require(!deniedB.entered(), "STALE_DUPLICATE_NATIVE_BACKEND_WAS_DRAINED");
        }
    }
    private void mixedBucket() throws Exception {
        var f = main(() -> new Fixture(1));
        record Inputs(MaterialLedger items, FluidLedger fluids, MixedResourceJournal mixed, UUID id, UUID fluidId,
                      FluidAmount portion, MaterialStack output, CompletableFuture<Void> itemSaved, CompletableFuture<Void> fluidSaved) {}
        try {
            main(() -> {
                f.fill(new FluidStack(Fluids.WATER, 2000)); f.grant(); f.player.getInventory().setItem(1, new ItemStack(Items.BUCKET));
                var workspace = f.workspace.getWorkspace(f.actor());
                f.items.bind(f.actor().owner(), new MaterialSourceGrants(workspace, System::currentTimeMillis));
                f.items.bindPreferences(f.actor().owner(), new MaterialPreferences(workspace));
                return null;
            });
            var itemSnapshot = scanOffThread(() -> f.items.scan(f.actor()), f.items::tick);
            var fluidSnapshot = scanOffThread(() -> f.service.scan(f.actor()), f.service::tick);
            require(fluidSnapshot.refusals().isEmpty(), "SOURCE_REFUSED_" + fluidSnapshot.refusals());
            var fluidSource = fluidSnapshot.sources().getFirst();
            var source = new SourceAndFluid(fluidSource, fluidSource.snapshot().kinds().values().iterator().next());
            var inputs = main(() -> {
                var workspace = f.workspace.getWorkspace(f.actor()); var root = workspace.directory().getParent().getParent();
                var bucket = itemSnapshot.rows().stream().filter(row -> row.stack().itemJson().contains("minecraft:bucket")).findFirst().orElseThrow();
                var portion = source.fluid().withAmount(1000);
                var nativeContainer = new net.neoforged.neoforge.fluids.capability.wrappers.FluidBucketWrapper(
                        ItemSnapshotCodec.restore(bucket.stack().withCount(1), f.player.registryAccess()));
                FluidStack exact = FluidSnapshotCodec.restore(portion, f.player.registryAccess());
                require(nativeContainer.fill(exact, IFluidHandler.FluidAction.EXECUTE) == 1000
                        && FluidStack.matches(nativeContainer.getFluidInTank(0), exact), "NATIVE_BUCKET_TRANSFORMATION_NOT_EXACT");
                var output = ItemSnapshotCodec.capture(nativeContainer.getContainer(), f.player.registryAccess());
                var items = new MaterialLedger(root); var fluids = new FluidLedger(root); var mixed = new MixedResourceJournal(workspace, items, fluids);
                UUID id = UUID.randomUUID(), fluidId = UUID.randomUUID(), task = UUID.randomUUID();
                String hash = firefly520.fireflymc.ai.storage.AiJson.digest("native-fluid-bucket:" + output + ":" + portion);
                var definition = new MixedResourceJournal.Definition(id, task, hash, List.of(bucket.stack().withCount(1)),
                        List.of(new MixedResourceJournal.FluidInput(fluidId, portion)), List.of(output), List.of(), "NeoForge FluidBucketWrapper native isolated conversion: exact 1000mB and actual container components");
                mixed.create(f.actor(), definition);
                items.reserve(f.actor(), task, id, hash, List.of(bucket), List.of(new MaterialLedger.Need(bucket.key(), 1)));
                fluids.reserve(f.actor(), task, fluidId, hash, source.source(), portion);
                var itemPrepared = f.items.prepareExtraction(f.actor(), bucket, 1);
                items.beginExtraction(f.actor(), id, 0, itemPrepared.before(), itemPrepared.poolUnits(), itemPrepared.witness());
                var actualItem = f.items.extract(itemPrepared, 1); items.extracted(f.actor(), id, actualItem.actual(), actualItem.poolUnitsAfter());
                require(actualItem.mutationEntered() && actualItem.actual().count() == 1, "MIXED_REAL_CONTAINER_NOT_EXTRACTED");
                var itemSaved = recordNativeSave(f.items.nativeSaves(), id, actualItem.save(),
                        proof -> items.nativeSaved(f.actor(), id, "extract", itemPrepared.witness(), proof));
                var fluidPrepared = f.service.prepare(f.actor(), source.source(), portion, false); String fluidWitness = UUID.randomUUID().toString();
                fluids.begin(f.actor(), fluidId, fluidWitness, fluidPrepared.source(), fluidPrepared.amount(), false);
                var actualFluid = f.service.perform(fluidPrepared); fluids.observed(f.actor(), fluidId, fluidWitness, actualFluid);
                require(actualFluid.verified() && actualFluid.actual().millibuckets() == 1000, "MIXED_REAL_FLUID_NOT_EXTRACTED");
                var fluidSaved = recordNativeSave(f.service.nativeSaves(), fluidId, actualFluid.nativeSave(),
                        proof -> fluids.nativeSaved(f.actor(), fluidId, fluidWitness, proof));
                require(!items.get(f.actor(), id).inputsSaved() && !fluids.get(f.actor(), fluidId).inputsSaved(), "MIXED_INPUT_PROOF_PREEXISTED_NATIVE_SAVE");
                refused(() -> mixed.prepareCommit(f.actor(), id, items, fluids), "MIXED_INPUT_SAVE_REQUIRED");
                refused(() -> mixed.commit(f.actor(), id, items, fluids), "MIXED_PHASE_CHANGED");
                refused(() -> items.cancel(f.actor(), id), "MIXED_RESOURCE_REVIEW_REQUIRED");
                require(mixed.get(f.actor(), id).state() == MixedResourceJournal.State.INPUTS_PENDING && items.get(f.actor(), id).products().isEmpty()
                        && fluids.get(f.actor(), fluidId).consumedMb() == 0 && fluids.get(f.actor(), fluidId).escrowMb() == 1000
                        && f.player.getInventory().countItem(Items.BUCKET) == 0 && f.player.getInventory().countItem(Items.WATER_BUCKET) == 0
                        && f.amount(portion.fingerprint()) == 1000, "UNSAVED_MIXED_INPUTS_CREATED_OUTPUT_OR_REFUND");
                inputSaveChecks.add("PASSED: mixed_commit_without_native_input_save_proof_produces_no_output_or_refund");
                return new Inputs(items, fluids, mixed, id, fluidId, portion, output, itemSaved, fluidSaved);
            });
            saveNative(f); awaitNativeSave(inputs.itemSaved()); awaitNativeSave(inputs.fluidSaved());
            var deliveredSave = main(() -> {
                var items = inputs.items(); var fluids = inputs.fluids(); var mixed = inputs.mixed(); var id = inputs.id();
                require(items.get(f.actor(), id).inputsSaved() && fluids.get(f.actor(), inputs.fluidId()).inputsSaved(), "MIXED_NATIVE_INPUT_SAVE_NOT_JOURNALLED");
                mixed.prepareCommit(f.actor(), id, items, fluids); mixed.commit(f.actor(), id, items, fluids);
                require(fluids.get(f.actor(), inputs.fluidId()).consumedMb() == 1000 && f.amount(inputs.portion().fingerprint()) == 1000, "MIXED_SOURCE_CONSERVATION_FAILED");
                items.noCallbacksRequired(f.actor(), id, "Native isolated bucket capability transfer has no crafting callback");
                var delivery = f.items.prepareDelivery(f.actor(), inputs.output());
                items.beginDelivery(f.actor(), id, 0, delivery.product().count(), delivery.witness(), delivery.before(), f.items.deliveryPools(delivery));
                var given = f.items.deliver(delivery); items.delivered(f.actor(), id, given.accepted(), given.verified());
                require(given.verified() && given.accepted() == 1, "MIXED_REAL_OUTPUT_NOT_DELIVERED");
                return recordNativeSave(f.items.nativeSaves(), id, given.save(),
                        proof -> items.nativeSaved(f.actor(), id, "deliver", delivery.witness(), proof));
            });
            saveNative(f); awaitNativeSave(deliveredSave);
            main(() -> {
                var root = f.workspace.getWorkspace(f.actor()).directory().getParent().getParent(); var id = inputs.id();
                require(f.player.getInventory().countItem(Items.BUCKET) == 0 && f.player.getInventory().countItem(Items.WATER_BUCKET) == 1,
                        "MIXED_CONTAINER_OR_OUTPUT_DUPLICATED");
                var reloadedItems = new MaterialLedger(root); var reloadedFluids = new FluidLedger(root);
                var reopened = new MixedResourceJournal(f.workspace.getWorkspace(f.actor()), reloadedItems, reloadedFluids);
                require(reopened.get(f.actor(), id).state() == MixedResourceJournal.State.COMMITTED && reopened.resourcesSettled(f.actor()),
                        "FULLY_SAVED_MIXED_DELIVERY_BECAME_SPURIOUS_RECOVERY");
                refused(() -> reopened.commit(f.actor(), id, reloadedItems, reloadedFluids), "MIXED_PHASE_CHANGED");
                require(f.player.getInventory().countItem(Items.WATER_BUCKET) == 1 && f.amount(inputs.portion().fingerprint()) == 1000
                        && reloadedFluids.get(f.actor(), inputs.fluidId()).recipeDebits().size() == 1, "MIXED_RELOAD_REPLAYED_NATIVE_EFFECT");
                refused(() -> reloadedItems.cancel(f.actor(), id), "MIXED_RESOURCE_REVIEW_REQUIRED");
                return null;
            });
        } finally { main(() -> { f.close(); return null; }); }
    }
    private void mixedFluidOutput() throws Exception {
        var f = main(() -> new Fixture(1));
        record Inputs(MaterialLedger items, FluidLedger fluids, MixedResourceJournal mixed, UUID id,
                      MaterialStack output, FluidAmount fluid, MaterialSourceGrants.Grant grant, CompletableFuture<Void> saved) {}
        record Deliveries(List<CompletableFuture<Void>> saved, MaterialSourceGrants.Grant grant) {}
        try {
            var initialGrant = main(() -> {
                var grant = f.grant(); f.player.getInventory().setItem(1, new ItemStack(Items.WATER_BUCKET));
                var owner = f.actor(); var workspace = f.workspace.getWorkspace(owner);
                f.items.bind(owner.owner(), new MaterialSourceGrants(workspace, System::currentTimeMillis)); f.items.bindPreferences(owner.owner(), new MaterialPreferences(workspace));
                return grant;
            });
            var itemSnapshot = scanOffThread(() -> f.items.scan(f.actor()), f.items::tick);
            var inputs = main(() -> {
                var owner = f.actor(); var workspace = f.workspace.getWorkspace(owner); var root = workspace.directory().getParent().getParent();
                var input = itemSnapshot.rows().stream().filter(row -> row.stack().itemJson().contains("minecraft:water_bucket")).findFirst().orElseThrow();
                var wrapper = new net.neoforged.neoforge.fluids.capability.wrappers.FluidBucketWrapper(ItemSnapshotCodec.restore(input.stack().withCount(1), f.player.registryAccess()));
                FluidStack actual = wrapper.drain(1000, IFluidHandler.FluidAction.EXECUTE);
                require(actual.getAmount() == 1000 && wrapper.getFluidInTank(0).isEmpty() && wrapper.getContainer().is(Items.BUCKET), "NATIVE_DECANT_NOT_EXACT");
                var output = ItemSnapshotCodec.capture(wrapper.getContainer(), f.player.registryAccess());
                var producedFluid = FluidSnapshotCodec.capture(actual, f.player.registryAccess());
                var items = new MaterialLedger(root); var fluids = new FluidLedger(root); var mixed = new MixedResourceJournal(workspace, items, fluids);
                UUID id = UUID.randomUUID(), task = UUID.randomUUID(); String hash = firefly520.fireflymc.ai.storage.AiJson.digest("native-bucket-decant:" + output + ":" + producedFluid);
                mixed.create(owner, new MixedResourceJournal.Definition(id, task, hash, List.of(input.stack().withCount(1)), List.of(),
                        List.of(output), List.of(producedFluid), "Exact native FluidBucketWrapper decant, no invented input fluid or container"));
                var initialTarget = f.service.current(owner, initialGrant.id(), initialGrant.revision(), false);
                require(f.service.prepare(owner, initialTarget, producedFluid, true).amount().equals(producedFluid), "NATIVE_OUTPUT_SPACE_MISSING");
                items.reserve(owner, task, id, hash, List.of(input), List.of(new MaterialLedger.Need(input.key(), 1)));
                var preparedInput = f.items.prepareExtraction(owner, input, 1);
                items.beginExtraction(owner, id, 0, preparedInput.before(), preparedInput.poolUnits(), preparedInput.witness());
                var taken = f.items.extract(preparedInput, 1); items.extracted(owner, id, taken.actual(), taken.poolUnitsAfter());
                require(taken.mutationEntered() && taken.actual().count() == 1, "REAL_FILLED_BUCKET_NOT_CONSUMED");
                var inputSaved = recordNativeSave(f.items.nativeSaves(), id, taken.save(),
                        proof -> items.nativeSaved(owner, id, "extract", preparedInput.witness(), proof));
                require(!items.get(owner, id).inputsSaved(), "DECANT_INPUT_PROOF_PREEXISTED_NATIVE_SAVE");
                refused(() -> mixed.prepareCommit(owner, id, items, fluids), "MIXED_INPUT_SAVE_REQUIRED");
                refused(() -> mixed.heldFluidOutputs(owner, id), "MIXED_COMMIT_NOT_CONFIRMED");
                refused(() -> items.cancel(owner, id), "MIXED_RESOURCE_REVIEW_REQUIRED");
                require(mixed.get(owner, id).state() == MixedResourceJournal.State.INPUTS_PENDING && items.get(owner, id).products().isEmpty()
                        && fluids.list(owner).isEmpty() && f.player.getInventory().countItem(Items.WATER_BUCKET) == 0
                        && f.player.getInventory().countItem(Items.BUCKET) == 0 && f.amount(producedFluid.fingerprint()) == 0,
                        "UNSAVED_DECANT_INPUT_CREATED_OUTPUT_OR_REFUND");
                inputSaveChecks.add("PASSED: decant_without_native_input_save_proof_produces_no_fluid_container_or_refund");
                return new Inputs(items, fluids, mixed, id, output, producedFluid, initialGrant, inputSaved);
            });
            saveNative(f); awaitNativeSave(inputs.saved());
            var deliveriesSaved = main(() -> {
                var owner = f.actor(); var items = inputs.items(); var fluids = inputs.fluids(); var mixed = inputs.mixed();
                var id = inputs.id(); var producedFluid = inputs.fluid(); var grant = inputs.grant();
                require(items.get(owner, id).inputsSaved(), "DECANT_NATIVE_INPUT_SAVE_NOT_JOURNALLED");
                mixed.prepareCommit(owner, id, items, fluids); mixed.commit(owner, id, items, fluids);
                require(fluids.list(owner).isEmpty() && mixed.heldFluidOutputs(owner, id).equals(List.of(producedFluid)), "DECANT_INVENTED_INPUT_FLUID");
                var initialTarget = f.service.current(owner, grant.id(), grant.revision(), false);
                var preparedOutput = f.service.prepare(owner, initialTarget, producedFluid, true); String rejected = UUID.randomUUID().toString();
                mixed.beginFluidDelivery(owner, id, 0, rejected, preparedOutput.source(), preparedOutput.amount());
                f.grants.revoke(owner, grant.id());
                var refused = f.service.perform(preparedOutput); mixed.fluidDeliveryObserved(owner, id, rejected, refused);
                require(!refused.entered() && mixed.heldFluidOutputs(owner, id).equals(List.of(producedFluid)), "REVOKED_OUTPUT_SOURCE_LOST_PRODUCT");
                grant = f.grant(); var target = f.service.current(owner, grant.id(), grant.revision(), false);
                preparedOutput = f.service.prepare(owner, target, producedFluid, true); String accepted = UUID.randomUUID().toString();
                mixed.beginFluidDelivery(owner, id, 0, accepted, preparedOutput.source(), preparedOutput.amount());
                var filled = f.service.perform(preparedOutput); mixed.fluidDeliveryObserved(owner, id, accepted, filled);
                require(filled.entered() && filled.verified() && filled.actual().equals(producedFluid), "NATIVE_RAW_PRODUCT_FILL_FAILED");
                var fluidSaved = recordNativeSave(f.service.nativeSaves(), id, filled.nativeSave(),
                        proof -> mixed.fluidDeliverySaved(owner, id, accepted, proof));
                items.noCallbacksRequired(owner, id, "Native isolated bucket capability has no crafting callback");
                var delivery = f.items.prepareDelivery(owner, inputs.output());
                items.beginDelivery(owner, id, 0, delivery.product().count(), delivery.witness(), delivery.before(), f.items.deliveryPools(delivery));
                var returned = f.items.deliver(delivery); items.delivered(owner, id, returned.accepted(), returned.verified());
                var containerSaved = recordNativeSave(f.items.nativeSaves(), id, returned.save(),
                        proof -> items.nativeSaved(owner, id, "deliver", delivery.witness(), proof));
                return new Deliveries(List.of(fluidSaved, containerSaved), grant);
            });
            saveNative(f); for (var saved : deliveriesSaved.saved()) awaitNativeSave(saved);
            main(() -> {
                var owner = f.actor(); var root = f.workspace.getWorkspace(owner).directory().getParent().getParent();
                var id = inputs.id(); var producedFluid = inputs.fluid(); var mixed = inputs.mixed();
                require(f.player.getInventory().countItem(Items.WATER_BUCKET) == 0 && f.player.getInventory().countItem(Items.BUCKET) == 1
                        && f.amount(producedFluid.fingerprint()) == 1000 && mixed.resourcesSettled(owner), "RAW_FLUID_AND_CONTAINER_NOT_CONSERVED");
                var reloadedItems = new MaterialLedger(root); var reloadedFluids = new FluidLedger(root); var reopened = new MixedResourceJournal(f.workspace.getWorkspace(owner), reloadedItems, reloadedFluids);
                require(reopened.resourcesSettled(owner) && reopened.heldFluidOutputs(owner, id).equals(List.of(FluidAmount.EMPTY)), "RELOADED_RAW_PRODUCT_DUPLICATED");
                var currentGrant = deliveriesSaved.grant();
                var after = f.service.current(owner, currentGrant.id(), currentGrant.revision(), false);
                refused(() -> reopened.beginFluidDelivery(owner, id, 0, UUID.randomUUID().toString(), after, producedFluid), "MIXED_FLUID_CUSTODY_UNAVAILABLE");
                return null;
            });
        } finally { main(() -> { f.close(); return null; }); }
    }
    private void sharedBackpack() throws Exception {
        try (var f = new Fixture(1)) {
            f.fill(new FluidStack(Fluids.WATER, 2000)); var fluidGrant = f.grant(); var owner = f.actor();
            var nativeItems = (IItemHandlerModifiable) f.wrapper.getClass().getMethod("getInventoryHandler").invoke(f.wrapper);
            nativeItems.setStackInSlot(0, new ItemStack(Items.BUCKET, 2));
            var workspace = f.workspace.getWorkspace(owner); var root = workspace.directory().getParent().getParent();
            var itemGrants = new MaterialSourceGrants(workspace, System::currentTimeMillis);
            f.items.bind(owner.owner(), itemGrants); f.items.bindPreferences(owner.owner(), new MaterialPreferences(workspace));
            var selected = f.items.selectBackpack(owner, "inventory/0"); var preview = itemGrants.preview(owner, selected.candidate());
            var itemGrant = itemGrants.confirm(owner, preview.id(), preview.digest(), f.items.confirmSelection(owner, selected));
            var scan = f.items.scan(owner); for (int i = 0; i < 128 && !scan.isDone(); i++) f.items.tick();
            var bucket = scan.join().rows().stream().filter(row -> row.sourceId().equals(itemGrant.id().toString()) && row.stack().itemJson().contains("minecraft:bucket")).findFirst().orElseThrow();
            var preparedItem = f.items.prepareExtraction(owner, bucket, 1);
            var currentFluid = f.service.current(owner, fluidGrant.id(), fluidGrant.revision(), true);
            var amount = currentFluid.snapshot().kinds().values().iterator().next().withAmount(1000);
            var items = new MaterialLedger(root); var fluids = new FluidLedger(root);
            UUID itemId = UUID.randomUUID(), fluidId = UUID.randomUUID(), task = UUID.randomUUID();
            String hash = firefly520.fireflymc.ai.storage.AiJson.digest("same-backpack-independent-resource-grants");
            items.reserve(owner, task, itemId, hash, List.of(bucket), List.of(new MaterialLedger.Need(bucket.key(), 1)));
            fluids.reserve(owner, task, fluidId, hash, currentFluid, amount);
            items.beginExtraction(owner, itemId, 0, preparedItem.before(), preparedItem.poolUnits(), preparedItem.witness());
            var preparedFluid = f.service.prepare(owner, currentFluid, amount, false); String witness = UUID.randomUUID().toString();
            fluids.begin(owner, fluidId, witness, preparedFluid.source(), preparedFluid.amount(), false);
            var takenFluid = f.service.perform(preparedFluid); fluids.observed(owner, fluidId, witness, takenFluid);
            require(takenFluid.verified() && takenFluid.actual().millibuckets() == 1000, "SHARED_BACKPACK_FLUID_DEBIT_FAILED");
            var takenItem = f.items.extract(preparedItem, 1);
            require(takenItem.mutationEntered() && takenItem.error().isEmpty() && takenItem.actual().count() == 1,
                    "LEGITIMATE_FLUID_BALANCE_INVALIDATED_ITEM_GRANT_" + takenItem.error());
            items.extracted(owner, itemId, takenItem.actual(), takenItem.poolUnitsAfter());
            require(nativeItems.getStackInSlot(0).getCount() == 1
                    && f.service.current(owner, fluidGrant.id(), fluidGrant.revision(), true).snapshot().amount(amount.fingerprint()) == 1000,
                    "SHARED_BACKPACK_RESOURCES_NOT_CONSERVED");
            f.grants.require(owner, fluidGrant.id(), fluidGrant.revision(), true);
            itemGrants.require(owner, itemGrant.id(), itemGrant.revision(), true);
            var itemSaved = new java.util.concurrent.CompletableFuture<Void>();
            f.items.nativeSaves().record(itemId, takenItem.save(), proof -> { items.nativeSaved(owner, itemId, "extract", preparedItem.witness(), proof); itemSaved.complete(null); });
            saveAndRecord(f, fluids, fluidId, witness, takenFluid); itemSaved.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
    private record SourceAndFluid(FluidInventoryService.Source source, FluidAmount fluid) {}
    private final class Fixture implements AutoCloseable {
        final ServerPlayer player; final EmbeddedChannel channel;
        final Actor identity;
        final WorkspaceManager workspace; final MaterialInventoryService items; final FluidInventoryService service;
        final MaterialSourceGrants grants; final ItemStack backpack; final Object wrapper;
        Fixture(int tanks) throws Exception {
            UUID id = UUID.randomUUID(); var cookie = new CommonListenerCookie(new GameProfile(id, "fluid-" + id.toString().substring(0, 8)),
                    0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
            player = new ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation());
            identity = Actor.player(id, true, true, 0);
            var connection = new Connection(PacketFlow.SERVERBOUND); channel = new EmbeddedChannel(connection);
            NetworkRegistry.configureMockConnection(connection); server.getPlayerList().placeNewPlayer(connection, player, cookie);
            ValidationHandshake.reply(player); ValidationHandshake.authenticateFixture(player);
            player.setGameMode(GameType.SURVIVAL); player.getInventory().clearContent();
            workspace = new WorkspaceManager(Files.createTempDirectory(server.getWorldPath(LevelResource.ROOT).toAbsolutePath(), "ai4-fluid-validation-"));
            var permissions = new InstalledPermissionAdapters(server);
            items = new MaterialInventoryService(server, AiServerService::authenticated, permissions);
            service = new FluidInventoryService(server, items, permissions);
            grants = new MaterialSourceGrants(workspace.getWorkspace(actor()), System::currentTimeMillis, MaterialSourceGrants.Domain.FLUIDS);
            service.bind(actor().owner(), grants);
            backpack = new ItemStack(item("diamond_backpack")); player.getInventory().setItem(0, backpack);
            Class<?> type = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper");
            wrapper = type.getMethod("fromStack", ItemStack.class).invoke(null, backpack);
            type.getMethod("setContentsUuid", UUID.class).invoke(wrapper, UUID.randomUUID());
            type.getMethod("getInventoryHandler").invoke(wrapper);
            for (int i = 0; i < tanks; i++) upgrades().setStackInSlot(i, new ItemStack(item("tank_upgrade")));
        }
        IItemHandlerModifiable upgrades() throws Exception { return (IItemHandlerModifiable) wrapper.getClass().getMethod("getUpgradeHandler").invoke(wrapper); }
        IFluidHandler handler() { return Objects.requireNonNull(backpack.getCapability(Capabilities.FluidHandler.ITEM)); }
        Actor actor() { return identity; }
        MaterialSourceGrants.Grant grant() throws Exception {
            var selection = service.selectBackpack(actor(), "inventory/0"); var preview = grants.preview(actor(), selection.candidate());
            return grants.confirm(actor(), preview.id(), preview.digest(), service.confirmSelection(actor(), selection));
        }
        void fill(FluidStack stack) {
            int remaining = stack.getAmount(), iterations = 0;
            while (remaining > 0 && iterations++ < 128) {
                int actual = handler().fill(stack.copyWithAmount(remaining), IFluidHandler.FluidAction.EXECUTE);
                require(actual > 0 && actual <= remaining, "NATIVE_FIXTURE_FILL_REJECTED"); remaining -= actual;
            }
            require(remaining == 0, "NATIVE_FIXTURE_FILL_LIMIT");
        }
        FluidInventoryService.Snapshot scan() {
            var future = service.scan(actor());
            for (int i = 0; i < 128 && !future.isDone(); i++) service.tick();
            require(future.isDone(), "FLUID_SCAN_UNBOUNDED"); return future.join();
        }
        SourceAndFluid source() {
            var result = scan(); require(result.refusals().isEmpty(), "SOURCE_REFUSED_" + result.refusals());
            var source = result.sources().getFirst(); return new SourceAndFluid(source, source.snapshot().kinds().values().iterator().next());
        }
        long amount(String fingerprint) { return FluidTankSnapshot.capture(handler(), player.registryAccess()).amount(fingerprint); }
        public void close() throws Exception {
            maximumFluidSliceMillis = Math.max(maximumFluidSliceMillis, service.maximumSliceNanos() / 1000000.0);
            service.close(); items.close(); workspace.close(); server.getPlayerList().remove(player);
            server.overworld().getDataStorage().save(); IOUtilities.waitUntilIOWorkerComplete(); channel.finishAndReleaseAll();
        }
    }
    private static Item item(String name) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse("sophisticatedbackpacks:" + name));
        require(item != Items.AIR, "ACTUAL_NATIVE_ITEM_MISSING_" + name); return item;
    }
    private <T> T main(Callable<T> work) throws Exception {
        if (server.isSameThread()) return work.call();
        var completion = new CompletableFuture<T>();
        server.execute(() -> {
            try { completion.complete(work.call()); }
            catch (Throwable failure) { completion.completeExceptionally(failure); }
        });
        return completion.get(10, TimeUnit.SECONDS);
    }
    private <T> T scanOffThread(Callable<CompletableFuture<T>> start, Checked slice) throws Exception {
        require(!server.isSameThread(), "NATIVE_SCAN_AWAIT_MUST_BE_OFF_SERVER_THREAD");
        // scan() queues its initialization. Let that server task run before ticking or waiting.
        var scan = main(start);
        for (int i = 0; i < 128 && !scan.isDone(); i++) main(() -> { slice.run(); return null; });
        require(scan.isDone(), "NATIVE_SCAN_UNBOUNDED");
        return scan.get(10, TimeUnit.SECONDS);
    }
    private CompletableFuture<Void> recordNativeSave(NativeSaveObserver observer, UUID operation,
                                                     NativeSaveBarrier.Receipt receipt, NativeSaveObserver.ProofWriter writer) {
        require(receipt != null, "NATIVE_SAVE_RECEIPT_REQUIRED");
        var saved = new CompletableFuture<Void>();
        observer.record(operation, receipt, proof -> {
            try {
                require(!server.isSameThread(), "NATIVE_SAVE_CALLBACK_BLOCKED_SERVER_THREAD");
                writer.write(proof); saved.complete(null);
            } catch (java.io.IOException | RuntimeException failure) { saved.completeExceptionally(failure); throw failure; }
        });
        return saved;
    }
    private void saveNative(Fixture fixture) throws Exception {
        require(!server.isSameThread(), "NATIVE_SAVE_AWAIT_MUST_BE_OFF_SERVER_THREAD");
        main(() -> {
            var field = net.minecraft.server.players.PlayerList.class.getDeclaredField("playerIo"); field.setAccessible(true);
            ((net.minecraft.world.level.storage.PlayerDataStorage) field.get(server.getPlayerList())).save(fixture.player);
            server.overworld().getDataStorage().save(); return null;
        });
        IOUtilities.waitUntilIOWorkerComplete();
    }
    private void awaitNativeSave(CompletableFuture<Void> saved) throws Exception {
        require(!server.isSameThread(), "NATIVE_SAVE_AWAIT_MUST_BE_OFF_SERVER_THREAD");
        saved.get(10, TimeUnit.SECONDS);
    }
    private static void refused(Checked action, String code) throws Exception {
        try { action.run(); } catch (RuntimeException failure) {
            if (code == null || code.equals(failure.getMessage())) return; throw failure;
        }
        throw new IllegalStateException("EXPECTED_REFUSAL_" + code);
    }
    private static void require(boolean test, String code) { if (!test) throw new IllegalStateException(code); }
    public void close() {}
}
