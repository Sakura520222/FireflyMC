package firefly520.fireflymc.validation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.*;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import java.nio.file.*;
import java.util.*;

/** Native serialization, actual disk writes/flushes and ledger reload. No graphical client or model. */
public final class NativePersistenceScenario implements AutoCloseable {
    private final MinecraftServer server;
    private final NativeSaveObserver saves;
    private final JsonArray checks = new JsonArray();
    private final long started = System.nanoTime();
    private final Path root;
    private Session owner, stranger;
    private MaterialInventoryService inventory;
    private MaterialLedger ledger;
    private UUID operation;
    private NativeSaveBarrier.Receipt extraction, delivery, chunkSave, entitySave, dataSave, failedSave, jsonSaves;
    private NativeSaveBarrier.Receipt largeContainerSave, droppedContainerSave, partialContainerSave;
    private final java.util.concurrent.CompletableFuture<NativeSaveBarrier.Receipt> deferredPoiSave = new java.util.concurrent.CompletableFuture<>();
    private Path externalChunk;
    private int phase;
    private String error = "";
    public NativePersistenceScenario(MinecraftServer server) throws Exception {
        this.server = server; saves = NativeSaveObserver.forServer(server);
        root = Files.createTempDirectory(server.getWorldPath(LevelResource.ROOT).toAbsolutePath(), "ai4-save-validation-");
    }
    public JsonObject tick() {
        try {
            if (phase == 0) { transfer(); phase++; return null; }
            if (phase == 1) {
                if (!ledger.get(owner.actor(), operation).saved()) return null;
                require(new MaterialLedger(root).get(owner.actor(), operation).state() == MaterialLedger.State.COMPLETED, "CONFIRMED_NATIVE_SAVE_DID_NOT_RESTORE");
                checks.add("real_inventory_extraction_return_save_and_durable_ledger_reload");
                recoverKnownEscrow();
                savedData(); phase++; return null;
            }
            if (phase == 2) {
                if (!dataSave.saved().isDone()) return null;
                require(!failedSave.saved().isDone(), "FAILED_IO_ACKNOWLEDGED");
                checks.add("saved_data_async_atomic_write_and_failed_io_no_ack");
                regions(); phase++; return null;
            }
            if (phase == 3) {
                if (!chunkSave.saved().isDone() || !entitySave.saved().isDone()) return null;
                checks.add("actual_chunk_serializer_region_write_and_flush");
                checks.add("actual_entity_serializer_region_write_and_flush");
                failedPlayerReplacement();
                containerSerialization();
                jsonStores(); phase++; return null;
            }
            if (phase == 5) {
                if (!deferredPoiSave.isDone()) return null;
                require(!deferredPoiSave.join().saved().isDone(), "QUEUED_POI_EFFECT_COMPLETION_PRETENDED_TO_BE_SAVE");
                server.getPlayerList().saveAll(); server.overworld().save(null, true, false); phase++; return null;
            }
            if (phase == 6) {
                if (!deferredPoiSave.join().saved().isDone()) return null;
                deferredPoiSave.join().saved().join();
                checks.add("queued_native_poi_update_requires_its_later_actual_save");
                phase++;
            }
            if (phase == 4) {
            if (!jsonSaves.saved().isDone() || !largeContainerSave.saved().isDone()) return null;
            require(!droppedContainerSave.saved().isDone(), "DROPPED_BLOCK_ENTITY_ACKNOWLEDGED_BY_CHUNK");
            require(!partialContainerSave.saved().isDone(), "PARTIAL_COMPONENT_CODEC_ACKNOWLEDGED");
            require(Files.isRegularFile(externalChunk) && Files.size(externalChunk) > 1_048_576, "OVERSIZED_NATIVE_EXTERNAL_FILE_NOT_EXERCISED");
            checks.add("dropped_block_entity_does_not_inherit_chunk_save_success");
            checks.add("partial_block_entity_component_codec_does_not_acknowledge_save");
            checks.add("actual_large_container_external_mcc_file_is_written_and_forced");
            checks.add("player_statistics_and_advancements_each_require_own_native_file");
            poiStores();
            additionalCallbacks();
            deferredPoi(); phase = 5; return null;
            }
        } catch (Exception | LinkageError failure) {
            failure.printStackTrace(); error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        }
        var result = new JsonObject(); result.addProperty("status", error.isEmpty() ? "PASSED" : "FAILED");
        result.addProperty("error", error); result.add("checks", checks);
        result.addProperty("durationMillis", (System.nanoTime() - started) / 1_000_000);
        result.addProperty("scope", "Native Java 21/NeoForge disk save hooks and ledger reload in isolated dedicated world, mock authenticated players; not power-loss or two graphical clients.");
        return result;
    }
    private void transfer() throws Exception {
        owner = new Session(); stranger = new Session();
        inventory = new MaterialInventoryService(server, p -> p == owner.player, new InstalledPermissionAdapters(server));
        owner.player.getInventory().setItem(0, new ItemStack(Items.STONE, 2));
        var scan = inventory.scan(owner.actor()); for (int i = 0; i < 512 && !scan.isDone(); i++) inventory.tick();
        var stock = scan.join().rows().stream().filter(row -> row.stack().itemJson().contains("minecraft:stone")).findFirst().orElseThrow();
        ledger = new MaterialLedger(root); operation = UUID.randomUUID();
        ledger.reserve(owner.actor(), UUID.randomUUID(), operation, AiJson.digest("return same two native items"),
                List.of(stock), List.of(new MaterialLedger.Need(stock.key(), 2)));
        var prepared = inventory.prepareExtraction(owner.actor(), stock, 2);
        ledger.beginExtraction(owner.actor(), operation, 0, prepared.before(), prepared.poolUnits(), prepared.witness());
        var taken = inventory.extract(prepared, 2);
        require(taken.mutationEntered() && taken.actual().count() == 2 && taken.save() != null, "REAL_EXTRACTION_FAILED_" + taken.error());
        ledger.extracted(owner.actor(), operation, taken.actual(), taken.poolUnitsAfter()); extraction = taken.save();
        saves.record(ledger, owner.actor(), operation, "extract", prepared.witness(), extraction);
        ledger.cancel(owner.actor(), operation);
        var output = inventory.prepareDelivery(owner.actor(), taken.actual());
        ledger.beginDelivery(owner.actor(), operation, 0, 2, output.witness(), output.before());
        var returned = inventory.deliver(output);
        require(returned.verified() && returned.accepted() == 2 && returned.save() != null, "REAL_RETURN_FAILED");
        ledger.delivered(owner.actor(), operation, returned.accepted(), returned.verified()); delivery = returned.save();
        saves.record(ledger, owner.actor(), operation, "deliver", output.witness(), delivery);
        require(java.util.concurrent.CompletableFuture.supplyAsync(() -> NativeSaveObserver.snapshot(owner.player).empty()).join(), "ASYNC_CAPTURE_ACCEPTED");
        checks.add("async_snapshot_cannot_claim_coherent_main_thread_state");
        require(owner.player.getInventory().countItem(Items.STONE) == 2, "NATIVE_ITEMS_NOT_CONSERVED");
        require(!ledger.get(owner.actor(), operation).saved(), "MEMORY_RETURN_USED_AS_SAVE_PROOF");
        playerIo().save(stranger.player);
        require(!extraction.saved().isDone() && !delivery.saved().isDone(), "OTHER_PLAYER_SAVE_ACKNOWLEDGED_OWNER");
        checks.add("other_player_file_cannot_acknowledge_owner_effect");
        playerIo().save(owner.player);
        require(extraction.saved().isDone() && delivery.saved().isDone(), "PLAYER_NATIVE_WRITE_HOOK_MISSING");
    }
    private void savedData() throws Exception {
        var value = new TestData(); String name = "ai4_save_" + UUID.randomUUID().toString().replace("-", "");
        server.overworld().getDataStorage().set(name, value);
        Path canonical = NativeSaveObserver.dimensionFolder(server.overworld()).resolve("data").resolve(name + ".dat");
        dataSave = saves.changed(List.of(NativeSaveObserver.Target.file(value, canonical)));
        value.save(root.resolve("backup.dat").toFile(), server.registryAccess()); IOUtilities.waitUntilIOWorkerComplete();
        require(!dataSave.saved().isDone(), "BACKUP_FILE_USED_AS_NATIVE_SAVE");
        checks.add("backup_serialization_does_not_acknowledge_canonical_store");
        value.setDirty(); value.save(canonical.toFile(), server.registryAccess());
        var failed = new TestData(); Path bad = root.resolve("missing-parent").resolve("unwritable.dat");
        failedSave = saves.changed(List.of(NativeSaveObserver.Target.file(failed, bad)));
        failed.save(bad.toFile(), server.registryAccess()); IOUtilities.waitUntilIOWorkerComplete();
        require(!failed.isDirty() && !failedSave.saved().isDone(), "DIRTY_FALSE_USED_AS_SAVE_SUCCESS");
        checks.add("native_dirty_false_after_swallowed_io_error_is_not_success");
    }
    private void recoverKnownEscrow() throws Exception {
        var actor = owner.actor(); var scan = inventory.scan(actor);
        for (int i = 0; i < 512 && !scan.isDone(); i++) inventory.tick();
        var stock = scan.join().rows().stream().filter(row -> row.stack().itemJson().contains("minecraft:stone")).findFirst().orElseThrow();
        UUID id = UUID.randomUUID(); ledger.reserve(actor, UUID.randomUUID(), id, AiJson.digest("saved escrow recovery fixture"),
                List.of(stock), List.of(new MaterialLedger.Need(stock.key(), 1)));
        var prepared = inventory.prepareExtraction(actor, stock, 1);
        ledger.beginExtraction(actor, id, 0, prepared.before(), prepared.poolUnits(), prepared.witness());
        var actual = inventory.extract(prepared, 1); ledger.extracted(actor, id, actual.actual(), actual.poolUnitsAfter());
        require(actual.save() != null && actual.actual().count() == 1, "RECOVERY_EXTRACTION_FAILED");
        playerIo().save(owner.player);
        ledger.nativeSaved(actor, id, "extract", prepared.witness(), actual.save().saved().join());
        ledger = new MaterialLedger(root); var review = ledger.recoveryReview(actor, id);
        var processing = new firefly520.fireflymc.ai.processing.VirtualProcessingService(server, inventory, ledger, () -> 1);
        try {
            var registry = new firefly520.fireflymc.ai.tools.ToolRegistry(); processing.register(registry);
            var descriptor = registry.get("processing_reconcile").descriptor();
            var call = new firefly520.fireflymc.ai.model.ModelMessage.ToolCall("recovery-fixture", descriptor.name(),
                    "{\"operationId\":\"" + id + "\",\"revision\":" + review.revision() + ",\"reviewHash\":\"" + review.reviewHash() + "\"}");
            var policy = new firefly520.fireflymc.ai.security.ToolPolicyEngine();
            var approved = firefly520.fireflymc.ai.security.ToolPolicyEngine.digest(actor.owner(), call);
            require(policy.check(actor, actor.owner(), descriptor, true, call, "").decision()
                    == firefly520.fireflymc.ai.security.ToolPolicyEngine.Decision.APPROVAL, "RECOVERY_BYPASSED_WORKBENCH_APPROVAL");
            require(policy.check(Actor.player(owner.player.getUUID(), true, true, 4), actor.owner(), descriptor, true, call, "").decision()
                    == firefly520.fireflymc.ai.security.ToolPolicyEngine.Decision.APPROVAL, "OP_BYPASSED_RECOVERY_APPROVAL");
            require(policy.check(stranger.actor(), actor.owner(), descriptor, true, call, approved).decision()
                    == firefly520.fireflymc.ai.security.ToolPolicyEngine.Decision.DENY, "RECOVERY_APPROVAL_CROSSED_OWNER");
            require(policy.check(actor, actor.owner(), descriptor, true, call, approved).decision()
                    == firefly520.fireflymc.ai.security.ToolPolicyEngine.Decision.ALLOW, "FIXED_RECOVERY_APPROVAL_REJECTED");
            checks.add("registered_reconciliation_tool_requires_owner_bound_workbench_approval_even_for_op");
        } finally { processing.close(); }
        int before = owner.player.getInventory().countItem(Items.STONE);
        var recovered = ledger.reconcile(actor, id, review.revision(), review.reviewHash());
        require(recovered.state() == MaterialLedger.State.ESCROWED && before == 1
                && owner.player.getInventory().countItem(Items.STONE) == before, "RECOVERY_REPLAYED_NATIVE_EFFECT");
        ledger.cancel(actor, id);
        var output = inventory.prepareDelivery(actor, recovered.escrow().getFirst());
        ledger.beginDelivery(actor, id, 0, output.product().count(), output.witness(), output.before(), inventory.deliveryPools(output));
        var returned = inventory.deliver(output); ledger.delivered(actor, id, returned.accepted(), returned.verified());
        playerIo().save(owner.player); ledger.nativeSaved(actor, id, "deliver", output.witness(), returned.save().saved().join());
        require(owner.player.getInventory().countItem(Items.STONE) == 2 && ledger.get(actor, id).saved(), "RECOVERED_ESCROW_NOT_CONSERVED");
        checks.add("positive_native_receipts_reconcile_saved_escrow_without_replaying_and_return_exact_items");
    }
    private void regions() {
        var level = server.overworld();
        // Touch only a fresh fixture position near this newly-created mock player; keep old failures.
        var pos = owner.player.blockPosition().offset(3, 8, 3);
        while (!level.getBlockState(pos).isAir()) pos = pos.above();
        level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
        var chunk = level.getChunkAt(pos); chunkSave = saves.changed(List.of(NativeSaveObserver.chunk(level, chunk)));
        var entity = new net.minecraft.world.entity.decoration.ArmorStand(level, pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5);
        entity.setNoGravity(true); require(level.addFreshEntity(entity), "FIXTURE_ENTITY_NOT_ADDED");
        entitySave = saves.changed(List.of(NativeSaveObserver.entity(entity)));
        // Validation explicitly requests native saves; production only observes existing saves.
        level.save(null, true, false);
    }
    private void jsonStores() throws Exception {
        Path stats = server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(owner.player.getStringUUID() + ".json");
        Path advancements = server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(owner.player.getStringUUID() + ".json");
        jsonSaves = saves.changed(List.of(NativeSaveObserver.Target.file(owner.player.getStats(), stats),
                NativeSaveObserver.Target.file(owner.player.getAdvancements(), advancements)));
        playerIo().save(owner.player); require(!jsonSaves.saved().isDone(), "PLAYER_NBT_PROVED_EXTERNAL_JSON");
        owner.player.getStats().save(); require(!jsonSaves.saved().isDone(), "STATS_PROVED_ADVANCEMENTS");
        owner.player.getAdvancements().save();
    }
    private void poiStores() throws Exception {
        var level = server.overworld(); var manager = level.getPoiManager();
        var pos = new net.minecraft.core.BlockPos(96, 80, 96);
        var chunk = new net.minecraft.world.level.ChunkPos(pos);
        var home = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.POINT_OF_INTEREST_TYPE)
                .getHolderOrThrow(net.minecraft.world.entity.ai.village.poi.PoiTypes.HOME);
        manager.add(pos, home);
        var added = saves.changed(List.of(NativeSaveObserver.poi(level, pos)));
        CompoundTag first = poiColumn(manager, chunk);
        // A normal block chunk with the same column cannot acknowledge the separate POI store.
        var nativeChunk = level.getChunkAt(pos);
        var blockTag = net.minecraft.world.level.chunk.storage.ChunkSerializer.write(level, nativeChunk);
        NativeSaveObserver.writtenRegion(blockTag, NativeSaveObserver.dimensionFolder(level).resolve("region"), chunk.toLong());
        require(!added.saved().isDone(), "BLOCK_CHUNK_PROVED_POI");
        manager.remove(pos);
        var removed = saves.changed(List.of(NativeSaveObserver.poi(level, pos)));
        CompoundTag second = poiColumn(manager, chunk);
        var field = net.minecraft.world.level.chunk.storage.SectionStorage.class.getDeclaredField("simpleRegionStorage"); field.setAccessible(true);
        var storage = (net.minecraft.world.level.chunk.storage.SimpleRegionStorage) field.get(manager);
        // Write a real export using the same native writer, but to a noncanonical folder.
        try (var backup = new net.minecraft.world.level.chunk.storage.SimpleRegionStorage(storage.storageInfo(), root.resolve("poi-backup"),
                net.minecraft.util.datafix.DataFixers.getDataFixer(), true, net.minecraft.util.datafix.DataFixTypes.POI_CHUNK)) {
            backup.write(chunk, second).get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        require(!added.saved().isDone() && !removed.saved().isDone(), "POI_BACKUP_PROVED_CANONICAL_WRITE");
        storage.write(chunk, first).get(10, java.util.concurrent.TimeUnit.SECONDS);
        require(added.saved().isDone() && !removed.saved().isDone(), "OLDER_POI_SNAPSHOT_ACKNOWLEDGED_LATER_REMOVAL");
        storage.write(chunk, second).get(10, java.util.concurrent.TimeUnit.SECONDS);
        require(removed.saved().isDone(), "CANONICAL_POI_WRITE_NOT_ACKNOWLEDGED");
        checks.add("poi_is_independent_from_block_chunk_and_backup_destinations");
        checks.add("older_poi_snapshot_cannot_acknowledge_later_native_removal");
        checks.add("actual_native_poi_column_write_and_force_acknowledge_exact_sections");

        var partialPos = pos.above(32);
        manager.add(partialPos, net.minecraft.core.Holder.direct(new net.minecraft.world.entity.ai.village.poi.PoiType(Set.of(Blocks.STONE.defaultBlockState()), 1, 1)));
        var partial = saves.changed(List.of(NativeSaveObserver.poi(level, partialPos)));
        manager.add(pos, home); var complete = saves.changed(List.of(NativeSaveObserver.poi(level, pos)));
        var mixed = poiColumn(manager, chunk);
        storage.write(chunk, mixed).get(10, java.util.concurrent.TimeUnit.SECONDS);
        require(complete.saved().isDone() && !partial.saved().isDone(), "PARTIAL_POI_SECTION_INHERITED_COLUMN_SAVE");
        checks.add("partial_poi_codec_does_not_inherit_other_sections_success");
    }
    private CompoundTag poiColumn(net.minecraft.world.entity.ai.village.poi.PoiManager manager, net.minecraft.world.level.ChunkPos chunk) throws Exception {
        var encode = net.minecraft.world.level.chunk.storage.SectionStorage.class.getDeclaredMethod("writeColumn", net.minecraft.world.level.ChunkPos.class, com.mojang.serialization.DynamicOps.class);
        encode.setAccessible(true);
        var encoded = (com.mojang.serialization.Dynamic<?>) encode.invoke(manager, chunk, server.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE));
        return (CompoundTag) encoded.getValue();
    }
    private void deferredPoi() {
        var level = server.overworld(); var candidate = new net.minecraft.core.BlockPos(128, 96, 128);
        level.getChunkAt(candidate);
        while (candidate.getY() < 255 && (!level.getBlockState(candidate).isAir() || level.getPoiManager().getType(candidate).isPresent())) candidate = candidate.above();
        final var pos = candidate;
        // execute() from a running TickTask is queued by ReentrantBlockableEventLoop.
        server.tell(new net.minecraft.server.TickTask(server.getTickCount(), () -> {
            try (var effect = firefly520.fireflymc.ai.blueprint.NativeConstructionSave.begin(owner.player)) {
                require(level.getPoiManager().getType(pos).isEmpty(), "DEFERRED_POI_FIXTURE_NOT_FRESH");
                require(level.setBlockAndUpdate(pos, Blocks.SMITHING_TABLE.defaultBlockState()), "DEFERRED_POI_BLOCK_NOT_PLACED");
                require(level.getPoiManager().getType(pos).isEmpty(), "NATIVE_POI_WAS_NOT_QUEUED");
                checks.add("native_block_return_can_precede_queued_poi_update");
                deferredPoiSave.complete(effect.finish());
            } catch (Throwable failure) { deferredPoiSave.completeExceptionally(failure); }
        }));
    }
    private void additionalCallbacks() {
        try (var effect = firefly520.fireflymc.ai.blueprint.NativeConstructionSave.begin(owner.player)) {
            server.overworld().getDataStorage().set("ai4-additional-save-" + UUID.randomUUID(), new TestData());
            require(effect.unsupportedEffect().equals("BUILD_ADDITIONAL_SAVED_DATA_EFFECT"), "ADDITIONAL_NATIVE_STORE_NOT_DETECTED");
            try { effect.finish(); throw new AssertionError("ADDITIONAL_NATIVE_STORE_PRETENDED_TO_BE_COVERED"); }
            catch (IllegalStateException expected) { require(expected.getMessage().equals("BUILD_ADDITIONAL_SAVED_DATA_EFFECT"), "WRONG_SAVE_BOUNDARY_FAILURE"); }
        }
        checks.add("unadapted_callback_saved_data_cannot_borrow_ordinary_construction_receipt");
        try (var effect = firefly520.fireflymc.ai.blueprint.NativeConstructionSave.begin(owner.player)) {
            var entity = new net.minecraft.world.entity.decoration.ArmorStand(server.overworld(), 128.5, 100, 128.5);
            entity.setNoGravity(true); require(server.overworld().addFreshEntity(entity), "ADDITIONAL_ENTITY_FIXTURE_NOT_ADDED");
            require(effect.unsupportedEffect().equals("BUILD_ADDITIONAL_ENTITY_EFFECT"), "ADDITIONAL_NATIVE_ENTITY_NOT_DETECTED");
        }
        checks.add("unadapted_callback_entity_is_an_explicit_recovery_boundary");
    }
    private void containerSerialization() {
        var level = server.overworld(); var pos = owner.player.blockPosition().offset(6, 12, 6);
        while (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.east()).isAir() || !level.getBlockState(pos.west()).isAir()) pos = pos.above();
        level.setBlockAndUpdate(pos, Blocks.BARREL.defaultBlockState());
        var barrel = (net.minecraft.world.level.block.entity.BarrelBlockEntity) level.getBlockEntity(pos);
        var random = new Random(729351L);
        for (int slot = 0; slot < 18; slot++) {
            byte[] noise = new byte[96 * 1024]; random.nextBytes(noise); var tag = new CompoundTag(); tag.putByteArray("validation_noise", noise);
            var item = new ItemStack(Items.STONE); item.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
            barrel.setItem(slot, item);
        }
        largeContainerSave = saves.changed(List.of(NativeSaveObserver.blockEntity(level, barrel)));
        var brokenPos = pos.east(); level.setBlockAndUpdate(brokenPos, Blocks.CHEST.defaultBlockState());
        var broken = new FailingChest(brokenPos); level.setBlockEntity(broken);
        droppedContainerSave = saves.changed(List.of(NativeSaveObserver.blockEntity(level, broken)));
        var partialPos = pos.west(); level.setBlockAndUpdate(partialPos, Blocks.BARREL.defaultBlockState());
        var partial = level.getBlockEntity(partialPos);
        var unregistered = net.minecraft.core.component.DataComponentType.<String>builder().persistent(com.mojang.serialization.Codec.STRING).build();
        partial.setComponents(net.minecraft.core.component.DataComponentMap.builder().set(unregistered, "intentional-unregistered-component-fixture").build());
        partial.setChanged(); partialContainerSave = saves.changed(List.of(NativeSaveObserver.blockEntity(level, partial)));
        var chunk = new net.minecraft.world.level.ChunkPos(pos);
        externalChunk = NativeSaveObserver.dimensionFolder(level).resolve("region").resolve("c." + chunk.x + "." + chunk.z + ".mcc");
        level.save(null, true, false);
    }
    private void failedPlayerReplacement() throws Exception {
        try (var session = new Session()) {
            var nativeIo = playerIo(); nativeIo.save(session.player);
            Path data = nativeIo.getPlayerDir().toPath().resolve(session.player.getStringUUID() + ".dat");
            byte[] original = Files.readAllBytes(data);
            Path old = nativeIo.getPlayerDir().toPath().resolve(session.player.getStringUUID() + ".dat_old");
            // Only this new mock player's isolated backup path. Retain the injected failure for inspection.
            Files.createDirectory(old); Files.writeString(old.resolve("intentional-save-failure.txt"), "Keep this native failure fixture.");
            session.player.getInventory().setItem(0, new ItemStack(Items.DIAMOND));
            var receipt = saves.changed(List.of(NativeSaveObserver.player(session.player)));
            nativeIo.save(session.player); // Vanilla swallows the safeReplaceFile failure.
            require(!receipt.saved().isDone() && Arrays.equals(original, Files.readAllBytes(data)), "FAILED_PLAYER_REPLACEMENT_ACKNOWLEDGED");
            checks.add("player_replace_failure_normal_return_is_not_save_success");
        }
    }
    private PlayerDataStorage playerIo() throws Exception {
        var field = net.minecraft.server.players.PlayerList.class.getDeclaredField("playerIo"); field.setAccessible(true);
        return (PlayerDataStorage) field.get(server.getPlayerList());
    }
    private static void require(boolean value, String reason) { if (!value) throw new IllegalStateException(reason); }
    private static final class TestData extends SavedData {
        TestData() { setDirty(); }
        @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) { tag.putInt("actual", 31); return tag; }
    }
    private static final class FailingChest extends net.minecraft.world.level.block.entity.ChestBlockEntity {
        FailingChest(net.minecraft.core.BlockPos pos) { super(pos, Blocks.CHEST.defaultBlockState()); }
        @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            throw new IllegalStateException("INTENTIONAL_NATIVE_BLOCK_ENTITY_SAVE_FAILURE");
        }
    }
    @Override public void close() {
        if (inventory != null) inventory.close(); if (owner != null) owner.close(); if (stranger != null) stranger.close();
    }
    private final class Session implements AutoCloseable {
        final ServerPlayer player; final EmbeddedChannel channel;
        Session() {
            UUID id = UUID.randomUUID(); var cookie = new CommonListenerCookie(new GameProfile(id, "save-" + id.toString().substring(0, 8)),
                    0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
            player = new ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND); channel = new EmbeddedChannel(connection);
            NetworkRegistry.configureMockConnection(connection); server.getPlayerList().placeNewPlayer(connection, player, cookie);
            ValidationHandshake.reply(player); player.setGameMode(GameType.SURVIVAL); player.getInventory().clearContent();
        }
        Actor actor() { return Actor.player(player.getUUID(), true, true, 0); }
        @Override public void close() { server.getPlayerList().remove(player); IOUtilities.waitUntilIOWorkerComplete(); channel.finishAndReleaseAll(); }
    }
}
