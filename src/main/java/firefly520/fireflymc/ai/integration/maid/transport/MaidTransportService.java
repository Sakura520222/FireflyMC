package firefly520.fireflymc.ai.integration.maid.transport;

import com.google.gson.*;
import firefly520.fireflymc.ai.blueprint.NativeConstructionSave;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.integration.maid.*;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.processing.VirtualProcessingService;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.TaskControl;
import firefly520.fireflymc.ai.task.CommittedCall;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import static firefly520.fireflymc.ai.integration.maid.transport.MaidTransportJournal.*;

/** A finite native 100-tick pass. No setTask, bauble mutation, free cost, task replay or admin command. */
public final class MaidTransportService implements AutoCloseable {
    private final MinecraftServer server;
    private final MaterialInventoryService inventory;
    private final VirtualProcessingService dispatcher;
    private final LongSupplier generation;
    private final BooleanSupplier enabled;
    private final MaterialLedger materials;
    private final UUID epoch = UUID.randomUUID();
    private final Map<String, MaidTransportJournal> journals = new ConcurrentHashMap<>();
    private final Map<UUID, Witness> witnesses = new ConcurrentHashMap<>();
    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final Map<UUID, Review> reviews = new ConcurrentHashMap<>();
    private final Map<UUID, ReturnWitness> returns = new ConcurrentHashMap<>();
    private final Map<UUID, MaidTransportReturn> returnIndex = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> saveIds = new ConcurrentHashMap<>();
    private final Map<UUID, MaterialLedger.NativeScopeLease> resourceLeases = new ConcurrentHashMap<>();
    private final Set<UUID> executing = ConcurrentHashMap.newKeySet();
    private final ArrayDeque<UUID> nativeTurns = new ArrayDeque<>(); // main thread only, new work joins the tail
    private int nativeTurnTick = Integer.MIN_VALUE;
    private final ThreadPoolExecutor evidence = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128),
            Thread.ofPlatform().daemon(true).name("fireflymc-maid-transport-evidence").factory(), new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed, broken;
    private MaidTransportNative nativeAccess;
    private record Witness(MaidTransportPlan plan, MaidTransportNative.Handle handle) {}
    private record Armed(Job job, String rejection) {}
    private record ReturnWitness(MaidTransportReturn plan, MaterialInventoryService.PreparedDelivery delivery) {}
    public record Review(UUID id, String owner, UUID taskId, UUID operationId, long operationRevision,
                         String operationDigest, long privacyEpoch, UUID executionEpoch, String hash) {
        Review signed() { return new Review(id, owner, taskId, operationId, operationRevision, operationDigest, privacyEpoch, executionEpoch,
                AiJson.digest(AiJson.canonical(new Review(id, owner, taskId, operationId, operationRevision, operationDigest, privacyEpoch, executionEpoch, "")))); }
        boolean intact() { return hash != null && hash.equals(new Review(id, owner, taskId, operationId, operationRevision, operationDigest, privacyEpoch, executionEpoch, "").signed().hash()); }
    }
    public MaidTransportService(MinecraftServer server, MaterialInventoryService inventory, InstalledPermissionAdapters permissions,
            VirtualProcessingService dispatcher, LongSupplier generation, BooleanSupplier enabled, MaterialLedger materials) {
        if (!server.isDedicatedServer()) throw new IllegalStateException("AI_DEDICATED_SERVER_REQUIRED");
        this.server = server; this.inventory = inventory; this.dispatcher = dispatcher; this.generation = generation; this.enabled = enabled; this.materials = Objects.requireNonNull(materials);
    }
    public MaidTransportJournal prepare(Workspace workspace) throws IOException {
        var known = journals.get(workspace.owner()); if (known != null) return known;
        synchronized (journals) {
            known = journals.get(workspace.owner()); if (known != null) return known;
            if (closed) throw new IllegalStateException("AI_STOPPING");
            var created = new MaidTransportJournal(workspace);
            synchronized (workspace.conversation()) {
                for (var event : workspace.conversation().getSnapshot(0).events()) if (event.kind().equals("maid_transport_return_plan")) {
                    var plan = AiJson.GSON.fromJson(event.data(), MaidTransportReturn.class);
                    if (plan == null || !plan.intact() || !plan.owner().equals(workspace.owner()) || plan.privacyEpoch() != workspace.conversation().privacyEpoch())
                        throw new IOException("MAID_TRANSPORT_RETURN_INDEX_INVALID");
                    returnIndex.put(plan.id(), plan);
                } else if (event.kind().equals("maid_transport_reconcile_preview")) {
                    var review = AiJson.GSON.fromJson(event.data(), Review.class);
                    if (review == null || !review.intact() || !review.owner().equals(workspace.owner()) || review.privacyEpoch() != workspace.conversation().privacyEpoch())
                        throw new IOException("MAID_TRANSPORT_REVIEW_INDEX_INVALID");
                    reviews.put(review.id(), review);
                }
                for (var obligation : created.resourceObligations()) {
                    var restored = materials.restoreNativeScope(obligation.owner(), obligation.operationId(), obligation.backendIds());
                    resourceLeases.put(obligation.operationId(), restored);
                }
            }
            journals.put(workspace.owner(), created); return created;
        }
    }
    private MaidTransportNative adapter() throws ReflectiveOperationException {
        thread(); if (nativeAccess == null) nativeAccess = new MaidTransportNative(inventory); return nativeAccess;
    }
    public MaidTransportPlan plan(AgentTool.Context ctx, UUID maidId, UUID maidGrant, long maidRevision, UUID blockGrant, long blockRevision) throws Exception {
        require(ctx); prepare(ctx.workspace());
        var witness = dispatcher.inspectOwned(ctx, () -> {
            var handle = adapter().capture(ctx.actor(), maidId, null, maidGrant, maidRevision, blockGrant, blockRevision);
            var simulation = MaidTransportNative.simulate(handle);
            if (simulation.moves().isEmpty()) throw new IllegalStateException("MAID_TRANSPORT_NO_MATCHING_CAPACITY");
            if (simulation.moves().stream().anyMatch(move -> inventory.protectedByOwner(ctx.actor(), move.stack())))
                throw new SecurityException("MATERIAL_ITEM_PROTECTED");
            if (simulation.matching().size() > 128) throw new IllegalStateException("MAID_TRANSPORT_MATCH_LIMIT");
            var backendIds = new TreeSet<>(handle.maidView().backends()); backendIds.addAll(handle.blockView().backends());
            var plan = new MaidTransportPlan(1, UUID.randomUUID(), ctx.actor().owner(), ctx.taskId(), ctx.workspace().metadata().serverInstanceId(),
                    ctx.workspace().primaryConversationId(), ctx.workspace().conversation().privacyEpoch(), epoch, generation.getAsLong(), System.currentTimeMillis() + 120000,
                    maidId, handle.nativeRevision(), handle.bindingRevision(), handle.player().serverLevel().dimension().location().toString(),
                    handle.target().getX(), handle.target().getY(), handle.target().getZ(), maidGrant, maidRevision, blockGrant, blockRevision,
                    handle.toChest(), MaidTransportNative.fingerprint(handle), handle.baubleSlot(), handle.blacklist(), handle.excluded(),
                    MaidTransportNative.snapshot(handle.filter(), handle.player()), simulation.source(), simulation.destination(), simulation.matching(),
                    simulation.moves(), simulation.afterSource(), simulation.afterDestination(), List.copyOf(backendIds), "").signed();
            return new Witness(plan, handle);
        });
        synchronized (ctx.workspace().conversation()) {
            require(ctx); witness.plan().requireIdentity(ctx.workspace(), ctx.actor(), epoch, generation.getAsLong());
            if (Files.exists(ctx.workspace().artifact(witness.plan().id()), LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("MAID_TRANSPORT_PLAN_EXISTS");
            AiJson.write(ctx.workspace().artifact(witness.plan().id()), witness.plan());
            var data = new JsonObject(); data.addProperty("planId", witness.plan().id().toString()); data.addProperty("planHash", witness.plan().hash());
            ctx.workspace().conversation().append(List.of(new Draft(ctx.taskId(), "maid_transport_plan", null, data, null)));
            witnesses.values().removeIf(value -> value.plan().expiresAt() <= System.currentTimeMillis());
            if (witnesses.size() >= 128) throw new IllegalStateException("MAID_TRANSPORT_PREVIEW_LIMIT");
            witnesses.put(witness.plan().id(), witness);
        }
        return witness.plan();
    }
    public MaidTransportPlan load(Workspace workspace, Actor actor, UUID id, String hash) throws IOException {
        actor.requireAccess(workspace.owner()); var witness = witnesses.get(id);
        if (witness == null || !witness.plan().hash().equals(hash)) throw new IllegalStateException("MAID_TRANSPORT_NEW_PREVIEW_REQUIRED");
        var plan = witness.plan(); plan.requireIdentity(workspace, actor, epoch, generation.getAsLong()); return plan;
    }
    public String review(Workspace workspace, Actor actor, UUID id, String hash) throws IOException {
        var plan = load(workspace, actor, id, hash);
        return "本人女仆原生无线搬运，按真实已佩戴/绑定饰品的方向、物品种类筛选及排除槽，等待下一个原生100 Tick机会。"
                + "来源与去向分别使用所列独立授权，不包含双手和装备；不改任务、饰品、能量或冷却。"
                + "执行前原子申请全局后端预约；已有其他真实预约时拒绝。仅执行所列完整有限匹配集与组件，不持续扫描。满仓/变更/取消会停止；未知效果不能重放。\n" + AiJson.GSON.toJson(plan);
    }
    public AgentTool.Result execute(AgentTool.Context ctx, MaidTransportPlan plan) throws Exception {
        ctx.actor().requireAccess(plan.owner());
        if (!executing.add(plan.id())) throw new IllegalStateException("MAID_TRANSPORT_ALREADY_EXECUTING");
        try { return executeOnce(ctx, plan); } finally { executing.remove(plan.id()); }
    }
    private AgentTool.Result executeOnce(AgentTool.Context ctx, MaidTransportPlan plan) throws Exception {
        require(ctx); plan.requireIdentity(ctx.workspace(), ctx.actor(), epoch, generation.getAsLong());
        if (!plan.taskId().equals(ctx.taskId())) throw new SecurityException("MAID_TRANSPORT_TASK_MISMATCH");
        var repo = prepare(ctx.workspace());
        var prior = repo.snapshot(ctx.actor()).stream().filter(op -> op.plan().id().equals(plan.id())).findFirst();
        if (prior.isPresent()) return result(false, "MAID_TRANSPORT_ALREADY_RECORDED", "已有真实回执，未重放搬运。", view(prior.get()));
        var witness = witnesses.get(plan.id());
        if (witness == null || witness.plan() != plan) throw new SecurityException("MAID_TRANSPORT_PLAN_CHANGED");
        dispatcher.inspectOwned(ctx, () -> { validate(witness, null, ctx); return true; });
        ctx.control().check();
        var resources = materials.acquireNativeScope(ctx.actor(), plan.id(), plan.backendIds()); resourceLeases.put(plan.id(), resources);
        try { repo.intent(ctx.actor(), plan); }
        catch (Exception noDispatch) { materials.releaseNativeScope(ctx.actor(), resources); resourceLeases.remove(plan.id(), resources); throw noDispatch; }
        try {
            var armed = dispatcher.commitObserved(ctx, () -> {
                try {
                    if (jobs.values().stream().filter(job -> !job.terminal).count() >= 8) throw new IllegalStateException("MAID_TRANSPORT_QUEUE_LIMIT");
                    if (jobs.values().stream().anyMatch(job -> !job.terminal && (job.plan.owner().equals(plan.owner()) || job.plan.maidId().equals(plan.maidId()))))
                        throw new IllegalStateException("MAID_TRANSPORT_ALREADY_ARMED");
                    var player = inventory.requirePlayer(ctx.actor());
                    var lease = inventory.maids().acquireOperation(player, player.getUUID(), plan.id());
                    if (lease.lease() == null) throw new IllegalStateException(lease.reason());
                    try {
                        var current = validate(witness, lease.lease(), ctx);
                        var job = new Job(ctx, repo, witness, current, lease.lease());
                        NativeMaidTransportScope.bind(current.maid(), job); jobs.put(plan.id(), job); return new Armed(job, "");
                    } catch (Exception failure) { inventory.maids().releaseRecordedOperation(player.getUUID(), lease.lease()); throw failure; }
                } catch (Exception failure) { return new Armed(null, MaidTransportNative.safe(failure)); }
            }, actual -> {
                if (actual.job() == null) releaseSettled(ctx.actor(), repo.observed(ctx.actor(), plan.id(), false, new Evidence(false, false, actual.rejection(), List.of(), List.of(), List.of(), 0)));
                else {
                    repo.armed(ctx.actor(), plan.id());
                    server.execute(() -> {
                        if (!closed && !actual.job().terminal) { nativeTurns.addLast(plan.id()); actual.job().ready = true; }
                    });
                }
            });
            if (armed.job() == null) return complete(ctx, plan.id());
            return waiting(ctx, armed.job());
        } catch (AgentTool.Deferred deferred) { throw deferred; }
        catch (CommittedCall.Rejected rejected) {
            releaseSettled(ctx.actor(), repo.observed(ctx.actor(), plan.id(), false, new Evidence(false, false, "REJECTED_BEFORE_ARM", List.of(), List.of(), List.of(), 0)));
            if (rejected.getCause() instanceof TaskControl.Stopped stopped) throw stopped;
            return complete(ctx, plan.id());
        }
        catch (Exception failure) {
            var job = jobs.get(plan.id());
            if (job == null) repo.uncertain(ctx.actor(), plan.id(), "NATIVE_ARM_ACKNOWLEDGEMENT_UNCERTAIN");
            throw failure;
        }
    }
    private MaidTransportNative.Handle validate(Witness witness, MaidCarrierBridge.Lease lease, AgentTool.Context ctx) throws Exception {
        require(ctx); var plan = witness.plan(); plan.requireIdentity(ctx.workspace(), ctx.actor(), epoch, generation.getAsLong());
        if (lease != null) requireResources(ctx.actor(), plan.id(), null);
        var current = adapter().capture(ctx.actor(), plan.maidId(), lease, plan.maidGrant(), plan.maidGrantRevision(), plan.blockGrant(), plan.blockGrantRevision());
        var old = witness.handle();
        if (current.player() != old.player() || current.connection() != old.connection() || current.maid() != old.maid()
                || current.bauble() != old.bauble() || current.baubleSlot() != plan.baubleSlot() || current.nativeRevision() != plan.maidRevision()
                || current.bindingRevision() != plan.bindingRevision() || current.toChest() != plan.maidToChest() || current.blacklist() != plan.blacklist()
                || !current.target().equals(old.target()) || !MaidTransportNative.fingerprint(current).equals(plan.baubleFingerprint())
                || !current.excluded().equals(plan.excludedSlots()) || !MaidTransportNative.snapshot(current.filter(), current.player()).equals(plan.filters())
                || !MaidTransportNative.snapshot(current.source(), current.player()).equals(plan.source())
                || !MaidTransportNative.snapshot(current.destination(), current.player()).equals(plan.destination()))
            throw new IllegalStateException("MAID_TRANSPORT_PREVIEW_CHANGED");
        return current;
    }
    private AgentTool.Result waiting(AgentTool.Context ctx, Job job) throws Exception {
        if (job.done.isDone()) return complete(ctx, job.plan.id());
        var checkpoint = new JsonObject(); checkpoint.addProperty("operationId", job.plan.id().toString()); checkpoint.addProperty("phase", "NATIVE_MAID_WIRELESS_IO");
        throw new AgentTool.Deferred("WAITING_NATIVE_MAID_WIRELESS_IO", checkpoint, job.done, fresh -> complete(fresh, job.plan.id()));
    }
    private AgentTool.Result complete(AgentTool.Context ctx, UUID id) throws Exception {
        ctx.actor().requireAccess(ctx.workspace().owner()); var repo = prepare(ctx.workspace()); var op = repo.get(ctx.actor(), id);
        if (!op.plan().taskId().equals(ctx.taskId())) throw new SecurityException("MAID_TRANSPORT_TASK_MISMATCH");
        if (op.state() == State.OBSERVED) {
            var saved = inventory.nativeSaves().awaitJournalled(saveIds.getOrDefault(id, id));
            if (!saved.toCompletableFuture().isDone()) {
                var data = new JsonObject(); data.addProperty("operationId", id.toString());
                throw new AgentTool.Deferred("WAITING_MAID_TRANSPORT_NATIVE_SAVE", data, saved, fresh -> complete(fresh, id));
            }
            op = repo.get(ctx.actor(), id);
            if (op.state() == State.OBSERVED) { repo.uncertain(ctx.actor(), id, "MAID_TRANSPORT_NATIVE_SAVE_UNCONFIRMED"); op = repo.get(ctx.actor(), id); }
        }
        boolean success = op.settled() && op.evidence() != null && op.evidence().achieved();
        return result(success, success ? "" : op.settled() ? op.reason() : "RECOVERY_REQUIRED", success
                ? "已按获批有限匹配集完成真实女仆搬运，组件/数量守恒与原生保存均核验。"
                : "搬运未确认全部完成；保留实际库存、托管与保存回执，未重放或凭空补料。", view(op));
    }
    private final class Job implements NativeMaidTransportScope.Job {
        final AgentTool.Context ctx; final MaidTransportJournal repo; final Witness witness; final MaidTransportPlan plan;
        final MaidCarrierBridge.Lease lease; final CompletableFuture<Void> done = new CompletableFuture<>();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        MaidTransportNative.Handle handle; NativeConstructionSave scope; MaidTransportNative.Transfer transfer;
        List<NativeSaveObserver.Target> physicalTargets = List.of();
        final Set<Object> changedContainers = Collections.newSetFromMap(new IdentityHashMap<>());
        volatile boolean ready, terminal; int attemptTick = -1; long started;
        Job(AgentTool.Context ctx, MaidTransportJournal repo, Witness witness, MaidTransportNative.Handle handle, MaidCarrierBridge.Lease lease) {
            this.ctx = ctx; this.repo = repo; this.witness = witness; this.plan = witness.plan(); this.handle = handle; this.lease = lease;
        }
        @Override public boolean before(Object maid, ItemStack stack) {
            if (!ready || terminal) return false;
            if (nativeTurnTick == server.getTickCount() || !plan.id().equals(nativeTurns.peekFirst())) return false;
            nativeTurnTick = server.getTickCount();
            try {
                handle = validate(witness, lease, ctx);
                if (maid != handle.maid() || stack != handle.bauble()) throw new SecurityException("MAID_TRANSPORT_NATIVE_TICK_CHANGED");
                started = System.nanoTime(); attemptTick = server.getTickCount();
                var targets = new ArrayList<NativeSaveObserver.Target>(); targets.addAll(handle.maidView().nativeTargets()); targets.addAll(handle.blockView().nativeTargets());
                physicalTargets = List.copyOf(targets); inventory.nativeSaves().requireCapacity(physicalTargets);
                scope = NativeConstructionSave.beginEntities(handle.player(), List.of()); return true;
            } catch (Exception failure) { finish(false, true, false, MaidTransportNative.safe(failure), plan.source(), plan.destination(), List.of(), null); return false; }
        }
        @Override public void helper(Object behavior, boolean toChest, IItemHandler maid, IItemHandler chest,
                boolean blacklist, IItemHandler filter, List<Boolean> excluded) {
            if (transfer != null) {
                transfer = new MaidTransportNative.Transfer(transfer.entered(), false, false, "MAID_TRANSPORT_MULTIPLE_NATIVE_PASSES",
                        transfer.source(), transfer.destination(), transfer.custody()); return;
            }
            try {
                var current = validate(witness, lease, ctx);
                if (toChest != plan.maidToChest() || blacklist != plan.blacklist() || !excluded.equals(plan.excludedSlots())
                        || !MaidTransportNative.snapshot(filter, current.player()).equals(plan.filters()))
                    throw new IllegalStateException("MAID_TRANSPORT_NATIVE_EVENT_CHANGED");
                var safeMaid = current.maidView().bindNative(maid); var safeChest = current.blockView().bindNative(chest);
                var actual = new MaidTransportNative.Handle(current.player(), current.connection(), current.maid(), current.bauble(), current.baubleSlot(), current.target(),
                        current.toChest(), blacklist, excluded, filter, maid, chest, toChest ? safeMaid : safeChest, toChest ? safeChest : safeMaid,
                        current.maidView(), current.blockView(), current.nativeRevision(), current.bindingRevision(), behavior, current.helper());
                var check = MaidTransportNative.simulate(actual);
                if (!check.moves().equals(plan.moves()) || !check.matching().equals(plan.matchingSlots()) || !check.source().equals(plan.source())
                        || !check.destination().equals(plan.destination()) || !check.afterSource().equals(plan.expectedSource()) || !check.afterDestination().equals(plan.expectedDestination()))
                    throw new IllegalStateException("MAID_TRANSPORT_NATIVE_EVENT_CHANGED");
                handle = actual;
                transfer = MaidTransportNative.transfer(actual, plan, ctx.control(), () -> {
                    require(ctx); plan.requireIdentity(ctx.workspace(), ctx.actor(), epoch, generation.getAsLong());
                    requireResources(ctx.actor(), plan.id(), null);
                    var player = inventory.requirePlayer(ctx.actor());
                    var state = inventory.maids().validateOperation(player, player.getUUID(), lease);
                    if (state.lease() == null || MaidNativeRevision.revision(handle.maid()) != plan.maidRevision()
                            || !MaidTransportNative.fingerprint(handle).equals(plan.baubleFingerprint())) throw new SecurityException("MAID_TRANSPORT_CONTROL_CHANGED");
                });
            } catch (Exception failure) { transfer = observed(false, MaidTransportNative.safe(failure)); }
        }
        private MaidTransportNative.Transfer observed(boolean achieved, String reason) {
            try {
                var source = MaidTransportNative.snapshot(handle.source(), handle.player()); var destination = MaidTransportNative.snapshot(handle.destination(), handle.player());
                boolean unchanged = source.equals(plan.source()) && destination.equals(plan.destination());
                return new MaidTransportNative.Transfer(false, unchanged, achieved, reason, source, destination, List.of());
            } catch (RuntimeException unavailable) { return new MaidTransportNative.Transfer(false, false, false, reason, List.of(), List.of(), List.of()); }
        }
        @Override public void blockEntityChanged(net.minecraft.world.level.block.entity.BlockEntity entity) {
            if (physicalTargets.stream().noneMatch(target -> target.identity() == entity)) {
                NativeConstructionSave.unadaptedSavedData(); return;
            }
            changedContainers.add(entity);
            if (entity.getLevel() instanceof net.minecraft.server.level.ServerLevel level && level.hasChunkAt(entity.getBlockPos()))
                changedContainers.add(level.getChunkAt(entity.getBlockPos()));
            else NativeConstructionSave.unadaptedSavedData();
        }
        @Override public void after() {
            if (terminal) return;
            if (transfer == null) transfer = observed(false, "MAID_TRANSPORT_NATIVE_EVENT_CANCELLED_OR_NO_MOVE");
            NativeSaveBarrier.Receipt receipt = null; boolean verified = transfer.verified(); String reason = transfer.reason();
            try {
                // Accepted effects retain their exact prepared save identities after a logout;
                // collecting their late receipt is not a new inventory access/authorization.
                // A canceled helper never writes the clean container. Do not invent a new BE /
                // chunk save obligation for it. Actual setChanged calls are scoped and audited;
                // maid/entity data and the real owner callbacks still get positive save receipts.
                var affected = physicalTargets.stream().filter(target -> target.identity() instanceof net.minecraft.world.entity.Entity
                        || changedContainers.contains(target.identity())).toList();
                var physical = inventory.nativeSaves().changedOrUnconfirmed(affected);
                var callbacks = scope.finish(); receipt = NativeSaveBarrier.combine(List.of(CompletableFuture.completedFuture(physical), CompletableFuture.completedFuture(callbacks)));
            } catch (RuntimeException failure) { verified = false; reason = MaidTransportNative.safe(failure); }
            finally { if (scope != null) scope.close(); scope = null; }
            finish(true, verified, transfer.achieved() && verified, reason, transfer.source(), transfer.destination(), transfer.custody(), receipt);
        }
        @Override public void aborted() {
            if (scope != null) scope.close(); scope = null;
            var actual = transfer == null ? observed(false, "MAID_TRANSPORT_NATIVE_RETURN_UNCERTAIN") : transfer;
            finish(true, false, false, "MAID_TRANSPORT_NATIVE_RETURN_UNCERTAIN", actual.source(), actual.destination(), actual.custody(), null);
        }
        void finish(boolean entered, boolean verified, boolean achieved, String reason, List<MaterialStack> source,
                List<MaterialStack> destination, List<MaterialStack> custody, NativeSaveBarrier.Receipt save) {
            if (terminal) return; terminal = true;
            nativeTurns.remove(plan.id());
            NativeMaidTransportScope.unbind(this); inventory.maids().releaseRecordedOperation(UUID.fromString(plan.owner()), lease);
            inventory.clearPrivateViews(plan.owner());
            long elapsed = started == 0 ? 0 : Math.max(0, System.nanoTime() - started);
            var recorded = new Evidence(entered && verified, entered && achieved, reason,
                    entered ? source : List.of(), entered ? destination : List.of(), entered ? custody : List.of(), elapsed);
            try { evidence.execute(() -> {
                try {
                    var op = repo.observed(ctx.actor(), plan.id(), entered, recorded); releaseSettled(ctx.actor(), op);
                    if (entered && save != null) {
                        String digest = AiJson.digest(AiJson.canonical(recorded));
                        inventory.nativeSaves().record(plan.id(), save, proof -> {
                            repo.saved(ctx.actor(), plan.id(), proof, digest); releaseSettled(ctx.actor(), repo.get(ctx.actor(), plan.id()));
                        });
                    }
                    done.complete(null);
                } catch (Exception failure) { broken = true; done.completeExceptionally(failure); }
            }); } catch (RejectedExecutionException rejected) { broken = true; done.completeExceptionally(rejected); }
        }
    }
    public void tick() {
        thread();
        for (var job : List.copyOf(jobs.values())) {
            if (!job.terminal) {
                if (job.attemptTick >= 0 && job.attemptTick < server.getTickCount()) { NativeMaidTransportScope.discardAborted(job); job.aborted(); continue; }
                try { require(job.ctx); if (System.nanoTime() >= job.deadline) throw new IllegalStateException("MAID_TRANSPORT_NATIVE_TICK_TIMEOUT"); }
                catch (RuntimeException stopped) { job.finish(false, true, false, MaidTransportNative.safe(stopped), job.plan.source(), job.plan.destination(), List.of(), null); }
            } else if (job.done.isDone()) { jobs.remove(job.plan.id(), job); witnesses.remove(job.plan.id()); }
        }
    }
    public List<Operation> operations(Workspace workspace, Actor actor) {
        actor.requireAccess(workspace.owner()); var repo = journals.get(workspace.owner());
        if (repo == null || broken) throw new IllegalStateException("MAID_TRANSPORT_NOT_PREPARED"); return repo.snapshot(actor);
    }
    public boolean resourcesSettled(Workspace workspace, Actor actor) {
        actor.requireAccess(workspace.owner()); var repo = journals.get(workspace.owner());
        return !broken && repo != null && repo.settled(actor) && resourceLeases.values().stream().noneMatch(lease -> lease.owner().toString().equals(actor.owner()));
    }
    public boolean requiresRecovery(Workspace workspace, Actor actor, UUID task) {
        actor.requireAccess(workspace.owner()); var repo = journals.get(workspace.owner());
        return broken || repo == null || !repo.taskSettled(actor, task) || repo.snapshot(actor).stream()
                .anyMatch(op -> op.plan().taskId().equals(task) && resourceLeases.containsKey(op.plan().id()));
    }
    public String exportJournal(Workspace workspace, Actor actor) throws IOException {
        actor.requireAccess(workspace.owner()); var repo = journals.get(workspace.owner());
        if (repo == null) throw new IllegalStateException("MAID_TRANSPORT_NOT_PREPARED"); return repo.exportJournal(actor);
    }
    /** Main-thread identity revocation. Accepted effects keep their late evidence and never replay. */
    public void disconnected(UUID owner) {
        thread();
        for (var job : List.copyOf(jobs.values())) if (!job.terminal && job.plan.owner().equals(owner.toString())) {
            if (job.scope != null && job.attemptTick == server.getTickCount()) {
                // A logout callback can occur inside an accepted native call. Stop further
                // actions, but let its actual return/custody reach after() before finalizing.
                job.ctx.control().stop("PLAYER_OFFLINE");
            } else if (job.scope != null) { NativeMaidTransportScope.discardAborted(job); job.aborted(); }
            else job.finish(false, false, false, "PLAYER_OFFLINE_BEFORE_NATIVE_TICK", List.of(), List.of(), List.of(), null);
        }
        witnesses.values().removeIf(value -> value.plan().owner().equals(owner.toString()));
        returns.values().removeIf(value -> value.plan().owner().equals(owner.toString()));
    }
    public void forgetErased(Workspace workspace, Actor actor) {
        actor.requireAccess(workspace.owner()); var repo = journals.get(workspace.owner());
        if (repo == null) return;
        if (!repo.forgettable(actor) || resourceLeases.values().stream().anyMatch(lease -> lease.owner().toString().equals(actor.owner()))
                || jobs.values().stream().anyMatch(job -> job.plan.owner().equals(actor.owner()) && !job.done.isDone()))
            throw new IllegalStateException("MAID_TRANSPORT_PRIVACY_SETTLEMENT_REQUIRED");
        journals.remove(workspace.owner(), repo); witnesses.values().removeIf(value -> value.plan().owner().equals(actor.owner()));
        reviews.values().removeIf(value -> value.owner().equals(actor.owner()));
        returns.values().removeIf(value -> value.plan().owner().equals(actor.owner())); returnIndex.values().removeIf(value -> value.owner().equals(actor.owner()));
    }
    public List<JsonObject> recoveryRows(Workspace workspace, Actor actor, UUID task) {
        return operations(workspace, actor).stream().filter(op -> op.plan().taskId().equals(task)).map(op -> {
            var row = view(op); row.addProperty("kind", "MAID_TRANSPORT"); var actions = new JsonArray();
            if (op.reconcilable() || returnable(op)) actions.add("MAID_TRANSPORT_RECONCILE"); row.add("actions", actions); return row;
        }).toList();
    }
    public Review recoveryPreview(AgentTool.Context ctx, UUID operation) throws Exception {
        var op = operation(ctx.workspace(), ctx.actor(), operation);
        if (!op.plan().taskId().equals(ctx.taskId()) || !op.reconcilable()) throw new IllegalStateException("MAID_TRANSPORT_RECONCILE_UNAVAILABLE");
        var review = new Review(UUID.randomUUID(), ctx.actor().owner(), ctx.taskId(), operation, op.revision(), AiJson.digest(AiJson.canonical(op)),
                ctx.workspace().conversation().privacyEpoch(), epoch, "").signed();
        synchronized (ctx.workspace().conversation()) {
            validateReview(ctx.workspace(), ctx.actor(), review); AiJson.write(ctx.workspace().artifact(review.id()), review);
            ctx.workspace().conversation().append(List.of(new Draft(ctx.taskId(), "maid_transport_reconcile_preview", null, AiJson.GSON.toJsonTree(review).getAsJsonObject(), review.id().toString())));
            reviews.put(review.id(), review);
        }
        return review;
    }
    public Review loadReconciliation(Workspace workspace, Actor actor, UUID id, String hash) {
        var review = reviews.get(id);
        if (review == null || !review.hash().equals(hash)) throw new IllegalStateException("MAID_TRANSPORT_RECONCILE_CHANGED");
        validateReview(workspace, actor, review); return review;
    }
    private void validateReview(Workspace workspace, Actor actor, Review review) {
        actor.requireAccess(workspace.owner()); actor.requireAccess(review.owner()); var op = operation(workspace, actor, review.operationId());
        if (!review.intact() || !epoch.equals(review.executionEpoch()) || review.privacyEpoch() != workspace.conversation().privacyEpoch()
                || !op.plan().taskId().equals(review.taskId()) || !op.reconcilable() || op.revision() != review.operationRevision()
                || !AiJson.digest(AiJson.canonical(op)).equals(review.operationDigest())) throw new IllegalStateException("MAID_TRANSPORT_RECONCILE_CHANGED");
    }
    public AgentTool.Result reconcile(AgentTool.Context ctx, Review review) throws Exception {
        require(ctx); if (!review.taskId().equals(ctx.taskId())) throw new SecurityException("MAID_TRANSPORT_TASK_MISMATCH");
        validateReview(ctx.workspace(), ctx.actor(), review);
        var op = prepare(ctx.workspace()).reconcile(ctx.actor(), review.operationId(), review.operationRevision(), review.operationDigest());
        releaseSettled(ctx.actor(), op);
        return result(true, "", "仅按真实守恒和保存回执恢复已知搬运状态，未执行原生搬运或交付托管。", view(op));
    }
    public String reconciliationReview(Workspace workspace, Actor actor, Review review) {
        validateReview(workspace, actor, review);
        return "仅确认原搬运的已知组件守恒、空托管与真实保存证据。不重新搬运，不补料或退款；未知效果不可确认。\n" + AiJson.GSON.toJson(operation(workspace, actor, review.operationId()));
    }
    public JsonObject recoveryResources(Workspace workspace, Actor actor, UUID id) {
        var op = operation(workspace, actor, id); var data = AiJson.GSON.toJsonTree(op).getAsJsonObject();
        data.addProperty("taskId", op.plan().taskId().toString()); data.addProperty("operationId", id.toString()); return data;
    }
    public boolean hasReturnableCustody(Workspace workspace, Actor actor, UUID id) { return returnable(operation(workspace, actor, id)); }
    public boolean operationSettled(Workspace workspace, Actor actor, UUID id) { return operation(workspace, actor, id).settled() && !resourceLeases.containsKey(id); }
    private static boolean returnable(Operation op) {
        return op.evidence() != null && op.evidence().verified() && !op.evidence().custody().isEmpty()
                && op.nativeProof().matches("[0-9a-f]{64}") && !op.reason().startsWith("RETURN_INTENT:");
    }
    public MaidTransportReturn returnPreview(AgentTool.Context ctx, UUID operationId, int index) throws Exception {
        require(ctx); var op = operation(ctx.workspace(), ctx.actor(), operationId);
        if (!op.plan().taskId().equals(ctx.taskId()) || !returnable(op) || index < 0 || index >= op.evidence().custody().size())
            throw new IllegalStateException("MAID_TRANSPORT_RETURN_UNAVAILABLE");
        var prepared = dispatcher.inspectOwned(ctx, () -> inventory.prepareDelivery(ctx.actor(), op.evidence().custody().get(index)));
        var destinations = dispatcher.inspectOwned(ctx, () -> new DeliveryIdentity(inventory.deliveryPools(prepared).stream().sorted().toList(),
                inventory.deliveryBackends(prepared).stream().sorted().toList()));
        var plan = new MaidTransportReturn(UUID.randomUUID(), ctx.actor().owner(), ctx.taskId(), ctx.workspace().metadata().serverInstanceId(),
                ctx.workspace().primaryConversationId(), ctx.workspace().conversation().privacyEpoch(), epoch, System.currentTimeMillis() + 120000,
                operationId, op.revision(), AiJson.digest(AiJson.canonical(op)), op.nativeProof(), index, prepared.product(), prepared.witness(),
                destinations.pools(), destinations.backends(), "").signed();
        synchronized (ctx.workspace().conversation()) {
            plan.requireIdentity(ctx.workspace(), ctx.actor(), epoch);
            if (operation(ctx.workspace(), ctx.actor(), operationId).revision() != op.revision()) throw new IllegalStateException("MAID_TRANSPORT_RETURN_CHANGED");
            AiJson.write(ctx.workspace().artifact(plan.id()), plan);
            ctx.workspace().conversation().append(List.of(new Draft(ctx.taskId(), "maid_transport_return_plan", null, AiJson.GSON.toJsonTree(plan).getAsJsonObject(), plan.id().toString())));
            returns.values().removeIf(value -> value.plan().expiresAt() <= System.currentTimeMillis());
            if (returns.size() >= 128) throw new IllegalStateException("MAID_TRANSPORT_RETURN_PREVIEW_LIMIT");
            returns.put(plan.id(), new ReturnWitness(plan, prepared)); returnIndex.put(plan.id(), plan);
        }
        return plan;
    }
    public MaidTransportReturn loadReturn(Workspace workspace, Actor actor, UUID id, String hash) {
        var witness = returns.get(id);
        if (witness == null || !witness.plan().hash().equals(hash)) throw new IllegalStateException("MAID_TRANSPORT_RETURN_CHANGED");
        var plan = witness.plan(); plan.requireIdentity(workspace, actor, epoch); var op = operation(workspace, actor, plan.operationId());
        if (!returnable(op) || op.revision() != plan.operationRevision() || !AiJson.digest(AiJson.canonical(op)).equals(plan.operationDigest()))
            throw new IllegalStateException("MAID_TRANSPORT_RETURN_CHANGED");
        return plan;
    }
    public String returnReview(Workspace workspace, Actor actor, MaidTransportReturn plan) {
        plan.requireIdentity(workspace, actor, epoch);
        return "只返还账本核验的这部分真实托管，优先本人背包，其余去向必须已有独立插入授权。目标与具体物品已固定；不重放原搬运。未知取料/交付不可返还。\n" + AiJson.GSON.toJson(plan);
    }
    public AgentTool.Result returnCustody(AgentTool.Context ctx, MaidTransportReturn plan) throws Exception {
        require(ctx); plan = loadReturn(ctx.workspace(), ctx.actor(), plan.id(), plan.hash());
        if (!plan.taskId().equals(ctx.taskId())) throw new SecurityException("MAID_TRANSPORT_TASK_MISMATCH");
        final var approved = plan; var witness = returns.remove(plan.id());
        if (witness == null) throw new IllegalStateException("MAID_TRANSPORT_RETURN_CHANGED");
        var repo = prepare(ctx.workspace()); repo.beginReturn(ctx.actor(), plan.operationId(), plan.operationRevision(), plan.operationDigest(), plan.id(), plan.destinationBackends());
        try {
            var resources = materials.acquireNativeScope(ctx.actor(), plan.operationId(), plan.destinationBackends());
            resourceLeases.put(plan.operationId(), resources); // Only replace after successful atomic extension.
        } catch (RuntimeException conflict) {
            repo.returnRejected(ctx.actor(), plan, "RETURN_SCOPE_REJECTED_BEFORE_NATIVE_DELIVERY"); throw conflict;
        }
        saveIds.put(plan.operationId(), plan.id());
        try {
            dispatcher.commitObserved(ctx, () -> {
                requireResources(ctx.actor(), approved.operationId(), approved.destinationBackends());
                return inventory.deliver(witness.delivery());
            }, actual -> {
                if (!actual.mutationEntered()) { repo.returnRejected(ctx.actor(), approved, "RETURN_REJECTED_BEFORE_NATIVE_DELIVERY"); return; }
                String reason = actual.verified() ? "RETURNED_VERIFIED_CUSTODY" : "RETURN_NATIVE_DELIVERY_UNCERTAIN";
                var observed = repo.returned(ctx.actor(), approved.operationId(), approved.id(), approved.custodyIndex(), approved.product(), actual.accepted(), actual.verified(), reason);
                if (actual.save() != null) {
                    String digest = AiJson.digest(AiJson.canonical(observed.evidence()));
                    inventory.nativeSaves().record(approved.id(), actual.save(), proof -> {
                        repo.saved(ctx.actor(), approved.operationId(), proof, digest); releaseSettled(ctx.actor(), repo.get(ctx.actor(), approved.operationId()));
                    });
                } else repo.uncertain(ctx.actor(), approved.operationId(), "RETURN_NATIVE_SAVE_UNCOVERED");
            });
        } catch (CommittedCall.Rejected rejected) {
            repo.returnRejected(ctx.actor(), approved, "RETURN_REJECTED_BEFORE_NATIVE_DELIVERY");
            if (rejected.getCause() instanceof TaskControl.Stopped stopped) throw stopped;
        }
        return complete(ctx, plan.operationId());
    }
    private Operation operation(Workspace workspace, Actor actor, UUID id) {
        return operations(workspace, actor).stream().filter(op -> op.plan().id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("MAID_TRANSPORT_OPERATION_NOT_FOUND"));
    }
    public Optional<Boolean> settledCall(Workspace workspace, Actor actor, UUID task, ModelMessage.ToolCall call) {
        if (!call.name().equals("maid_transport_execute") && !call.name().equals("maid_transport_reconcile") && !call.name().equals("maid_transport_return")) return Optional.empty();
        try {
            var args = AiJson.object(call.arguments(), 4096, 8);
            if (call.name().equals("maid_transport_return")) {
                if (!args.keySet().equals(Set.of("returnId", "returnHash"))) return Optional.of(false);
                var plan = returnIndex.get(UUID.fromString(args.get("returnId").getAsString()));
                return Optional.of(plan != null && plan.intact() && plan.owner().equals(actor.owner()) && plan.taskId().equals(task)
                        && plan.hash().equals(args.get("returnHash").getAsString()) && operationSettled(workspace, actor, plan.operationId()));
            }
            if (call.name().equals("maid_transport_reconcile")) {
                if (!args.keySet().equals(Set.of("reviewId", "reviewHash"))) return Optional.of(false);
                var review = reviews.get(UUID.fromString(args.get("reviewId").getAsString()));
                return Optional.of(review != null && review.intact() && review.owner().equals(actor.owner()) && review.taskId().equals(task)
                        && review.hash().equals(args.get("reviewHash").getAsString()) && operationSettled(workspace, actor, review.operationId()));
            }
            if (!args.keySet().equals(Set.of("planId", "planHash"))) return Optional.of(false);
            var op = operation(workspace, actor, UUID.fromString(args.get("planId").getAsString()));
            return Optional.of(op.settled() && !resourceLeases.containsKey(op.plan().id()) && op.plan().taskId().equals(task) && op.plan().hash().equals(args.get("planHash").getAsString()));
        } catch (RuntimeException invalid) { return Optional.of(false); }
    }
    public void register(ToolRegistry registry) {
        tool(registry, "maid_transport_plan", "规划本人已佩戴并绑定的女仆无线搬运完整有限集。必须提供普通女仆背包及绑定容器的独立授权ID/版本；饰品需原生配置排除双手。不是免设备合成。",
                "{\"maidId\":{\"type\":\"string\"},\"maidGrant\":{\"type\":\"string\"},\"maidRevision\":{\"type\":\"integer\",\"minimum\":1},\"blockGrant\":{\"type\":\"string\"},\"blockRevision\":{\"type\":\"integer\",\"minimum\":1}}",
                List.of("maidId", "maidGrant", "maidRevision", "blockGrant", "blockRevision"), AgentTool.Risk.READ_ONLY, (ctx, args) -> {
                    var plan = plan(ctx, uuid(args, "maidId"), uuid(args, "maidGrant"), args.get("maidRevision").getAsLong(), uuid(args, "blockGrant"), args.get("blockRevision").getAsLong());
                    return result(true, "", "真实佩戴/绑定、双向独立授权和原生完整匹配集已核验，尚未搬运。", planView(plan));
                });
        tool(registry, "maid_transport_execute", "按工作台固定批准执行女仆原生无线搬运，等待自然100 Tick机会；不改变任务/配置/工具/费用。变更、离线、取消或未知效果不自动重放。",
                "{\"planId\":{\"type\":\"string\"},\"planHash\":{\"type\":\"string\"}}", List.of("planId", "planHash"), AgentTool.Risk.PLAYER_STATE,
                (ctx, args) -> execute(ctx, load(ctx.workspace(), ctx.actor(), uuid(args, "planId"), args.get("planHash").getAsString())));
        tool(registry, "maid_transport_status", "读取本人真实搬运回执、具体托管和保存；未知执行不能普通继续。",
                "{}", List.of(), AgentTool.Risk.READ_ONLY, (ctx, args) -> {
                    var data = new JsonObject(); data.add("operations", AiJson.GSON.toJsonTree(operations(ctx.workspace(), ctx.actor()).stream().map(MaidTransportService::view).toList()));
                    return result(true, "", "本人搬运账本。", data);
                });
        tool(registry, "maid_transport_reconcile", "仅按已知守恒、空托管及真实保存证据确认原搬运终态，不执行搬运或返还，必须新批准。",
                "{\"reviewId\":{\"type\":\"string\"},\"reviewHash\":{\"type\":\"string\"}}", List.of("reviewId", "reviewHash"), AgentTool.Risk.PLAYER_STATE,
                (ctx, args) -> reconcile(ctx, loadReconciliation(ctx.workspace(), ctx.actor(), uuid(args, "reviewId"), args.get("reviewHash").getAsString())));
        tool(registry, "maid_transport_return_preview", "为本人经过组件守恒和保存确认的真实托管生成固定返还预览；不重复取料，不处理未知执行。",
                "{\"operationId\":{\"type\":\"string\"},\"custodyIndex\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":127}}", List.of("operationId", "custodyIndex"), AgentTool.Risk.READ_ONLY,
                (ctx, args) -> result(true, "", "真实托管返还预览，尚未交付。", AiJson.GSON.toJsonTree(returnPreview(ctx, uuid(args, "operationId"), args.get("custodyIndex").getAsInt())).getAsJsonObject()));
        tool(registry, "maid_transport_return", "仅按固定新批准返还本人具体托管。未知交付保持恢复要求，不重放或补料。",
                "{\"returnId\":{\"type\":\"string\"},\"returnHash\":{\"type\":\"string\"}}", List.of("returnId", "returnHash"), AgentTool.Risk.PLAYER_STATE,
                (ctx, args) -> returnCustody(ctx, loadReturn(ctx.workspace(), ctx.actor(), uuid(args, "returnId"), args.get("returnHash").getAsString())));
    }
    private interface Action { AgentTool.Result run(AgentTool.Context ctx, JsonObject args) throws Exception; }
    private record DeliveryIdentity(List<String> pools, List<String> backends) {}
    private void requireResources(Actor actor, UUID operation, Collection<String> touchedBackends) {
        var lease = resourceLeases.get(operation);
        if (lease == null) throw new IllegalStateException("MAID_TRANSPORT_RESOURCE_LEASE_MISSING");
        if (touchedBackends == null) materials.requireNativeScope(actor, lease);
        else materials.requireNativeScope(actor, lease, touchedBackends);
    }
    private void releaseSettled(Actor actor, Operation op) {
        if (!op.settled()) return;
        var latest = resourceLeases.get(op.plan().id());
        if (latest != null) {
            materials.releaseNativeScope(actor, latest); resourceLeases.remove(op.plan().id(), latest);
        }
    }
    /** Prepared server-maintenance metadata; never a player/model query or an authentication claim. */
    public List<MaidTransportJournal.ResourceObligation> resourceObligations() {
        return journals.values().stream().flatMap(journal -> journal.resourceObligations().stream()).toList();
    }
    private void tool(ToolRegistry registry, String name, String description, String properties, List<String> required, AgentTool.Risk risk, Action action) {
        var schema = new JsonObject(); schema.addProperty("type", "object"); schema.add("properties", JsonParser.parseString(properties));
        schema.add("required", AiJson.GSON.toJsonTree(required)); schema.addProperty("additionalProperties", false);
        registry.register(new DefinedTool(new AgentTool.Descriptor(name, description, schema, risk, "maid.control.own", 0,
                AgentTool.ExecutionThread.IO, 120000, true, ""), (ctx, args) -> { require(ctx); return action.run(ctx, args); }));
    }
    private void require(AgentTool.Context ctx) {
        ctx.actor().requireAccess(ctx.workspace().owner()); ctx.control().check();
        if (ctx.taskId() == null) throw new IllegalArgumentException("MAID_TRANSPORT_TASK_REQUIRED");
        if (closed || broken || !enabled.getAsBoolean()) throw new TaskControl.Stopped("AI_STOPPING");
    }
    private void thread() { if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED"); }
    private static UUID uuid(JsonObject args, String key) { return UUID.fromString(args.get(key).getAsString()); }
    private static JsonObject planView(MaidTransportPlan plan) {
        var data = new JsonObject(); data.addProperty("planId", plan.id().toString()); data.addProperty("planHash", plan.hash());
        data.addProperty("direction", plan.maidToChest() ? "MAID_TO_CONTAINER" : "CONTAINER_TO_MAID"); data.addProperty("matchingSlots", plan.matchingSlots().size());
        data.addProperty("plannedTransfers", plan.moves().size()); data.addProperty("fullPreviewAvailable", true); data.addProperty("processingWaiversApply", false); return data;
    }
    private static JsonObject view(Operation op) {
        var data = new JsonObject(); data.addProperty("operationId", op.plan().id().toString()); data.addProperty("taskId", op.plan().taskId().toString());
        data.addProperty("state", op.state().name()); data.addProperty("reason", op.reason()); data.addProperty("settled", op.settled()); data.addProperty("saved", !op.nativeProof().isEmpty());
        if (op.evidence() != null) { data.addProperty("verified", op.evidence().verified()); data.addProperty("achieved", op.evidence().achieved());
            data.add("custody", AiJson.GSON.toJsonTree(op.evidence().custody())); data.addProperty("elapsedNanos", op.evidence().elapsedNanos()); }
        data.addProperty("private", true); data.addProperty("noReplay", true); return data;
    }
    private static AgentTool.Result result(boolean success, String code, String summary, JsonObject data) {
        return new AgentTool.Result(success, code, summary, data, "actual-touhou-little-maid-1.5.3-wireless-io", "", false);
    }
    @Override public void close() {
        thread(); closed = true;
        for (var job : List.copyOf(jobs.values())) if (!job.terminal) {
            if (job.scope != null) { NativeMaidTransportScope.discardAborted(job); job.aborted(); }
            else job.finish(false, true, false, "SHUTDOWN_BEFORE_NATIVE_TICK", job.plan.source(), job.plan.destination(), List.of(), null);
        }
    }
    public boolean awaitStopped(long millis) throws InterruptedException {
        if (!closed) return false;
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        for (var job : jobs.values()) try { job.done.get(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS); }
        catch (ExecutionException ignored) { /* The unresolved journal retains the effect. */ }
        catch (TimeoutException timeout) { return false; }
        evidence.shutdown(); return evidence.awaitTermination(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS);
    }
}
