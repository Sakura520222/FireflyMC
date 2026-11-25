package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.material.ItemSnapshotCodec;
import firefly520.fireflymc.ai.material.MaterialStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.TriState;
import java.util.*;

/**
 * Native survival removal stages. The caller must journal EACH accepted stage and install the
 * approved world-mutation scope; this internal kernel grants no area/remote/undo permission.
 * Uses the actual owner's native tool, effects, exhaustion, drops and BreakEvent without moving
 * them or replacing their GameMode/manual-mining fields. No refund or creative block write exists.
 */
public final class NativeRemovalKernel {
    public enum State { MINING, READY, REJECTED, REMOVED, CANCELLED, RECOVERY_REQUIRED }
    public record Request(Map<BlockPos, BlockState> before, Map<BlockPos, BlockState> expected, BlockPos anchor,
                          Direction face, MaterialStack hand) {
        public Request(Map<BlockPos, BlockState> before, Set<BlockPos> targets, BlockPos anchor, Direction face, MaterialStack hand) {
            this(before, targets.stream().collect(java.util.stream.Collectors.toMap(p -> p, p -> net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())), anchor, face, hand);
        }
        public Request {
            before = Map.copyOf(before); expected = Map.copyOf(expected); anchor = anchor.immutable();
            if (before.isEmpty() || before.size() > 128 || expected.isEmpty() || !before.keySet().containsAll(expected.keySet())
                    || !expected.containsKey(anchor) || face == null || hand == null) throw new IllegalArgumentException("REMOVAL_FOOTPRINT_INVALID");
            if (expected.values().stream().anyMatch(state -> !state.isAir()) && (expected.size() != 1
                    || !RemovalTransitions.halfSlab(BlueprintBlockStateCodec.describe(before.get(anchor)), BlueprintBlockStateCodec.describe(expected.get(anchor)))))
                throw new IllegalArgumentException("REMOVAL_PARTIAL_TARGET_INVALID");
        }
        public Set<BlockPos> targets() { return expected.keySet(); }
    }
    public record Observation(State state, String reason, double progress, MaterialStack hand,
                              float exhaustion, Map<BlockPos, BlockState> world) {
        public Observation { world = Map.copyOf(world); }
    }
    public static final class Session {
        private final ServerPlayer owner;
        private final net.minecraft.server.level.ServerLevel level;
        private final Object connection;
        private final int handSlot;
        private final Request request;
        private final NativePlacementKernel.Guard guard;
        private final MiningProgress progress = new MiningProgress();
        private MaterialStack hand;
        private State state = State.MINING;
        private String reason = "";
        private Session(ServerPlayer owner, Request request, NativePlacementKernel.Guard guard) {
            this.owner = owner; this.level = owner.serverLevel(); this.connection = owner.connection; this.handSlot = owner.getInventory().selected;
            this.request = request; this.guard = guard; hand = request.hand();
        }
        public Observation observation() {
            requireThread(owner); var world = new LinkedHashMap<BlockPos, BlockState>();
            request.before().keySet().forEach(pos -> { if (level.hasChunkAt(pos)) world.put(pos, level.getBlockState(pos)); });
            return new Observation(state, reason, progress.value(), ItemSnapshotCodec.capture(owner.getMainHandItem(), owner.registryAccess()), owner.getFoodData().getExhaustionLevel(), world);
        }
    }
    private NativeRemovalKernel() { }
    /** Mirrors the native START sequence after the caller's separate approved remote-range check. */
    public static Session start(ServerPlayer owner, Request request, NativePlacementKernel.Guard guard) {
        var session = new Session(owner, request, Objects.requireNonNull(guard)); preflight(session);
        try (var chain = NativeChainScope.begin(owner)) {
            var event = CommonHooks.onLeftClickBlock(owner, request.anchor(), request.face(), Action.START_DESTROY_BLOCK);
            if (event.isCanceled()) { reject(session, "NATIVE_START_DENIED"); return session; }
            preflight(session);
            BlockState block = request.before().get(request.anchor());
            EnchantmentHelper.onHitBlock(owner.serverLevel(), owner.getMainHandItem(), owner, owner, EquipmentSlot.MAINHAND,
                    Vec3.atCenterOf(request.anchor()), block, broken -> owner.onEquippedItemBroken(broken, EquipmentSlot.MAINHAND));
            session.hand = ItemSnapshotCodec.capture(owner.getMainHandItem(), owner.registryAccess());
            preflight(session);
            if (event.getUseBlock() != TriState.FALSE) block.attack(session.level, request.anchor(), owner);
            advance(session);
        } catch (RuntimeException | LinkageError unknown) { uncertain(session, "NATIVE_START_UNCERTAIN"); }
        return session;
    }
    public static Observation advance(Session session) {
        requireThread(session.owner);
        if (session.state != State.MINING && session.state != State.READY) throw new IllegalStateException("MINING_SESSION_CLOSED");
        preflight(session);
        if (session.progress.counted(session.owner.server.getTickCount())) return session.observation();
        try {
            double rate = session.request.before().get(session.request.anchor()).getDestroyProgress(session.owner, session.level, session.request.anchor());
            if (!unchanged(session)) uncertain(session, "MINING_SPEED_CALLBACK_CHANGED_STATE");
            else {
                session.progress.advance(session.owner.server.getTickCount(), rate);
                session.state = session.progress.complete() ? State.READY : State.MINING;
            }
        } catch (RuntimeException | LinkageError unknown) { uncertain(session, "MINING_SPEED_CALLBACK_UNCERTAIN"); }
        return session.observation();
    }
    /** Once-only final native action; callers cannot bypass mining time by repeatedly invoking it. */
    public static Observation remove(Session session) {
        requireThread(session.owner);
        if (session.state != State.READY || !session.progress.complete()) throw new IllegalStateException("NATIVE_MINING_NOT_COMPLETE");
        preflight(session);
        try (var chain = NativeChainScope.begin(session.owner)) {
            double rate = session.request.before().get(session.request.anchor()).getDestroyProgress(session.owner, session.level, session.request.anchor());
            session.progress.advance(session.owner.server.getTickCount(), rate); session.progress.recheck(rate);
            if (!unchanged(session)) { uncertain(session, "MINING_SPEED_CALLBACK_CHANGED_STATE"); return session.observation(); }
            if (!session.progress.complete()) { session.state = State.MINING; session.reason = "MINING_RATE_CHANGED"; return session.observation(); }
            session.state = State.RECOVERY_REQUIRED; session.reason = "NATIVE_REMOVAL_ENTERED";
            var event = CommonHooks.onLeftClickBlock(session.owner, session.request.anchor(), session.request.face(), Action.STOP_DESTROY_BLOCK);
            if (event.isCanceled()) { reject(session, "NATIVE_STOP_DENIED"); return session.observation(); }
            preflight(session);
            session.progress.recheck(session.request.before().get(session.request.anchor()).getDestroyProgress(session.owner, session.level, session.request.anchor()));
            if (!session.progress.complete() || !unchanged(session)) { uncertain(session, "MINING_RATE_OR_STATE_CHANGED_BY_STOP"); return session.observation(); }
            boolean partial = !session.request.expected().get(session.request.anchor()).isAir();
            boolean returned, nativePartial;
            try (var scope = partial ? NativeKleeSlabScope.begin(session.owner, session.request.anchor(), session.request.before().get(session.request.anchor()),
                    session.request.expected().get(session.request.anchor()), () -> preflight(session)) : null) {
                returned = session.owner.gameMode.destroyBlock(session.request.anchor()); nativePartial = scope != null && scope.verified();
            }
            var observed = session.observation();
            boolean removed = session.request.expected().entrySet().stream().allMatch(row -> row.getValue().equals(observed.world().get(row.getKey())));
            boolean passive = session.request.before().entrySet().stream().filter(row -> !session.request.targets().contains(row.getKey()))
                    .allMatch(row -> row.getValue().equals(observed.world().get(row.getKey())));
            if ((returned && !partial || !returned && nativePartial) && removed && passive) {
                session.state = State.REMOVED; session.reason = "";
                if (partial) NativeRemovalProfiles.nativeExecuted(session.owner.server);
            }
            else if (!returned && observed.hand().equals(session.hand) && observed.world().equals(session.request.before())) {
                session.state = State.REJECTED; session.reason = "NATIVE_BREAK_DENIED";
            } else uncertain(session, "NATIVE_REMOVAL_OUTCOME_UNCERTAIN");
        } catch (RuntimeException | LinkageError unknown) { uncertain(session, "NATIVE_REMOVAL_CALLBACK_UNCERTAIN"); }
        return session.observation();
    }
    public static Observation cancel(Session session) {
        requireThread(session.owner);
        if (session.state != State.MINING && session.state != State.READY) throw new IllegalStateException("MINING_SESSION_CLOSED");
        session.state = State.CANCELLED; session.reason = "MINING_CANCELLED";
        try (var chain = NativeChainScope.begin(session.owner)) {
            preflight(session);
            CommonHooks.onLeftClickBlock(session.owner, session.request.anchor(), session.request.face(), Action.ABORT_DESTROY_BLOCK);
            if (!unchanged(session)) uncertain(session, "NATIVE_ABORT_CHANGED_STATE");
        }
        catch (RuntimeException | LinkageError unknown) { uncertain(session, "NATIVE_ABORT_CALLBACK_UNCERTAIN"); }
        return session.observation();
    }
    private static void preflight(Session session) {
        var owner = session.owner; requireThread(owner); var level = owner.serverLevel();
        if (owner.isFakePlayer() || owner.gameMode.getGameModeForPlayer() != GameType.SURVIVAL || owner.getAbilities().instabuild
                || !owner.isAlive() || owner.connection != session.connection || owner.serverLevel() != session.level
                || owner.server.getPlayerList().getPlayer(owner.getUUID()) != owner) throw new SecurityException("SURVIVAL_OWNER_REQUIRED");
        if (owner.getInventory().selected != session.handSlot || !ItemSnapshotCodec.capture(owner.getMainHandItem(), owner.registryAccess()).equals(session.hand))
            throw new IllegalStateException("MINING_TOOL_CHANGED");
        for (var row : session.request.before().entrySet()) {
            BlockPos pos = row.getKey(); session.guard.check(owner, pos);
            if (!level.hasChunkAt(pos) || level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)
                    || !level.getBlockState(pos).equals(row.getValue())) throw new IllegalStateException("REMOVAL_WORLD_CHANGED");
            if (session.request.targets().contains(pos)) {
                NativeRemovalProfiles.require(owner, pos, row.getValue(), session.request.expected().get(pos));
                if (row.getValue().isAir() || !row.getValue().getFluidState().isEmpty() || level.getBlockEntity(pos) != null
                        || row.getValue().getDestroySpeed(level, pos) < 0) throw new IllegalStateException("REMOVAL_SPECIAL_ADAPTER_REQUIRED");
                if (!level.mayInteract(owner, pos) || owner.blockActionRestricted(level, pos, GameType.SURVIVAL)) throw new SecurityException("REMOVAL_PROTECTED");
            }
        }
    }
    private static boolean unchanged(Session session) {
        var observed = session.observation(); return observed.hand().equals(session.hand) && observed.world().equals(session.request.before());
    }
    private static void reject(Session session, String reason) {
        if (unchanged(session)) { session.state = State.REJECTED; session.reason = reason; }
        else uncertain(session, reason + "_WITH_SIDE_EFFECTS");
    }
    private static void uncertain(Session session, String reason) { session.state = State.RECOVERY_REQUIRED; session.reason = reason; }
    private static void requireThread(ServerPlayer owner) { if (!owner.server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED"); }
}
