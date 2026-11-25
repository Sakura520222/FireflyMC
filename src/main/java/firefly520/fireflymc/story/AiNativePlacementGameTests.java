package firefly520.fireflymc.story;

import firefly520.fireflymc.ai.blueprint.NativePlacementKernel;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.blueprint.*;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.AgentTask;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import java.util.*;
import java.util.function.Consumer;

@GameTestHolder("fireflymc")
@PrefixGameTestTemplate(false)
public final class AiNativePlacementGameTests {
    @GameTest(template = "story_empty", batch = "ai-native-placement", timeoutTicks = 80)
    public static void realExtractedBlockUsesNativePlacementWithoutMovingOwner(GameTestHelper helper) {
        try (var session = new PlayerSession(helper)) {
        var owner = session.player;
        var position = owner.position(); float yaw = owner.getYRot(); var advancement = owner.getAdvancements();
        owner.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS));
        var taken = NativeMaterialTransfer.extract(new InvWrapper(owner.getInventory()), 0, 1, owner.registryAccess(), () -> owner.getInventory().getItem(0).getCount());
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 2, 1));
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.DIRT);
        var request = new NativePlacementKernel.Request(Map.of(anchor, Blocks.AIR.defaultBlockState(), anchor.below(), Blocks.DIRT.defaultBlockState()),
                Map.of(anchor, Blocks.OAK_PLANKS.defaultBlockState()), new BlockHitResult(Vec3.atCenterOf(anchor), Direction.UP, anchor, false), 0, 0, taken.actual());
        var outcome = NativePlacementKernel.place(owner, request, (player, pos) -> {
            if (player != owner || !request.before().containsKey(pos)) throw new SecurityException("TEST_REGION_DENIED");
        });
        helper.assertTrue(outcome.success() && !outcome.uncertain(), "Actual native placement succeeds with a consumed real input");
        helper.assertTrue(helper.getLevel().getBlockState(anchor).is(Blocks.OAK_PLANKS), "The native world contains the planned block");
        helper.assertTrue(owner.getInventory().getItem(0).isEmpty() && outcome.remaining().empty(), "One real item was extracted and consumed");
        helper.assertValueEqual(owner.position(), position, "Owner position is unchanged");
        helper.assertValueEqual(owner.getYRot(), yaw, "Owner camera orientation is unchanged");
        helper.assertTrue(owner.getAdvancements() == advancement, "The live advancement object is preserved");
        helper.assertTrue(outcome.nativeFootprint().contains(anchor), "NeoForge placement event observed the change");
        helper.succeed();
        }
    }
    @GameTest(template = "story_empty", batch = "ai-native-placement", timeoutTicks = 80)
    public static void cancelledNativePlaceRestoresWorldAndKeepsEscrowMaterial(GameTestHelper helper) {
        try (var session = new PlayerSession(helper)) {
        var owner = session.player;
        BlockPos anchor = helper.absolutePos(new BlockPos(2, 2, 2));
        var material = ItemSnapshotCodec.capture(new ItemStack(Items.STONE), owner.registryAccess());
        var request = new NativePlacementKernel.Request(Map.of(anchor, Blocks.AIR.defaultBlockState()), Map.of(anchor, Blocks.STONE.defaultBlockState()),
                new BlockHitResult(Vec3.atCenterOf(anchor), Direction.UP, anchor, false), 0, 0, material);
        Consumer<BlockEvent.EntityPlaceEvent> deny = event -> { if (event.getEntity() != null && event.getEntity().getUUID().equals(owner.getUUID())) event.setCanceled(true); };
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, false, deny);
        try {
            var outcome = NativePlacementKernel.place(owner, request, (player, pos) -> { if (!pos.equals(anchor)) throw new SecurityException("TEST_REGION_DENIED"); });
            helper.assertTrue(!outcome.success() && !outcome.uncertain(), "Cancellation is a verified rejection, not a successful placement");
            helper.assertTrue(helper.getLevel().getBlockState(anchor).isAir(), "NeoForge restored the cancelled block snapshot");
            helper.assertValueEqual(outcome.remaining(), material, "The native stack remains available to the caller's escrow");
        } finally { NeoForge.EVENT_BUS.unregister(deny); }
        helper.succeed();
        }
    }
    @GameTest(template = "story_empty", batch = "ai-native-placement", timeoutTicks = 80)
    public static void targetMismatchCannotTurnARealStoneIntoDiamondBlock(GameTestHelper helper) {
        try (var session = new PlayerSession(helper)) {
        var owner = session.player;
        BlockPos anchor = helper.absolutePos(new BlockPos(3, 2, 3));
        var material = ItemSnapshotCodec.capture(new ItemStack(Items.STONE), owner.registryAccess());
        var request = new NativePlacementKernel.Request(Map.of(anchor, Blocks.AIR.defaultBlockState()), Map.of(anchor, Blocks.DIAMOND_BLOCK.defaultBlockState()),
                new BlockHitResult(Vec3.atCenterOf(anchor), Direction.UP, anchor, false), 0, 0, material);
        var outcome = NativePlacementKernel.place(owner, request, (player, pos) -> { if (!pos.equals(anchor)) throw new SecurityException("TEST_REGION_DENIED"); });
        helper.assertTrue(!outcome.success() && !outcome.uncertain(), "A planned state must equal the actual native item placement");
        helper.assertTrue(helper.getLevel().getBlockState(anchor).isAir(), "Invalid target did not alter the world");
        helper.assertValueEqual(outcome.remaining(), material, "Stone was not converted into another material");
        helper.succeed();
        }
    }
    @GameTest(template = "story_empty", batch = "ai-native-placement", timeoutTicks = 80)
    public static void oneDoorItemCreatesBothApprovedHalvesThroughTheMultiPlaceEvent(GameTestHelper helper) {
        try (var session = new PlayerSession(helper)) {
            var owner = session.player; BlockPos lower = helper.absolutePos(new BlockPos(2, 2, 2)), upper = lower.above();
            helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE);
            var lowState = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
            var highState = lowState.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
            var request = new NativePlacementKernel.Request(Map.of(lower, Blocks.AIR.defaultBlockState(), upper, Blocks.AIR.defaultBlockState(), lower.below(), Blocks.STONE.defaultBlockState()),
                    Map.of(lower, lowState, upper, highState), new BlockHitResult(new Vec3(lower.getX() + 0.75, lower.getY() + 0.5, lower.getZ() + 0.5), Direction.UP, lower, false),
                    0, 0, ItemSnapshotCodec.capture(new ItemStack(Items.OAK_DOOR), owner.registryAccess()));
            var outcome = NativePlacementKernel.place(owner, request, (player, pos) -> { if (!request.before().containsKey(pos)) throw new SecurityException("TEST_REGION_DENIED"); });
            helper.assertTrue(outcome.success(), "Native door placement must match both approved halves: " + outcome.code());
            helper.assertTrue(outcome.remaining().empty(), "Only one door item is consumed");
            helper.assertTrue(outcome.nativeFootprint().containsAll(List.of(lower, upper)), "Native multi-place captures both positions");
            helper.succeed();
        }
    }
    @GameTest(template = "story_empty", batch = "ai-native-placement", timeoutTicks = 80)
    public static void virtualActorDoesNotInheritOperatorCommandPrivileges(GameTestHelper helper) {
        try (var session = new PlayerSession(helper)) {
            var owner = session.player; var players = owner.server.getPlayerList(); players.op(owner.getGameProfile());
            var saw = new java.util.concurrent.atomic.AtomicBoolean();
            Consumer<BlockEvent.EntityPlaceEvent> check = event -> {
                if (event.getEntity() instanceof ServerPlayer actor && actor.getUUID().equals(owner.getUUID())) {
                    saw.set(true); helper.assertTrue(!actor.hasPermissions(2) && !actor.getAbilities().instabuild,
                            "Proxy retains survival costs and zero command permission despite attributed OP profile");
                }
            };
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, false, check);
            try {
                BlockPos pos = helper.absolutePos(new BlockPos(3, 2, 1));
                var request = new NativePlacementKernel.Request(Map.of(pos, Blocks.AIR.defaultBlockState()), Map.of(pos, Blocks.STONE.defaultBlockState()),
                        new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false), 0, 0, ItemSnapshotCodec.capture(new ItemStack(Items.STONE), owner.registryAccess()));
                var outcome = NativePlacementKernel.place(owner, request, (player, target) -> { if (!target.equals(pos)) throw new SecurityException("TEST_REGION_DENIED"); });
                helper.assertTrue(outcome.success() && saw.get(), "Operator owner still uses an observed survival placement");
            } finally { NeoForge.EVENT_BUS.unregister(check); players.deop(owner.getGameProfile()); }
            helper.succeed();
        }
    }
    @GameTest(template = "story_empty", batch = "ai-native-placement", timeoutTicks = 100)
    public static void durableEscrowAndWorldJournalChargeTwoPlacementsAndReturnOnlyOneUnusedBlock(GameTestHelper helper) throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("ai4-test-ledgers").toAbsolutePath(); java.nio.file.Files.createDirectories(root);
        root = java.nio.file.Files.createTempDirectory(root, "world-ledger-");
        try (var session = new PlayerSession(helper); var manager = new WorkspaceManager(root)) {
            var owner = session.player; var actor = Actor.player(owner.getUUID(), true, true, 0);
            var workspace = manager.getWorkspace(actor); var journal = new BuildJournal(workspace, UUID.randomUUID());
            var ledger = new MaterialLedger(root); var task = AgentTask.create(actor.owner(), UUID.randomUUID(), "place two blocks");
            workspace.conversation().append(List.of(Draft.checkpoint(task)));
            owner.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 3));
            var input = ItemSnapshotCodec.capture(owner.getInventory().getItem(0), owner.registryAccess());
            var source = new MaterialLedger.Stock("player", 1, "native-owner", "native-owner-slot", 0, input, 1, 3, 3);
            String hash = AiJson.digest("two exact native placements"); UUID plan = UUID.randomUUID();
            var operation = ledger.reserve(actor, task.id(), UUID.randomUUID(), hash, List.of(source), List.of(new MaterialLedger.Need(source.key(), 3)));
            UUID materialId = operation.operationId(); var inventory = new InvWrapper(owner.getInventory());
            ledger.beginExtraction(actor, materialId, 0, input, 3, "actual-owner-slot");
            var extracted = NativeMaterialTransfer.extract(inventory, 0, 3, owner.registryAccess(), () -> owner.getInventory().getItem(0).getCount());
            ledger.extracted(actor, materialId, extracted.actual(), extracted.unitsAfter());
            for (int i = 0; i < 2; i++) {
                BlockPos pos = helper.absolutePos(new BlockPos(1 + i, 2, 3)); var plain = new BlueprintDocument.Position(pos.getX(), pos.getY(), pos.getZ());
                var before = Blocks.AIR.defaultBlockState(); var target = Blocks.OAK_PLANKS.defaultBlockState(); UUID step = UUID.randomUUID();
                journal.prepare(actor, step, task.id(), plan, hash, materialId, 0, helper.getLevel().dimension().location().toString(),
                        List.of(new BuildJournal.Cell(plain, BlueprintBlockStateCodec.describe(before), BlueprintBlockStateCodec.describe(target))), input.withCount(1));
                ledger.beginWorldUse(actor, materialId, step, 0); journal.begin(actor, step);
                var request = new NativePlacementKernel.Request(Map.of(pos, before), Map.of(pos, target),
                        new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false), 0, 0, input.withCount(1));
                var nativeResult = NativePlacementKernel.place(owner, request, (player, point) -> { if (!point.equals(pos)) throw new SecurityException("TEST_REGION_DENIED"); });
                helper.assertTrue(nativeResult.success(), "Each escrowed unit must actually place through native hooks");
                var observed = nativeResult.observed().entrySet().stream().map(entry -> new BuildJournal.ObservedCell(
                        new BlueprintDocument.Position(entry.getKey().getX(), entry.getKey().getY(), entry.getKey().getZ()), BlueprintBlockStateCodec.describe(entry.getValue()))).toList();
                journal.observed(actor, step, new BuildJournal.Observation(nativeResult.success(), nativeResult.mutationEntered(), nativeResult.uncertain(),
                        nativeResult.code(), nativeResult.remaining(), nativeResult.otherItems(), observed));
                String liveEvidence = AiJson.digest(step + AiJson.canonical(observed));
                ledger.worldUsed(actor, materialId, step, nativeResult.remaining(), !nativeResult.uncertain(), liveEvidence);
                journal.verified(actor, step, observed, liveEvidence);
                try { journal.begin(actor, step); helper.fail("A verified native operation must not replay"); }
                catch (IllegalStateException expected) { }
            }
            operation = ledger.cancel(actor, materialId);
            helper.assertValueEqual(operation.remainingProducts(), List.of(input.withCount(1)), "Only the unplaced third unit can return");
            ledger.beginDelivery(actor, materialId, 0, 1, "original-owner-empty-slot", MaterialStack.EMPTY);
            var returned = NativeMaterialTransfer.insert(inventory, 0, input.withCount(1), MaterialStack.EMPTY, owner.registryAccess());
            operation = ledger.delivered(actor, materialId, returned.accepted(), returned.verified());
            helper.assertValueEqual(operation.state(), MaterialLedger.State.COMPLETED, "World consumption plus remainder delivery are accounted");
            helper.assertValueEqual(owner.getInventory().getItem(0).getCount(), 1, "No world-consumed material is recreated");
            helper.assertValueEqual(operation.worldDebits().stream().mapToInt(MaterialLedger.WorldDebit::count).sum(), 2, "Two world debits remain in the durable ledger");
            helper.succeed();
        }
    }
    @GameTest(template = "story_empty", batch = "ai-native-removal", timeoutTicks = 200)
    public static void survivalRemovalPaysNativeTimeDurabilityAndDropsWithoutMovingOwner(GameTestHelper helper) {
        var session = new PlayerSession(helper); var owner = session.player;
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.STONE); helper.setBlock(new BlockPos(3, 1, 3), Blocks.STONE);
        var stand = helper.absolutePos(new BlockPos(3, 2, 3)); owner.moveTo(stand.getX() + .5, stand.getY(), stand.getZ() + .5, 0, 0);
        owner.setNoGravity(true); owner.setOnGround(true); owner.setDeltaMovement(Vec3.ZERO);
        owner.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
        var position = owner.position(); var pos = helper.absolutePos(new BlockPos(1, 2, 1));
        var request = removal(owner, pos, Blocks.STONE.defaultBlockState());
        var started = NativeRemovalKernel.start(owner, request, (player, point) -> {
            if (player != owner || !point.equals(pos)) throw new SecurityException("TEST_REMOVAL_DENIED");
        });
        helper.assertValueEqual(started.observation().state(), NativeRemovalKernel.State.MINING, "Survival stone must not be instantly removed");
        var done = new java.util.concurrent.atomic.AtomicBoolean();
        helper.onEachTick(() -> {
            if (done.get()) return;
            try {
                var progress = NativeRemovalKernel.advance(started);
                if (progress.state() == NativeRemovalKernel.State.RECOVERY_REQUIRED) helper.fail(progress.reason());
                if (progress.state() != NativeRemovalKernel.State.READY) return;
                var result = NativeRemovalKernel.remove(started);
                helper.assertValueEqual(result.state(), NativeRemovalKernel.State.REMOVED, "Actual native removal must succeed");
                helper.assertValueEqual(owner.getMainHandItem().getDamageValue(), 1, "Mining tool durability is not a processing exemption");
                helper.assertTrue(result.exhaustion() > 0, "Native mining exhaustion remains charged");
                helper.assertValueEqual(owner.position(), position, "Virtual remote permission must not teleport the owner");
                var drops = owner.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new AABB(pos).inflate(1.5));
                helper.assertValueEqual(drops.stream().filter(item -> item.getItem().is(Items.COBBLESTONE)).mapToInt(item -> item.getItem().getCount()).sum(), 1,
                        "The native loot table drops one real cobblestone");
                try { NativeRemovalKernel.remove(started); helper.fail("A completed removal cannot repeat"); }
                catch (IllegalStateException expected) { }
                done.set(true); session.close(); helper.succeed();
            } catch (Throwable failure) { if (done.compareAndSet(false, true)) session.close(); throw failure; }
        });
        helper.runAtTickTime(195, () -> { if (done.compareAndSet(false, true)) { session.close(); helper.fail("Native mining did not complete within its test budget"); } });
    }
    @GameTest(template = "story_empty", batch = "ai-native-removal", timeoutTicks = 80)
    public static void cancelAndRepeatedSameTickProgressNeverRemoveTheBlockOrWearTheTool(GameTestHelper helper) {
        try (var session = new PlayerSession(helper)) {
            var owner = session.player; var pos = helper.absolutePos(new BlockPos(1, 2, 1)); helper.setBlock(new BlockPos(1, 2, 1), Blocks.STONE);
            owner.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
            var started = NativeRemovalKernel.start(owner, removal(owner, pos, Blocks.STONE.defaultBlockState()), (player, point) -> { });
            double initial = started.observation().progress();
            for (int i = 0; i < 100; i++) NativeRemovalKernel.advance(started);
            helper.assertValueEqual(started.observation().progress(), initial, "Repeated polling cannot skip native mining ticks");
            try { NativeRemovalKernel.remove(started); helper.fail("Unfinished mining cannot destroy a block"); } catch (IllegalStateException expected) { }
            var cancelled = NativeRemovalKernel.cancel(started);
            helper.assertValueEqual(cancelled.state(), NativeRemovalKernel.State.CANCELLED, "Cancel ends this mining session");
            helper.assertTrue(owner.serverLevel().getBlockState(pos).is(Blocks.STONE), "Cancelled mining preserves the world");
            helper.assertValueEqual(owner.getMainHandItem().getDamageValue(), 0, "No native removal means no invented durability charge");
            helper.succeed();
        }
    }
    @GameTest(template = "story_empty", batch = "ai-native-removal", timeoutTicks = 80)
    public static void nativeBreakEventCanStillDenyAfterMiningHasCompleted(GameTestHelper helper) {
        try (var session = new PlayerSession(helper)) {
            var owner = session.player; var pos = helper.absolutePos(new BlockPos(1, 2, 1)); helper.setBlock(new BlockPos(1, 2, 1), Blocks.STONE);
            owner.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
            owner.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_BREAK_SPEED).setBaseValue(1000);
            var started = NativeRemovalKernel.start(owner, removal(owner, pos, Blocks.STONE.defaultBlockState()), (player, point) -> { });
            helper.assertValueEqual(started.observation().state(), NativeRemovalKernel.State.READY, "The test attribute makes the real native rate instantaneous");
            Consumer<BlockEvent.BreakEvent> deny = event -> { if (event.getPlayer() == owner) event.setCanceled(true); };
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, deny);
            try {
                var result = NativeRemovalKernel.remove(started);
                helper.assertValueEqual(result.state(), NativeRemovalKernel.State.REJECTED, "Native protection is authoritative at final break");
                helper.assertTrue(owner.serverLevel().getBlockState(pos).is(Blocks.STONE), "Denied break cannot remove stone");
                helper.assertValueEqual(owner.getMainHandItem().getDamageValue(), 0, "Denied break does not wear the tool");
            } finally { NeoForge.EVENT_BUS.unregister(deny); }
            helper.succeed();
        }
    }
    @GameTest(template = "story_empty", batch = "ai-native-removal", timeoutTicks = 80)
    public static void removalRejectsUnapprovedContainersAndAChangedNativeTool(GameTestHelper helper) {
        try (var session = new PlayerSession(helper)) {
            var owner = session.player; var pos = helper.absolutePos(new BlockPos(1, 2, 1)); helper.setBlock(new BlockPos(1, 2, 1), Blocks.CHEST);
            try { NativeRemovalKernel.start(owner, removal(owner, pos, Blocks.CHEST.defaultBlockState()), (player, point) -> { }); helper.fail("A data block needs a separate adapter and confirmation"); }
            catch (IllegalStateException expected) { }
            helper.setBlock(new BlockPos(1, 2, 1), Blocks.STONE); owner.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
            var started = NativeRemovalKernel.start(owner, removal(owner, pos, Blocks.STONE.defaultBlockState()), (player, point) -> { });
            owner.getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
            try { NativeRemovalKernel.advance(started); helper.fail("Changing a tool invalidates the old mining attempt"); }
            catch (IllegalStateException expected) { helper.assertValueEqual(expected.getMessage(), "MINING_TOOL_CHANGED", "Exact component snapshot is rechecked"); }
            helper.assertTrue(owner.serverLevel().getBlockState(pos).is(Blocks.STONE), "Invalidated session cannot change the world");
            helper.succeed();
        }
    }
    private static NativeRemovalKernel.Request removal(ServerPlayer owner, BlockPos pos, net.minecraft.world.level.block.state.BlockState before) {
        return new NativeRemovalKernel.Request(Map.of(pos, before), Set.of(pos), pos, Direction.UP, ItemSnapshotCodec.capture(owner.getMainHandItem(), owner.registryAccess()));
    }
    @GameTest(template = "story_empty", batch = "ai-native-removal", timeoutTicks = 80)
    public static void nativeStopCallbackChangingTheToolCannotProceedWithTheOldPlan(GameTestHelper helper) {
        try (var session = new PlayerSession(helper)) {
            var owner = session.player; var pos = helper.absolutePos(new BlockPos(1, 2, 1)); helper.setBlock(new BlockPos(1, 2, 1), Blocks.STONE);
            owner.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
            owner.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_BREAK_SPEED).setBaseValue(1000);
            var started = NativeRemovalKernel.start(owner, removal(owner, pos, Blocks.STONE.defaultBlockState()), (player, point) -> { });
            Consumer<net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock> change = event -> {
                if (event.getEntity() == owner) owner.getInventory().setItem(0, new ItemStack(Items.DIRT));
            };
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, change);
            try {
                var result = NativeRemovalKernel.remove(started);
                helper.assertValueEqual(result.state(), NativeRemovalKernel.State.RECOVERY_REQUIRED, "An accepted callback changing the planned tool requires reconciliation");
                helper.assertTrue(owner.serverLevel().getBlockState(pos).is(Blocks.STONE), "Never invoke destroyBlock using the callback's unexpected tool");
                helper.assertTrue(owner.getMainHandItem().is(Items.DIRT), "Do not overwrite a real callback result with an earlier copied tool");
            } finally { NeoForge.EVENT_BUS.unregister(change); }
            helper.succeed();
        }
    }
    @GameTest(template = "story_empty", batch = "ai-native-removal", timeoutTicks = 80)
    public static void changingNativeMiningSpeedRechecksReadinessBeforeRemoval(GameTestHelper helper) {
        try (var session = new PlayerSession(helper)) {
            var owner = session.player; var pos = helper.absolutePos(new BlockPos(1, 2, 1)); helper.setBlock(new BlockPos(1, 2, 1), Blocks.STONE);
            owner.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
            var speed = owner.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_BREAK_SPEED); speed.setBaseValue(1000);
            var started = NativeRemovalKernel.start(owner, removal(owner, pos, Blocks.STONE.defaultBlockState()), (player, point) -> { });
            helper.assertValueEqual(started.observation().state(), NativeRemovalKernel.State.READY, "The initial native speed is fast enough");
            speed.setBaseValue(1);
            var delayed = NativeRemovalKernel.remove(started);
            helper.assertValueEqual(delayed.state(), NativeRemovalKernel.State.MINING, "A slower current rate must invalidate an earlier ready decision");
            helper.assertTrue(owner.serverLevel().getBlockState(pos).is(Blocks.STONE), "Never break using an expired mining-speed decision");
            helper.assertValueEqual(owner.getMainHandItem().getDamageValue(), 0, "Waiting for native mining time cannot damage a tool");
            NativeRemovalKernel.cancel(started); helper.succeed();
        }
    }
    @GameTest(template = "story_empty", batch = "ai-native-removal", timeoutTicks = 80)
    public static void approvedNativeLootCustodyPreservesPostEventCountAndComponents(GameTestHelper helper) {
        lootCustody(helper, "modified");
    }
    @GameTest(template = "story_empty", batch = "ai-native-removal", timeoutTicks = 80)
    public static void cancelledNativeLootEventCannotManufactureEscrowOutputs(GameTestHelper helper) {
        lootCustody(helper, "cancelled");
    }
    @GameTest(template = "story_empty", batch = "ai-native-removal", timeoutTicks = 80)
    public static void duplicateNativeDropIdentitiesCannotCreditTheSamePhysicalLootTwice(GameTestHelper helper) {
        lootCustody(helper, "duplicate");
    }
    private static void lootCustody(GameTestHelper helper, String mode) {
        try (var session = new PlayerSession(helper)) {
            var owner = session.player; var pos = helper.absolutePos(new BlockPos(1, 2, 1)); helper.setBlock(new BlockPos(1, 2, 1), Blocks.STONE);
            owner.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
            owner.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_BREAK_SPEED).setBaseValue(1000);
            var started = NativeRemovalKernel.start(owner, removal(owner, pos, Blocks.STONE.defaultBlockState()), (player, point) -> { });
            var nativeItems = new ArrayList<net.minecraft.world.entity.item.ItemEntity>();
            Consumer<net.neoforged.neoforge.event.level.BlockDropsEvent> modifier = event -> {
                if (event.getBreaker() != owner) return;
                nativeItems.addAll(event.getDrops());
                if (mode.equals("cancelled")) event.setCanceled(true);
                else if (mode.equals("duplicate")) event.getDrops().add(event.getDrops().getFirst());
                else {
                    var stack = event.getDrops().getFirst().getItem(); stack.setCount(3);
                    stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("native loot evidence"));
                }
            };
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, modifier);
            try (var custody = NativeLootEscrowScope.begin(owner, Set.of(pos))) {
                var result = NativeRemovalKernel.remove(started);
                helper.assertValueEqual(result.state(), NativeRemovalKernel.State.REMOVED, "Only real removal produces native loot");
                var world = owner.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new AABB(pos).inflate(1.5));
                if (mode.equals("modified")) {
                    helper.assertTrue(custody.unsupportedReason().isEmpty(), "The native batch is within the verified scope");
                    helper.assertValueEqual(custody.held().size(), 1, "Capture the final post-event output once");
                    helper.assertValueEqual(custody.held().getFirst().stack().count(), 3, "Do not replace real modified quantity with the recipe's first result");
                    var actual = ItemSnapshotCodec.restore(custody.held().getFirst().stack(), owner.registryAccess());
                    helper.assertValueEqual(actual.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME).getString(), "native loot evidence", "Preserve actual native components");
                    helper.assertTrue(nativeItems.stream().allMatch(item -> item.isRemoved() && item.getItem().isEmpty()), "Native wrappers surrender custody and cannot spawn another copy");
                    helper.assertTrue(world.isEmpty(), "Escrow collection does not pretend to spawn an item entity");
                } else if (mode.equals("cancelled")) {
                    helper.assertTrue(custody.held().isEmpty() && world.isEmpty(), "Native cancellation produces no material in either destination");
                } else {
                    helper.assertTrue(!custody.unsupportedReason().isEmpty() && custody.held().isEmpty(), "Unverified identity batches are not copied into escrow");
                    helper.assertValueEqual(world.stream().mapToInt(item -> item.getItem().getCount()).sum(), 1, "Keep the original native rejection of duplicate entity identity");
                }
                helper.assertValueEqual(owner.getMainHandItem().getDamageValue(), 1, "Loot custody does not exempt native mining tool costs");
            } finally { NeoForge.EVENT_BUS.unregister(modifier); }
            helper.succeed();
        }
    }
    @GameTest(template = "story_empty", batch = "ai-native-chain", timeoutTicks = 80)
    public static void ultimineHeldKeyCannotExpandApprovedRemovalButManualMiningStillChains(GameTestHelper helper) throws Exception {
        if (!net.neoforged.fml.ModList.get().isLoaded("ftbultimine")) { helper.succeed(); return; }
        try (var session = new PlayerSession(helper); var observer = new PlayerSession(helper)) {
            var owner = session.player; var other = observer.player;
            var pos = helper.absolutePos(new BlockPos(1, 2, 1)); var next = helper.absolutePos(new BlockPos(2, 2, 1)); var third = helper.absolutePos(new BlockPos(2, 2, 2));
            for (var point : List.of(pos, next, third)) owner.serverLevel().setBlockAndUpdate(point, Blocks.STONE.defaultBlockState());
            owner.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
            owner.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_BREAK_SPEED).setBaseValue(1000);
            owner.moveTo(pos.getX() + .5, pos.getY() + 2, pos.getZ() + .5, 0, 90);
            Class<?> ftb = Class.forName("dev.ftb.mods.ftbultimine.FTBUltimine"); Object instance = ftb.getMethod("getInstance").invoke(null);
            Object data = ftb.getMethod("getOrCreatePlayerData", net.minecraft.world.entity.player.Player.class).invoke(instance, owner);
            data.getClass().getMethod("setPressed", boolean.class).invoke(data, true);
            Class<?> restriction = Class.forName("dev.ftb.mods.ftbultimine.RestrictionHandlerRegistry"); Object registry = restriction.getField("INSTANCE").get(null);
            var allowed = Class.forName("dev.ftb.mods.ftbultimine.api.util.CanUltimineResult").getMethod("isAllowed");
            var read = restriction.getMethod("canUltimine", net.minecraft.world.entity.player.Player.class);
            helper.assertTrue((boolean) allowed.invoke(read.invoke(registry, owner)), "Native owner restrictions allow normal mining before the scope");
            try (var scope = NativeChainScope.begin(owner)) {
                helper.assertTrue(!(boolean) allowed.invoke(read.invoke(registry, owner)), "Deny expansion before Ultimine enters its mutable chain state");
                helper.assertTrue((boolean) allowed.invoke(read.invoke(registry, other)), "Another player is not disabled by the AI scope");
            }
            var started = NativeRemovalKernel.start(owner, removal(owner, pos, Blocks.STONE.defaultBlockState()), (player, point) -> { });
            var result = NativeRemovalKernel.remove(started);
            helper.assertValueEqual(result.state(), NativeRemovalKernel.State.REMOVED, "Native approved removal survives a real held Ultimine key");
            helper.assertTrue(owner.serverLevel().getBlockState(next).is(Blocks.STONE) && owner.serverLevel().getBlockState(third).is(Blocks.STONE), "No unapproved connected blocks were mined");
            helper.assertValueEqual(owner.getMainHandItem().getDamageValue(), 1, "Only the approved block charged native tool durability");
            helper.assertTrue((boolean) allowed.invoke(read.invoke(registry, owner)), "The native restriction is restored immediately after the operation");
            owner.moveTo(next.getX() + .5, next.getY() + 2, next.getZ() + .5, 0, 90);
            owner.gameMode.destroyBlock(next);
            helper.assertTrue(owner.serverLevel().getBlockState(next).isAir() && owner.serverLevel().getBlockState(third).isAir(), "The same owner's ordinary native Ultimine still mines the connected cluster");
            helper.succeed();
        }
    }
    @GameTest(template = "story_empty", batch = "ai-native-removal", timeoutTicks = 80)
    public static void changingToAnIdenticalHotbarToolInvalidatesNativeMining(GameTestHelper helper) {
        try (var session = new PlayerSession(helper)) {
            var owner = session.player; var pos = helper.absolutePos(new BlockPos(1, 2, 1)); helper.setBlock(new BlockPos(1, 2, 1), Blocks.STONE);
            owner.getInventory().selected = 0; owner.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE)); owner.getInventory().setItem(1, new ItemStack(Items.IRON_PICKAXE));
            var started = NativeRemovalKernel.start(owner, removal(owner, pos, Blocks.STONE.defaultBlockState()), (player, point) -> { });
            owner.getInventory().selected = 1;
            try { NativeRemovalKernel.advance(started); helper.fail("An equal stack in another hand slot is a changed mining tool binding"); }
            catch (IllegalStateException expected) { helper.assertValueEqual(expected.getMessage(), "MINING_TOOL_CHANGED", "Slot identity is part of the native session"); }
            helper.assertTrue(owner.serverLevel().getBlockState(pos).is(Blocks.STONE), "A moved tool must not destroy the target"); helper.succeed();
        }
    }
    @GameTest(template = "story_empty", batch = "ai-native-slab-boundary", timeoutTicks = 80)
    public static void installedPartialSlabSemanticsAreDetectedBeforeAnIncorrectFullRemoval(GameTestHelper helper) {
        if (!net.neoforged.fml.ModList.get().isLoaded("kleeslabs")) { helper.succeed(); return; }
        try (var session = new PlayerSession(helper)) {
            var owner = session.player; var pos = helper.absolutePos(new BlockPos(1, 2, 1));
            var doubleSlab = Blocks.OAK_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.DOUBLE);
            owner.serverLevel().setBlockAndUpdate(pos, doubleSlab); owner.getInventory().setItem(0, new ItemStack(Items.IRON_AXE));
            try { NativeRemovalKernel.start(owner, removal(owner, pos, doubleSlab), (player, point) -> { }); helper.fail("A two-phase native slab transformation cannot be treated as an air-target break"); }
            catch (IllegalStateException expected) { helper.assertValueEqual(expected.getMessage(), "REMOVAL_PARTIAL_SLAB_PLAN_REQUIRED", "Use the actual installed converter and player mode"); }
            helper.assertTrue(owner.serverLevel().getBlockState(pos).equals(doubleSlab), "Refuse before changing either half or creating unaccounted loot");
            helper.assertTrue(owner.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new AABB(pos).inflate(2)).isEmpty(), "No native drop has been spawned");
            var remaining = doubleSlab.setValue(net.minecraft.world.level.block.SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.BOTTOM);
            owner.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_BREAK_SPEED).setBaseValue(1000); var location = owner.position();
            try (var custody = NativeLootEscrowScope.begin(owner, Set.of(pos))) {
                var request = new NativeRemovalKernel.Request(Map.of(pos, doubleSlab), Map.of(pos, remaining), pos, Direction.UP, ItemSnapshotCodec.capture(owner.getMainHandItem(), owner.registryAccess()));
                var result = NativeRemovalKernel.remove(NativeRemovalKernel.start(owner, request, (player, point) -> { }));
                helper.assertValueEqual(result.state(), NativeRemovalKernel.State.REMOVED, "A verified native Klee callback completes only the approved first phase");
                helper.assertTrue(owner.serverLevel().getBlockState(pos).equals(remaining) && owner.position().equals(location), "The virtual hit chooses the approved half without moving the owner");
                helper.assertValueEqual(custody.held().stream().mapToInt(d -> d.stack().count()).sum(), 1, "One post-event Klee item enters custody");
                helper.assertValueEqual(owner.getMainHandItem().getDamageValue(), 0, "Do not invent the vanilla mineBlock callback omitted by Klee's native cancellation");
                helper.assertTrue(owner.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new AABB(pos).inflate(2)).isEmpty(), "The candidate was not added to the world");
            }
            try (var custody = NativeLootEscrowScope.begin(owner, Set.of(pos))) {
                var result = NativeRemovalKernel.remove(NativeRemovalKernel.start(owner, removal(owner, pos, remaining), (player, point) -> { }));
                helper.assertValueEqual(result.state(), NativeRemovalKernel.State.REMOVED, "The second phase uses the actual remaining native slab");
                helper.assertValueEqual(custody.held().stream().mapToInt(d -> d.stack().count()).sum(), 1, "The second phase produces only its own native slab");
                helper.assertValueEqual(owner.getMainHandItem().getDamageValue(), 1, "Normal native mining cost applies to the second phase");
            }
            owner.serverLevel().setBlockAndUpdate(pos, doubleSlab);
            owner.gameMode.destroyBlock(pos);
            var actual = owner.serverLevel().getBlockState(pos);
            helper.assertTrue(actual.is(Blocks.OAK_SLAB) && actual.getValue(net.minecraft.world.level.block.SlabBlock.TYPE) != net.minecraft.world.level.block.state.properties.SlabType.DOUBLE,
                    "The same player's ordinary KleeSlabs still removes only one half");
            helper.assertValueEqual(owner.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new AABB(pos).inflate(2)).stream().mapToInt(e -> e.getItem().getCount()).sum(),
                    1, "The native partial-break path emits one actual slab");
            helper.succeed();
        }
    }
    @GameTest(template = "story_empty", batch = "ai-native-slab-custody", timeoutTicks = 80)
    public static void kleeDirectLootPreservesFinalNativeEntityEventComponents(GameTestHelper helper) { kleeLoot(helper, "modified"); }
    @GameTest(template = "story_empty", batch = "ai-native-slab-custody", timeoutTicks = 80)
    public static void kleeCancelledNativeEntityEventProducesZeroCustody(GameTestHelper helper) { kleeLoot(helper, "cancelled"); }
    @GameTest(template = "story_empty", batch = "ai-native-slab-custody", timeoutTicks = 80)
    public static void kleeDuplicateKnownUuidCannotCreateCreditedLoot(GameTestHelper helper) { kleeLoot(helper, "duplicate"); }
    @GameTest(template = "story_empty", batch = "ai-native-slab-custody", timeoutTicks = 80)
    public static void kleeNestedEntityCallbackRetainsExplicitRecovery(GameTestHelper helper) { kleeLoot(helper, "nested"); }
    @GameTest(template = "story_empty", batch = "ai-native-slab-custody", timeoutTicks = 80)
    public static void kleeReentrantSameEntityAddCannotCreditAnAlreadySpawnedCandidate(GameTestHelper helper) { kleeLoot(helper, "reentrant"); }
    @GameTest(template = "story_empty", batch = "ai-native-slab-custody", timeoutTicks = 80)
    public static void lateBreakUncancellationCannotExpandTheApprovedHalfStep(GameTestHelper helper) { kleeLoot(helper, "uncancel"); }
    private static void kleeLoot(GameTestHelper helper, String mode) {
        if (!net.neoforged.fml.ModList.get().isLoaded("kleeslabs")) { helper.succeed(); return; }
        try (var session = new PlayerSession(helper)) {
            var owner = session.player; var pos = helper.absolutePos(new BlockPos(1, 2, 1));
            var before = Blocks.OAK_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.DOUBLE);
            var after = before.setValue(net.minecraft.world.level.block.SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.BOTTOM);
            owner.serverLevel().setBlockAndUpdate(pos, before); owner.getInventory().setItem(0, new ItemStack(Items.IRON_AXE));
            owner.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_BREAK_SPEED).setBaseValue(1000);
            var candidates = new ArrayList<net.minecraft.world.entity.item.ItemEntity>();
            Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> eventHandler = event -> {
                if (!NativeKleeSlabScope.isExpectedCandidate(event.getEntity())) return;
                var candidate = (net.minecraft.world.entity.item.ItemEntity) event.getEntity(); candidates.add(candidate);
                switch (mode) {
                    case "modified" -> { candidate.getItem().setCount(3); candidate.getItem().set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("native Klee event")); }
                    case "cancelled" -> event.setCanceled(true);
                    case "duplicate" -> candidate.setUUID(owner.getUUID());
                    case "nested" -> owner.serverLevel().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(owner.serverLevel(), pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, new ItemStack(Items.DIRT)));
                    case "reentrant" -> owner.serverLevel().addFreshEntity(candidate);
                }
            };
            Consumer<BlockEvent.BreakEvent> uncancel = event -> { if (mode.equals("uncancel") && event.getPlayer() == owner) event.setCanceled(false); };
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, eventHandler);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, uncancel);
            try (var custody = NativeLootEscrowScope.begin(owner, Set.of(pos))) {
                var request = new NativeRemovalKernel.Request(Map.of(pos, before), Map.of(pos, after), pos, Direction.UP, ItemSnapshotCodec.capture(owner.getMainHandItem(), owner.registryAccess()));
                var result = NativeRemovalKernel.remove(NativeRemovalKernel.start(owner, request, (player, point) -> { }));
                helper.assertValueEqual(candidates.size(), 1, "The real native event saw the precise Klee candidate once");
                if (mode.equals("modified")) {
                    helper.assertValueEqual(result.state(), NativeRemovalKernel.State.REMOVED, "Post-event component changes are actual native loot");
                    helper.assertValueEqual(custody.held().getFirst().stack().count(), 3, "Observe listeners before snapshotting the amount");
                    helper.assertValueEqual(ItemSnapshotCodec.restore(custody.held().getFirst().stack(), owner.registryAccess()).get(net.minecraft.core.component.DataComponents.CUSTOM_NAME).getString(),
                            "native Klee event", "Retain final actual components");
                    helper.assertTrue(candidates.getFirst().isRemoved() && candidates.getFirst().getItem().isEmpty(), "Custody surrenders the exact unspawned wrapper");
                } else if (mode.equals("cancelled")) {
                    helper.assertValueEqual(result.state(), NativeRemovalKernel.State.REMOVED, "Native Klee still changes the half after an actual rejected spawn");
                    helper.assertTrue(custody.held().isEmpty(), "Do not credit the canceled candidate");
                } else {
                    helper.assertValueEqual(result.state(), NativeRemovalKernel.State.RECOVERY_REQUIRED, "Unverified nested or duplicate native effects cannot become success");
                    if (mode.equals("uncancel")) helper.assertValueEqual(custody.held().stream().mapToInt(d -> d.stack().count()).sum(), 1, "Retain already accepted custody, but prevent an unapproved second destruction");
                    else helper.assertTrue(custody.held().isEmpty(), "Unverified candidates are not credited");
                }
                helper.assertTrue(owner.serverLevel().getBlockState(pos).equals(after), "Record the native remaining half, including an uncertain outcome");
                if (!Set.of("nested", "reentrant").contains(mode)) helper.assertTrue(owner.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new AABB(pos).inflate(2)).isEmpty(), "No unaccounted candidate entered the world");
                helper.assertTrue(owner.serverLevel().getEntity(owner.getUUID()) == owner, "A candidate cannot replace an existing native UUID");
            } finally { NeoForge.EVENT_BUS.unregister(eventHandler); NeoForge.EVENT_BUS.unregister(uncancel); }
            helper.succeed();
        }
    }
    @GameTest(template = "story_empty", batch = "ai-native-slab-custody", timeoutTicks = 80)
    public static void kleeRespectsAnEarlierCancelledBalmEventInsideTheApprovedScope(GameTestHelper helper) throws Exception {
        if (!net.neoforged.fml.ModList.get().isLoaded("kleeslabs")) { helper.succeed(); return; }
        try (var session = new PlayerSession(helper)) {
            var owner = session.player; var pos = helper.absolutePos(new BlockPos(1, 2, 1));
            var before = Blocks.OAK_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.DOUBLE);
            var after = before.setValue(net.minecraft.world.level.block.SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.BOTTOM);
            owner.serverLevel().setBlockAndUpdate(pos, before);
            Class<?> eventClass = Class.forName("net.blay09.mods.balm.api.event.BreakBlockEvent");
            Object event = eventClass.getConstructor(net.minecraft.world.level.Level.class, net.minecraft.world.entity.player.Player.class, BlockPos.class,
                    net.minecraft.world.level.block.state.BlockState.class, net.minecraft.world.level.block.entity.BlockEntity.class).newInstance(owner.serverLevel(), owner, pos, before, null);
            eventClass.getMethod("setCanceled", boolean.class).invoke(event, true);
            try (var scope = NativeKleeSlabScope.begin(owner, pos, before, after, () -> { }); var custody = NativeLootEscrowScope.begin(owner, Set.of(pos))) {
                Class.forName("net.blay09.mods.kleeslabs.BlockBreakHandler").getMethod("onBreakBlock", eventClass).invoke(null, event);
                helper.assertTrue(!scope.verified() && custody.held().isEmpty() && owner.serverLevel().getBlockState(pos).equals(before), "An earlier Balm protection denial prevents native partial destruction");
            }
            helper.succeed();
        }
    }
    private static final class PlayerSession implements AutoCloseable {
        final ServerPlayer player;
        final EmbeddedChannel channel;
        PlayerSession(GameTestHelper helper) {
            var server = helper.getLevel().getServer(); UUID id = UUID.randomUUID();
            var cookie = new CommonListenerCookie(new GameProfile(id, "native-" + id.toString().substring(0, 8)), 0,
                    ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
            player = new ServerPlayer(server, helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND); channel = new EmbeddedChannel(connection);
            NetworkRegistry.configureMockConnection(connection); server.getPlayerList().placeNewPlayer(connection, player, cookie);
            player.setGameMode(GameType.SURVIVAL);
        }
        @Override public void close() {
            player.server.getPlayerList().remove(player);
            net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
            StoryEngine.instance().cleanupPlayer(player);
            channel.finishAndReleaseAll();
        }
    }
}
