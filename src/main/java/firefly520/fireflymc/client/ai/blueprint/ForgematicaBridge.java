package firefly520.fireflymc.client.ai.blueprint;

import firefly520.fireflymc.ai.blueprint.BlueprintDocument;
import firefly520.fireflymc.ai.blueprint.BlueprintBlockStateCodec;
import firefly520.fireflymc.ai.blueprint.LitematicSourceValidator;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * Client-only, delayed optional-mod binding. IO uses the caller's bounded executor; native objects
 * are read/filled on tick with a 1024-unit / 2 ms budget. This class never performs gameplay writes.
 * Callers must authenticate server documents and independently review every imported client proposal.
 */
public final class ForgematicaBridge {
    public static final String FORGEMATICA_VERSION = "0.4.2+mc1.21.1";
    public static final String MAFGLIB_VERSION = "0.4.3+mc1.21.1";
    private static final int UNITS_PER_TICK = 1024;
    private static final long TICK_NANOS = 2_000_000;
    private static final long JOB_TIMEOUT_NANOS = 120_000_000_000L;
    private static final int MAX_PREVIEWS = 4;

    public enum Readiness { DISCOVERED, ABSENT, STATIC_API_MATCH, RUNTIME_CALLABLE, UNSUPPORTED_VERSION, FAILED }
    public record Status(Readiness readiness, String forgematicaVersion, String mafglibVersion,
                         int protocolVersion, boolean behaviorVerified, String reason) {}
    public record Outcome<T>(boolean success, String code, T value, Status status) {}
    public record PreviewHandle(UUID id) {}
    /** Null handle means every preview must be invalidated after a bounded event queue overflow. */
    public record PreviewChange(PreviewHandle handle, String code) {}
    public record Progress(String stage, long processed, long total) {}

    private final Executor io;
    private volatile Status status = new Status(Readiness.DISCOVERED, "", "", 1, false, "NOT_PROBED");
    private ForgematicaAccess access;
    private Job<?> active;
    private final Map<UUID, Owned> owned = new LinkedHashMap<>();
    private final Map<UUID, PreviewChange> changes = new LinkedHashMap<>();
    private int internalMutation;
    private volatile boolean foreignThreadMutation;
    private boolean changesOverflow;
    private long ownedCells;

    /** The executor must have a bounded queue and a rejection policy; this class creates no threads. */
    public ForgematicaBridge(Executor boundedIo) { io = Objects.requireNonNull(boundedIo); }
    public Status status() { return status; }

    public Status probe() {
        requireMainThread();
        if (status.readiness() != Readiness.DISCOVERED) return status;
        String fm = "", ml = "";
        try {
            fm = version("forgematica"); ml = version("mafglib");
            if (fm.isEmpty() || ml.isEmpty()) return status = state(Readiness.ABSENT, fm, ml, "MOD_MISSING");
            if (!FORGEMATICA_VERSION.equals(fm) || !MAFGLIB_VERSION.equals(ml))
                return status = state(Readiness.UNSUPPORTED_VERSION, fm, ml, "UNSUPPORTED_VERSION");
            access = new ForgematicaAccess();
            access.listen(this::nativeEvent);
            return status = state(Readiness.STATIC_API_MATCH, fm, ml, "STATIC_API_MATCH");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            access = null;
            return status = state(Readiness.FAILED, fm, ml, "SIGNATURE_PROBE_FAILED");
        }
    }

    public CompletableFuture<Outcome<BlueprintDocument>> importSelected() {
        String unavailable = ready();
        if (unavailable != null) return completed(unavailable);
        if (active != null) return completed("BRIDGE_BUSY");
        try {
            Object manager = access.manager(), selected = access.selected(manager);
            if (selected == null) return completed("NO_SELECTED_PLACEMENT");
            var snapshot = access.capture(manager, selected);
            if (!snapshot.enabled()) return completed("PLACEMENT_DISABLED");
            Owned trusted = owned.values().stream().filter(v -> v.schematic == snapshot.schematic()).findFirst().orElse(null);
            if (trusted == null && snapshot.source() == null) return completed("SOURCE_PROVENANCE_UNAVAILABLE");
            if (trusted == null && snapshot.modified()) return completed("UNSAVED_SOURCE_CHANGES");
            var job = new ImportJob(snapshot, trusted, context());
            active = job;
            job.begin();
            return job.future;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            recordNativeFailure(failure);
            failActive(code(failure));
            return completed(code(failure));
        }
    }

    /** Only pass documents accepted from the authenticated server; DTO source labels are not trusted. */
    public CompletableFuture<Outcome<PreviewHandle>> showPreview(BlueprintDocument document) {
        String unavailable = ready();
        if (unavailable != null) return completed(unavailable);
        if (active != null) return completed("BRIDGE_BUSY");
        if (owned.size() >= MAX_PREVIEWS) return completed("PREVIEW_COUNT_LIMIT");
        try {
            Objects.requireNonNull(document, "document");
            Object manager = access.manager();
            var job = new PreviewJob(document, manager, access.selected(manager), context());
            active = job;
            return job.future;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            recordNativeFailure(failure);
            return completed(code(failure));
        }
    }

    public Optional<Progress> progress() {
        requireMainThread();
        return active == null ? Optional.empty() : Optional.of(new Progress(active.stage, active.processed, active.total));
    }

    public List<PreviewChange> drainChanges() {
        requireMainThread();
        List<PreviewChange> result = new ArrayList<>(changes.values());
        if (changesOverflow) result.add(new PreviewChange(null, "ALL_PREVIEWS_CHANGED"));
        changes.clear();
        changesOverflow = false;
        return List.copyOf(result);
    }

    /** Called by the existing client tick. No provider/model/network request is made here. */
    public void tick() {
        requireMainThread();
        if (foreignThreadMutation) {
            foreignThreadMutation = false;
            failActive("SOURCE_CHANGED_OFF_THREAD");
            for (Owned value : owned.values()) queueChange(value.id, "PREVIEW_CHANGED");
        }
        Job<?> job = active;
        if (job == null) return;
        if (job.future.isCancelled()) { job.cancelIo(); active = null; return; }
        if (!job.context.equals(context())) { failActive("CONNECTION_CHANGED"); return; }
        if (System.nanoTime() - job.started > JOB_TIMEOUT_NANOS) { failActive("BRIDGE_JOB_TIMEOUT"); return; }
        long deadline = System.nanoTime() + TICK_NANOS;
        try {
            job.validate();
            for (int units = 0; active == job && units < UNITS_PER_TICK && System.nanoTime() < deadline; units++) {
                if (!job.step()) break;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            recordNativeFailure(failure);
            failActive(code(failure));
        }
    }

    public Outcome<Void> removePreview(PreviewHandle handle) {
        requireMainThread();
        Owned value = handle == null ? null : owned.get(handle.id());
        if (value == null) return failure("PREVIEW_NOT_OWNED");
        try {
            internalMutation++;
            boolean wasSelected = access.selected(value.manager) == value.placement;
            access.remove(value.manager, value.placement);
            for (var entry : owned.entrySet()) {
                Owned other = entry.getValue();
                if (other.previousSelection == value.placement && other.context.equals(value.context))
                    entry.setValue(other.withPrevious(value.previousSelection));
            }
            owned.remove(value.id);
            ownedCells -= value.cells;
            if (wasSelected && value.context.equals(context()) && access.manager() == value.manager)
                access.select(value.manager, value.previousSelection); // Native method rejects a stale nonmember.
            return success(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return failure("PREVIEW_CLEANUP_FAILED");
        } finally { internalMutation--; }
    }

    public Outcome<Void> clearOwnedPreviews() {
        requireMainThread();
        boolean failed = false;
        List<UUID> ids = new ArrayList<>(owned.keySet());
        java.util.Collections.reverse(ids);
        for (UUID id : ids)
            if (!removePreview(new PreviewHandle(id)).success()) failed = true;
        return failed ? failure("PREVIEW_CLEANUP_FAILED") : success(null);
    }

    /** Invoke on logout before native managers are torn down; repeat if cleanup reports failure. */
    public Outcome<Void> disconnect() {
        requireMainThread();
        failActive("CONNECTION_CLOSED");
        Outcome<Void> result = clearOwnedPreviews();
        changes.clear();
        changesOverflow = false;
        return result;
    }

    public void cancelActive() {
        requireMainThread();
        failActive("BRIDGE_CANCELLED");
    }

    private record Context(Object connection, String dimension) {}
    private Context context() {
        Minecraft mc = Minecraft.getInstance();
        return new Context(mc.getConnection(), mc.level == null ? "" : mc.level.dimension().location().toString());
    }
    private String ready() {
        if (!Minecraft.getInstance().isSameThread()) return "CLIENT_THREAD_REQUIRED";
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null || mc.hasSingleplayerServer())
            return "REMOTE_CLIENT_REQUIRED";
        probe();
        return access != null && (status.readiness() == Readiness.STATIC_API_MATCH
                || status.readiness() == Readiness.RUNTIME_CALLABLE) ? null : status.reason();
    }
    private static void requireMainThread() {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("CLIENT_THREAD_REQUIRED");
    }
    private static String version(String id) {
        return ModList.get().getModContainerById(id).map(m -> m.getModInfo().getVersion().toString()).orElse("");
    }
    private static Status state(Readiness readiness, String fm, String ml, String reason) {
        return new Status(readiness, fm, ml, 1, false, reason);
    }
    private void callable() {
        status = state(Readiness.RUNTIME_CALLABLE, status.forgematicaVersion(), status.mafglibVersion(), "CALL_SUCCEEDED_UNVERIFIED");
    }
    private void recordNativeFailure(Throwable failure) {
        if (failure instanceof ReflectiveOperationException || failure instanceof LinkageError)
            status = state(Readiness.FAILED, status.forgematicaVersion(), status.mafglibVersion(), "OPTIONAL_API_CALL_FAILED");
    }
    private <T> Outcome<T> failure(String code) { return new Outcome<>(false, code, null, status); }
    private <T> Outcome<T> success(T value) { return new Outcome<>(true, "OK", value, status); }
    private <T> CompletableFuture<Outcome<T>> completed(String code) { return CompletableFuture.completedFuture(failure(code)); }
    private void failActive(String code) {
        if (active == null) return;
        active.cancelIo();
        active.future.complete(failure(code));
        active = null;
    }
    private <T> CompletableFuture<T> submit(Callable<T> work) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try { return work.call(); }
                catch (Exception failure) { throw new CompletionException(failure); }
            }, io);
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            throw new IllegalStateException("BLUEPRINT_IO_QUEUE_FULL", rejected);
        }
    }
    private static String code(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        String message = failure.getMessage();
        return message != null && message.matches("[A-Z][A-Z0-9_]{1,95}") ? message
                : failure instanceof IOException ? "SOURCE_IO_FAILED" : "BRIDGE_OPERATION_FAILED";
    }

    private void nativeEvent(String event, Object placement) {
        if (!Minecraft.getInstance().isSameThread()) { foreignThreadMutation = true; return; }
        if (internalMutation != 0) return;
        if (active instanceof ImportJob job && job.snapshot.placement() == placement) job.invalidated = true;
        for (Owned value : List.copyOf(owned.values())) {
            if (value.placement != placement) continue;
            queueChange(value.id, event.equals("onPlacementRemoved") ? "PREVIEW_REMOVED" : "PREVIEW_CHANGED");
            if (event.equals("onPlacementRemoved")) { owned.remove(value.id); ownedCells -= value.cells; }
        }
    }

    private void queueChange(UUID id, String code) {
        if (!changes.containsKey(id) && changes.size() >= 64) {
            changes.remove(changes.keySet().iterator().next());
            changesOverflow = true;
        }
        changes.put(id, new PreviewChange(new PreviewHandle(id), code));
    }

    private abstract class Job<T> {
        final CompletableFuture<Outcome<T>> future = new CompletableFuture<>();
        final Context context;
        final long started = System.nanoTime();
        String stage;
        long processed, total;
        Job(Context context) { this.context = context; }
        void validate() throws ReflectiveOperationException {}
        abstract boolean step() throws ReflectiveOperationException;
        void cancelIo() {}
        void finish(T value) {
            callable();
            future.complete(success(value));
            active = null;
        }
    }

    private final class ImportJob extends Job<BlueprintDocument> {
        final ForgematicaAccess.Snapshot snapshot;
        final Owned trusted;
        final List<BlueprintDocument.Block> output = new ArrayList<>();
        final Map<BlueprintDocument.Position, BlueprintDocument.State> positions = new LinkedHashMap<>();
        final Map<String, List<BlockState>> palettes = new TreeMap<>();
        final Map<String, IdentityHashMap<BlockState, BlueprintDocument.State>> transformed = new TreeMap<>();
        CompletableFuture<LitematicSourceValidator.Source> reading;
        CompletableFuture<BlueprintDocument> finishing;
        LitematicSourceValidator.Source source;
        Iterator<Map.Entry<String, LitematicSourceValidator.Region>> paletteRegions;
        Map.Entry<String, LitematicSourceValidator.Region> paletteRegion;
        int paletteIndex, regionIndex, cell;
        long bytes = 2048;
        boolean invalidated;
        ImportJob(ForgematicaAccess.Snapshot snapshot, Owned trusted, Context context) {
            super(context); this.snapshot = snapshot; this.trusted = trusted; stage = "READING_SOURCE";
            total = snapshot.regions().stream().filter(ForgematicaAccess.Region::enabled).mapToLong(r -> r.transform().volume()).sum();
        }
        void begin() {
            if (trusted != null) { stage = "CAPTURING_CELLS"; return; }
            int dataVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
            reading = submit(() -> LitematicSourceValidator.read(snapshot.source(), dataVersion));
        }
        @Override void validate() throws ReflectiveOperationException {
            if (invalidated || access.manager() != snapshot.manager()
                    || access.selected(snapshot.manager()) != snapshot.placement()
                    || !snapshot.same(access.capture(snapshot.manager(), snapshot.placement())))
                throw new IllegalStateException("SELECTED_PLACEMENT_CHANGED");
        }
        @Override void cancelIo() {
            if (reading != null) reading.cancel(true);
            if (finishing != null) finishing.cancel(true);
        }
        @Override boolean step() throws ReflectiveOperationException {
            if (stage.equals("READING_SOURCE")) {
                if (!reading.isDone()) return false;
                source = reading.join();
                if (!source.regions().keySet().equals(snapshot.sizes().keySet()))
                    throw new IllegalStateException("SOURCE_REGION_MISMATCH");
                for (var entry : source.regions().entrySet()) {
                    if (!entry.getValue().signedSize().equals(ForgematicaAccess.position(snapshot.sizes().get(entry.getKey())))
                            || !entry.getValue().position().equals(ForgematicaAccess.position(snapshot.originalPositions().get(entry.getKey()))))
                        throw new IllegalStateException("SOURCE_REGION_MISMATCH");
                }
                paletteRegions = source.regions().entrySet().iterator();
                stage = "VALIDATING_PALETTE";
            }
            if (stage.equals("VALIDATING_PALETTE")) {
                if (paletteRegion == null || paletteIndex == paletteRegion.getValue().palette().size()) {
                    if (!paletteRegions.hasNext()) { stage = "CAPTURING_CELLS"; return true; }
                    paletteRegion = paletteRegions.next(); paletteIndex = 0;
                    palettes.put(paletteRegion.getKey(), new ArrayList<>());
                }
                palettes.get(paletteRegion.getKey()).add(BlueprintBlockStateCodec.resolve(paletteRegion.getValue().palette().get(paletteIndex++)));
                return true;
            }
            if (stage.equals("CAPTURING_CELLS")) {
                if (regionIndex == snapshot.regions().size()) {
                    if (output.isEmpty()) throw new IllegalArgumentException("BLUEPRINT_EMPTY");
                    stage = "VERIFYING_SOURCE";
                    // The list stops changing before being handed to IO for DTO validation/copying.
                    finishing = submit(() -> {
                        if (source != null) LitematicSourceValidator.verifyUnchanged(source.stamp());
                        return new BlueprintDocument(1, "Forgematica selection", "CLIENT_FORGEMATICA",
                                FORGEMATICA_VERSION, source == null ? "" : source.stamp().digest(),
                                ForgematicaAccess.position(snapshot.origin()), output);
                    });
                    return false;
                }
                var region = snapshot.regions().get(regionIndex);
                if (!region.enabled()) { regionIndex++; cell = 0; return true; }
                if (cell == region.transform().volume()) { regionIndex++; cell = 0; return true; }
                Vec3i size = region.transform().containerSize();
                int x = cell % size.getX(), z = cell / size.getX() % size.getZ(), y = cell / (size.getX() * size.getZ());
                BlockState actual = access.get(region.nativeContainer(), x, y, z);
                BlockState expected;
                if (trusted != null) {
                    if (!region.name().equals("fireflymc_task") || !region.signedSize().equals(trusted.size))
                        throw new IllegalStateException("OWNED_SOURCE_CHANGED");
                    expected = trusted.states.getOrDefault(new BlueprintDocument.Position(
                            trusted.min.x() + x, trusted.min.y() + y, trusted.min.z() + z), Blocks.STRUCTURE_VOID.defaultBlockState());
                } else {
                    expected = palettes.get(region.name()).get(source.regions().get(region.name()).paletteIndex(cell));
                }
                if (actual != expected) throw new IllegalStateException("SOURCE_CONTENT_MISMATCH");
                if (actual.getBlock() != Blocks.STRUCTURE_VOID) {
                    BlueprintDocument.State normalized = transformed.computeIfAbsent(region.name(), ignored -> new IdentityHashMap<>())
                            .computeIfAbsent(actual, state -> BlueprintBlockStateCodec.describe(region.transform().transformState(state)));
                    BlueprintDocument.Position world = ForgematicaAccess.position(region.transform().worldPosition(x, y, z));
                    BlueprintDocument.checkWorld(world);
                    var relative = world.subtract(ForgematicaAccess.position(snapshot.origin()));
                    var previous = positions.putIfAbsent(relative, normalized);
                    if (previous != null && !previous.equals(normalized)) throw new IllegalStateException("BLUEPRINT_REGION_CONFLICT");
                    if (previous == null) {
                        var block = new BlueprintDocument.Block(relative, normalized);
                        bytes += block.estimatedBytes();
                        if (bytes > BlueprintDocument.MAX_ENCODED_BYTES) throw new IllegalArgumentException("BLUEPRINT_DATA_LIMIT");
                        output.add(block);
                    }
                }
                cell++; processed++;
                return true;
            }
            if (stage.equals("VERIFYING_SOURCE")) {
                if (!finishing.isDone()) return false;
                finish(finishing.join());
            }
            return false;
        }
    }

    private final class PreviewJob extends Job<PreviewHandle> {
        final BlueprintDocument document;
        final Object manager, previousSelection;
        final Map<BlueprintDocument.Position, BlockState> states = new LinkedHashMap<>();
        final Map<BlueprintDocument.State, BlockState> stateCache = new LinkedHashMap<>();
        int next, nonAir;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        BlockPos size, worldOrigin;
        BlueprintDocument.Position min;
        Object schematic, cells;
        PreviewJob(BlueprintDocument document, Object manager, Object previousSelection, Context context) {
            super(context); this.document = document; this.manager = manager; this.previousSelection = previousSelection;
            stage = "VALIDATING_DOCUMENT"; total = document.blocks().size();
        }
        @Override void validate() throws ReflectiveOperationException {
            if (access.manager() != manager) throw new IllegalStateException("CONNECTION_CHANGED");
        }
        @Override boolean step() throws ReflectiveOperationException {
            if (stage.equals("VALIDATING_DOCUMENT")) {
                if (next < document.blocks().size()) {
                    var block = document.blocks().get(next++);
                    BlockState state = stateCache.computeIfAbsent(block.state(), BlueprintBlockStateCodec::resolve);
                    states.put(block.position(), state);
                    if (!state.isAir() && state.getBlock() != Blocks.STRUCTURE_VOID) nonAir++;
                    var p = block.position();
                    minX = Math.min(minX, p.x()); minY = Math.min(minY, p.y()); minZ = Math.min(minZ, p.z());
                    maxX = Math.max(maxX, p.x()); maxY = Math.max(maxY, p.y()); maxZ = Math.max(maxZ, p.z());
                    if ((long) maxX - minX >= LitematicSourceValidator.MAX_AXIS
                            || (long) maxY - minY >= LitematicSourceValidator.MAX_AXIS
                            || (long) maxZ - minZ >= LitematicSourceValidator.MAX_AXIS)
                        throw new IllegalArgumentException("PREVIEW_AXIS_LIMIT");
                    processed++;
                    return true;
                }
                size = new BlockPos(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
                long volume = (long) size.getX() * size.getY() * size.getZ();
                if (volume + ownedCells > BlueprintDocument.MAX_BLOCKS) throw new IllegalArgumentException("PREVIEW_CELL_LIMIT");
                min = new BlueprintDocument.Position(minX, minY, minZ);
                var start = document.origin().add(min);
                BlueprintDocument.checkWorld(start);
                worldOrigin = new BlockPos(start.x(), start.y(), start.z());
                schematic = access.empty("FireflyMC " + document.name(), size);
                cells = access.container(schematic);
                next = 0; processed = 0; total = volume; stage = "FILLING_PREVIEW";
                return true;
            }
            if (stage.equals("FILLING_PREVIEW")) {
                if (next < total) {
                    int x = next % size.getX(), z = next / size.getX() % size.getZ(), y = next / (size.getX() * size.getZ());
                    BlockState state = states.getOrDefault(new BlueprintDocument.Position(minX + x, minY + y, minZ + z),
                            Blocks.STRUCTURE_VOID.defaultBlockState());
                    access.set(cells, x, y, z, state);
                    next++; processed++;
                    return true;
                }
                UUID id = UUID.randomUUID();
                Object placement = access.placement(schematic, worldOrigin, "FireflyMC " + document.name(), id, nonAir);
                Owned value = new Owned(id, context, manager, placement, schematic, previousSelection,
                        min, size, total, states);
                internalMutation++;
                try {
                    owned.put(id, value); ownedCells += total;
                    access.add(manager, placement);
                    access.select(manager, placement);
                    if (access.selected(manager) != placement)
                        throw new IllegalStateException("PREVIEW_REGISTRATION_REJECTED");
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    removePreview(new PreviewHandle(id));
                    throw failure;
                } finally { internalMutation--; }
                if (future.isCancelled()) { removePreview(new PreviewHandle(id)); active = null; return false; }
                finish(new PreviewHandle(id));
            }
            return false;
        }
    }

    private record Owned(UUID id, Context context, Object manager, Object placement, Object schematic,
                         Object previousSelection, BlueprintDocument.Position min, BlockPos size,
                         long cells, Map<BlueprintDocument.Position, BlockState> states) {
        Owned withPrevious(Object previous) {
            return new Owned(id, context, manager, placement, schematic, previous, min, size, cells, states);
        }
    }
}
