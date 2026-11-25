package firefly520.fireflymc.ai.material;

import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.integration.equipment.PlayerEquipmentBridge;
import firefly520.fireflymc.ai.integration.maid.MaidCarrierBridge;
import firefly520.fireflymc.ai.integration.maid.MaidEntityPermission;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Real, explicitly authorized inventories. Minecraft access stays on its main thread;
 * bounded scans yield between slots and never enumerate nearby unapproved containers.
 */
public final class MaterialInventoryService {
    public record Refusal(String sourceId, String reason) {}
    public record Snapshot(UUID id, String owner, long createdAt, List<MaterialLedger.Stock> rows,
                           List<Refusal> refusals) {
        public Snapshot { rows = List.copyOf(rows); refusals = List.copyOf(refusals); }
    }
    private record Slot(String sourceId, long revision, String backend, String pool, int localSlot,
                        long weight, Supplier<Long> poolUnits, IItemHandler handler, int handlerSlot,
                        Object host, MaterialSourceGrants.Grant grant, boolean repeatableExtraction) {}
    public record PreparedExtraction(String owner, MaterialLedger.Stock stock, MaterialStack before,
                                     long poolUnits, String witness, Object opaque) {}
    public record Extracted(MaterialStack actual, long poolUnitsAfter, boolean mutationEntered, String error, NativeSaveBarrier.Receipt save) {
        public Extracted(MaterialStack actual, long units, boolean entered, String error) { this(actual, units, entered, error, null); }
    }
    public record PreparedDelivery(String owner, int slot, MaterialStack product, MaterialStack before,
                                   String witness, Object playerIdentity, Object destination) {
        public PreparedDelivery(String owner, int slot, MaterialStack product, MaterialStack before,
                                String witness, Object playerIdentity) {
            this(owner, slot, product, before, witness, playerIdentity, null);
        }
    }
    private record DeliveryTarget(Slot slot, long poolUnits) {}
    public record Delivered(int accepted, boolean verified, boolean mutationEntered, String error, NativeSaveBarrier.Receipt save) {
        public Delivered(int accepted, boolean verified, boolean entered, String error) { this(accepted, verified, entered, error, null); }
    }
    public record SourceSelection(MaterialSourceGrants.Candidate candidate, BlockPos pos, Direction side,
                                  List<BlockEntity> identities, Object connection, Object portableWitness) {
        public SourceSelection(MaterialSourceGrants.Candidate candidate, BlockPos pos, Direction side,
                               List<BlockEntity> identities, Object connection) {
            this(candidate, pos, side, identities, connection, null);
        }
    }
    public record BackpackChoice(String locator, String itemId, String fingerprint, String source, boolean active) {}
    private record PortableHost(String locator, Object reference, String fingerprint, String backend,
                                String dimension, String stateRevision, Supplier<ItemStack> currentItem) {}
    private static final class Scan {
        final String owner;
        final List<Slot> slots;
        final List<MaterialLedger.Stock> rows = new ArrayList<>();
        final List<Refusal> refusals;
        final CompletableFuture<Snapshot> future;
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        int cursor;
        int encodedBytes;
        Scan(String owner, List<Slot> slots, List<Refusal> refusals, CompletableFuture<Snapshot> future) {
            this.owner = owner; this.slots = slots; this.refusals = refusals; this.future = future;
        }
    }
    private final MinecraftServer server;
    private final Predicate<ServerPlayer> authenticated;
    private final InstalledPermissionAdapters permissions;
    private final StorageSourceIntrospector storage = new StorageSourceIntrospector();
    private final PlayerEquipmentBridge equipment;
    private final MaidCarrierBridge maids;
    private final MaidEntityPermission maidPermission;
    private final NativeSaveObserver nativeSaves;
    private final Map<String, MaterialSourceGrants> grants = new ConcurrentHashMap<>();
    private final Map<String, MaterialPreferences> preferences = new ConcurrentHashMap<>();
    private final ArrayDeque<Scan> scans = new ArrayDeque<>();
    private final Map<UUID, Snapshot> snapshots = new LinkedHashMap<>();
    private volatile long maximumSliceNanos;
    private java.util.function.BiPredicate<String, String> recoveryBlocked = (owner, backend) -> false;

    public void recoveryGuard(java.util.function.BiPredicate<String, String> guard) { thread(); recoveryBlocked = Objects.requireNonNull(guard); }
    private void mutationAllowed(String owner, String backend) {
        if (recoveryBlocked.test(owner, backend)) throw new IllegalStateException("MATERIAL_SOURCE_RECOVERY_REQUIRED");
    }

    public MaterialInventoryService(MinecraftServer server, Predicate<ServerPlayer> authenticated,
                                    InstalledPermissionAdapters permissions) {
        if (!server.isDedicatedServer()) throw new IllegalStateException("AI_DEDICATED_SERVER_REQUIRED");
        this.server = server; this.authenticated = authenticated; this.permissions = permissions;
        equipment = new PlayerEquipmentBridge(server, authenticated);
        maids = new MaidCarrierBridge(server, authenticated);
        maidPermission = new MaidEntityPermission(server, authenticated, permissions);
        nativeSaves = NativeSaveObserver.forServer(server);
    }
    public void bind(String owner, MaterialSourceGrants sourceGrants) { grants.put(owner, sourceGrants); }
    public void bindPreferences(String owner, MaterialPreferences policy) { preferences.put(owner, policy); }
    public MaterialPreferences preferences(Actor actor) {
        actor.requireAccess(actor.owner()); var policy = preferences.get(actor.owner());
        if (policy == null) throw new IllegalStateException("MATERIAL_PREFERENCES_NOT_READY"); return policy;
    }
    /** Immutable policy/snapshot lookup; safe for the private IO projection, with no native state access. */
    public boolean protectedByOwner(Actor actor, MaterialStack stack) {
        actor.requireAccess(actor.owner()); var policy = preferences.get(actor.owner());
        return policy != null && !stack.empty() && policy.protects(AiJson.object(stack.itemJson(), 1_048_576, 32).get("id").getAsString());
    }
    public void clearPrivateViews(String owner) {
        thread(); snapshots.values().removeIf(snapshot -> snapshot.owner().equals(owner));
        scans.removeIf(scan -> {
            if (!scan.owner.equals(owner)) return false;
            scan.future.completeExceptionally(new IllegalStateException("WORKSPACE_PRIVACY_EPOCH_CHANGED")); return true;
        });
    }
    public MaterialSourceGrants grants(Actor actor) {
        actor.requireAccess(actor.owner());
        var repository = grants.get(actor.owner());
        if (repository == null) throw new IllegalStateException("SOURCE_AUTHORIZATION_NOT_READY");
        return repository;
    }
    public SourceSelection selectLookedAt(Actor actor) {
        ServerPlayer player = requirePlayer(actor);
        var hit = player.pick(player.blockInteractionRange(), 1, false);
        if (!(hit instanceof net.minecraft.world.phys.BlockHitResult block)
                || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK)
            throw new IllegalStateException("LOOK_AT_A_CONTAINER");
        var candidate = blockCandidate(actor, block.getBlockPos(), block.getDirection(), true, true, false);
        List<BlockEntity> identities = candidate.members().stream().map(m ->
                player.serverLevel().getBlockEntity(new BlockPos(m.x(), m.y(), m.z()))).toList();
        return new SourceSelection(candidate, block.getBlockPos().immutable(), block.getDirection(), identities, player.connection);
    }
    public MaterialSourceGrants.Candidate confirmSelection(Actor actor, SourceSelection selection) {
        ServerPlayer player = requirePlayer(actor);
        if (selection.connection() != player.connection) throw new IllegalStateException("SOURCE_CONNECTION_CHANGED");
        if (selection.candidate().kind() != MaterialSourceGrants.Kind.BLOCK) {
            SourceSelection current = selection.candidate().kind() == MaterialSourceGrants.Kind.MAID_BACKPACK
                    ? selectMaidBackpack(actor, UUID.fromString(selection.candidate().locator().split("\\|", -1)[2]))
                    : selectBackpack(actor, selection.candidate().locator().split("\\|", -1)[1]);
            if (!current.candidate().equals(selection.candidate())
                    || !samePortableHost((PortableHost) selection.portableWitness(), (PortableHost) current.portableWitness()))
                throw new IllegalStateException("SOURCE_HOST_CHANGED");
            return current.candidate();
        }
        for (var member : selection.identities()) {
            if (member.getLevel() == null || member.isRemoved() || !member.getLevel().hasChunkAt(member.getBlockPos())
                    || member.getLevel().getBlockEntity(member.getBlockPos()) != member)
                throw new IllegalStateException("SOURCE_IDENTITY_CHANGED");
        }
        // Recheck the full membership and permission before attaching even the identity metadata.
        var current = blockCandidate(actor, selection.pos(), selection.side(), true, true, false);
        if (!current.equals(selection.candidate())) throw new IllegalStateException("SOURCE_PREVIEW_CHANGED");
        return blockCandidate(actor, selection.pos(), selection.side(), true, true, true);
    }
    public long maximumSliceNanos() { return maximumSliceNanos; }
    public ServerPlayer requirePlayer(Actor actor) { actor.requireAccess(actor.owner()); return player(actor.owner()); }
    public PlayerEquipmentBridge equipment() { return equipment; }
    public MaidCarrierBridge maids() { return maids; }
    private void checkMaidPermission(ServerPlayer player, MaidCarrierBridge.BackpackSource source) {
        maidPermission.require(player, player.getUUID(), source.entity());
    }

    /** Lists host items only. Contents remain unread until this player explicitly selects a host. */
    public List<BackpackChoice> backpackChoices(Actor actor) {
        ServerPlayer player = requirePlayer(actor);
        return equipment.hostSlots(player, player.getUUID()).stream()
                .filter(host -> !host.current().isEmpty() && host.current().getItem().getClass().getName()
                        .equals("net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem"))
                .map(host -> new BackpackChoice(host.locator(), BuiltInRegistries.ITEM.getKey(host.current().getItem()).toString(),
                        ItemSnapshotCodec.capture(host.current(), player.registryAccess()).fingerprint(), host.source(), host.active()))
                .toList();
    }

    public SourceSelection selectBackpack(Actor actor, String locator) {
        ServerPlayer player = requirePlayer(actor);
        var host = equipment.requireHost(player, player.getUUID(), locator);
        var captured = storage.captureItemHost(player, locator, 0, host.inventory(), host.nativeSlot());
        var probe = storage.probeItemBackpack(player, captured);
        if (!probe.callable() || probe.slots().isEmpty()) throw new IllegalStateException(probe.reason());
        var evidence = probe.slots().getFirst().evidence();
        requireUnambiguousBackpackHost(player, evidence.contentsUuid(), host.current());
        String fingerprint = backpackFingerprint(player, host.current(), probe);
        MaterialSourceGrants.Kind kind = host.source().equals("minecraft") ? MaterialSourceGrants.Kind.BACKPACK_ITEM
                : MaterialSourceGrants.Kind.EQUIPMENT_BACKPACK;
        String backend = StorageSourceIntrospector.backpackBackend(evidence.contentsUuid());
        var candidate = new MaterialSourceGrants.Candidate(kind, "item|" + locator + "|" + evidence.contentsUuid()
                + "|" + player.serverLevel().dimension().location(),
                "本人背包内容 " + locator + "；不授权消耗或卸下宿主装备", List.of(), fingerprint, true, true);
        return new SourceSelection(candidate, null, null, List.of(), player.connection,
                new PortableHost(locator, host.current(), fingerprint, backend,
                        player.serverLevel().dimension().location().toString(), fingerprint, host::current));
    }

    public SourceSelection selectMaidBackpack(Actor actor, UUID maidId) {
        ServerPlayer player = requirePlayer(actor);
        var source = maids.backpackSource(player, player.getUUID(), maidId);
        checkMaidPermission(player, source);
        var candidate = new MaterialSourceGrants.Candidate(MaterialSourceGrants.Kind.MAID_BACKPACK,
                "maid|" + source.dimension() + "|" + maidId, "本人女仆 " + maidId + " 的普通背包 " + source.slots()
                + " 格；不含手持、护甲、饰品、隐藏或任务槽", List.of(), source.revisionFingerprint(), true, true);
        return new SourceSelection(candidate, null, null, List.of(), player.connection,
                new PortableHost(maidId.toString(), source.entity(), source.revisionFingerprint(), "maid/" + maidId,
                        source.dimension(), source.revisionFingerprint(), null));
    }

    /** Server-derived visible target for workbench selection; no client entity ID or nearby maid scan. */
    public UUID lookedAtOwnedMaid(Actor actor) {
        ServerPlayer player = requirePlayer(actor);
        double reach = player.entityInteractionRange();
        var start = player.getEyePosition(1);
        var direction = player.getViewVector(1).scale(reach);
        var end = start.add(direction);
        var hit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(player, start, end,
                player.getBoundingBox().expandTowards(direction).inflate(1),
                entity -> !entity.isSpectator() && entity.isPickable(), reach * reach);
        var block = player.pick(reach, 1, false);
        if (hit == null || block.getType() != net.minecraft.world.phys.HitResult.Type.MISS
                && start.distanceToSqr(block.getLocation()) < start.distanceToSqr(hit.getLocation()))
            throw new IllegalStateException("LOOK_AT_OWN_MAID");
        var observed = maids.inspectOwned(player, player.getUUID(), hit.getEntity().getUUID());
        if (observed.maid() == null) throw new IllegalStateException(observed.reason());
        return observed.maid().maidId();
    }

    private static boolean samePortableHost(PortableHost before, PortableHost current) {
        return before != null && current != null && before.reference() == current.reference()
                && before.locator().equals(current.locator()) && before.fingerprint().equals(current.fingerprint())
                && before.backend().equals(current.backend()) && before.dimension().equals(current.dimension())
                && before.stateRevision().equals(current.stateRevision());
    }
    private String backpackFingerprint(ServerPlayer player, ItemStack host, StorageSourceIntrospector.Probe probe) {
        var data = new com.google.gson.JsonObject();
        data.addProperty("host", ItemSnapshotCodec.capture(storage.authorizationFingerprintStack(player, host, true), player.registryAccess()).fingerprint());
        data.addProperty("slots", probe.slots().size());
        data.addProperty("version", probe.versionEvidence());
        var upgrades = new com.google.gson.JsonArray();
        for (var upgrade : probe.slots().getFirst().evidence().upgrades()) {
            var row = new com.google.gson.JsonObject(); row.addProperty("slot", upgrade.slot());
            row.addProperty("type", upgrade.wrapperType()); row.addProperty("enabled", upgrade.enabled());
            row.addProperty("stack", ItemSnapshotCodec.capture(storage.authorizationFingerprintStack(player, upgrade.snapshot(), false), player.registryAccess()).fingerprint());
            upgrades.add(row);
        }
        data.add("upgrades", upgrades);
        return AiJson.digest(AiJson.GSON.toJson(data));
    }
    private void requireUnambiguousBackpackHost(ServerPlayer player, UUID backend, ItemStack selected) {
        for (var host : equipment.hostSlots(player, player.getUUID())) {
            var identity = storage.existingBackpackIdentity(player, host.current());
            if (identity.filter(backend::equals).isPresent() && host.current() != selected)
                throw new IllegalStateException("BACKPACK_BACKEND_AMBIGUOUS");
        }
    }

    /** Conservative simulation on detached main slots, accounting only for exact planned player inputs. */
    public boolean outputFits(Actor actor, List<MaterialStack> products, List<MaterialLedger.Stock> sources,
                              List<MaterialLedger.Need> needs) {
        ServerPlayer player = requirePlayer(actor);
        var handler = new PlayerMainInvWrapper(player.getInventory());
        List<ItemStack> slots = new ArrayList<>();
        for (int i = 0; i < handler.getSlots(); i++) slots.add(RecipeSafeCopy(handler.getStackInSlot(i), player));
        for (var need : needs) {
            var source = sources.stream().filter(s -> s.key().equals(need.stockKey())).findFirst().orElseThrow();
            if (source.sourceId().equals("player") && source.slot() < 36) {
                ItemStack actual = slots.get(source.slot());
                if (!ItemSnapshotCodec.capture(actual, player.registryAccess()).equals(source.stack()) || actual.getCount() < need.count())
                    throw new IllegalStateException("MATERIAL_SOURCE_CHANGED");
                actual.shrink(need.count());
            }
        }
        List<MaterialStack> overflow = new ArrayList<>();
        for (MaterialStack product : products) {
            ItemStack remaining = ItemSnapshotCodec.restore(product, player.registryAccess());
            for (int i = 0; i < slots.size() && !remaining.isEmpty(); i++) {
                ItemStack current = slots.get(i);
                if (!current.isEmpty() && !ItemStack.isSameItemSameComponents(current, remaining)) continue;
                int count = Math.min(remaining.getCount(), Math.min(64, remaining.getMaxStackSize()) - current.getCount());
                if (count <= 0) continue;
                if (current.isEmpty()) slots.set(i, remaining.copyWithCount(count)); else current.grow(count);
                remaining.shrink(count);
            }
            if (!remaining.isEmpty()) overflow.add(ItemSnapshotCodec.capture(remaining, player.registryAccess()));
        }
        return overflow.isEmpty() || authorizedOutputFits(player, actor, overflow);
    }

    public CompletableFuture<Snapshot> scan(Actor actor) {
        actor.requireAccess(actor.owner());
        var future = new CompletableFuture<Snapshot>();
        server.execute(() -> {
            try {
                ServerPlayer player = player(actor.owner());
                if (scans.size() >= 16) throw new IllegalStateException("MATERIAL_SCAN_QUEUE_FULL");
                var refusals = new ArrayList<Refusal>();
                var slots = new ArrayList<>(ownSlots(player));
                MaterialSourceGrants repository = grants.get(actor.owner());
                if (repository != null) for (var grant : repository.list(actor)) {
                    if (grant.revoked() || !grant.source().extract()) continue;
                    try { slots.addAll(grantedSlots(player, grant, true)); }
                    catch (RuntimeException failure) { refusals.add(new Refusal(grant.id().toString(), safeReason(failure))); }
                }
                if (slots.size() > 16384) throw new IllegalStateException("MATERIAL_SCAN_SLOT_LIMIT");
                scans.addLast(new Scan(actor.owner(), uniqueSources(slots, refusals), refusals, future));
            } catch (Exception failure) { future.completeExceptionally(failure); }
        });
        return future;
    }

    public void tick() {
        thread();
        long start = System.nanoTime();
        int count = 0;
        while (!scans.isEmpty() && count < 64 && System.nanoTime() - start < 1_500_000) {
            Scan scan = scans.removeFirst();
            if (scan.future.isCancelled()) continue;
            try {
                if (System.nanoTime() > scan.deadline) throw new IllegalStateException("MATERIAL_SCAN_TIMEOUT");
                ServerPlayer player = player(scan.owner);
                int ownerCount = 0;
                Set<UUID> checkedGrants = new HashSet<>();
                while (scan.cursor < scan.slots.size() && ownerCount++ < 16 && count++ < 64) {
                    Slot slot = scan.slots.get(scan.cursor++);
                    if (slot.grant() == null || checkedGrants.add(slot.grant().id())) validateGrant(player, slot, true);
                    else grants(Actor.player(player.getUUID(), true, true, 0)).require(
                            Actor.player(player.getUUID(), true, true, 0), slot.grant().id(), slot.revision(), true);
                    if (!liveHost(player, slot)) throw new IllegalStateException("MATERIAL_HOST_CHANGED");
                    var captured = capture(player, slot);
                    if (!captured.stack().empty()) {
                        scan.encodedBytes += captured.stack().itemJson().length() * 3;
                        if (scan.encodedBytes > 8 * 1024 * 1024) throw new IllegalStateException("MATERIAL_SCAN_COMPONENT_LIMIT");
                        scan.rows.add(captured);
                    }
                    if (System.nanoTime() - start >= 1_500_000) break;
                }
                if (scan.cursor == scan.slots.size()) {
                    Snapshot value = new Snapshot(UUID.randomUUID(), scan.owner, System.currentTimeMillis(), scan.rows, scan.refusals);
                    snapshots.values().removeIf(s -> System.currentTimeMillis() - s.createdAt() > 60000);
                    while (snapshots.size() >= 64) snapshots.remove(snapshots.keySet().iterator().next());
                    snapshots.put(value.id(), value);
                    long bytes = snapshots.values().stream().flatMap(s -> s.rows().stream()).mapToLong(r -> r.stack().itemJson().length() * 3L).sum();
                    while (bytes > 32L * 1024 * 1024 && snapshots.size() > 1) {
                        var removed = snapshots.remove(snapshots.keySet().iterator().next());
                        bytes -= removed.rows().stream().mapToLong(r -> r.stack().itemJson().length() * 3L).sum();
                    }
                    scan.future.complete(value);
                } else scans.addLast(scan);
            } catch (Exception failure) { scan.future.completeExceptionally(failure); }
        }
        maximumSliceNanos = Math.max(maximumSliceNanos, System.nanoTime() - start);
    }

    public Snapshot snapshot(Actor actor, UUID id) {
        thread(); actor.requireAccess(actor.owner());
        Snapshot value = snapshots.get(id);
        if (value == null || !value.owner().equals(actor.owner())) throw new SecurityException("MATERIAL_SNAPSHOT_NOT_FOUND");
        if (System.currentTimeMillis() - value.createdAt() > 60000) throw new IllegalStateException("MATERIAL_SNAPSHOT_EXPIRED");
        return value;
    }

    public PreparedExtraction prepareExtraction(Actor actor, MaterialLedger.Stock expected, int count) {
        thread();
        mutationAllowed(actor.owner(), expected.backendId());
        ServerPlayer player = player(actor.owner());
        actor.requireAccess(player.getUUID().toString());
        Slot slot = resolve(player, expected, true);
        MaterialLedger.Stock current = capture(player, slot);
        if (!current.stack().equals(expected.stack()) || current.poolUnits() != expected.poolUnits()
                || count < 1 || current.extractableCount() < count || current.maxTransferCount() < count)
            throw new IllegalStateException("MATERIAL_SOURCE_CHANGED");
        String witness = AiJson.digest(current.toString() + ":" + UUID.randomUUID());
        return new PreparedExtraction(actor.owner(), current, current.stack(), current.poolUnits(), witness, slot);
    }

    /** Call only after the matching ledger intent is durable. A rejected preflight never calls native extraction. */
    public Extracted extract(PreparedExtraction prepared, int count) {
        thread();
        Slot slot;
        ServerPlayer player;
        List<NativeSaveObserver.Target> nativeStores;
        try {
            player = player(prepared.owner());
            mutationAllowed(prepared.owner(), prepared.stock().backendId());
            slot = (Slot) prepared.opaque();
            validateGrant(player, slot, true);
            if (!liveHost(player, slot)) throw new IllegalStateException("MATERIAL_HOST_CHANGED");
            // Capabilities/upgrades can be replaced during the durable-intent IO gap. Resolve the
            // current safe native handler again; a retained wrapper is not an authorization proof.
            slot = resolve(player, prepared.stock(), true);
            MaterialLedger.Stock current = capture(player, slot);
            if (!current.equals(prepared.stock()) || count < 1 || count > current.maxTransferCount())
                throw new IllegalStateException("MATERIAL_SOURCE_CHANGED");
            nativeStores = nativeStores(player, slot); nativeSaves.requireCapacity(nativeStores);
        } catch (Exception failure) { return new Extracted(MaterialStack.EMPTY, -1, false, safeReason(failure)); }
        try {
            final Slot currentSlot = slot;
            var taken = NativeMaterialTransfer.extract(slot.handler(), slot.handlerSlot(), count, player.registryAccess(),
                    () -> currentSlot.poolUnits().get());
            player.getInventory().setChanged();
            return new Extracted(taken.actual(), taken.unitsAfter(), true, "", nativeSaves.changedOrUnconfirmed(nativeStores));
        } catch (Exception | LinkageError failure) {
            return new Extracted(MaterialStack.EMPTY, -1, true, "NATIVE_EXTRACTION_UNCERTAIN");
        }
    }

    /** Main inventory first, then explicitly insertion-authorized finite destinations. Never drops overflow. */
    public PreparedDelivery prepareDelivery(Actor actor, MaterialStack product) {
        return prepareDelivery(actor, product, -1);
    }
    /** Keep an active worker's approved hand slot, and any newly selected hand, free of automatic loot insertion. */
    public PreparedDelivery prepareDelivery(Actor actor, MaterialStack product, int protectedPlayerSlot) {
        thread();
        mutationAllowed(actor.owner(), "player:" + actor.owner());
        if (protectedPlayerSlot < -1 || protectedPlayerSlot >= 36) throw new IllegalArgumentException("OUTPUT_PROTECTED_SLOT_INVALID");
        ServerPlayer player = requirePlayer(actor);
        var handler = new PlayerMainInvWrapper(player.getInventory());
        ItemStack stack = ItemSnapshotCodec.restore(product, player.registryAccess());
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (protectedPlayerSlot >= 0 && (slot == protectedPlayerSlot || slot == player.getInventory().selected)) continue;
            MaterialStack before = ItemSnapshotCodec.capture(handler.getStackInSlot(slot), player.registryAccess());
            ItemStack trial = handler.insertItem(slot, RecipeSafeCopy(stack, player), true);
            if (!ItemSnapshotCodec.capture(handler.getStackInSlot(slot), player.registryAccess()).equals(before))
                throw new IllegalStateException("OUTPUT_SIMULATION_MUTATED");
            int fits = stack.getCount() - trial.getCount();
            if (fits > 0) {
                return new PreparedDelivery(actor.owner(), slot, product.withCount(fits), before,
                        AiJson.digest(actor.owner() + ":" + slot + ":" + before + ":" + UUID.randomUUID()), player);
            }
        }
        for (Slot slot : authorizedDestinations(player, actor)) {
            if (recoveryBlocked.test(actor.owner(), slot.backend())) continue;
            int fits = insertionCapacity(player, slot, product);
            if (fits <= 0) continue;
            MaterialStack before = ItemSnapshotCodec.capture(slot.handler().getStackInSlot(slot.handlerSlot()), player.registryAccess());
            long units = slot.poolUnits().get();
            return new PreparedDelivery(actor.owner(), slot.localSlot(), product.withCount(fits), before,
                    AiJson.digest(actor.owner() + ":" + slot.sourceId() + ":" + slot.backend() + ":" + slot.localSlot()
                            + ":" + before + ":" + units + ":" + UUID.randomUUID()), player, new DeliveryTarget(slot, units));
        }
        throw new IllegalStateException("OUTPUT_FULL");
    }

    public Delivered deliver(PreparedDelivery prepared) {
        thread();
        if (prepared.destination() != null) return deliverAuthorized(prepared);
        ServerPlayer player;
        PlayerMainInvWrapper handler;
        try {
            player = player(prepared.owner());
            mutationAllowed(prepared.owner(), "player:" + prepared.owner());
            if (player != prepared.playerIdentity()) throw new IllegalStateException("PLAYER_CONNECTION_CHANGED");
            handler = new PlayerMainInvWrapper(player.getInventory());
            if (!ItemSnapshotCodec.capture(handler.getStackInSlot(prepared.slot()), player.registryAccess()).equals(prepared.before()))
                throw new IllegalStateException("OUTPUT_SLOT_CHANGED");
            nativeSaves.requireCapacity();
        } catch (Exception failure) { return new Delivered(0, false, false, safeReason(failure)); }
        try {
            var inserted = NativeMaterialTransfer.insert(handler, prepared.slot(), prepared.product(), prepared.before(), player.registryAccess());
            player.getInventory().setChanged();
            return new Delivered(inserted.accepted(), inserted.verified(), true, inserted.verified() ? "" : "OUTPUT_CONSERVATION_UNCERTAIN",
                    nativeSaves.changedOrUnconfirmed(List.of(NativeSaveObserver.player(player))));
        } catch (Exception | LinkageError failure) { return new Delivered(0, false, true, "NATIVE_DELIVERY_UNCERTAIN"); }
    }

    private Delivered deliverAuthorized(PreparedDelivery prepared) {
        ServerPlayer player;
        Slot slot;
        long beforeUnits;
        List<NativeSaveObserver.Target> nativeStores;
        try {
            player = player(prepared.owner());
            if (player != prepared.playerIdentity()) throw new IllegalStateException("PLAYER_CONNECTION_CHANGED");
            var target = (DeliveryTarget) prepared.destination();
            mutationAllowed(prepared.owner(), target.slot().backend());
            validateGrant(player, target.slot(), false);
            if (!liveHost(player, target.slot())) throw new IllegalStateException("MATERIAL_HOST_CHANGED");
            slot = grantedSlots(player, target.slot().grant(), false).stream()
                    .filter(row -> row.backend().equals(target.slot().backend()) && row.localSlot() == target.slot().localSlot())
                    .findFirst().orElseThrow(() -> new IllegalStateException("SOURCE_SLOT_CHANGED"));
            beforeUnits = slot.poolUnits().get();
            if (beforeUnits != target.poolUnits()
                    || !ItemSnapshotCodec.capture(slot.handler().getStackInSlot(slot.handlerSlot()), player.registryAccess()).equals(prepared.before())
                    || insertionCapacity(player, slot, prepared.product()) < prepared.product().count())
                throw new IllegalStateException("OUTPUT_SLOT_CHANGED");
            nativeStores = nativeStores(player, slot); nativeSaves.requireCapacity(nativeStores);
        } catch (Exception failure) { return new Delivered(0, false, false, safeReason(failure)); }
        try {
            var result = NativeMaterialTransfer.insert(slot.handler(), slot.handlerSlot(), prepared.product(), prepared.before(), player.registryAccess());
            boolean verified = result.verified()
                    && slot.poolUnits().get() - beforeUnits == Math.multiplyExact((long) result.accepted(), slot.weight());
            player.getInventory().setChanged();
            return new Delivered(result.accepted(), verified, true, verified ? "" : "OUTPUT_CONSERVATION_UNCERTAIN",
                    nativeSaves.changedOrUnconfirmed(nativeStores));
        } catch (Exception | LinkageError failure) { return new Delivered(0, false, true, "NATIVE_DELIVERY_UNCERTAIN"); }
    }

    private List<Slot> authorizedDestinations(ServerPlayer player, Actor actor) {
        var repository = grants.get(actor.owner());
        if (repository == null) return List.of();
        List<Slot> candidates = new ArrayList<>();
        for (var grant : repository.list(actor)) if (!grant.revoked() && grant.source().insert()) {
            try { candidates.addAll(grantedSlots(player, grant, false)); }
            catch (RuntimeException unavailable) { /* Keep undelivered items in escrow if this source ceased to be safe. */ }
            if (candidates.size() > 16384) throw new IllegalStateException("OUTPUT_SOURCE_SLOT_LIMIT");
        }
        return uniqueSources(candidates, new ArrayList<>());
    }
    private List<NativeSaveObserver.Target> nativeStores(ServerPlayer player, Slot slot) {
        if (slot.backend().startsWith("sophisticatedbackpacks/contents/")) {
            Object host = slot.host() instanceof PortableHost portable ? portable.reference() : slot.host();
            var contents = NativeSaveObserver.Target.file(storage.backpackSaveStore(player, host, slot.backend()),
                    NativeSaveObserver.dimensionFolder(player.server.overworld()).resolve("data/sophisticatedbackpacks.dat"));
            var identity = host instanceof BlockEntity block && block.getLevel() instanceof ServerLevel level
                    ? NativeSaveObserver.blockEntity(level, block) : NativeSaveObserver.player(player);
            return List.of(contents, identity); // The UUID-bearing host and its independent contents must both save.
        }
        if (slot.host() == player.getInventory()) return List.of(NativeSaveObserver.player(player));
        if (slot.host() instanceof BlockEntity block && block.getLevel() instanceof ServerLevel level && level.hasChunkAt(block.getBlockPos()))
            return List.of(NativeSaveObserver.blockEntity(level, block));
        if (slot.host() instanceof PortableHost host && host.reference() instanceof net.minecraft.world.entity.Entity entity)
            return List.of(NativeSaveObserver.entity(entity));
        throw new IllegalStateException("MATERIAL_NATIVE_SAVE_STORE_UNSUPPORTED");
    }
    public NativeSaveObserver nativeSaves() { return nativeSaves; }

    /**
     * Trusted main-thread bridge for one owned maid's native wireless IO. This does not perform a
     * new selection, infer a grant from proximity, or change the ordinary material-tool rules.
     * The returned capability is bounded to a complete already-authorized physical inventory;
     * the native maid's two hand slots are deliberately absent from its authority.
     */
    public MaidTransportView maidTransportView(Actor actor, UUID maidId, MaidCarrierBridge.Lease lease,
            UUID grantId, long revision, boolean extraction) {
        thread(); var player = requirePlayer(actor);
        var bound = maids.validateBinding(player, player.getUUID());
        if (bound.binding() == null || !bound.binding().maidId().equals(maidId))
            throw new SecurityException("MAID_TRANSPORT_BINDING_CHANGED");
        var maid = maids.backpackSource(player, player.getUUID(), maidId);
        maidPermission.requireControl(player, player.getUUID(), maid.entity());
        var grant = grants(actor).require(actor, grantId, revision, extraction);
        if (grant.source().kind() != MaterialSourceGrants.Kind.BLOCK
                && grant.source().kind() != MaterialSourceGrants.Kind.MAID_BACKPACK)
            throw new IllegalStateException("MAID_TRANSPORT_NATIVE_CONTAINER_UNSUPPORTED");
        if (grant.source().kind() == MaterialSourceGrants.Kind.MAID_BACKPACK
                && !grant.source().locator().equals("maid|" + maid.dimension() + "|" + maidId))
            throw new SecurityException("MAID_TRANSPORT_OTHER_MAID_GRANT");
        List<Slot> slots = grantedSlots(player, grant, extraction);
        if (slots.isEmpty() || slots.size() > 128 || slots.stream().anyMatch(row -> row.weight() != 1)
                || slots.stream().map(Slot::pool).distinct().count() != slots.size())
            throw new IllegalStateException("MAID_TRANSPORT_FINITE_PLAIN_VIEW_REQUIRED");
        return new MaidTransportView(actor, player.connection, maid, lease, grant, extraction, slots);
    }

    /** Opaque server-only view. Never serialize it or expose a raw native handler through a payload. */
    public final class MaidTransportView {
        private final Actor actor;
        private final Object connection;
        private final MaidCarrierBridge.BackpackSource maid;
        private final MaidCarrierBridge.Lease lease;
        private final MaterialSourceGrants.Grant grant;
        private final boolean extraction;
        private final List<Slot> slots;
        private MaidTransportView(Actor actor, Object connection, MaidCarrierBridge.BackpackSource maid,
                MaidCarrierBridge.Lease lease, MaterialSourceGrants.Grant grant, boolean extraction, List<Slot> slots) {
            this.actor = actor; this.connection = connection; this.maid = maid; this.lease = lease;
            this.grant = grant; this.extraction = extraction; this.slots = List.copyOf(slots);
        }
        public Set<String> pools() { thread(); return slots.stream().map(Slot::pool).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
        public Set<String> backends() { thread(); return slots.stream().map(Slot::backend).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
        public List<NativeSaveObserver.Target> nativeTargets() {
            var player = validate(false);
            var targets = new ArrayList<NativeSaveObserver.Target>();
            for (var row : slots) {
                targets.addAll(nativeStores(player, row));
                if (row.host() instanceof BlockEntity block && block.getLevel() instanceof ServerLevel level)
                    targets.add(NativeSaveObserver.chunk(level, level.getChunkAt(block.getBlockPos())));
            }
            return targets.stream().distinct().toList();
        }
        private ServerPlayer validate(boolean mutating) {
            thread(); var player = requirePlayer(actor);
            if (player.connection != connection) throw new SecurityException("MAID_TRANSPORT_CONNECTION_CHANGED");
            var current = maids.backpackSource(player, player.getUUID(), maid.maidId());
            if (current.entity() != maid.entity() || !current.revisionFingerprint().equals(maid.revisionFingerprint()))
                throw new SecurityException("MAID_TRANSPORT_CARRIER_CHANGED");
            maidPermission.requireControl(player, player.getUUID(), current.entity());
            if (mutating) {
                var checked = maids.validateOperation(player, player.getUUID(), lease);
                if (lease == null || checked.lease() == null || !lease.equals(checked.lease()))
                    throw new SecurityException("MAID_TRANSPORT_CONTROL_LEASE_CHANGED");
            }
            grants(actor).require(actor, grant.id(), grant.revision(), extraction);
            if (grant.source().kind() == MaterialSourceGrants.Kind.BLOCK) {
                final float radius;
                try { radius = ((Number) maid.entity().getClass().getMethod("getRestrictRadius").invoke(maid.entity())).floatValue(); }
                catch (ReflectiveOperationException failure) { throw new IllegalStateException("MAID_TRANSPORT_NATIVE_RANGE_UNAVAILABLE", failure); }
                if (!Float.isFinite(radius) || radius < 0) throw new IllegalStateException("MAID_TRANSPORT_NATIVE_RANGE_UNAVAILABLE");
                for (var member : grant.source().members()) {
                    var pos = new BlockPos(member.x(), member.y(), member.z());
                    if (!member.dimension().equals(maid.dimension()) || maid.entity().blockPosition().distSqr(pos) > (double) radius * radius)
                        throw new SecurityException("MAID_TRANSPORT_OUTSIDE_NATIVE_RANGE");
                }
            }
            for (var row : slots) {
                if (!liveHost(player, row)) throw new IllegalStateException("SOURCE_HOST_CHANGED");
                validateGrant(player, row, extraction);
                if (mutating) mutationAllowed(actor.owner(), row.backend());
            }
            return player;
        }
        /** Match physical membership, not equal item contents or an arbitrary handler's claimed size. */
        public IItemHandler bindNative(IItemHandler nativeHandler) {
            validate(false); Objects.requireNonNull(nativeHandler);
            final int authorized;
            if (grant.source().kind() == MaterialSourceGrants.Kind.MAID_BACKPACK) {
                if (!nativeHandler.getClass().getName().equals("com.github.tartaricacid.touhoulittlemaid.inventory.handler.MaidInvWrapper")
                        || nativeHandler.getSlots() != maid.slots() + 2) throw new SecurityException("MAID_TRANSPORT_NATIVE_MAID_VIEW_CHANGED");
                try {
                    if (nativeHandler.getClass().getMethod("getMaid").invoke(nativeHandler) != maid.entity())
                        throw new SecurityException("MAID_TRANSPORT_OTHER_MAID_VIEW");
                } catch (ReflectiveOperationException failure) { throw new IllegalStateException("MAID_TRANSPORT_NATIVE_API_CHANGED", failure); }
                authorized = maid.slots();
            } else {
                if (nativeHandler.getClass() != InvWrapper.class)
                    throw new IllegalStateException("MAID_TRANSPORT_NATIVE_HANDLER_UNREVIEWED");
                var container = ((InvWrapper) nativeHandler).getInv();
                var members = slots.stream().map(Slot::host).distinct().toList();
                if (members.stream().anyMatch(host -> !(host instanceof BaseContainerBlockEntity)
                        || !Set.of(ChestBlockEntity.class, TrappedChestBlockEntity.class, BarrelBlockEntity.class).contains(host.getClass())))
                    throw new IllegalStateException("MAID_TRANSPORT_NATIVE_CONTAINER_UNSUPPORTED");
                boolean exact = members.size() == 1 && container == members.getFirst();
                if (members.size() == 2 && container instanceof net.minecraft.world.CompoundContainer compound)
                    exact = members.stream().allMatch(host -> compound.contains((net.minecraft.world.Container) host));
                int count = members.stream().mapToInt(host -> ((net.minecraft.world.Container) host).getContainerSize()).sum();
                if (!exact || count != slots.size() || nativeHandler.getSlots() != count)
                    throw new SecurityException("MAID_TRANSPORT_NATIVE_MEMBERS_CHANGED");
                authorized = count;
            }
            return new IItemHandler() {
                private void index(int slot) { if (slot < 0 || slot >= getSlots()) throw new IndexOutOfBoundsException(slot); }
                private ServerPlayer reading() {
                    thread(); var player = requirePlayer(actor);
                    if (player.connection != connection || !(maid.entity() instanceof net.minecraft.world.entity.TamableAnimal tame)
                            || !tame.isTame() || !tame.isOwnedBy(player) || !player.getUUID().equals(tame.getOwnerUUID()))
                        throw new SecurityException("MAID_TRANSPORT_OWNER_OR_CONNECTION_CHANGED");
                    grants(actor).require(actor, grant.id(), grant.revision(), extraction); return player;
                }
                @Override public int getSlots() { thread(); return nativeHandler.getSlots(); }
                @Override public ItemStack getStackInSlot(int slot) {
                    index(slot); var player = reading();
                    return slot < authorized ? RecipeSafeCopy(nativeHandler.getStackInSlot(slot), player) : ItemStack.EMPTY;
                }
                @Override public int getSlotLimit(int slot) { thread(); index(slot); return slot < authorized ? nativeHandler.getSlotLimit(slot) : 0; }
                @Override public boolean isItemValid(int slot, ItemStack value) {
                    thread(); index(slot); return slot < authorized && nativeHandler.isItemValid(slot, value);
                }
                @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
                    index(slot); var player = validate(!simulate);
                    if (!extraction || slot >= authorized) throw new SecurityException("MAID_TRANSPORT_SLOT_NOT_EXTRACTION_AUTHORIZED");
                    MaterialStack value = ItemSnapshotCodec.capture(nativeHandler.getStackInSlot(slot), player.registryAccess());
                    if (protectedByOwner(actor, value)) throw new SecurityException("MATERIAL_ITEM_PROTECTED");
                    return nativeHandler.extractItem(slot, amount, simulate);
                }
                @Override public ItemStack insertItem(int slot, ItemStack value, boolean simulate) {
                    index(slot); validate(!simulate);
                    if (extraction || slot >= authorized) throw new SecurityException("MAID_TRANSPORT_SLOT_NOT_INSERTION_AUTHORIZED");
                    return nativeHandler.insertItem(slot, value, simulate);
                }
            };
        }
    }
    public Set<String> deliveryPools(PreparedDelivery prepared) {
        if (prepared.destination() instanceof DeliveryTarget target)
            return Set.of(target.slot().pool(), MaterialSourceScope.key(target.slot().backend()));
        return Set.of("player:" + prepared.owner() + ":slot:" + prepared.slot(), MaterialSourceScope.key("player:" + prepared.owner()));
    }
    /** Trusted main-only backend identity for native-domain reservation admission, never a model argument. */
    public Set<String> deliveryBackends(PreparedDelivery prepared) {
        thread();
        if (prepared.destination() instanceof DeliveryTarget target) return Set.of(target.slot().backend());
        return Set.of("player:" + prepared.owner());
    }
    private int insertionCapacity(ServerPlayer player, Slot slot, MaterialStack product) {
        MaterialStack before = ItemSnapshotCodec.capture(slot.handler().getStackInSlot(slot.handlerSlot()), player.registryAccess());
        // DrawerItemHandler may route an empty non-first drawer to a full member scan. Only
        // a populated exact view or local drawer zero has an audited single-slot insertion path.
        if (before.empty() && slot.localSlot() != 0
                && slot.handler().getClass().getName().equals("com.jaquadro.minecraft.storagedrawers.capabilities.PlatformDrawerItemHandler"))
            return 0;
        if (!before.empty() && !before.itemJson().equals(product.itemJson())) return 0;
        long units = slot.poolUnits().get();
        MaterialStack remaining = ItemSnapshotCodec.capture(slot.handler().insertItem(slot.handlerSlot(),
                ItemSnapshotCodec.restore(product, player.registryAccess()), true), player.registryAccess());
        if (!ItemSnapshotCodec.capture(slot.handler().getStackInSlot(slot.handlerSlot()), player.registryAccess()).equals(before)
                || units != slot.poolUnits().get()) throw new IllegalStateException("OUTPUT_SIMULATION_MUTATED");
        if (!remaining.empty() && !remaining.itemJson().equals(product.itemJson()) || remaining.count() > product.count())
            throw new IllegalStateException("OUTPUT_SIMULATION_INVALID");
        return product.count() - remaining.count();
    }
    private boolean authorizedOutputFits(ServerPlayer player, Actor actor, List<MaterialStack> products) {
        List<Slot> destinations = authorizedDestinations(player, actor);
        Map<String, MaterialStack> planned = new HashMap<>();
        Map<String, Long> plannedWeights = new HashMap<>();
        for (MaterialStack product : products) {
            int remaining = product.count();
            for (Slot slot : destinations) {
                String key = slot.pool();
                MaterialStack occupied = planned.getOrDefault(key, MaterialStack.EMPTY);
                if (!occupied.empty() && (!occupied.itemJson().equals(product.itemJson())
                        || plannedWeights.get(key) != slot.weight())) continue;
                int total = Math.addExact(remaining, occupied.count());
                int fits = Math.max(0, insertionCapacity(player, slot, product.withCount(total)) - occupied.count());
                int accepted = Math.min(remaining, fits);
                if (accepted > 0) {
                    planned.put(key, product.withCount(Math.addExact(occupied.count(), accepted)));
                    plannedWeights.put(key, slot.weight());
                }
                remaining -= accepted;
                if (remaining == 0) break;
            }
            if (remaining > 0) return false;
        }
        return true;
    }

    /** Preview observes only the player's explicitly selected source. Confirm attaches durable source identities. */
    public MaterialSourceGrants.Candidate blockCandidate(Actor actor, BlockPos pos, Direction side,
                                                         boolean extract, boolean insert, boolean confirmed) {
        thread();
        ServerPlayer player = requirePlayer(actor);
        ServerLevel level = player.serverLevel();
        if (pos == null || side == null || !player.canInteractWithBlock(pos, 1.0))
            throw new SecurityException("SOURCE_OUT_OF_REACH");
        checkContainer(player, level, pos, side);
        List<BlockEntity> members = blockMembers(player, level, pos, side);
        var result = new ArrayList<MaterialSourceGrants.Member>();
        for (BlockEntity entity : members) {
            checkContainer(player, level, entity.getBlockPos(), side);
            BlockPos member = entity.getBlockPos();
            result.add(new MaterialSourceGrants.Member(level.dimension().location().toString(),
                    member.getX(), member.getY(), member.getZ(), BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString(),
                    confirmed ? MaterialSourceIdentity.confirmed(entity) : MaterialSourceIdentity.existing(entity)));
        }
        result.sort(Comparator.comparingInt(MaterialSourceGrants.Member::x).thenComparingInt(MaterialSourceGrants.Member::y)
                .thenComparingInt(MaterialSourceGrants.Member::z));
        String locator = "block|" + level.dimension().location() + "|" + pos.asLong() + "|" + side.getName();
        String fingerprint = "";
        if (level.getBlockEntity(pos).getClass().getName().startsWith("net.p3pp3rf1y.sophisticatedbackpacks.")) {
            var probe = storage.probeBlock(player, level, pos, side);
            if (!probe.callable() || probe.slots().isEmpty()) throw new IllegalStateException(probe.reason());
            var evidence = probe.slots().getFirst().evidence();
            requireUnambiguousBackpackHost(player, evidence.contentsUuid(), evidence.hostReference());
            fingerprint = backpackFingerprint(player, evidence.hostReference(), probe);
        }
        return new MaterialSourceGrants.Candidate(MaterialSourceGrants.Kind.BLOCK, locator,
                "容器 " + level.dimension().location() + " " + pos.toShortString() + "，" + members.size() + " 个物理成员",
                result, fingerprint, extract, insert);
    }

    private List<Slot> ownSlots(ServerPlayer player) {
        List<Slot> result = new ArrayList<>();
        var inventory = player.getInventory();
        IItemHandler main = new PlayerMainInvWrapper(inventory);
        String backend = "player:" + player.getUUID();
        for (int slot = 0; slot < main.getSlots(); slot++) addPlain(result, "player", 0, backend, slot, main, slot, inventory, null);
        IItemHandler offhand = new PlayerOffhandInvWrapper(inventory);
        addPlain(result, "player", 0, backend, 40, offhand, 0, inventory, null);
        return result;
    }

    private List<Slot> grantedSlots(ServerPlayer player, MaterialSourceGrants.Grant grant, boolean extract) {
        var actor = Actor.player(player.getUUID(), true, true, 0);
        MaterialSourceGrants repository = grants.get(actor.owner());
        if (repository == null) throw new SecurityException("SOURCE_AUTHORIZATION_REQUIRED");
        repository.require(actor, grant.id(), grant.revision(), extract);
        if (grant.source().kind() != MaterialSourceGrants.Kind.BLOCK)
            return portableSlots(player, grant);
        String[] locator = grant.source().locator().split("\\|", -1);
        if (locator.length != 4 || !locator[0].equals("block")) throw new IllegalArgumentException("SOURCE_LOCATOR_INVALID");
        ServerLevel level = level(locator[1]);
        BlockPos pos = BlockPos.of(Long.parseLong(locator[2]));
        Direction side = Direction.byName(locator[3]);
        if (side == null) throw new IllegalArgumentException("SOURCE_SIDE_INVALID");
        Map<BlockPos, BlockEntity> members = new HashMap<>();
        for (var member : grant.source().members()) {
            BlockPos memberPos = new BlockPos(member.x(), member.y(), member.z());
            if (!member.dimension().equals(locator[1])) throw new SecurityException("SOURCE_DIMENSION_CHANGED");
            checkContainer(player, level, memberPos, side);
            BlockEntity entity = level.getBlockEntity(memberPos);
            if (entity == null || member.identity() == null || !member.identity().equals(MaterialSourceIdentity.existing(entity))
                    || !member.blockEntityType().equals(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString()))
                throw new IllegalStateException("SOURCE_IDENTITY_CHANGED");
            members.put(memberPos, entity);
        }
        List<BlockEntity> liveMembers = blockMembers(player, level, pos, side);
        if (!new HashSet<>(liveMembers).equals(new HashSet<>(members.values())))
            throw new IllegalStateException("SOURCE_MEMBERS_CHANGED");
        var result = new ArrayList<Slot>();
        if (vanilla(level.getBlockEntity(pos))) {
            for (var entity : liveMembers) {
                BaseContainerBlockEntity container = (BaseContainerBlockEntity) entity;
                IItemHandler handler = entity instanceof ChestBlockEntity ? new InvWrapper(container)
                        : level.getCapability(Capabilities.ItemHandler.BLOCK, entity.getBlockPos(), side);
                if (handler == null) throw new IllegalStateException("SOURCE_HANDLER_UNAVAILABLE");
                String backend = "block:" + locator[1] + ":" + entity.getBlockPos().asLong() + ":" + MaterialSourceIdentity.existing(entity);
                for (int slot = 0; slot < handler.getSlots(); slot++)
                    addPlain(result, grant.id().toString(), grant.revision(), backend, slot, handler, slot, entity, grant);
            }
        } else {
            var probe = storage.probeBlock(player, level, pos, side);
            if (!probe.callable()) throw new IllegalStateException(probe.reason());
            if (probe.adapterId().equals("sophisticated_backpacks")) {
                if (probe.slots().isEmpty()) throw new IllegalStateException("BACKPACK_LAYOUT_UNAVAILABLE");
                var evidence = probe.slots().getFirst().evidence();
                requireUnambiguousBackpackHost(player, evidence.contentsUuid(), evidence.hostReference());
                if (!backpackFingerprint(player, evidence.hostReference(), probe).equals(grant.source().hostFingerprint()))
                    throw new IllegalStateException("SOURCE_HOST_CHANGED");
            }
            Map<String, Long> aliases = probe.slots().stream().collect(java.util.stream.Collectors.groupingBy(
                    StorageSourceIntrospector.SlotRef::poolId, java.util.stream.Collectors.counting()));
            for (var nativeSlot : probe.slots()) {
                BlockEntity member = members.get(nativeSlot.memberPos());
                if (member == null) throw new SecurityException("SOURCE_MEMBER_NOT_AUTHORIZED");
                // A placed backpack and its item host expose the same UUID-backed storage.
                // Keep authorization witnesses separate from its physical pool identity.
                String suffix = probe.adapterId().equals("sophisticated_backpacks") ? ""
                        : ":" + MaterialSourceIdentity.existing(member);
                String pool = nativeSlot.poolId() + suffix;
                Supplier<Long> units = () -> {
                    if (nativeSlot.unitsPerItem() == 1 && aliases.get(nativeSlot.poolId()) == 1
                            && nativeSlot.poolUnits() == nativeSlot.snapshot().getCount())
                        return (long) nativeSlot.handler().getStackInSlot(nativeSlot.handlerSlot()).getCount();
                    // Fractional drawers share one pool. Reprobe the physical drawer, not the
                    // entire controller network for every slot and every simulation.
                    var current = storage.probeBlock(player, level, nativeSlot.memberPos(), side);
                    if (!current.callable()) throw new IllegalStateException(current.reason());
                    return current.slots().stream().filter(s -> s.poolId().equals(nativeSlot.poolId()))
                            .findFirst().orElseThrow(() -> new IllegalStateException("SOURCE_POOL_CHANGED")).poolUnits();
                };
                result.add(new Slot(grant.id().toString(), grant.revision(), nativeSlot.backendId() + suffix, pool,
                        nativeSlot.localSlot(), nativeSlot.unitsPerItem(), units, nativeSlot.handler(),
                        nativeSlot.handlerSlot(), member, grant, true));
            }
        }
        return result;
    }

    private List<Slot> portableSlots(ServerPlayer player, MaterialSourceGrants.Grant grant) {
        String[] locator = grant.source().locator().split("\\|", -1);
        var result = new ArrayList<Slot>();
        if (grant.source().kind() == MaterialSourceGrants.Kind.BACKPACK_ITEM
                || grant.source().kind() == MaterialSourceGrants.Kind.EQUIPMENT_BACKPACK) {
            if (locator.length != 4 || !locator[0].equals("item")
                    || !locator[3].equals(player.serverLevel().dimension().location().toString()))
                throw new IllegalStateException("SOURCE_HOST_LOCATION_CHANGED");
            var host = equipment.requireHost(player, player.getUUID(), locator[1]);
            boolean equipped = !host.source().equals("minecraft");
            if (equipped != (grant.source().kind() == MaterialSourceGrants.Kind.EQUIPMENT_BACKPACK))
                throw new IllegalStateException("SOURCE_HOST_LOCATION_CHANGED");
            var probe = storage.probeItemBackpack(player, storage.captureItemHost(player, locator[1], grant.revision(),
                    host.inventory(), host.nativeSlot()));
            if (!probe.callable() || probe.slots().isEmpty()) throw new IllegalStateException(probe.reason());
            var first = probe.slots().getFirst();
            requireUnambiguousBackpackHost(player, first.evidence().contentsUuid(), host.current());
            String fingerprint = backpackFingerprint(player, host.current(), probe);
            if (!locator[2].equals(first.evidence().contentsUuid().toString())
                    || !fingerprint.equals(grant.source().hostFingerprint()))
                throw new IllegalStateException("SOURCE_HOST_CHANGED");
            var witness = new PortableHost(locator[1], host.current(), fingerprint, first.backendId(),
                    locator[3], fingerprint, host::current);
            for (var nativeSlot : probe.slots())
                result.add(new Slot(grant.id().toString(), grant.revision(), nativeSlot.backendId(), nativeSlot.poolId(),
                        nativeSlot.localSlot(), nativeSlot.unitsPerItem(),
                        () -> (long) nativeSlot.handler().getStackInSlot(nativeSlot.handlerSlot()).getCount(),
                        nativeSlot.handler(), nativeSlot.handlerSlot(), witness, grant, true));
        } else if (grant.source().kind() == MaterialSourceGrants.Kind.MAID_BACKPACK) {
            if (locator.length != 3 || !locator[0].equals("maid")
                    || !locator[1].equals(player.serverLevel().dimension().location().toString()))
                throw new IllegalStateException("SOURCE_HOST_LOCATION_CHANGED");
            var source = maids.backpackSource(player, player.getUUID(), UUID.fromString(locator[2]));
            checkMaidPermission(player, source);
            if (!source.revisionFingerprint().equals(grant.source().hostFingerprint()))
                throw new IllegalStateException("SOURCE_MAID_TASK_OR_BACKPACK_CHANGED");
            String backend = "maid/" + source.maidId() + "/backpack";
            var witness = new PortableHost(locator[2], source.entity(), source.revisionFingerprint(), backend,
                    source.dimension(), source.revisionFingerprint(), null);
            for (int slot = 0; slot < source.slots(); slot++)
                addPlain(result, grant.id().toString(), grant.revision(), backend, slot, source.inventory(), slot, witness, grant);
        } else throw new IllegalStateException("EQUIPMENT_CONSUMPTION_NOT_AUTHORIZED");
        return result;
    }

    private List<BlockEntity> blockMembers(ServerPlayer player, ServerLevel level, BlockPos pos, Direction side) {
        checkContainer(player, level, pos, side);
        BlockEntity entity = level.getBlockEntity(pos);
        if (vanilla(entity)) {
            var result = new ArrayList<BlockEntity>();
            result.add(entity);
            if (entity instanceof ChestBlockEntity && entity.getBlockState().getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                BlockPos other = pos.relative(ChestBlock.getConnectedDirection(entity.getBlockState()));
                checkContainer(player, level, other, side);
                BlockEntity second = level.getBlockEntity(other);
                if (!(second instanceof ChestBlockEntity) || !vanilla(second)) throw new IllegalStateException("CHEST_MEMBERS_CHANGED");
                result.add(second);
            }
            return result;
        }
        var probe = storage.probeBlock(player, level, pos, side);
        if (!probe.callable()) throw new IllegalStateException(probe.reason());
        var members = new LinkedHashSet<BlockEntity>();
        // Controller itself is part of the binding even if it holds no physical slot.
        members.add(entity);
        for (var slot : probe.slots()) {
            if (slot.memberPos() == null) throw new IllegalStateException("SOURCE_MEMBER_MISSING");
            checkContainer(player, level, slot.memberPos(), side);
            members.add(level.getBlockEntity(slot.memberPos()));
        }
        if (members.contains(null) || members.size() > 128) throw new IllegalStateException("SOURCE_MEMBER_LIMIT");
        return List.copyOf(members);
    }

    private static boolean vanilla(BlockEntity entity) {
        if (entity == null) return false;
        return Set.of(ChestBlockEntity.class, TrappedChestBlockEntity.class, BarrelBlockEntity.class,
                ShulkerBoxBlockEntity.class, HopperBlockEntity.class, DispenserBlockEntity.class, DropperBlockEntity.class)
                .contains(entity.getClass());
    }
    private void checkContainer(ServerPlayer player, ServerLevel level, BlockPos pos, Direction side) {
        if (level == null || side == null || !level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos))
            throw new IllegalStateException("SOURCE_UNLOADED_OR_OUTSIDE_BOUNDS");
        var protection = permissions.checkContainer(player, level, pos, side);
        if (!protection.allowed()) throw new SecurityException(protection.code());
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null || entity.isRemoved()) throw new IllegalStateException("SOURCE_MISSING");
        if (entity instanceof RandomizableContainerBlockEntity random && random.getLootTable() != null)
            throw new IllegalStateException("SOURCE_LOOT_NOT_OPENED");
        if (entity instanceof BaseContainerBlockEntity base && !base.canOpen(player))
            throw new SecurityException("SOURCE_LOCKED");
        if (entity instanceof ChestBlockEntity && ChestBlock.isChestBlockedAt(level, pos))
            throw new IllegalStateException("SOURCE_CHEST_BLOCKED");
    }
    private static void addPlain(List<Slot> slots, String source, long revision, String backend, int local,
                                 IItemHandler handler, int index, Object host, MaterialSourceGrants.Grant grant) {
        slots.add(new Slot(source, revision, backend, backend + ":slot:" + local, local, 1,
                () -> (long) handler.getStackInSlot(index).getCount(), handler, index, host, grant, false));
    }
    private MaterialLedger.Stock capture(ServerPlayer player, Slot slot) {
        MaterialStack value = ItemSnapshotCodec.capture(slot.handler().getStackInSlot(slot.handlerSlot()), player.registryAccess());
        long units = slot.poolUnits().get();
        int extractable = 0;
        int maxTransfer = 0;
        var policy = preferences.get(player.getUUID().toString());
        boolean protectedInput = !value.empty() && policy != null && policy.protects(BuiltInRegistries.ITEM.getKey(slot.handler().getStackInSlot(slot.handlerSlot()).getItem()).toString());
        if (!protectedInput && !value.empty() && !(slot.grant() == null && slot.handler().getStackInSlot(slot.handlerSlot()).getItem()
                .getClass().getName().equals("net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem"))) {
            ItemStack test = slot.handler().extractItem(slot.handlerSlot(), value.count(), true);
            MaterialStack simulated = ItemSnapshotCodec.capture(test, player.registryAccess());
            if (!simulated.empty() && !simulated.itemJson().equals(value.itemJson()) || simulated.count() > value.count())
                throw new IllegalStateException("SOURCE_SIMULATION_INVALID");
            if (!ItemSnapshotCodec.capture(slot.handler().getStackInSlot(slot.handlerSlot()), player.registryAccess()).equals(value)
                    || units != slot.poolUnits().get()) throw new IllegalStateException("SOURCE_SIMULATION_MUTATED");
            maxTransfer = simulated.count();
            // Only the exact finite adapters accepted by StorageSourceIntrospector have a
            // reviewed repeatable extraction contract. Sophisticated default slots cap one call
            // at getMaxStackSize(); that cap is not their total physical stock. Every subsequent
            // transfer still re-probes permissions, upgrades, components and native simulation.
            extractable = slot.repeatableExtraction() && maxTransfer > 0 ? value.count() : maxTransfer;
        }
        return new MaterialLedger.Stock(slot.sourceId(), slot.revision(), slot.backend(), slot.pool(), slot.localSlot(),
                value, slot.weight(), units, extractable, maxTransfer);
    }
    private static List<Slot> uniqueSources(List<Slot> sources, List<Refusal> refusals) {
        Map<String, Object> backpackHosts = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (Slot slot : sources) if (slot.backend().startsWith("sophisticatedbackpacks/contents/")) {
            Object identity = slot.host() instanceof PortableHost host ? host.reference() : slot.host();
            Object existing = backpackHosts.putIfAbsent(slot.backend(), identity);
            if (existing != null && existing != identity) ambiguous.add(slot.backend());
        }
        var result = new LinkedHashMap<String, Slot>();
        Set<String> rejectedSources = new HashSet<>();
        for (Slot slot : sources) {
            if (ambiguous.contains(slot.backend())) {
                if (rejectedSources.add(slot.sourceId())) refusals.add(new Refusal(slot.sourceId(), "BACKPACK_BACKEND_AMBIGUOUS"));
            } else result.putIfAbsent(slot.backend() + "\n" + slot.localSlot(), slot);
        }
        return List.copyOf(result.values());
    }
    private Slot resolve(ServerPlayer player, MaterialLedger.Stock expected, boolean extract) {
        List<Slot> slots;
        if (expected.sourceId().equals("player")) slots = ownSlots(player);
        else {
            var repository = grants.get(player.getUUID().toString());
            if (repository == null) throw new SecurityException("SOURCE_AUTHORIZATION_REQUIRED");
            var actor = Actor.player(player.getUUID(), true, true, 0);
            var grant = repository.require(actor, UUID.fromString(expected.sourceId()), expected.sourceRevision(), extract);
            slots = grantedSlots(player, grant, extract);
        }
        return slots.stream().filter(slot -> slot.backend().equals(expected.backendId())
                && slot.pool().equals(expected.poolId()) && slot.localSlot() == expected.slot()).findFirst()
                .orElseThrow(() -> new IllegalStateException("SOURCE_SLOT_CHANGED"));
    }
    private void validateGrant(ServerPlayer player, Slot slot, boolean extract) {
        if (slot.grant() == null) return;
        var actor = Actor.player(player.getUUID(), true, true, 0);
        MaterialSourceGrants repository = grants.get(actor.owner());
        if (repository == null) throw new SecurityException("SOURCE_AUTHORIZATION_REQUIRED");
        repository.require(actor, slot.grant().id(), slot.revision(), extract);
        if (slot.grant().source().kind() != MaterialSourceGrants.Kind.BLOCK) {
            var current = portableSlots(player, slot.grant());
            if (current.stream().noneMatch(row -> row.backend().equals(slot.backend()) && row.localSlot() == slot.localSlot()
                    && samePortableHost((PortableHost) slot.host(), (PortableHost) row.host())))
                throw new IllegalStateException("SOURCE_HOST_CHANGED");
            return;
        }
        String[] locator = slot.grant().source().locator().split("\\|", -1);
        Direction side = locator.length == 4 ? Direction.byName(locator[3]) : null;
        if (side == null) throw new IllegalStateException("SOURCE_SIDE_INVALID");
        for (var member : slot.grant().source().members()) {
            var level = level(member.dimension());
            var pos = new BlockPos(member.x(), member.y(), member.z());
            checkContainer(player, level, pos, side);
            if (!member.identity().equals(MaterialSourceIdentity.existing(level.getBlockEntity(pos))))
                throw new IllegalStateException("SOURCE_IDENTITY_CHANGED");
        }
    }
    private boolean liveHost(ServerPlayer player, Slot slot) {
        if (slot.host() == player.getInventory()) return true;
        if (slot.host() instanceof BlockEntity entity) {
            var level = entity.getLevel();
            return level != null && !entity.isRemoved() && level.hasChunkAt(entity.getBlockPos())
                    && level.getBlockEntity(entity.getBlockPos()) == entity;
        }
        if (slot.host() instanceof PortableHost host) {
            if (!host.dimension().equals(player.serverLevel().dimension().location().toString())) return false;
            if (host.reference() instanceof ItemStack stack) {
                return host.currentItem() != null && host.currentItem().get() == stack;
            }
            if (host.reference() instanceof net.minecraft.world.entity.Entity entity)
                return !entity.isRemoved() && entity.isAlive() && entity.level() == player.serverLevel()
                        && player.serverLevel().getEntity(entity.getUUID()) == entity;
        }
        return false;
    }
    private ServerLevel level(String id) {
        ResourceLocation name = ResourceLocation.parse(id);
        for (var level : server.getAllLevels()) if (level.dimension().location().equals(name)) return level;
        throw new IllegalStateException("SOURCE_DIMENSION_UNAVAILABLE");
    }
    private ServerPlayer player(String owner) {
        thread();
        ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(owner));
        if (player == null) throw new IllegalStateException("PLAYER_OFFLINE");
        if (!authenticated.test(player)) throw new SecurityException("AUTH_REQUIRED");
        if (player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL || player.getAbilities().instabuild || player.isSpectator())
            throw new SecurityException("MATERIAL_SURVIVAL_REQUIRED");
        if (!permissions.checkBooleanPermission(player, "fireflymc.ai.materials", true).allowed())
            throw new SecurityException("MATERIAL_PERMISSION_DENIED");
        return player;
    }
    private void thread() { if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED"); }
    private static ItemStack RecipeSafeCopy(ItemStack stack, ServerPlayer player) {
        return firefly520.fireflymc.ai.processing.RecipeInputIsolation.copy(stack, player.registryAccess());
    }
    private static String safeReason(Throwable error) {
        String value = error.getMessage();
        return value != null && value.matches("[A-Z_]{1,100}") ? value : "SOURCE_ADAPTER_FAILED";
    }
    public void close() {
        thread();
        for (Scan scan : scans) scan.future.completeExceptionally(new IllegalStateException("AI_STOPPING"));
        scans.clear(); snapshots.clear(); grants.clear(); preferences.clear();
    }
}
