package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.*;
import net.neoforged.neoforge.common.util.*;
import net.neoforged.neoforge.event.level.BlockEvent;
import java.util.*;

/**
 * One native survival placement from already escrowed material. The caller must durably journal
 * intent, prove the fixed approval and provide a live owner/region/claim guard before invoking it.
 * This kernel does not create material, persist ownership, approve plans, or retry uncertain calls.
 */
public final class NativePlacementKernel {
    @FunctionalInterface public interface Guard { void check(ServerPlayer owner, BlockPos position); }
    public record Request(Map<BlockPos, BlockState> before, Map<BlockPos, BlockState> targets,
                          BlockHitResult hit, float yaw, float pitch, MaterialStack material) {
        public Request {
            before = Map.copyOf(before); targets = Map.copyOf(targets);
            if (targets.isEmpty() || targets.size() > 2 || !before.keySet().containsAll(targets.keySet()) || before.size() > 128
                    || hit == null || !Float.isFinite(yaw) || !Float.isFinite(pitch) || material == null || material.empty() || material.count() != 1)
                throw new IllegalArgumentException("PLACEMENT_REQUEST_INVALID");
        }
    }
    public record Outcome(boolean success, boolean mutationEntered, boolean uncertain, String code,
                          MaterialStack remaining, List<MaterialStack> otherItems, Map<BlockPos, BlockState> observed, List<BlockPos> nativeFootprint) {
        public Outcome { otherItems = List.copyOf(otherItems); observed = Map.copyOf(observed); nativeFootprint = List.copyOf(nativeFootprint); }
    }
    private static final ThreadLocal<Attempt> ACTIVE = new ThreadLocal<>();
    private static boolean listening;
    private static final class Proxy extends FakePlayer {
        Proxy(ServerLevel level, ServerPlayer owner) {
            // NeoForge 21.1.264 gives FakePlayers a non-persistent FakePlayerAdvancements instance.
            // Reusing the authenticated profile preserves claim attribution without replacing the live player.
            super(level, owner.getGameProfile());
            gameMode.changeGameModeForPlayer(GameType.SURVIVAL); getAbilities().instabuild = false; getAbilities().mayBuild = true;
        }
        @Override protected int getPermissionLevel() { return 0; }
    }
    private static final class Attempt {
        final ServerPlayer owner;
        final Proxy proxy;
        final Request request;
        final Guard guard;
        final Set<BlockPos> footprint = new LinkedHashSet<>();
        String refusal = "";
        Attempt(ServerPlayer owner, Proxy proxy, Request request, Guard guard) {
            this.owner = owner; this.proxy = proxy; this.request = request; this.guard = guard;
        }
    }
    private NativePlacementKernel() {}
    public static Outcome place(ServerPlayer owner, Request request, Guard guard) {
        Objects.requireNonNull(guard);
        ServerLevel level = owner.serverLevel();
        if (!level.getServer().isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        if (owner.isFakePlayer() || owner.gameMode.getGameModeForPlayer() != GameType.SURVIVAL)
            throw new SecurityException("SURVIVAL_OWNER_REQUIRED");
        if (ACTIVE.get() != null || level.captureBlockSnapshots || level.restoringBlockSnapshots || !level.capturedBlockSnapshots.isEmpty())
            throw new IllegalStateException("PLACEMENT_NESTED_TRANSACTION");
        if (!listening) {
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, NativePlacementKernel::placed);
            listening = true;
        }
        ItemStack item = ItemSnapshotCodec.restore(request.material(), owner.registryAccess());
        if (!(item.getItem() instanceof BlockItem blockItem) || blockItem.getBlock() instanceof GameMasterBlock
                || blockItem.getBlock() == Blocks.BARRIER || blockItem.getBlock() == Blocks.LIGHT || blockItem.getBlock() instanceof StructureVoidBlock)
            throw new IllegalArgumentException("PLACEMENT_ITEM_FORBIDDEN");
        NativePlacementComponents.require(item);
        for (var entry : request.before().entrySet()) {
            BlockPos pos = entry.getKey(); guard.check(owner, pos);
            if (!level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos) || !level.mayInteract(owner, pos)
                    || !level.getBlockState(pos).equals(entry.getValue())) throw new IllegalStateException("PLACEMENT_WORLD_CHANGED");
            if (request.targets().containsKey(pos) && level.getBlockEntity(pos) != null) throw new IllegalStateException("PLACEMENT_DATA_BLOCK_CONFLICT");
            if (request.targets().containsKey(pos)) PlacementWaterPolicy.requireReachable(entry.getValue(), request.targets().get(pos));
            if (request.targets().containsKey(pos) && !entry.getValue().isAir()
                    && !(entry.getValue().getBlock() instanceof SlabBlock && entry.getValue().is(request.targets().get(pos).getBlock()))
                    && !PlacementWaterPolicy.preservesSource(entry.getValue(), request.targets().get(pos)))
                throw new IllegalStateException("PLACEMENT_REQUIRES_SEPARATE_DEMOLITION");
        }
        guard.check(owner, request.hit().getBlockPos());
        if (!level.hasChunkAt(request.hit().getBlockPos())) throw new IllegalStateException("PLACEMENT_UNLOADED");
        Proxy proxy = new Proxy(level, owner);
        var anchor = request.targets().keySet().iterator().next();
        proxy.moveTo(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5, request.yaw(), request.pitch());
        proxy.setShiftKeyDown(true); proxy.setItemInHand(InteractionHand.MAIN_HAND, RecipeInputIsolation.copy(item, owner.registryAccess()));
        Attempt attempt = new Attempt(owner, proxy, request, guard); ACTIVE.set(attempt);
        boolean entered = false; String failure = "";
        try {
            entered = true; // Even a cancelled interaction can invoke third-party callbacks.
            var hit = CommonHooks.onRightClickBlock(proxy, InteractionHand.MAIN_HAND, request.hit().getBlockPos(), request.hit());
            if (hit.isCanceled() || hit.getUseItem() == TriState.FALSE) failure = "PLACEMENT_INTERACTION_DENIED";
            else {
                // Hooks/events run with the attributed proxy, while the actual player's inventory and position stay untouched.
                var context = new UseOnContext(level, proxy, InteractionHand.MAIN_HAND, proxy.getMainHandItem(), request.hit());
                var result = CommonHooks.onPlaceItemIntoWorld(context);
                if (!result.consumesAction()) failure = attempt.refusal.isEmpty() ? "PLACEMENT_NATIVE_REJECTED" : attempt.refusal;
            }
        } catch (RuntimeException | LinkageError error) { failure = "PLACEMENT_NATIVE_CALLBACK_UNCERTAIN"; }
        finally {
            ACTIVE.remove();
            // The outer preflight rejected existing captures, so these can only belong to this failed native invocation.
            // Preserve the footprint as evidence; do not restore arbitrary world state or replay the operation here.
            for (var snapshot : level.capturedBlockSnapshots) attempt.footprint.add(snapshot.getPos().immutable());
            level.captureBlockSnapshots = false; level.restoringBlockSnapshots = false; level.capturedBlockSnapshots.clear();
        }
        var outcome = outcome(false, entered, !failure.isEmpty() && failure.endsWith("UNCERTAIN"), failure, proxy, level, attempt);
        boolean exact = request.targets().entrySet().stream().allMatch(e -> level.getBlockState(e.getKey()).equals(e.getValue()));
        boolean unchanged = request.before().entrySet().stream().allMatch(e -> level.getBlockState(e.getKey()).equals(e.getValue()));
        boolean passiveUnchanged = request.before().entrySet().stream().filter(e -> !request.targets().containsKey(e.getKey()))
                .allMatch(e -> level.getBlockState(e.getKey()).equals(e.getValue()));
        boolean consumed = outcome.remaining().empty();
        boolean held = outcome.remaining().equals(request.material());
        boolean extraItems = !outcome.otherItems().isEmpty();
        if (exact && consumed && passiveUnchanged && !extraItems && failure.isEmpty()) {
            try { NativePlacementComponents.requireEmptyNativeContainers(level, request.targets(), item); }
            catch (RuntimeException | LinkageError unknown) {
                return new Outcome(false, true, true, "PLACEMENT_DEFAULT_CONTAINER_INITIALIZATION_UNCERTAIN",
                        outcome.remaining(), outcome.otherItems(), outcome.observed(), outcome.nativeFootprint());
            }
            return new Outcome(true, true, false, "", outcome.remaining(), outcome.otherItems(), outcome.observed(), outcome.nativeFootprint());
        }
        if (unchanged && held && !extraItems && !outcome.uncertain())
            return new Outcome(false, entered, false, failure.isEmpty() ? "PLACEMENT_NO_CHANGE" : failure,
                    outcome.remaining(), outcome.otherItems(), outcome.observed(), outcome.nativeFootprint());
        return new Outcome(false, entered, true, "RECOVERY_REQUIRED", outcome.remaining(), outcome.otherItems(), outcome.observed(), outcome.nativeFootprint());
    }
    private static Outcome outcome(boolean success, boolean entered, boolean uncertain, String code, Proxy proxy, ServerLevel level, Attempt attempt) {
        var observed = new LinkedHashMap<BlockPos, BlockState>();
        for (var pos : attempt.request.before().keySet()) observed.put(pos, level.getBlockState(pos));
        for (var pos : attempt.footprint) observed.put(pos, level.getBlockState(pos));
        var otherItems = new ArrayList<MaterialStack>();
        for (int slot = 1; slot < proxy.getInventory().getContainerSize(); slot++) {
            var stack = proxy.getInventory().getItem(slot);
            if (!stack.isEmpty()) otherItems.add(ItemSnapshotCodec.capture(stack, level.registryAccess()));
        }
        return new Outcome(success, entered, uncertain, code, ItemSnapshotCodec.capture(proxy.getMainHandItem(), level.registryAccess()), otherItems, observed, List.copyOf(attempt.footprint));
    }
    private static void placed(BlockEvent.EntityPlaceEvent event) {
        Attempt attempt = ACTIVE.get(); if (attempt == null || event.getEntity() != attempt.proxy) return;
        var snapshots = event instanceof BlockEvent.EntityMultiPlaceEvent multi ? multi.getReplacedBlockSnapshots() : List.of(event.getBlockSnapshot());
        try {
            for (var snapshot : snapshots) {
                BlockPos pos = snapshot.getPos().immutable(); attempt.footprint.add(pos);
                if (!attempt.request.targets().containsKey(pos) || !attempt.request.before().get(pos).equals(snapshot.getState()))
                    throw new SecurityException("PLACEMENT_FOOTPRINT_CHANGED");
                attempt.guard.check(attempt.owner, pos);
                if (!attempt.request.targets().get(pos).equals(snapshot.getCurrentState())) throw new SecurityException("PLACEMENT_TARGET_CHANGED");
            }
        } catch (RuntimeException denied) { attempt.refusal = "PLACEMENT_PROTECTION_OR_FOOTPRINT_DENIED"; event.setCanceled(true); }
    }
}
