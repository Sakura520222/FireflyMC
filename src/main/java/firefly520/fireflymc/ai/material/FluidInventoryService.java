package firefly520.fireflymc.ai.material;

import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.security.Actor;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/** Main-thread native fluid sources. Grants are independent from item/container consumption. */
public final class FluidInventoryService implements AutoCloseable {
    public record Selection(MaterialSourceGrants.Candidate candidate, Object host, Object connection) {}
    public record Source(UUID grantId, long revision, String backend, String authorizationFingerprint,
                         FluidTankSnapshot snapshot) {
        public Source {
            if (grantId == null || revision < 1 || backend == null || backend.isBlank() || backend.length() > 1024
                    || authorizationFingerprint == null || !authorizationFingerprint.matches("[0-9a-f]{64}") || snapshot == null)
                throw new IllegalArgumentException("FLUID_SOURCE_INVALID");
        }
    }
    public record Refusal(UUID sourceId, String code) {}
    public record Snapshot(UUID id, String owner, long capturedAt, List<Source> sources, List<Refusal> refusals) {
        public Snapshot { sources = List.copyOf(sources); refusals = List.copyOf(refusals); }
    }
    /** This single-use object contains native references; never serialize it or accept it from clients. */
    public static final class Prepared {
        private final Actor actor;
        private final Source source;
        private final FluidAmount amount;
        private final boolean filling;
        private final Object host, connection;
        private boolean used;
        private Prepared(Actor actor, Source source, FluidAmount amount, boolean filling, Object host, Object connection) {
            this.actor = actor; this.source = source; this.amount = amount; this.filling = filling;
            this.host = host; this.connection = connection;
        }
        public Source source() { return source; }
        public FluidAmount amount() { return amount; }
        public boolean filling() { return filling; }
    }
    public record Outcome(boolean entered, boolean verified, FluidAmount actual, FluidTankSnapshot after,
                          String reason, NativeSaveBarrier.Receipt nativeSave) {}
    private record Live(ServerPlayer player, MaterialSourceGrants.Grant grant, StorageSourceIntrospector.FluidProbe probe) {}
    private static final class Scan {
        final Actor actor;
        final List<MaterialSourceGrants.Grant> grants;
        final CompletableFuture<Snapshot> future;
        final MaterialSourceGrants authorization;
        final List<Source> sources = new ArrayList<>();
        final List<Refusal> refusals = new ArrayList<>();
        final Set<String> backends = new HashSet<>();
        int index, bytes;
        Scan(Actor actor, MaterialSourceGrants authorization, List<MaterialSourceGrants.Grant> grants, CompletableFuture<Snapshot> future) {
            this.actor = actor; this.authorization = authorization; this.grants = grants; this.future = future;
        }
    }
    private final MinecraftServer server;
    private final MaterialInventoryService items;
    private final InstalledPermissionAdapters permissions;
    private final StorageSourceIntrospector storage = new StorageSourceIntrospector();
    private final NativeSaveObserver saves;
    private final Map<String, MaterialSourceGrants> authorizations = new ConcurrentHashMap<>();
    private final Map<UUID, Snapshot> snapshots = new HashMap<>();
    private final ArrayDeque<Scan> scans = new ArrayDeque<>();
    private final Map<CompletableFuture<Snapshot>, String> pendingReads = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private long maximumSliceNanos;
    private Predicate<String> recoveryBlocked = backend -> false;
    private Predicate<String> ownerRecoveryBlocked = owner -> false;

    public FluidInventoryService(MinecraftServer server, MaterialInventoryService items, InstalledPermissionAdapters permissions) {
        if (!server.isDedicatedServer()) throw new IllegalStateException("AI_DEDICATED_SERVER_REQUIRED");
        this.server = server; this.items = items; this.permissions = permissions;
        saves = NativeSaveObserver.forServer(server);
    }
    public void bind(String owner, MaterialSourceGrants grants) {
        if (grants.domain() != MaterialSourceGrants.Domain.FLUIDS) throw new IllegalArgumentException("FLUID_GRANT_REQUIRED");
        authorizations.put(owner, grants);
    }
    public void recoveryGuard(Predicate<String> guard) { thread(); recoveryBlocked = Objects.requireNonNull(guard); }
    public void recoveryGuard(Predicate<String> backendGuard, Predicate<String> ownerGuard) {
        recoveryGuard(backendGuard); ownerRecoveryBlocked = Objects.requireNonNull(ownerGuard);
    }
    public MaterialSourceGrants grants(Actor actor) {
        actor.requireAccess(actor.owner());
        var grants = authorizations.get(actor.owner());
        if (grants == null) throw new SecurityException("FLUID_AUTHORIZATION_NOT_READY");
        return grants;
    }
    public Selection selectBackpack(Actor actor, String locator) {
        thread(); ServerPlayer player = items.requirePlayer(actor);
        var host = items.equipment().requireHost(player, player.getUUID(), locator);
        var probe = storage.probeItemBackpackFluids(player, storage.captureItemHost(player, locator, 0,
                host.inventory(), host.nativeSlot()));
        require(probe.callable(), probe.reason()); unambiguous(player, probe);
        String fingerprint = storage.fluidSourceFingerprint(player, probe);
        var kind = host.source().equals("minecraft") ? MaterialSourceGrants.Kind.BACKPACK_ITEM
                : MaterialSourceGrants.Kind.EQUIPMENT_BACKPACK;
        var candidate = new MaterialSourceGrants.Candidate(kind, "fluid-item|" + locator + "|"
                + probe.evidence().contentsUuid() + "|" + player.serverLevel().dimension().location(),
                "本人储罐 " + locator + "；只授权原料流体盘点、获批取液与回填，不授权宿主或物品栏", List.of(), fingerprint, true, true);
        return new Selection(candidate, host.current(), player.connection);
    }
    public Selection selectLookedAt(Actor actor) {
        thread(); ServerPlayer player = items.requirePlayer(actor);
        var hit = player.pick(player.blockInteractionRange(), 1, false);
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK)
            throw new IllegalStateException("LOOK_AT_A_FLUID_SOURCE");
        return selectBlock(actor, block.getBlockPos(), block.getDirection(), false);
    }
    private Selection selectBlock(Actor actor, BlockPos pos, Direction side, boolean confirmed) {
        ServerPlayer player = items.requirePlayer(actor);
        var probe = block(player, pos, side); var entity = probe.evidence().memberEntity();
        UUID identity = confirmed ? MaterialSourceIdentity.confirmed(entity) : MaterialSourceIdentity.existing(entity);
        var member = new MaterialSourceGrants.Member(player.serverLevel().dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(), BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString(), identity);
        var source = new MaterialSourceGrants.Candidate(MaterialSourceGrants.Kind.BLOCK,
                "fluid-block|" + member.dimension() + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "|" + side.getName(),
                "原料流体来源 " + member.dimension() + " " + pos.toShortString() + " " + side.getName(),
                List.of(member), storage.fluidSourceFingerprint(player, probe), true, true);
        return new Selection(source, entity, player.connection);
    }
    public MaterialSourceGrants.Candidate confirmSelection(Actor actor, Selection selection) {
        thread(); ServerPlayer player = items.requirePlayer(actor);
        require(selection.connection() == player.connection, "SOURCE_CONNECTION_CHANGED");
        String[] parts = selection.candidate().locator().split("\\|", -1);
        Selection now = selection.candidate().kind() == MaterialSourceGrants.Kind.BLOCK
                ? selectBlock(actor, position(parts[2]), Direction.byName(parts[3]), false)
                : selectBackpack(actor, parts[1]);
        require(now.host() == selection.host() && now.candidate().equals(selection.candidate()), "FLUID_SOURCE_PREVIEW_CHANGED");
        return selection.candidate().kind() == MaterialSourceGrants.Kind.BLOCK
                ? selectBlock(actor, position(parts[2]), Direction.byName(parts[3]), true).candidate() : now.candidate();
    }
    public CompletableFuture<Snapshot> scan(Actor actor) {
        var future = read(actor); if (future.isDone()) return future;
        server.execute(() -> {
            if (future.isDone()) return;
            try {
                thread(); items.requirePlayer(actor);
                require(scans.size() < 16, "FLUID_SCAN_QUEUE_FULL");
                var authorization = grants(actor);
                scans.add(new Scan(actor, authorization, authorization.list(actor).stream().filter(g -> !g.revoked()).toList(), future));
            } catch (RuntimeException failure) { future.completeExceptionally(failure); }
        });
        return future;
    }
    /** One source per fair slice; no filesystem, HTTP, or unbounded world search on the game thread. */
    public void tick() {
        thread(); long start = System.nanoTime();
        try { scanTick(); } finally { maximumSliceNanos = Math.max(maximumSliceNanos, System.nanoTime() - start); }
    }
    public long maximumSliceNanos() { return maximumSliceNanos; }
    private void scanTick() {
        snapshots.values().removeIf(snapshot -> snapshot.capturedAt() + 120000 < System.currentTimeMillis());
        Scan scan = scans.poll(); if (scan == null) return;
        if (scan.future.isCancelled()) return;
        try {
            items.requirePlayer(scan.actor);
            scan.authorization.list(scan.actor); // The old privacy epoch cannot publish a partly accumulated scan.
            require(grants(scan.actor) == scan.authorization, "WORKSPACE_PRIVACY_EPOCH_CHANGED");
            if (scan.index < scan.grants.size()) {
                var grant = scan.grants.get(scan.index++);
                try {
                    Source source = capture(resolve(scan.actor, grant.id(), grant.revision(), false));
                    if (scan.backends.add(source.backend())) {
                        scan.bytes += source.snapshot().tanks().stream().mapToInt(t -> t.fluid().unitJson().length()).sum();
                        require(scan.bytes <= 4 * 1048576, "FLUID_SCAN_COMPONENT_LIMIT"); scan.sources.add(source);
                    }
                } catch (RuntimeException refusal) { scan.refusals.add(new Refusal(grant.id(), safe(refusal))); }
            }
            if (scan.index == scan.grants.size()) {
                var result = new Snapshot(UUID.randomUUID(), scan.actor.owner(), System.currentTimeMillis(), scan.sources, scan.refusals);
                snapshots.values().removeIf(old -> old.owner().equals(scan.actor.owner()));
                snapshots.put(result.id(), result); scan.future.complete(result);
            } else scans.add(scan);
        } catch (RuntimeException failure) { scan.future.completeExceptionally(failure); }
    }
    public Snapshot snapshot(Actor actor, UUID id) {
        thread(); items.requirePlayer(actor);
        var snapshot = snapshots.get(id);
        require(snapshot != null && snapshot.owner().equals(actor.owner())
                && snapshot.capturedAt() + 120000 >= System.currentTimeMillis(), "FLUID_SNAPSHOT_EXPIRED");
        return snapshot;
    }
    public CompletableFuture<Snapshot> snapshotAsync(Actor actor, UUID id) {
        var future = read(actor); if (future.isDone()) return future;
        server.execute(() -> {
            if (future.isDone()) return;
            try { future.complete(snapshot(actor, id)); }
            catch (RuntimeException failure) { future.completeExceptionally(failure); }
        });
        return future;
    }
    private CompletableFuture<Snapshot> read(Actor actor) {
        actor.requireAccess(actor.owner()); var future = new CompletableFuture<Snapshot>();
        if (pendingReads.size() >= 64) return CompletableFuture.failedFuture(new IllegalStateException("FLUID_READ_QUEUE_FULL"));
        pendingReads.put(future, actor.owner()); future.whenComplete((result, failure) -> pendingReads.remove(future));
        if (closed) future.completeExceptionally(new IllegalStateException("SERVER_STOPPING"));
        return future;
    }
    public Source current(Actor actor, UUID grant, long revision, boolean extraction) {
        thread(); return capture(resolve(actor, grant, revision, extraction));
    }
    public Prepared prepare(Actor actor, Source expected, FluidAmount amount, boolean filling) {
        thread(); require(!amount.empty() && amount.millibuckets() <= 1000000, "FLUID_TRANSFER_LIMIT");
        require(!ownerRecoveryBlocked.test(actor.owner()), "FLUID_OWNER_RECOVERY_REQUIRED");
        var live = resolve(actor, expected.grantId(), expected.revision(), !filling);
        Source current = capture(live);
        require(current.equals(expected), "FLUID_SOURCE_CHANGED");
        var input = FluidSnapshotCodec.restore(amount, live.player().registryAccess());
        FluidAmount simulated;
        if (filling) {
            int accepted = live.probe().handler().fill(input, FluidAction.SIMULATE);
            require(accepted >= 0 && accepted <= amount.millibuckets(), "FLUID_SIMULATION_INVALID");
            simulated = amount.withAmount(accepted);
        } else simulated = FluidSnapshotCodec.capture(live.probe().handler().drain(input, FluidAction.SIMULATE), live.player().registryAccess());
        require(current.snapshot().equals(FluidTankSnapshot.capture(live.probe().handler(), live.player().registryAccess())), "FLUID_SIMULATION_MUTATED");
        require(simulated.empty() || simulated.unitJson().equals(amount.unitJson())
                && simulated.millibuckets() <= amount.millibuckets(), "FLUID_SIMULATION_INVALID");
        require(!simulated.empty(), filling ? "FLUID_OUTPUT_FULL" : "FLUID_NOT_AVAILABLE");
        saves.requireCapacity(nativeStores(live));
        return new Prepared(actor, current, simulated, filling, nativeHost(live.probe()), live.player().connection);
    }
    /** Caller must durably journal this exact prepared intent before entering this method. */
    public Outcome perform(Prepared prepared) {
        thread(); if (prepared.used) throw new IllegalStateException("FLUID_PREPARED_ALREADY_USED");
        prepared.used = true;
        Live live; List<NativeSaveObserver.Target> targets; net.neoforged.neoforge.fluids.FluidStack input;
        try {
            require(!ownerRecoveryBlocked.test(prepared.actor.owner()), "FLUID_OWNER_RECOVERY_REQUIRED");
            live = resolve(prepared.actor, prepared.source.grantId(), prepared.source.revision(), !prepared.filling);
            require(nativeHost(live.probe()) == prepared.host && live.player().connection == prepared.connection,
                    "FLUID_HOST_OR_CONNECTION_CHANGED");
            require(capture(live).equals(prepared.source), "FLUID_SOURCE_CHANGED");
            targets = nativeStores(live); saves.requireCapacity(targets);
            input = FluidSnapshotCodec.restore(prepared.amount, live.player().registryAccess());
        } catch (RuntimeException rejected) { return new Outcome(false, true, FluidAmount.EMPTY, prepared.source.snapshot(), safe(rejected), null); }
        FluidAmount actual = FluidAmount.EMPTY; FluidTankSnapshot after = null; String reason = ""; boolean verified = false;
        try {
            if (prepared.filling) {
                int accepted = live.probe().handler().fill(input, FluidAction.EXECUTE);
                require(accepted >= 0 && accepted <= prepared.amount.millibuckets(), "FLUID_NATIVE_AMOUNT_INVALID");
                actual = prepared.amount.withAmount(accepted);
            } else actual = FluidSnapshotCodec.capture(live.probe().handler().drain(input, FluidAction.EXECUTE), live.player().registryAccess());
            after = FluidTankSnapshot.capture(live.probe().handler(), live.player().registryAccess());
            verified = (actual.empty() || actual.unitJson().equals(prepared.amount.unitJson()))
                    && actual.millibuckets() <= prepared.amount.millibuckets()
                    && prepared.source.snapshot().verifies(after, actual, prepared.filling);
            if (!verified) reason = "FLUID_CONSERVATION_UNCERTAIN";
        } catch (RuntimeException | LinkageError failure) { reason = safe(failure); }
        return new Outcome(true, verified, actual, after, reason, saves.changedOrUnconfirmed(targets));
    }
    private Live resolve(Actor actor, UUID id, long revision, boolean extraction) {
        ServerPlayer player = items.requirePlayer(actor);
        var grant = grants(actor).require(actor, id, revision, extraction);
        String[] parts = grant.source().locator().split("\\|", -1);
        require(parts.length == 4, "FLUID_SOURCE_LOCATOR_INVALID");
        StorageSourceIntrospector.FluidProbe probe;
        if (grant.source().kind() == MaterialSourceGrants.Kind.BLOCK) {
            require(parts[0].equals("fluid-block") && parts[1].equals(player.serverLevel().dimension().location().toString())
                    && grant.source().members().size() == 1, "FLUID_SOURCE_LOCATION_CHANGED");
            probe = block(player, position(parts[2]), Direction.byName(parts[3]));
            var member = grant.source().members().getFirst();
            require(member.identity() != null && member.identity().equals(MaterialSourceIdentity.existing(probe.evidence().memberEntity())),
                    "SOURCE_IDENTITY_CHANGED");
        } else {
            require(parts[0].equals("fluid-item") && parts[3].equals(player.serverLevel().dimension().location().toString()), "FLUID_SOURCE_LOCATION_CHANGED");
            var host = items.equipment().requireHost(player, player.getUUID(), parts[1]);
            require(host.source().equals("minecraft") ? grant.source().kind() == MaterialSourceGrants.Kind.BACKPACK_ITEM
                    : grant.source().kind() == MaterialSourceGrants.Kind.EQUIPMENT_BACKPACK, "FLUID_HOST_KIND_CHANGED");
            probe = storage.probeItemBackpackFluids(player, storage.captureItemHost(player, parts[1], revision, host.inventory(), host.nativeSlot()));
            require(probe.callable(), probe.reason());
            require(parts[2].equals(probe.evidence().contentsUuid().toString()), "FLUID_BACKEND_CHANGED"); unambiguous(player, probe);
        }
        require(!recoveryBlocked.test(probe.backendId()), "FLUID_SOURCE_RECOVERY_REQUIRED");
        require(storage.fluidSourceFingerprint(player, probe).equals(grant.source().hostFingerprint()), "FLUID_SOURCE_UPGRADES_CHANGED");
        return new Live(player, grant, probe);
    }
    private StorageSourceIntrospector.FluidProbe block(ServerPlayer player, BlockPos pos, Direction side) {
        require(side != null && player.serverLevel().hasChunkAt(pos), "FLUID_SOURCE_UNLOADED");
        var permission = permissions.checkContainer(player, player.serverLevel(), pos, side);
        if (!permission.allowed()) throw new SecurityException(permission.code());
        var probe = storage.probeBlockFluids(player, player.serverLevel(), pos, side);
        require(probe.callable(), probe.reason()); unambiguous(player, probe); return probe;
    }
    private void unambiguous(ServerPlayer player, StorageSourceIntrospector.FluidProbe probe) {
        for (var host : items.equipment().hostSlots(player, player.getUUID()))
            if (host.current() != probe.evidence().hostReference()
                    && storage.existingBackpackIdentity(player, host.current()).filter(probe.evidence().contentsUuid()::equals).isPresent())
                throw new IllegalStateException("BACKPACK_DUPLICATE_HOST");
    }
    private Source capture(Live live) {
        return new Source(live.grant().id(), live.grant().revision(), live.probe().backendId(),
                live.grant().source().hostFingerprint(), // resolve already validated this exact main-thread probe.
                FluidTankSnapshot.capture(live.probe().handler(), live.player().registryAccess()));
    }
    private List<NativeSaveObserver.Target> nativeStores(Live live) {
        var store = storage.backpackFluidSaveStore(live.player(), live.probe());
        var content = NativeSaveObserver.Target.file(store, NativeSaveObserver.dimensionFolder(server.overworld()).resolve("data/sophisticatedbackpacks.dat"));
        var host = nativeHost(live.probe());
        return List.of(content, host instanceof BlockEntity entity ? NativeSaveObserver.blockEntity(live.player().serverLevel(), entity)
                : NativeSaveObserver.player(live.player()));
    }
    private static Object nativeHost(StorageSourceIntrospector.FluidProbe probe) {
        return probe.evidence().memberEntity() == null ? probe.evidence().hostReference() : probe.evidence().memberEntity();
    }
    private static BlockPos position(String value) {
        String[] coordinates = value.split(",", -1);
        if (coordinates.length != 3) throw new IllegalArgumentException("FLUID_SOURCE_POSITION_INVALID");
        return new BlockPos(Integer.parseInt(coordinates[0]), Integer.parseInt(coordinates[1]), Integer.parseInt(coordinates[2]));
    }
    public void clearPrivateViews(String owner) {
        thread(); snapshots.values().removeIf(snapshot -> snapshot.owner().equals(owner));
        pendingReads.forEach((future, player) -> {
            if (player.equals(owner)) future.completeExceptionally(new IllegalStateException("WORKSPACE_PRIVACY_EPOCH_CHANGED"));
        });
        scans.removeIf(scan -> {
            if (!scan.actor.owner().equals(owner)) return false;
            scan.future.completeExceptionally(new IllegalStateException("WORKSPACE_PRIVACY_EPOCH_CHANGED")); return true;
        });
    }
    public NativeSaveObserver nativeSaves() { return saves; }
    public void close() {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        closed = true; scans.forEach(scan -> scan.future.completeExceptionally(new IllegalStateException("SERVER_STOPPING")));
        pendingReads.keySet().forEach(future -> future.completeExceptionally(new IllegalStateException("SERVER_STOPPING")));
        scans.clear(); snapshots.clear();
    }
    private void thread() {
        if (!server.isDedicatedServer()) throw new IllegalStateException("AI_DEDICATED_SERVER_REQUIRED");
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        if (closed) throw new IllegalStateException("SERVER_STOPPING");
    }
    private static void require(boolean test, String code) { if (!test) throw new IllegalStateException(code); }
    private static String safe(Throwable failure) {
        String message = failure.getMessage();
        return message != null && message.matches("[A-Z_]{1,100}") ? message : "FLUID_NATIVE_FAILURE";
    }
}
