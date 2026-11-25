package firefly520.fireflymc.validation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.integration.equipment.EquipmentTools;
import firefly520.fireflymc.ai.integration.equipment.PlayerEquipmentBridge;
import firefly520.fireflymc.ai.integration.maid.MaidCarrierBridge;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Real DedicatedServer fixtures in the explicitly selected isolated validation gameDir. No model/UI calls. */
public final class EquipmentScenario implements AutoCloseable {
    private final MinecraftServer server;
    private final JsonArray results = new JsonArray();
    private final List<Case> cases = new ArrayList<>();
    private int next;
    private double worstMaterialSliceMillis;
    private JsonObject caseEvidence = new JsonObject();
    private record Case(String name, String requiredMod, Checked action) {}
    private interface Checked { void run() throws Exception; }

    public EquipmentScenario(MinecraftServer server) {
        if (!server.isDedicatedServer()) throw new IllegalStateException("DEDICATED_EQUIPMENT_SCENARIO_REQUIRED");
        this.server = server;
        cases.add(new Case("own_equipment_private_and_no_armour_consumption", "", this::ownEquipment));
        cases.add(new Case("protected_material_blocks_prepared_extraction_without_hiding_physical_stock", "", this::protectedMaterial));
        cases.add(new Case("authorized_output_warehouse_revocation_and_conservation", "", this::outputWarehouse));
        cases.add(new Case("backpack_initialization_authorization_and_transfer", "sophisticatedbackpacks", this::backpackGrant));
        cases.add(new Case("backpack_move_invalidates_old_transfer", "sophisticatedbackpacks", this::backpackMove));
        cases.add(new Case("backpack_duplicate_uuid_refused", "sophisticatedbackpacks", this::duplicateBackpack));
        cases.add(new Case("cross_player_backpack_alias_cannot_extract_stale_native_balance", "sophisticatedbackpacks", this::crossPlayerBackpackAlias));
        cases.add(new Case("new_empty_backpack_without_serialized_inventory_remains_an_authorizable_source", "sophisticatedbackpacks", this::emptyBackpack));
        cases.add(new Case("backpack_total_stock_is_not_native_transfer_limit", "sophisticatedbackpacks", this::stackUpgrade));
        cases.add(new Case("backpack_void_and_infinity_refused", "sophisticatedbackpacks", this::unsafeUpgrades));
        cases.add(new Case("curios_native_equip_activity_unequip_and_curse", "curios+sophisticatedbackpacks", this::curiosEquipment));
        cases.add(new Case("accessories_native_equip_and_unequip_invalidate_host", "accessories+sophisticatedbackpacks", this::accessoriesEquipment));
        cases.add(new Case("curios_accessories_duplicate_backend_boundary", "curios+accessories+sophisticatedbackpacks", this::equipmentAliases));
        cases.add(new Case("maid_owner_and_native_task_interruption", "touhou_little_maid", this::maidOwnership));
        cases.add(new Case("maid_inventory_grant_native_transfer_and_owner_revocation", "touhou_little_maid", this::maidMaterials));
    }
    public JsonObject tick() {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        if (next < cases.size()) {
            var test = cases.get(next++); JsonObject row = new JsonObject(); row.addProperty("name", test.name());
            caseEvidence = new JsonObject();
            long started = System.nanoTime();
            var missing = Arrays.stream(test.requiredMod().split("\\+")).filter(id -> !id.isEmpty() && !ModList.get().isLoaded(id)).toList();
            if (!missing.isEmpty()) {
                row.addProperty("status", "NOT_RUN_MOD_ABSENT"); row.add("missingMods", AiJson.GSON.toJsonTree(missing));
            } else try {
                test.action().run(); row.addProperty("status", "PASSED");
            } catch (Exception | LinkageError failure) {
                Throwable cause = failure;
                for (int i = 0; i < 8 && cause.getCause() != null && cause.getCause() != cause; i++) cause = cause.getCause();
                row.addProperty("status", "FAILED"); row.addProperty("errorClass", cause.getClass().getSimpleName());
                String message = cause.getMessage(); row.addProperty("error", message == null ? "" : message.substring(0, Math.min(500, message.length())));
                row.add("failureFrames", AiJson.GSON.toJsonTree(Arrays.stream(cause.getStackTrace()).limit(4).map(Object::toString).toList()));
            }
            row.addProperty("milliseconds", (System.nanoTime() - started) / 1_000_000.0);
            row.add("evidence", caseEvidence); results.add(row);
            return null;
        }
        JsonObject report = new JsonObject(); report.add("checks", results.deepCopy());
        boolean failed = results.asList().stream().anyMatch(row -> row.getAsJsonObject().get("status").getAsString().equals("FAILED"));
        report.addProperty("status", failed ? "FAILED" : "PASSED");
        report.addProperty("dedicatedServer", server.isDedicatedServer());
        report.addProperty("worstMaterialSliceMillis", worstMaterialSliceMillis);
        report.addProperty("scope", "Actual dedicated service-chain, native fixture players and explicitly loaded mods; no real clients, rendering, external model or native maid movement-task claim.");
        return report;
    }
    private void protectedMaterial() throws Exception {
        try (var session = new Session(); var manager = manager()) {
            var actor = session.actor(); var workspace = manager.getWorkspace(actor);
            var preferences = new MaterialPreferences(workspace); var service = service(session);
            service.bindPreferences(actor.owner(), preferences);
            session.player.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 8));
            var before = planks(scan(service, actor)); var prepared = service.prepareExtraction(actor, before, 4);
            preferences.save(actor, 0, Set.of("minecraft:oak_planks"));
            var denied = service.extract(prepared, 4);
            require(!denied.mutationEntered() && denied.actual().empty(), "PROTECTED_PREPARED_TRANSFER_MUST_NOT_DISPATCH");
            require(session.player.getInventory().getItem(0).getCount() == 8, "PROTECTED_INPUT_UNCHANGED");
            var protectedStock = planks(scan(service, actor));
            require(protectedStock.stack().count() == 8 && protectedStock.extractableCount() == 0, "PROTECTION_IS_NOT_PHANTOM_MISSING_STOCK");
            preferences.save(actor, 1, Set.of()); var fresh = planks(scan(service, actor));
            var taken = service.extract(service.prepareExtraction(actor, fresh, 4), 4);
            require(taken.mutationEntered() && taken.actual().count() == 4 && session.player.getInventory().getItem(0).getCount() == 4,
                    "EXPLICIT_PLAYER_POLICY_CHANGE_REQUIRES_FRESH_NATIVE_VALIDATION");
            service.close();
        }
    }
    private void ownEquipment() throws Exception {
        try (var session = new Session(); var other = new Session()) {
            var player = session.player; var actor = session.actor();
            ItemStack helmet = new ItemStack(Items.DIAMOND_HELMET);
            helmet.set(DataComponents.CUSTOM_NAME, Component.literal("private-secret-name"));
            player.getInventory().armor.set(3, helmet);
            var bridge = new PlayerEquipmentBridge(server, p -> p == player);
            require(bridge.inspect(other.player, player.getUUID()).reason().equals("WORKSPACE_OWNER_MISMATCH"), "WORKSPACE_OWNER_GATE");
            require(new PlayerEquipmentBridge(server, p -> false).inspect(player, player.getUUID()).reason()
                    .equals("AUTHENTICATION_REQUIRED"), "AUTHENTICATION_GATE");
            var material = service(session); var tools = new EquipmentTools(server, material);
            JsonObject equipment = tools.inspectEquipment(actor, 0);
            String projected = equipment.toString();
            caseEvidence.add("equipmentAdapters", equipment.getAsJsonArray("adapters").deepCopy());
            require(projected.contains("minecraft:diamond_helmet") && !projected.contains("private-secret-name"), "EQUIPMENT_PRIVATE_COMPONENTS");
            require(scan(material, actor).rows().stream().noneMatch(row -> row.stack().itemJson().contains("diamond_helmet")), "ARMOUR_NOT_CONSUMABLE");
            material.close();
        }
    }
    private void outputWarehouse() throws Exception {
        try (var session = new Session(); var manager = manager()) {
            var player = session.player; var actor = session.actor(); var level = player.serverLevel();
            BlockPos pos = level.getSharedSpawnPos().offset(80, 0, 80).atY(80);
            level.getChunkAt(pos); level.setBlockAndUpdate(pos, Blocks.BARREL.defaultBlockState());
            player.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 1.5);
            var barrel = (BarrelBlockEntity) level.getBlockEntity(pos);
            require(barrel != null, "BARREL_FIXTURE_MISSING"); barrel.setItem(0, new ItemStack(Items.DIAMOND, 8));
            for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
            var service = service(session); var workspace = manager.getWorkspace(actor);
            var grants = new MaterialSourceGrants(workspace, System::currentTimeMillis); service.bind(actor.owner(), grants);
            var candidate = service.blockCandidate(actor, pos, Direction.UP, true, true, false);
            var preview = grants.preview(actor, candidate);
            var grant = grants.confirm(actor, preview.id(), preview.digest(), service.blockCandidate(actor, pos, Direction.UP, true, true, true));
            var row = scan(service, actor).rows().stream().filter(r -> r.stack().itemJson().contains("minecraft:diamond")).findFirst().orElseThrow();
            var ledger = new MaterialLedger(workspace.directory().getParent().getParent());
            var op = ledger.reserve(actor, UUID.randomUUID(), UUID.randomUUID(), AiJson.digest("authorized-output"), List.of(row), List.of(new MaterialLedger.Need(row.key(), 8)));
            var take = service.prepareExtraction(actor, row, 8);
            ledger.beginExtraction(actor, op.operationId(), 0, take.before(), take.poolUnits(), take.witness());
            var taken = service.extract(take, 8); require(taken.mutationEntered() && taken.error().isEmpty(), "BARREL_NATIVE_EXTRACTION");
            ledger.extracted(actor, op.operationId(), taken.actual(), taken.poolUnitsAfter()); op = ledger.cancel(actor, op.operationId());
            require(service.outputFits(actor, op.remainingProducts(), List.of(), List.of()), "AUTHORIZED_OUTPUT_SPACE_MISSING");
            var output = service.prepareDelivery(actor, op.remainingProducts().getFirst());
            grants.revoke(actor, grant.id());
            var refused = service.deliver(output);
            require(!refused.mutationEntered() && barrel.getItem(0).isEmpty(), "REVOKED_OUTPUT_AUTHORIZATION");
            candidate = service.blockCandidate(actor, pos, Direction.UP, true, true, false); preview = grants.preview(actor, candidate);
            grants.confirm(actor, preview.id(), preview.digest(), service.blockCandidate(actor, pos, Direction.UP, true, true, true));
            output = service.prepareDelivery(actor, op.remainingProducts().getFirst());
            ledger.beginDelivery(actor, op.operationId(), 0, output.product().count(), output.witness(), output.before());
            var delivered = service.deliver(output);
            op = ledger.delivered(actor, op.operationId(), delivered.accepted(), delivered.verified());
            require(op.state() == MaterialLedger.State.COMPLETED && barrel.getItem(0).getCount() == 8, "OUTPUT_NATIVE_CONSERVATION");
            service.close(); level.removeBlock(pos, false);
        }
    }
    private void backpackGrant() throws Exception {
        try (var session = new Session(); var manager = manager()) {
            var player = session.player; var actor = session.actor(); ItemStack host = backpack(); player.getInventory().setItem(0, host);
            var service = service(session); var before = ItemSnapshotCodec.capture(host, player.registryAccess());
            refused(() -> service.selectBackpack(actor, "inventory/0"), "BACKPACK_NOT_INITIALIZED");
            require(ItemSnapshotCodec.capture(host, player.registryAccess()).equals(before), "READ_INITIALIZED_BACKPACK");
            IItemHandler contents = initialize(host); contents.insertItem(0, new ItemStack(Items.OAK_PLANKS, 8), false);
            require(scan(service, actor).rows().stream().noneMatch(row -> row.stack().itemJson().contains("oak_planks")), "UNAUTHORIZED_BACKPACK_CONTENTS");
            var workspace = manager.getWorkspace(actor); grant(service, new MaterialSourceGrants(workspace, System::currentTimeMillis), actor, "inventory/0");
            var row = planks(scan(service, actor));
            caseEvidence.addProperty("physicalStock", row.stack().count());
            caseEvidence.addProperty("extractableStock", row.extractableCount());
            caseEvidence.addProperty("maximumNativeTransfer", row.maxTransferCount());
            var ledger = new MaterialLedger(workspace.directory().getParent().getParent());
            var operation = ledger.reserve(actor, UUID.randomUUID(), UUID.randomUUID(), AiJson.digest("backpack-transfer"),
                    List.of(row), List.of(new MaterialLedger.Need(row.key(), 4)));
            var prepared = service.prepareExtraction(actor, row, 4);
            ledger.beginExtraction(actor, operation.operationId(), 0, prepared.before(), prepared.poolUnits(), prepared.witness());
            var actual = service.extract(prepared, 4); require(actual.mutationEntered() && actual.error().isEmpty(), "BACKPACK_NATIVE_EXTRACTION");
            operation = ledger.extracted(actor, operation.operationId(), actual.actual(), actual.poolUnitsAfter());
            require(actual.actual().count() == 4 && contents.getStackInSlot(0).getCount() == 4
                    && operation.escrow().getFirst().count() == 4, "BACKPACK_INPUT_CONSERVATION");
            require(actual.save() != null, "BACKPACK_NATIVE_SAVE_BINDING_MISSING");
            server.getPlayerList().saveAll();
            require(!actual.save().saved().isDone(), "PLAYER_SAVE_USED_AS_BACKPACK_CONTENTS_PROOF");
            server.overworld().getDataStorage().save();
            net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
            require(actual.save().saved().isDone() && !actual.save().saved().isCompletedExceptionally(), "BACKPACK_ACTUAL_SAVED_DATA_WRITE_NOT_ACKNOWLEDGED");
            ledger.nativeSaved(actor, operation.operationId(), "extract", prepared.witness(), actual.save().saved().join());
            caseEvidence.addProperty("playerSaveProvesInternalInventory", false);
            caseEvidence.addProperty("actualSavedDataWriteAcknowledged", true);
            var current = planks(scan(service, actor));
            var second = ledger.reserve(actor, UUID.randomUUID(), UUID.randomUUID(), AiJson.digest("backpack-host-save-transfer"),
                    List.of(current), List.of(new MaterialLedger.Need(current.key(), 1)));
            var secondPrepared = service.prepareExtraction(actor, current, 1);
            ledger.beginExtraction(actor, second.operationId(), 0, secondPrepared.before(), secondPrepared.poolUnits(), secondPrepared.witness());
            var secondTaken = service.extract(secondPrepared, 1);
            require(secondTaken.actual().count() == 1 && secondTaken.save() != null, "SECOND_BACKPACK_TRANSFER_FAILED");
            ledger.extracted(actor, second.operationId(), secondTaken.actual(), secondTaken.poolUnitsAfter());
            server.overworld().getDataStorage().save(); net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
            require(!secondTaken.save().saved().isDone(), "SAVED_CONTENTS_USED_AS_UNSAVED_HOST_IDENTITY_PROOF");
            server.getPlayerList().saveAll();
            require(secondTaken.save().saved().isDone() && !secondTaken.save().saved().isCompletedExceptionally(), "BACKPACK_HOST_AND_CONTENTS_RECEIPT_MISSING");
            ledger.nativeSaved(actor, second.operationId(), "extract", secondPrepared.witness(), secondTaken.save().saved().join());
            require(contents.getStackInSlot(0).getCount() == 3, "SECOND_BACKPACK_TRANSFER_NOT_CONSERVED");
            caseEvidence.addProperty("contentsSaveProvesHostIdentity", false);
            caseEvidence.addProperty("actualHostAndContentsWritesAcknowledged", true);
            require(player.getInventory().getItem(0) == host, "BACKPACK_HOST_CONSUMED"); service.close();
        }
    }
    private void backpackMove() throws Exception {
        try (var session = new Session(); var manager = manager()) {
            var player = session.player; var actor = session.actor(); ItemStack host = backpack(); player.getInventory().setItem(0, host);
            IItemHandler contents = initialize(host); contents.insertItem(0, new ItemStack(Items.OAK_PLANKS, 8), false);
            var service = service(session); var grants = new MaterialSourceGrants(manager.getWorkspace(actor), System::currentTimeMillis);
            grant(service, grants, actor, "inventory/0"); var row = planks(scan(service, actor)); var prepared = service.prepareExtraction(actor, row, 4);
            player.getInventory().setItem(0, ItemStack.EMPTY); player.getInventory().setItem(1, host);
            require(!service.extract(prepared, 4).mutationEntered() && contents.getStackInSlot(0).getCount() == 8, "MOVED_HOST_TRANSFER_NOT_REJECTED");
            grant(service, grants, actor, "inventory/1");
            require(scan(service, actor).rows().stream().filter(r -> r.stack().itemJson().contains("oak_planks")).count() == 1, "BACKPACK_HOST_RELOCATION_DUPLICATED");
            player.getInventory().setItem(1, ItemStack.EMPTY); player.getInventory().armor.set(2, host);
            grant(service, grants, actor, "armor/2");
            require(scan(service, actor).rows().stream().filter(r -> r.stack().itemJson().contains("oak_planks")).count() == 1,
                    "EQUIPPED_BACKPACK_CONTENTS_NOT_RELOCATED");
            require(player.getInventory().armor.get(2) == host, "EQUIPPED_BACKPACK_HOST_CHANGED");
            service.close();
        }
    }
    private void duplicateBackpack() throws Exception {
        try (var session = new Session()) {
            ItemStack host = backpack(); session.player.getInventory().setItem(0, host); initialize(host);
            session.player.getInventory().setItem(1, host.copy()); var service = service(session);
            refused(() -> service.selectBackpack(session.actor(), "inventory/0"), "BACKPACK_BACKEND_AMBIGUOUS");
            require(session.player.getInventory().getItem(1).getCount() == 1, "DUPLICATE_HOST_REWRITTEN"); service.close();
        }
    }
    private void stackUpgrade() throws Exception {
        try (var session = new Session(); var manager = manager()) {
            var actor = session.actor(); ItemStack host = backpack(); session.player.getInventory().setItem(0, host); initialize(host);
            Class<?> type = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper");
            Object wrapper = type.getMethod("fromStack", ItemStack.class).invoke(null, host);
            var upgrade = BuiltInRegistries.ITEM.get(ResourceLocation.parse("sophisticatedbackpacks:stack_upgrade_tier_1"));
            require(upgrade != Items.AIR, "ACTUAL_STACK_UPGRADE_MISSING");
            var upgrades = (IItemHandler) type.getMethod("getUpgradeHandler").invoke(wrapper);
            require(upgrades.insertItem(0, new ItemStack(upgrade), false).isEmpty(), "STACK_UPGRADE_FIXTURE_REJECTED");
            var contents = (IItemHandler) type.getMethod("getInventoryHandler").invoke(wrapper);
            require(contents.insertItem(0, new ItemStack(Items.OAK_PLANKS, 128), false).isEmpty(), "STACK_UPGRADE_CAPACITY_MISSING");
            var service = service(session); var workspace = manager.getWorkspace(actor);
            grant(service, new MaterialSourceGrants(workspace, System::currentTimeMillis), actor, "inventory/0");
            var row = planks(scan(service, actor));
            caseEvidence.addProperty("physicalStock", row.stack().count());
            caseEvidence.addProperty("extractableStock", row.extractableCount());
            caseEvidence.addProperty("maximumNativeTransfer", row.maxTransferCount());
            require(row.stack().count() == 128 && row.extractableCount() == 128 && row.maxTransferCount() == 64,
                    "PHYSICAL_STOCK_CONFLATED_WITH_TRANSFER_LIMIT");
            var ledger = new MaterialLedger(workspace.directory().getParent().getParent());
            var op = ledger.reserve(actor, UUID.randomUUID(), UUID.randomUUID(), AiJson.digest("backpack-batch"),
                    List.of(row), List.of(new MaterialLedger.Need(row.key(), 96)));
            int calls = 0;
            JsonArray transfers = new JsonArray(); caseEvidence.add("transfers", transfers);
            while (op.claims().getFirst().remaining() > 0) {
                row = planks(scan(service, actor)); int amount = Math.min(op.claims().getFirst().remaining(), row.maxTransferCount());
                var prepared = service.prepareExtraction(actor, row, amount);
                ledger.beginExtraction(actor, op.operationId(), 0, prepared.before(), prepared.poolUnits(), prepared.witness(), amount);
                var actual = service.extract(prepared, amount); require(actual.mutationEntered() && actual.error().isEmpty(), "BATCH_EXTRACTION_FAILED");
                JsonObject transfer = new JsonObject(); transfer.addProperty("requested", amount);
                transfer.addProperty("returned", actual.actual().count()); transfer.addProperty("beforePool", prepared.poolUnits());
                transfer.addProperty("afterPool", actual.poolUnitsAfter()); transfers.add(transfer);
                op = ledger.extracted(actor, op.operationId(), actual.actual(), actual.poolUnitsAfter()); calls++;
                require(calls <= 2, "BATCH_EXTRACTION_DID_NOT_FINISH");
            }
            int remaining = contents.getStackInSlot(0).getCount();
            int escrow = op.escrow().stream().mapToInt(MaterialStack::count).sum();
            caseEvidence.addProperty("remainingNativeStock", remaining); caseEvidence.addProperty("escrowTotal", escrow);
            caseEvidence.add("escrowChunks", AiJson.GSON.toJsonTree(op.escrow().stream().map(MaterialStack::count).toList()));
            caseEvidence.addProperty("ledgerState", op.state().name());
            require(calls == 2 && remaining == 32 && escrow == 96 && op.state() == MaterialLedger.State.ESCROWED,
                    "BATCH_EXTRACTION_CONSERVATION"); service.close();
        }
    }
    private void unsafeUpgrades() throws Exception {
        try (var session = new Session()) {
            var service = service(session);
            for (String id : List.of("void_upgrade", "infinity_upgrade")) {
                ItemStack host = backpack(); session.player.getInventory().setItem(0, host); initialize(host);
                Class<?> type = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper");
                Object wrapper = type.getMethod("fromStack", ItemStack.class).invoke(null, host);
                var upgrade = BuiltInRegistries.ITEM.get(ResourceLocation.parse("sophisticatedbackpacks:" + id));
                require(upgrade != Items.AIR, "ACTUAL_UNSAFE_UPGRADE_MISSING");
                var handler = (IItemHandlerModifiable) type.getMethod("getUpgradeHandler").invoke(wrapper);
                // Construct an existing unsafe native inventory fixture, including creative-only
                // upgrades. The production adapter must refuse it even with an authenticated owner.
                handler.setStackInSlot(0, new ItemStack(upgrade));
                refused(() -> service.selectBackpack(session.actor(), "inventory/0"), "DESTRUCTIVE_OR_INFINITE_UPGRADE");
            }
            service.close();
        }
    }
    private void curiosEquipment() throws Exception {
        try (var session = new Session(); var manager = manager()) {
            var actor = session.actor(); var service = service(session);
            var nativeInventory = curios(session.player);
            ItemStack host = backpack(); initialize(host).insertItem(0, new ItemStack(Items.OAK_PLANKS, 8), false);
            require(nativeInventory.slots().isItemValid(0, host), "CURIOS_NATIVE_SLOT_REJECTED_BACKPACK");
            require(nativeInventory.slots().insertItem(0, host, false).isEmpty(), "CURIOS_NATIVE_EQUIP_FAILED");
            ItemStack equipped = nativeInventory.slots().getStackInSlot(0); warmExisting(equipped);
            var grants = new MaterialSourceGrants(manager.getWorkspace(actor), System::currentTimeMillis);
            var observed = service.equipment().inspect(session.player, session.player.getUUID());
            caseEvidence.add("adapters", AiJson.GSON.toJsonTree(observed.adapters()));
            require(observed.slots().stream().anyMatch(s -> s.source().equals("curios") && s.slotName().equals("back")
                    && s.item().itemId().equals("sophisticatedbackpacks:backpack") && !s.consumptionAuthorized()), "CURIOS_OBSERVATION_MISSING");
            require(scan(service, actor).rows().stream().noneMatch(r -> r.stack().itemJson().contains("oak_planks")), "EQUIPMENT_VIEW_GRANTED_CONTENTS");
            grant(service, grants, actor, "curios/back/0");
            var prepared = service.prepareExtraction(actor, planks(scan(service, actor)), 4);
            nativeInventory.api().getMethod("setSlotActive", String.class, int.class, boolean.class)
                    .invoke(nativeInventory.inventory(), "back", 0, false);
            require(!service.extract(prepared, 4).mutationEntered(), "INACTIVE_CURIOS_HOST_STILL_USABLE");
            nativeInventory.api().getMethod("setSlotActive", String.class, int.class, boolean.class)
                    .invoke(nativeInventory.inventory(), "back", 0, true);
            ItemStack removed = nativeInventory.slots().extractItem(0, 1, false);
            require(!removed.isEmpty(), "CURIOS_NATIVE_UNEQUIP_FAILED");
            session.player.getInventory().setItem(0, removed);
            require(!service.extract(prepared, 4).mutationEntered(), "UNEQUIPPED_CURIOS_HOST_STILL_USABLE");
            session.player.getInventory().setItem(0, ItemStack.EMPTY);
            removed.enchant(session.player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.BINDING_CURSE), 1);
            require(nativeInventory.slots().insertItem(0, removed, false).isEmpty(), "CURSED_CURIOS_FIXTURE_EQUIP_FAILED");
            require(nativeInventory.slots().extractItem(0, 1, false).isEmpty(), "NATIVE_BINDING_CURSE_BYPASSED");
            caseEvidence.addProperty("nativeCurseRetainedHost", true); service.close();
        }
    }
    private void accessoriesEquipment() throws Exception {
        try (var session = new Session(); var manager = manager()) {
            var actor = session.actor(); var service = service(session); ItemStack host = backpack();
            initialize(host).insertItem(0, new ItemStack(Items.OAK_PLANKS, 8), false);
            var equipped = accessoriesEquip(session.player, host);
            require(equipped != null, "ACCESSORIES_NATIVE_EQUIP_FAILED"); warmExisting(equipped.stack());
            settleAccessories(session.player);
            var observed = service.equipment().inspect(session.player, session.player.getUUID());
            caseEvidence.add("adapters", AiJson.GSON.toJsonTree(observed.adapters()));
            require(observed.slots().stream().anyMatch(s -> s.source().equals("accessories") && s.slotName().equals(equipped.slotName())
                    && s.item().itemId().equals("sophisticatedbackpacks:backpack") && !s.consumptionAuthorized()), "ACCESSORIES_OBSERVATION_MISSING");
            require(scan(service, actor).rows().stream().noneMatch(r -> r.stack().itemJson().contains("oak_planks")), "ACCESSORIES_VIEW_GRANTED_CONTENTS");
            var grants = new MaterialSourceGrants(manager.getWorkspace(actor), System::currentTimeMillis);
            grant(service, grants, actor, equipped.locator());
            var prepared = service.prepareExtraction(actor, planks(scan(service, actor)), 4);
            Class<?> api = Class.forName("io.wispforest.accessories.api.AccessoriesAPI");
            require((boolean) api.getMethod("canUnequip", ItemStack.class, equipped.referenceApi()).invoke(null, equipped.stack(), equipped.reference()),
                    "ACCESSORIES_NATIVE_UNEQUIP_DENIED");
            require((boolean) equipped.referenceApi().getMethod("setStack", ItemStack.class).invoke(equipped.reference(), ItemStack.EMPTY),
                    "ACCESSORIES_NATIVE_UNEQUIP_FAILED");
            session.player.getInventory().setItem(0, equipped.stack());
            require(!service.extract(prepared, 4).mutationEntered(), "UNEQUIPPED_ACCESSORIES_HOST_STILL_USABLE");
            service.close();
        }
    }
    private void equipmentAliases() throws Exception {
        try (var session = new Session(); var manager = manager()) {
            var actor = session.actor(); var service = service(session); var nativeInventory = curios(session.player);
            ItemStack host = backpack(); initialize(host).insertItem(0, new ItemStack(Items.OAK_PLANKS, 8), false);
            require(nativeInventory.slots().insertItem(0, host, false).isEmpty(), "CURIOS_NATIVE_EQUIP_FAILED");
            ItemStack first = nativeInventory.slots().getStackInSlot(0); warmExisting(first);
            var grants = new MaterialSourceGrants(manager.getWorkspace(actor), System::currentTimeMillis);
            grant(service, grants, actor, "curios/back/0");
            var duplicate = accessoriesEquip(session.player, first.copy());
            if (duplicate == null) {
                caseEvidence.addProperty("duplicateOutcome", "NATIVE_SECOND_EQUIP_DENIED");
                require(scan(service, actor).rows().stream().filter(r -> r.stack().itemJson().contains("oak_planks")).count() == 1,
                        "NATIVE_DUPLICATE_DENIAL_CHANGED_STOCK");
            } else {
                warmExisting(duplicate.stack());
                settleAccessories(session.player);
                caseEvidence.addProperty("duplicateOutcome", "NATIVE_SLOTS_ALLOW_DISTINCT_HOSTS_SAME_UUID_ADAPTER_REFUSES");
                refused(() -> service.selectBackpack(actor, duplicate.locator()), "BACKPACK_BACKEND_AMBIGUOUS");
                var snapshot = scan(service, actor);
                require(snapshot.rows().stream().noneMatch(r -> r.stack().itemJson().contains("oak_planks")), "DUPLICATED_BACKEND_COUNTED");
                require(snapshot.refusals().stream().anyMatch(r -> r.reason().equals("BACKPACK_BACKEND_AMBIGUOUS")), "DUPLICATED_BACKEND_NOT_EXPLAINED");
            }
            service.close();
        }
    }
    private record CuriosInventory(Class<?> api, Object inventory, IItemHandler slots) {}
    private static CuriosInventory curios(ServerPlayer player) throws Exception {
        Class<?> facade = Class.forName("top.theillusivec4.curios.api.CuriosApi");
        Object value = ((Optional<?>) facade.getMethod("getCuriosInventory", LivingEntity.class).invoke(null, player)).orElseThrow();
        Class<?> api = Class.forName("top.theillusivec4.curios.api.type.capability.ICuriosItemHandler");
        Object group = ((Optional<?>) api.getMethod("getStacksHandler", String.class).invoke(value, "back")).orElseThrow();
        Class<?> groups = Class.forName("top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler");
        IItemHandler slots = (IItemHandler) groups.getMethod("getStacks").invoke(group);
        require(slots.getSlots() > 0, "NATIVE_CURIOS_BACK_SLOT_MISSING");
        return new CuriosInventory(api, value, slots);
    }
    private record AccessoryHost(Class<?> referenceApi, Object reference, String slotName, int index, ItemStack stack) {
        String locator() { return "accessories/" + slotName + "/" + index; }
    }
    private static AccessoryHost accessoriesEquip(ServerPlayer player, ItemStack host) throws Exception {
        Class<?> api = Class.forName("io.wispforest.accessories.api.AccessoriesCapability");
        Object capability = api.getMethod("get", LivingEntity.class).invoke(null, player);
        require(capability != null, "NATIVE_ACCESSORIES_CAPABILITY_MISSING");
        api.getMethod("getContainers").invoke(capability); // Native fixture initialization, never a production AI read.
        Object reference = api.getMethod("attemptToEquipAccessory", ItemStack.class).invoke(capability, host);
        if (reference == null) return null;
        Class<?> refs = Class.forName("io.wispforest.accessories.api.slot.SlotReference");
        ItemStack actual = (ItemStack) refs.getMethod("getStack").invoke(reference);
        return new AccessoryHost(refs, reference, (String) refs.getMethod("slotName").invoke(reference),
                (int) refs.getMethod("slot").invoke(reference), actual);
    }
    private static void warmExisting(ItemStack host) throws Exception {
        Class<?> type = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper");
        Object wrapper = type.getMethod("fromStack", ItemStack.class).invoke(null, host);
        type.getMethod("getInventoryHandler").invoke(wrapper);
    }
    private static void settleAccessories(ServerPlayer player) throws Exception {
        // The test fixture completes the mod's normal update cycle after native equipment
        // mutations. Production observation never initializes/resizes native containers.
        Class<?> api = Class.forName("io.wispforest.accessories.api.AccessoriesCapability");
        Object capability = api.getMethod("get", LivingEntity.class).invoke(null, player);
        api.getMethod("updateContainers").invoke(capability);
    }
    private void maidOwnership() throws Exception {
        Entity entity = null;
        try (var session = new Session(); var other = new Session()) {
            entity = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("touhou_little_maid:maid")).create(server.overworld());
            if (!(entity instanceof TamableAnimal maid)) throw new IllegalStateException("ACTUAL_MAID_TYPE_MISSING");
            maid.tame(session.player); maid.setNoAi(true); maid.moveTo(session.player.getX(), session.player.getY(), session.player.getZ());
            server.overworld().addFreshEntity(maid); var bridge = new MaidCarrierBridge(server, p -> true);
            var source = bridge.backpackSource(session.player, session.player.getUUID(), maid.getUUID());
            require(source.slots() > 0 && source.slots() <= 36, "MAID_BACKPACK_RANGE");
            refused(() -> bridge.backpackSource(other.player, other.player.getUUID(), maid.getUUID()), "MAID_OWNER_MISMATCH");
            require(bridge.bindOwned(session.player, session.player.getUUID(), maid.getUUID()).binding() != null, "MAID_BINDING_FAILED");
            maid.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, other.player);
            refused(() -> bridge.backpackSource(session.player, session.player.getUUID(), maid.getUUID()), "NATIVE_MAID_BUSY");
            maid.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET); maid.setOwnerUUID(other.player.getUUID());
            require(bridge.validateBinding(session.player, session.player.getUUID()).binding() == null, "MAID_OWNER_CHANGE_NOT_REVOKED");
        } finally { if (entity != null) entity.discard(); }
    }
    private void maidMaterials() throws Exception {
        Entity entity = null;
        try (var session = new Session(); var other = new Session(); var manager = manager()) {
            var actor = session.actor();
            entity = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("touhou_little_maid:maid")).create(server.overworld());
            if (!(entity instanceof TamableAnimal maid)) throw new IllegalStateException("ACTUAL_MAID_TYPE_MISSING");
            maid.tame(session.player); maid.setNoAi(true); maid.moveTo(session.player.getX(), session.player.getY(), session.player.getZ());
            server.overworld().addFreshEntity(maid);
            var service = service(session); var nativeSource = service.maids().backpackSource(session.player, session.player.getUUID(), maid.getUUID());
            require(nativeSource.inventory().insertItem(0, new ItemStack(Items.OAK_PLANKS, 8), false).isEmpty(), "MAID_NATIVE_INPUT_FIXTURE_REJECTED");
            require(service.maids().bindOwned(session.player, session.player.getUUID(), maid.getUUID()).binding() != null, "MAID_BINDING_FAILED");
            require(scan(service, actor).rows().stream().noneMatch(row -> row.stack().itemJson().contains("oak_planks")), "CARRIER_BINDING_GRANTED_MATERIALS");
            var workspace = manager.getWorkspace(actor); var grants = new MaterialSourceGrants(workspace, System::currentTimeMillis);
            service.bind(actor.owner(), grants); var selection = service.selectMaidBackpack(actor, maid.getUUID());
            var preview = grants.preview(actor, selection.candidate()); grants.confirm(actor, preview.id(), preview.digest(), service.confirmSelection(actor, selection));
            var row = planks(scan(service, actor)); var ledger = new MaterialLedger(workspace.directory().getParent().getParent());
            var op = ledger.reserve(actor, UUID.randomUUID(), UUID.randomUUID(), AiJson.digest("maid-materials"), List.of(row), List.of(new MaterialLedger.Need(row.key(), 4)));
            var prepared = service.prepareExtraction(actor, row, 4);
            ledger.beginExtraction(actor, op.operationId(), 0, prepared.before(), prepared.poolUnits(), prepared.witness());
            var taken = service.extract(prepared, 4); require(taken.mutationEntered() && taken.error().isEmpty(), "MAID_NATIVE_EXTRACTION_FAILED");
            op = ledger.extracted(actor, op.operationId(), taken.actual(), taken.poolUnitsAfter());
            require(op.state() == MaterialLedger.State.ESCROWED && nativeSource.inventory().getStackInSlot(0).getCount() == 4, "MAID_EXTRACTION_CONSERVATION");
            prepared = service.prepareExtraction(actor, planks(scan(service, actor)), 1);
            maid.setOwnerUUID(other.player.getUUID());
            require(!service.extract(prepared, 1).mutationEntered() && nativeSource.inventory().getStackInSlot(0).getCount() == 4,
                    "MAID_OWNER_CHANGE_DID_NOT_REVOKE_MATERIAL_SOURCE");
            caseEvidence.addProperty("consumedIntoEscrow", taken.actual().count()); caseEvidence.addProperty("nativeRemaining", 4);
            caseEvidence.addProperty("carrierBindingDidNotGrantInventory", true); service.close();
        } finally { if (entity != null) entity.discard(); }
    }
    private MaterialInventoryService service(Session session) {
        return new MaterialInventoryService(server, player -> player == session.player, new InstalledPermissionAdapters(server));
    }
    private MaterialInventoryService.Snapshot scan(MaterialInventoryService service, Actor actor) {
        var future = service.scan(actor);
        for (int i = 0; i < 512 && !future.isDone(); i++) service.tick();
        require(future.isDone(), "MATERIAL_SCAN_DID_NOT_FINISH");
        worstMaterialSliceMillis = Math.max(worstMaterialSliceMillis, service.maximumSliceNanos() / 1_000_000.0);
        return future.join();
    }
    private static MaterialLedger.Stock planks(MaterialInventoryService.Snapshot snapshot) {
        return snapshot.rows().stream().filter(r -> r.stack().itemJson().contains("oak_planks")).findFirst().orElseThrow();
    }
    private WorkspaceManager manager() throws Exception {
        Path root = server.getWorldPath(LevelResource.ROOT).resolve("ai4-equipment-validation").toAbsolutePath(); Files.createDirectories(root);
        return new WorkspaceManager(Files.createTempDirectory(root, "case-"));
    }
    private static void grant(MaterialInventoryService service, MaterialSourceGrants grants, Actor actor, String locator) throws Exception {
        service.bind(actor.owner(), grants); var source = service.selectBackpack(actor, locator); var preview = grants.preview(actor, source.candidate());
        grants.confirm(actor, preview.id(), preview.digest(), service.confirmSelection(actor, source));
    }
    private static ItemStack backpack() {
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse("sophisticatedbackpacks:backpack"));
        require(item != Items.AIR, "ACTUAL_BACKPACK_ITEM_MISSING"); return new ItemStack(item);
    }
    private void crossPlayerBackpackAlias() throws Exception {
        try (var a = new Session(); var b = new Session(); var manager = manager()) {
            ValidationHandshake.authenticateFixture(a.player); ValidationHandshake.authenticateFixture(b.player);
            // Authentication may grant the native starter kit; only the selected backend is the fixture input.
            a.player.getInventory().clearContent(); b.player.getInventory().clearContent();
            var permissions = new InstalledPermissionAdapters(server);
            var first = new MaterialInventoryService(server, firefly520.fireflymc.ai.AiServerService::authenticated, permissions);
            var second = new MaterialInventoryService(server, firefly520.fireflymc.ai.AiServerService::authenticated, permissions);
            try {
                ItemStack host = backpack(); a.player.getInventory().setItem(0, host);
                IItemHandler nativeFirst = initialize(host);
                require(nativeFirst.insertItem(0, new ItemStack(Items.OAK_PLANKS, 8), false).isEmpty(), "ALIAS_INPUT_FIXTURE_REJECTED");
                ItemStack copy = host.copy(); b.player.getInventory().setItem(0, copy);
                Class<?> type = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper");
                Object secondWrapper = type.getMethod("fromStack", ItemStack.class).invoke(null, copy);
                type.getMethod("getInventoryHandler").invoke(secondWrapper);
                var ga = new MaterialSourceGrants(manager.getWorkspace(a.actor()), System::currentTimeMillis);
                var gb = new MaterialSourceGrants(manager.getWorkspace(b.actor()), System::currentTimeMillis);
                grant(first, ga, a.actor(), "inventory/0"); grant(second, gb, b.actor(), "inventory/0");
                var stockA = planks(scan(first, a.actor())); var stockB = planks(scan(second, b.actor()));
                require(stockA.poolId().equals(stockB.poolId()), "ALIAS_FIXTURE_NOT_SAME_NATIVE_POOL");
                var ledger = new MaterialLedger(manager.getWorkspace(a.actor()).directory().getParent().getParent());
                var opA = ledger.reserve(a.actor(), UUID.randomUUID(), UUID.randomUUID(), AiJson.digest("alias-a"), List.of(stockA), List.of(new MaterialLedger.Need(stockA.key(), 4)));
                var opB = ledger.reserve(b.actor(), UUID.randomUUID(), UUID.randomUUID(), AiJson.digest("alias-b"), List.of(stockB), List.of(new MaterialLedger.Need(stockB.key(), 4)));
                var preparedB = second.prepareExtraction(b.actor(), stockB, 4);
                var preparedA = first.prepareExtraction(a.actor(), stockA, 4);
                ledger.beginExtraction(a.actor(), opA.operationId(), 0, preparedA.before(), preparedA.poolUnits(), preparedA.witness());
                ledger.beginExtraction(b.actor(), opB.operationId(), 0, preparedB.before(), preparedB.poolUnits(), preparedB.witness());
                var actualA = first.extract(preparedA, 4);
                require(actualA.mutationEntered() && actualA.error().isEmpty(), "ALIAS_FIRST_EXTRACTION_FAILED");
                ledger.extracted(a.actor(), opA.operationId(), actualA.actual(), actualA.poolUnitsAfter());
                var actualB = second.extract(preparedB, 4);
                if (actualB.mutationEntered()) ledger.extracted(b.actor(), opB.operationId(), actualB.actual(), actualB.poolUnitsAfter());
                else ledger.rejectedBeforeExecution(b.actor(), opB.operationId(), actualB.error());
                caseEvidence.addProperty("firstRealExtracted", actualA.actual().count());
                caseEvidence.addProperty("secondNativeEntered", actualB.mutationEntered());
                caseEvidence.addProperty("secondRefusal", actualB.error());
                require(!actualB.mutationEntered(), "STALE_BACKPACK_ALIAS_DUPLICATED_ITEMS");
            } finally { first.close(); second.close(); }
        }
    }
    private void emptyBackpack() throws Exception {
        try (var session = new Session(); var manager = manager()) {
            ItemStack host = backpack(); session.player.getInventory().setItem(0, host); IItemHandler nativeInventory = initialize(host);
            var service = service(session);
            try {
                var grants = new MaterialSourceGrants(manager.getWorkspace(session.actor()), System::currentTimeMillis);
                grant(service, grants, session.actor(), "inventory/0");
                require(grants.list(session.actor()).size() == 1, "EMPTY_BACKPACK_AUTHORIZATION_REJECTED");
                for (int i = 0; i < nativeInventory.getSlots(); i++) require(nativeInventory.getStackInSlot(i).isEmpty(), "EMPTY_PROBE_CREATED_ITEMS");
            } finally { service.close(); }
        }
    }
    private static IItemHandler initialize(ItemStack host) throws Exception {
        Class<?> type = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper");
        Object wrapper = type.getMethod("fromStack", ItemStack.class).invoke(null, host);
        type.getMethod("setContentsUuid", UUID.class).invoke(wrapper, UUID.randomUUID());
        return (IItemHandler) type.getMethod("getInventoryHandler").invoke(wrapper);
    }
    private static void refused(Checked action, String reason) throws Exception {
        try { action.run(); } catch (RuntimeException failure) { if (reason.equals(failure.getMessage())) return; throw failure; }
        throw new IllegalStateException("EXPECTED_REFUSAL_" + reason);
    }
    private static void require(boolean value, String reason) { if (!value) throw new IllegalStateException(reason); }
    @Override public void close() { }
    private final class Session implements AutoCloseable {
        final ServerPlayer player; final EmbeddedChannel channel;
        Session() {
            UUID id = UUID.randomUUID(); var cookie = new CommonListenerCookie(new GameProfile(id, "equip-" + id.toString().substring(0, 8)),
                    0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
            player = new ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND); channel = new EmbeddedChannel(connection);
            NetworkRegistry.configureMockConnection(connection); server.getPlayerList().placeNewPlayer(connection, player, cookie);
            ValidationHandshake.reply(player);
            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().clearContent();
        }
        Actor actor() { return Actor.player(player.getUUID(), true, true, 0); }
        @Override public void close() {
            server.getPlayerList().remove(player); net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
            channel.finishAndReleaseAll();
        }
    }
}
