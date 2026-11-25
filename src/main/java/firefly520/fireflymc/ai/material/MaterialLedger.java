package firefly520.fireflymc.ai.material;

import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Durable material ownership, shared physical-pool reservations and escrow.
 * Call from bounded storage workers, never while holding the Minecraft thread.
 * Each world-side step is preceded by a durable intent; an unresolved intent is never replayed.
 * This ledger cannot make inventories and disk atomic and therefore retains explicit uncertainty.
 */
public final class MaterialLedger {
    public enum State {
        RESERVED, EXTRACT_INTENT, ESCROWED, TRANSFORM_INTENT, PRODUCED,
        CALLBACK_INTENT, CALLBACK_DONE, DELIVER_INTENT, COMPLETED, CANCELLED, RECOVERY_REQUIRED, WORLD_INTENT, EXTERNAL_INTENT
    }
    public record Stock(String sourceId, long sourceRevision, String backendId, String poolId, int slot,
                        MaterialStack stack, long unitsPerItem, long poolUnits, int extractableCount, int maxTransferCount) {
        public Stock(String sourceId, long sourceRevision, String backendId, String poolId, int slot,
                     MaterialStack stack, long unitsPerItem, long poolUnits, int extractableCount) {
            this(sourceId, sourceRevision, backendId, poolId, slot, stack, unitsPerItem, poolUnits, extractableCount, extractableCount);
        }
        public Stock {
            if (maxTransferCount == 0) maxTransferCount = extractableCount;
            if (sourceId == null || sourceId.isBlank() || backendId == null || backendId.isBlank()
                    || poolId == null || poolId.isBlank() || sourceRevision < 0 || slot < 0
                    || stack == null || unitsPerItem < 1 || poolUnits < 0 || extractableCount < 0 || maxTransferCount < 0
                    || extractableCount > stack.count()
                    || Math.multiplyExact((long) extractableCount, unitsPerItem) > poolUnits)
                throw new IllegalArgumentException("MATERIAL_STOCK_INVALID");
        }
        public String key() { return poolId + "\n" + stack.fingerprint(); }
    }
    public record Need(String stockKey, int count) {
        public Need { if (stockKey == null || count <= 0) throw new IllegalArgumentException("MATERIAL_NEED_INVALID"); }
    }
    public record Claim(Stock stock, int requested, int extracted) {
        public Claim {
            if (stock == null || requested < 1 || extracted < 0 || extracted > requested)
                throw new IllegalArgumentException("MATERIAL_CLAIM_INVALID");
        }
        public int remaining() { return requested - extracted; }
        public long reservedUnits() { return Math.multiplyExact((long) remaining(), stock.unitsPerItem()); }
    }
    /** Intended native call and before-state, saved before dispatch. No client can create this evidence. */
    public record Intent(String kind, int index, int count, String witness, MaterialStack before,
                         long poolUnitsBefore, List<MaterialStack> materials) {
        public Intent { materials = materials == null ? List.of() : List.copyOf(materials); }
        public Intent(String kind, int index, int count, String witness, MaterialStack before, long poolUnitsBefore) {
            this(kind, index, count, witness, before, poolUnitsBefore, List.of());
        }
    }
    public record ExternalDebit(UUID effectId, String kind, boolean known, boolean accepted,
                                List<MaterialStack> requestedInputs, List<MaterialStack> actualReturns, String receipt) {
        public ExternalDebit {
            Objects.requireNonNull(effectId); Objects.requireNonNull(kind);
            requestedInputs = List.copyOf(requestedInputs); actualReturns = List.copyOf(actualReturns);
            if (receipt == null || !receipt.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("EXTERNAL_RECEIPT_REQUIRED");
        }
    }
    public record WorldDebit(UUID stepId, int count, String itemFingerprint, String receipt) {
        public WorldDebit {
            if (stepId == null || count < 0 || count > 1 || itemFingerprint == null || !itemFingerprint.matches("[0-9a-f]{64}")
                    || receipt == null || !receipt.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("WORLD_MATERIAL_PROOF_INVALID");
        }
    }
    /** A native call's save obligation. An in-memory return is not a disk acknowledgement. */
    public record NativeSave(String kind, String witness, Set<String> pools, String receipt) {
        public NativeSave {
            Objects.requireNonNull(kind); Objects.requireNonNull(witness);
            pools = Collections.unmodifiableSet(new TreeSet<>(pools)); receipt = receipt == null ? "" : receipt;
            if (!receipt.isEmpty() && !receipt.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("NATIVE_SAVE_RECEIPT_INVALID");
        }
    }
    public record Operation(int version, UUID operationId, String owner, UUID taskId, String planHash,
                            String policy, State state, List<Claim> claims, List<MaterialStack> escrow,
                            List<MaterialStack> products, List<Integer> delivered, Intent intent,
                            String reason, List<MaterialStack> sampledOutputs, String samplingEvidence, List<WorldDebit> worldDebits,
                            List<ExternalDebit> externalDebits, List<NativeSave> nativeSaves, long revision) {
        public Operation {
            claims = List.copyOf(claims); escrow = List.copyOf(escrow);
            products = List.copyOf(products); delivered = List.copyOf(delivered);
            sampledOutputs = sampledOutputs == null ? List.of() : List.copyOf(sampledOutputs);
            samplingEvidence = samplingEvidence == null ? "" : samplingEvidence;
            worldDebits = worldDebits == null ? List.of() : List.copyOf(worldDebits);
            externalDebits = externalDebits == null ? List.of() : List.copyOf(externalDebits);
            nativeSaves = nativeSaves == null ? List.of() : List.copyOf(nativeSaves);
        }
        public boolean settled() { return state == State.COMPLETED || state == State.CANCELLED; }
        public boolean uncertain() { return state == State.RECOVERY_REQUIRED; }
        public boolean saved() {
            // Old journals retain their history, but do not acquire a fabricated native save proof.
            boolean effects = claims.stream().anyMatch(c -> c.extracted() > 0) || !worldDebits.isEmpty()
                    || !externalDebits.isEmpty() || delivered.stream().anyMatch(count -> count > 0);
            return (!effects || !nativeSaves.isEmpty()) && nativeSaves.stream().allMatch(s -> !s.receipt().isEmpty());
        }
        public boolean inputsSaved() {
            var extractions = nativeSaves.stream().filter(save -> save.kind().equals("extract")).toList();
            return (claims.stream().noneMatch(claim -> claim.extracted() > 0) || !extractions.isEmpty())
                    && extractions.stream().allMatch(save -> !save.receipt().isEmpty());
        }
        public List<MaterialStack> remainingProducts() {
            var result = new ArrayList<MaterialStack>();
            for (int i = 0; i < products.size(); i++) {
                int left = products.get(i).count() - delivered.get(i);
                if (left > 0) result.add(products.get(i).withCount(left));
            }
            return List.copyOf(result);
        }
    }

    private final Path workspaceRoot;
    private final Map<String, ConversationStore> journals = new HashMap<>();
    private final Map<UUID, Operation> operations = new LinkedHashMap<>();
    private final Map<UUID, Operation> lastKnownOperations = new HashMap<>();
    private final Set<UUID> canonicalBaselines = new HashSet<>();
    private volatile boolean broken;
    private final MixedResourceOwnership mixedOwnership = new MixedResourceOwnership();
    public boolean journalAvailable() { return !broken; }
    void bindMixed(String owner, UUID id, String receipt) { mixedOwnership.bind(owner, id, receipt); }
    void bindMixed(String owner, UUID id, String receipt, boolean inputClosed) { mixedOwnership.bind(owner, id, receipt, inputClosed); }
    private volatile Set<String> quarantinedScopes = Set.of();
    /** A trusted non-processing native controller owns these locks; no material or cost policy is invented. */
    public static final class NativeScopeLease {
        private final UUID owner, operationId, generation;
        private final Set<String> backendIds, scopes;
        private final boolean recovered;
        private NativeScopeLease(UUID owner, UUID operationId, Collection<String> backends, boolean recovered) {
            this.owner = owner; this.operationId = operationId; generation = UUID.randomUUID(); this.recovered = recovered;
            backendIds = Set.copyOf(backends); scopes = backendIds.stream().map(MaterialSourceScope::key).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        public UUID owner() { return owner; }
        public UUID operationId() { return operationId; }
        public UUID generation() { return generation; }
        public Set<String> backendIds() { return backendIds; }
        public boolean recovered() { return recovered; }
    }
    private final Map<UUID, NativeScopeLease> nativeScopes = new HashMap<>();
    private volatile boolean nativeScopeRestoreFailed;
    /** Fixed world footprint held by an asynchronous native job, independently of material fees. */
    public static final class NativeWorldScopeLease {
        private final UUID owner, operationId, generation = UUID.randomUUID();
        private final String dimension;
        private final Set<Long> positions;
        private final Set<String> keys;
        private final boolean recovered;
        private NativeWorldScopeLease(UUID owner, UUID operation, String dimension, Collection<Long> cells, boolean recovered) {
            this.owner = owner; operationId = operation; this.dimension = dimension; this.recovered = recovered;
            positions = Set.copyOf(cells); keys = positions.stream().map(cell -> packedWorldScope(dimension, cell)).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        public UUID owner() { return owner; }
        public UUID operationId() { return operationId; }
        public UUID generation() { return generation; }
        public String dimension() { return dimension; }
        public Set<Long> positions() { return positions; }
        public boolean recovered() { return recovered; }
    }
    private final Map<UUID, NativeWorldScopeLease> nativeWorldScopes = new HashMap<>();
    private volatile List<NativeWorldScopeLease> nativeWorldScopeIndex = List.of();
    public synchronized NativeWorldScopeLease acquireNativeWorldScope(Actor actor, UUID operation, String dimension, Collection<Long> cells) {
        actor.requireAccess(actor.owner()); requireNativeResourceAdmission();
        return worldScope(UUID.fromString(actor.owner()), operation, dimension, cells, false);
    }
    /** Restores only a negative world fence; this token can never authorize replay. */
    public synchronized NativeWorldScopeLease restoreNativeWorldScope(UUID owner, UUID operation, String dimension, Collection<Long> cells) {
        try { return worldScope(Objects.requireNonNull(owner), operation, dimension, cells, true); }
        catch (RuntimeException invalid) { nativeScopeRestoreFailed = true; throw invalid; }
    }
    private NativeWorldScopeLease worldScope(UUID owner, UUID operation, String dimension, Collection<Long> cells, boolean restore) {
        if (broken) throw new IllegalStateException("MATERIAL_JOURNAL_UNAVAILABLE");
        if (operation == null || dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || dimension.contains("..")
                || cells == null || cells.isEmpty() || cells.size() > 16384 || cells.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("NATIVE_WORLD_SCOPE_INVALID");
        var previous = nativeWorldScopes.get(operation);
        if (previous != null && (!previous.owner.equals(owner) || !previous.dimension.equals(dimension))) throw new SecurityException("NATIVE_WORLD_OPERATION_REUSED");
        if (!restore && previous != null && previous.recovered) throw new IllegalStateException("NATIVE_WORLD_SCOPE_REQUIRES_RECONCILIATION");
        var union = new HashSet<>(cells); if (previous != null) union.addAll(previous.positions);
        long total = union.size() + nativeWorldScopes.values().stream().filter(value -> value != previous).mapToLong(value -> value.positions.size()).sum();
        if (union.size() > 16384 || total > 131072 || previous == null && nativeWorldScopes.size() >= 64) throw new IllegalStateException("NATIVE_WORLD_SCOPE_LIMIT");
        if (!restore) requireNativeScopesFree(operation, cells.stream().map(cell -> packedWorldScope(dimension, cell)).collect(java.util.stream.Collectors.toSet()));
        if (previous != null && previous.positions.equals(union)) return previous;
        var next = new NativeWorldScopeLease(owner, operation, dimension, union, restore || previous != null && previous.recovered);
        nativeWorldScopes.put(operation, next); nativeWorldScopeIndex = List.copyOf(nativeWorldScopes.values()); return next;
    }
    /** Lock-free per-cell check: no ledger monitor or journal IO may run inside a native block mutation. */
    public void requireNativeWorldScope(Actor actor, NativeWorldScopeLease lease, long position) {
        actor.requireAccess(lease.owner.toString()); requireNativeResourceAdmission();
        var index = nativeWorldScopeIndex;
        if (broken || !index.contains(lease)) throw new IllegalStateException("NATIVE_WORLD_LEASE_CHANGED");
        if (lease.recovered) throw new IllegalStateException("NATIVE_WORLD_SCOPE_REQUIRES_RECONCILIATION");
        if (!lease.positions.contains(position)) throw new SecurityException("NATIVE_WORLD_POSITION_NOT_HELD");
        if (quarantinedScopes.contains(packedWorldScope(lease.dimension, position))) throw new IllegalStateException("NATIVE_WORLD_SCOPE_REQUIRES_RECONCILIATION");
        for (var other : index) if (other != lease && other.dimension.equals(lease.dimension) && other.positions.contains(position))
            throw new IllegalStateException("NATIVE_WORLD_SCOPE_BUSY");
    }
    public void requireNativeWorldScope(Actor actor, NativeWorldScopeLease lease) {
        for (long position : lease.positions) requireNativeWorldScope(actor, lease, position);
    }
    public synchronized void releaseNativeWorldScope(Actor actor, NativeWorldScopeLease lease) {
        actor.requireAccess(lease.owner.toString());
        if (nativeWorldScopes.get(lease.operationId) != lease) throw new IllegalStateException("NATIVE_WORLD_LEASE_CHANGED");
        nativeWorldScopes.remove(lease.operationId); nativeWorldScopeIndex = List.copyOf(nativeWorldScopes.values());
    }
    private boolean nativeWorldHeld(String key) {
        for (var lease : nativeWorldScopeIndex) if (lease.keys.contains(key)) return true;
        return false;
    }
    private static String packedWorldScope(String dimension, long position) {
        return worldScope(dimension, (int) (position >> 38), (int) (position << 52 >> 52), (int) (position << 26 >> 38));
    }

    /** Atomic admission before a native callback may touch shared physical inventories. */
    public synchronized NativeScopeLease acquireNativeScope(Actor actor, UUID operationId, Collection<String> backendIds) {
        actor.requireAccess(actor.owner());
        return nativeScope(UUID.fromString(actor.owner()), operationId, backendIds, false);
    }
    /** Negative startup quarantine from a validated existing journal, including owners who are offline. */
    public synchronized NativeScopeLease restoreNativeScope(UUID owner, UUID operationId, Collection<String> backendIds) {
        try { return nativeScope(Objects.requireNonNull(owner), operationId, backendIds, true); }
        catch (RuntimeException invalidJournal) { nativeScopeRestoreFailed = true; throw invalidJournal; }
    }
    public void quarantineNativeScopeRestoration() { nativeScopeRestoreFailed = true; }
    private void requireNativeResourceAdmission() {
        if (nativeScopeRestoreFailed) throw new IllegalStateException("NATIVE_RESOURCE_RESTORE_REQUIRES_RECONCILIATION");
    }
    private NativeScopeLease nativeScope(UUID owner, UUID operationId, Collection<String> backends, boolean restore) {
        if (broken) throw new IllegalStateException("MATERIAL_JOURNAL_UNAVAILABLE");
        if (operationId == null || backends == null || backends.isEmpty() || backends.size() > 32
                || backends.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 1024 || id.chars().anyMatch(Character::isISOControl)))
            throw new IllegalArgumentException("NATIVE_RESOURCE_SCOPE_INVALID");
        if (operations.containsKey(operationId)) throw new IllegalStateException("NATIVE_RESOURCE_OPERATION_COLLISION");
        var previous = nativeScopes.get(operationId);
        if (previous != null && !previous.owner.equals(owner)) throw new SecurityException("NATIVE_RESOURCE_OPERATION_REUSED");
        var union = new HashSet<>(backends); if (previous != null) union.addAll(previous.backendIds);
        if (union.size() > 32) throw new IllegalStateException("NATIVE_RESOURCE_SCOPE_LIMIT");
        if (previous == null && nativeScopes.size() >= 256) throw new IllegalStateException("NATIVE_RESOURCE_SCOPE_LIMIT");
        var next = new NativeScopeLease(owner, operationId, union, restore || previous != null && previous.recovered);
        if (!restore) {
            requireNativeResourceAdmission();
            requireNativeScopesFree(operationId, backends.stream().map(MaterialSourceScope::key).collect(java.util.stream.Collectors.toSet()));
        }
        if (previous != null && previous.backendIds.equals(union)) return previous;
        nativeScopes.put(operationId, next); return next;
    }
    private void requireNativeScopesFree(UUID operationId, Set<String> touched) {
        for (var other : nativeScopes.values()) if (!other.operationId.equals(operationId) && !Collections.disjoint(other.scopes, touched))
            throw new IllegalStateException("NATIVE_RESOURCE_SCOPE_BUSY");
        for (var scope : touched) if (quarantinedScopes.contains(scope)) throw new IllegalStateException("NATIVE_RESOURCE_SCOPE_REQUIRES_RECONCILIATION");
        for (var other : nativeWorldScopeIndex) if (!other.operationId.equals(operationId) && !Collections.disjoint(other.keys, touched))
            throw new IllegalStateException("NATIVE_WORLD_SCOPE_BUSY");
        for (var op : operations.values()) if (!op.settled() || !op.saved()) {
            boolean claims = op.claims().stream().anyMatch(claim -> touched.contains(MaterialSourceScope.key(claim.stock().backendId()))
                    && (claim.remaining() > 0 || op.uncertain() || !op.saved()));
            boolean pending = op.nativeSaves().stream().filter(save -> save.receipt().isEmpty()).flatMap(save -> save.pools().stream())
                    .anyMatch(pool -> touched.contains(pool) || touched.contains(MaterialSourceScope.key(pool)));
            if (claims || pending) throw new IllegalStateException("NATIVE_RESOURCE_SCOPE_RESERVED");
        }
    }
    public synchronized void requireNativeScope(Actor actor, NativeScopeLease lease) {
        requireNativeScope(actor, lease, lease.backendIds);
    }
    public synchronized void requireNativeScope(Actor actor, NativeScopeLease lease, Collection<String> touchedBackendIds) {
        actor.requireAccess(lease.owner.toString());
        requireNativeResourceAdmission();
        if (broken || nativeScopes.get(lease.operationId) != lease) throw new IllegalStateException("NATIVE_RESOURCE_LEASE_CHANGED");
        if (touchedBackendIds == null || touchedBackendIds.isEmpty() || touchedBackendIds.size() > 32
                || touchedBackendIds.stream().anyMatch(id -> id == null || !lease.scopes.contains(MaterialSourceScope.key(id))))
            throw new SecurityException("NATIVE_RESOURCE_SCOPE_NOT_HELD");
        requireNativeScopesFree(lease.operationId, touchedBackendIds.stream().map(MaterialSourceScope::key).collect(java.util.stream.Collectors.toSet()));
    }
    /** The owning controller releases only after reliable no-dispatch or its positive native settlement. */
    public synchronized void releaseNativeScope(Actor actor, NativeScopeLease lease) {
        actor.requireAccess(lease.owner.toString());
        if (nativeScopes.get(lease.operationId) != lease) throw new IllegalStateException("NATIVE_RESOURCE_LEASE_CHANGED");
        nativeScopes.remove(lease.operationId);
    }
    private boolean nativeScopeHeld(String backend) {
        String scope = MaterialSourceScope.key(backend);
        return nativeWorldHeld(scope) || nativeScopes.values().stream().anyMatch(lease -> lease.scopes.contains(scope));
    }

    public MaterialLedger(Path aiRoot) throws IOException {
        workspaceRoot = aiRoot.toAbsolutePath().normalize().resolve("workspace");
        if (Files.isSymbolicLink(workspaceRoot)) throw new IOException("MATERIAL_UNSAFE_PATH");
        if (Files.exists(workspaceRoot)) try (var entries = Files.list(workspaceRoot)) {
            for (Path directory : entries.toList()) {
                try { UUID.fromString(directory.getFileName().toString()); }
                catch (IllegalArgumentException ignored) { continue; }
                if (Files.exists(directory.resolve("materials/journal.jsonl"))) open(directory.getFileName().toString());
            }
        }
        // A durable intent may already have executed in Minecraft. Even identical current counts
        // cannot establish otherwise, because another actor might have moved/refilled the source.
        for (Operation operation : List.copyOf(operations.values())) {
            if (operation.settled() && !operation.saved())
                persist(copy(operation, State.RECOVERY_REQUIRED, operation.claims(), operation.escrow(),
                        operation.products(), operation.delivered(), operation.intent(), "NATIVE_SAVE_NOT_CONFIRMED"));
            else if (!operation.uncertain() && !operation.settled() && (operation.intent() != null || !operation.escrow().isEmpty()
                    || !operation.products().isEmpty() || !operation.nativeSaves().isEmpty()
                    || operation.claims().stream().anyMatch(c -> c.extracted() > 0)))
                persist(copy(operation, State.RECOVERY_REQUIRED, operation.claims(), operation.escrow(),
                        operation.products(), operation.delivered(), operation.intent(), "RESTART_UNCERTAIN"));
        }
        publishQuarantine();
    }

    private ConversationStore open(String owner) throws IOException {
        UUID.fromString(owner);
        var existing = journals.get(owner);
        if (existing != null) return existing;
        Path directory = workspaceRoot.resolve(owner);
        Path materials = directory.resolve("materials"), file = materials.resolve("journal.jsonl");
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(materials) || Files.isSymbolicLink(file))
            throw new IOException("MATERIAL_UNSAFE_PATH");
        var store = new ConversationStore(file);
        for (var event : store.getSnapshot(0).events()) {
            if (!Set.of(MaterialJournalCodec.FULL, MaterialJournalCodec.DELTA).contains(event.kind())) throw new IOException("MATERIAL_JOURNAL_EVENT");
            var payload = event.data();
            UUID id;
            try { id = UUID.fromString(payload.get("operationId").getAsString()); }
            catch (RuntimeException invalid) { throw new IOException("MATERIAL_JOURNAL_IDENTITY", invalid); }
            Operation previous = operations.get(id);
            var restored = MaterialJournalCodec.decode(previous == null ? null : AiJson.GSON.toJsonTree(previous).getAsJsonObject(), event.kind(), payload);
            Operation op = AiJson.GSON.fromJson(restored, Operation.class);
            if (op.version() != 1 || !owner.equals(op.owner()) || !op.policy().equals("MATERIALS_ONLY")
                    || op.revision() != (previous == null ? 1 : previous.revision() + 1)
                    || previous != null && !previous.owner().equals(owner))
                throw new IOException("MATERIAL_JOURNAL_IDENTITY");
            operations.put(op.operationId(), op);
            if (!op.uncertain()) lastKnownOperations.put(op.operationId(), op);
        }
        journals.put(owner, store);
        return store;
    }

    public synchronized List<Operation> list(Actor actor) {
        actor.requireAccess(actor.owner());
        return operations.values().stream().filter(op -> op.owner().equals(actor.owner())).toList();
    }
    public synchronized Operation get(Actor actor, UUID id) {
        actor.requireAccess(actor.owner());
        Operation op = operations.get(id);
        if (op == null || !op.owner().equals(actor.owner())) throw new SecurityException("MATERIAL_OPERATION_NOT_FOUND");
        return op;
    }
    public synchronized boolean settled(String owner) {
        return !broken && operations.values().stream().filter(op -> op.owner().equals(owner))
                .allMatch(op -> op.settled() && op.saved());
    }
    public synchronized boolean settledForTask(Actor actor, UUID task) {
        actor.requireAccess(actor.owner());
        return !broken && list(actor).stream().filter(op -> op.taskId().equals(task)).allMatch(op -> op.settled() && op.saved());
    }
    public synchronized String exportJournal(Actor actor) {
        actor.requireAccess(actor.owner());
        var journal = journals.get(actor.owner());
        return AiJson.GSON.toJson(journal == null ? List.of() : journal.getSnapshot(0).events());
    }
    public synchronized void forgetSettled(Actor actor) {
        actor.requireAccess(actor.owner());
        if (!settled(actor.owner())) throw new IllegalStateException("MATERIALS_REQUIRE_SETTLEMENT");
        operations.values().removeIf(op -> op.owner().equals(actor.owner()));
        canonicalBaselines.removeIf(id -> !operations.containsKey(id));
        lastKnownOperations.values().removeIf(op -> op.owner().equals(actor.owner()));
        journals.remove(actor.owner());
        mixedOwnership.forget(actor.owner());
    }

    /** Deduplicates aliases and subtracts reservations across all owners sharing the same native pool. */
    public synchronized Map<String, Long> available(List<Stock> observed) {
        var stocks = normalize(observed);
        Map<String, Long> reserved = reserved();
        Map<String, Long> result = new LinkedHashMap<>();
        for (Stock stock : stocks.values()) {
            long units = reserved.containsKey(MaterialSourceScope.key(stock.backendId())) || nativeWorldHeld(MaterialSourceScope.key(stock.backendId())) ? 0
                    : Math.max(0, stock.poolUnits() - reserved.getOrDefault(stock.poolId(), 0L));
            result.put(stock.key(), Math.min((long) stock.extractableCount(), units / stock.unitsPerItem()));
        }
        return Collections.unmodifiableMap(result);
    }

    public synchronized Map<String, Long> availablePools(List<Stock> observed) {
        var reserved = reserved();
        var result = new HashMap<String, Long>();
        for (Stock stock : normalize(observed).values())
            result.put(stock.poolId(), reserved.containsKey(MaterialSourceScope.key(stock.backendId())) || nativeWorldHeld(MaterialSourceScope.key(stock.backendId())) ? 0
                    : Math.max(0, stock.poolUnits() - reserved.getOrDefault(stock.poolId(), 0L)));
        return Map.copyOf(result);
    }
    public synchronized Optional<Operation> find(Actor actor, UUID id) {
        actor.requireAccess(actor.owner());
        Operation op = operations.get(id);
        if (op != null && !op.owner().equals(actor.owner())) throw new SecurityException("MATERIAL_OPERATION_NOT_FOUND");
        return Optional.ofNullable(op);
    }

    synchronized void approveMixedRemainingInputs(Actor actor, UUID id, String receipt) {
        mixedOwnership.approveRemainingInputs(actor, id, receipt);
    }

    public synchronized Operation reserve(Actor actor, UUID task, UUID id, String planHash,
                                          List<Stock> observed, List<Need> needs) throws IOException {
        actor.requireAccess(actor.owner());
        if (!operations.containsKey(id)) mixedOwnership.inputOpen(actor, id);
        if (broken) throw new IOException("MATERIAL_JOURNAL_UNAVAILABLE");
        if (task == null || id == null || planHash == null || !planHash.matches("[0-9a-f]{64}")
                || needs.isEmpty() || needs.size() > 81) throw new IllegalArgumentException("MATERIAL_PLAN_INVALID");
        Operation duplicate = operations.get(id);
        if (duplicate != null) {
            if (!duplicate.owner().equals(actor.owner()) || !duplicate.taskId().equals(task)
                    || !duplicate.planHash().equals(planHash)) throw new SecurityException("MATERIAL_OPERATION_REUSED");
            return duplicate; // The caller receives the previous state; it must not repeat native effects.
        }
        if (operations.values().stream().filter(op -> op.owner().equals(actor.owner()) && !op.settled()).count() >= 32)
            throw new IllegalStateException("MATERIAL_PENDING_LIMIT");
        var stocks = normalize(observed);
        var used = reserved();
        var variants = new HashMap<String, Integer>();
        var claims = new ArrayList<Claim>();
        for (Need need : needs) {
            Stock stock = stocks.get(need.stockKey());
            if (stock == null || stock.stack().empty()) throw new IllegalStateException("MATERIAL_SOURCE_CHANGED");
            if (nativeScopeHeld(stock.backendId())) throw new IllegalStateException("NATIVE_RESOURCE_SCOPE_BUSY");
            int count = Math.addExact(variants.getOrDefault(need.stockKey(), 0), need.count());
            if (count > stock.extractableCount()) throw new IllegalStateException("MATERIAL_MISSING");
            variants.put(need.stockKey(), count);
            long units = Math.multiplyExact(stock.unitsPerItem(), (long) need.count());
            if (used.getOrDefault(stock.poolId(), 0L) == Long.MAX_VALUE || used.containsKey(MaterialSourceScope.key(stock.backendId())))
                throw new IllegalStateException("MATERIAL_POOL_REQUIRES_RECONCILIATION");
            long total = Math.addExact(used.getOrDefault(stock.poolId(), 0L), units);
            if (total > stock.poolUnits()) throw new IllegalStateException("MATERIAL_RESERVED_OR_MISSING");
            used.put(stock.poolId(), total);
            claims.add(new Claim(stock, need.count(), 0));
        }
        return persist(new Operation(1, id, actor.owner(), task, planHash, "MATERIALS_ONLY", State.RESERVED,
                claims, List.of(), List.of(), List.of(), null, "", List.of(), "", List.of(), List.of(), List.of(), 1));
    }
    /**
     * Move already-created native loot into the material account. Native survival costs are in
     * RemovalJournal; MATERIALS_ONLY here describes material custody, not a mining cost exemption.
     * Lock order is workspace -> material ledger, matching explicit privacy deletion.
     */
    public Operation adoptHarvest(Actor actor, firefly520.fireflymc.ai.blueprint.RemovalJournal journal, UUID id) throws IOException {
        return journal.accountHarvest(actor, id, (step, receipt) -> adoptHarvest(actor, step, receipt));
    }
    /** Reserve no material, but reject a new irreversible world stage before its custody budget is exhausted. */
    public synchronized void requireHarvestCapacity(Actor actor) throws IOException {
        actor.requireAccess(actor.owner()); if (broken) throw new IOException("MATERIAL_JOURNAL_UNAVAILABLE");
        if (operations.values().stream().filter(op -> op.owner().equals(actor.owner()) && !op.settled()).count() >= 32)
            throw new IllegalStateException("MATERIAL_PENDING_LIMIT");
    }
    private synchronized Operation adoptHarvest(Actor actor, firefly520.fireflymc.ai.blueprint.RemovalJournal.Step step, String receipt) throws IOException {
        actor.requireAccess(step.owner()); if (broken) throw new IOException("MATERIAL_JOURNAL_UNAVAILABLE");
        var input = step.input(); var previous = operations.get(input.operationId());
        String source = "native_block_harvest:" + receipt;
        if (previous != null) {
            if (!previous.owner().equals(actor.owner()) || !previous.taskId().equals(input.taskId()) || !previous.planHash().equals(input.planHash())
                    || !previous.samplingEvidence().equals(source)) throw new SecurityException("MATERIAL_OPERATION_REUSED");
            return previous;
        }
        requireHarvestCapacity(actor);
        var outputs = step.evidence().drops().stream().map(firefly520.fireflymc.ai.blueprint.RemovalJournal.Drop::stack).toList();
        var pools = new TreeSet<String>(); pools.add(MaterialSourceScope.key("player:" + actor.owner()));
        input.cells().stream().filter(cell -> cell.target() != null).forEach(cell -> pools.add(worldScope(input.dimension(), cell.position().x(), cell.position().y(), cell.position().z())));
        String proof = step.saved() ? AiJson.digest(AiJson.canonical(step.saves())) : "";
        return persist(new Operation(1, input.operationId(), actor.owner(), input.taskId(), input.planHash(), "MATERIALS_ONLY",
                outputs.isEmpty() ? State.COMPLETED : State.CALLBACK_DONE, List.of(), List.of(), outputs, Collections.nCopies(outputs.size(), 0), null,
                "NATIVE_SURVIVAL_LOOT_IN_CUSTODY", outputs, source, List.of(),
                List.of(new ExternalDebit(input.operationId(), "native_block_harvest", true, true, List.of(), outputs, receipt)),
                List.of(new NativeSave("external:native_block_harvest", input.operationId().toString(), pools, proof)), 1));
    }
    public synchronized Operation harvestSaved(Actor actor, firefly520.fireflymc.ai.blueprint.RemovalJournal.Step step) throws IOException {
        actor.requireAccess(step.owner()); var op = get(actor, step.input().operationId());
        String source = firefly520.fireflymc.ai.blueprint.RemovalJournal.harvestReceipt(step);
        if (!step.saved() || !op.samplingEvidence().equals("native_block_harvest:" + source)) throw new IllegalStateException("NATIVE_HARVEST_SAVE_NOT_CONFIRMED");
        nativeSaved(actor, op.operationId(), "external:native_block_harvest", op.operationId().toString(), AiJson.digest(AiJson.canonical(step.saves())));
        return get(actor, op.operationId());
    }
    /** Failed native stages quarantine the same physical cells/player even before harvest adoption exists. */
    public synchronized Operation quarantineRemoval(Actor actor, firefly520.fireflymc.ai.blueprint.RemovalJournal.Step step) throws IOException {
        actor.requireAccess(step.owner());
        if (step.state() != firefly520.fireflymc.ai.blueprint.RemovalJournal.State.RECOVERY_REQUIRED) throw new IllegalArgumentException("UNCERTAIN_REMOVAL_REQUIRED");
        var input = step.input(); String identity = "uncertain_native_removal:" + AiJson.digest(AiJson.canonical(input));
        var previous = operations.get(input.operationId());
        if (previous != null && (!previous.owner().equals(actor.owner()) || !previous.planHash().equals(input.planHash()) || !previous.taskId().equals(input.taskId())))
            throw new SecurityException("MATERIAL_OPERATION_REUSED");
        if (previous != null && !previous.samplingEvidence().equals(identity)) return uncertain(previous, "NATIVE_REMOVAL_UNCONFIRMED");
        var outputs = step.evidence() == null ? previous == null ? List.<MaterialStack>of() : previous.products()
                : step.evidence().drops().stream().map(firefly520.fireflymc.ai.blueprint.RemovalJournal.Drop::stack).toList();
        if (previous != null && !previous.products().isEmpty() && !previous.products().equals(outputs)) throw new IllegalStateException("REMOVAL_CUSTODY_EVIDENCE_CHANGED");
        var pools = new TreeSet<String>(); pools.add(MaterialSourceScope.key("player:" + actor.owner()));
        input.cells().stream().filter(cell -> cell.target() != null).forEach(cell -> pools.add(worldScope(input.dimension(), cell.position().x(), cell.position().y(), cell.position().z())));
        return persist(new Operation(1, input.operationId(), actor.owner(), input.taskId(), input.planHash(), "MATERIALS_ONLY", State.RECOVERY_REQUIRED,
                List.of(), List.of(), outputs, Collections.nCopies(outputs.size(), 0), null, "NATIVE_REMOVAL_UNCONFIRMED", outputs, identity, List.of(),
                List.of(new ExternalDebit(input.operationId(), "native_block_harvest", false, false, List.of(), outputs, AiJson.digest(AiJson.canonical(step)))),
                List.of(new NativeSave("external:native_block_harvest", input.operationId().toString(), pools, "")), previous == null ? 1 : previous.revision() + 1));
    }
    /** Lock-free main-thread gate; no filesystem or global-ledger scan in a world mutation. */
    public boolean recoveryBlocksWorld(String dimension, int x, int y, int z) { String key = worldScope(dimension, x, y, z);
        return broken || nativeScopeRestoreFailed || quarantinedScopes.contains(key) || nativeWorldHeld(key); }
    public boolean recoveryBlocksPlayer(String owner) { return broken || quarantinedScopes.contains(MaterialSourceScope.key("player:" + owner)); }
    public boolean recoveryBlocksBackend(String backend) { String key = MaterialSourceScope.key(backend);
        return broken || nativeScopeRestoreFailed || quarantinedScopes.contains(key) || nativeWorldHeld(key); }
    private static String worldScope(String dimension, int x, int y, int z) { return "@world:" + dimension + ":" + x + "," + y + "," + z; }
    private void publishQuarantine() {
        var scopes = new HashSet<String>();
        for (var op : operations.values()) if (op.uncertain()) {
            op.nativeSaves().forEach(save -> scopes.addAll(save.pools()));
            op.claims().forEach(claim -> { scopes.add(claim.stock().poolId()); scopes.add(MaterialSourceScope.key(claim.stock().backendId())); });
        }
        quarantinedScopes = Set.copyOf(scopes);
    }

    /** Publish a random outcome and consume its real escrow in one durable transition, never a refundable preview. */
    public synchronized Operation produceSampled(Actor actor, UUID id, List<MaterialStack> outputs, String evidence) throws IOException {
        mixedOwnership.standalone(actor, id);
        Operation op = get(actor, id);
        require(op, State.ESCROWED);
        if (outputs == null || outputs.size() > 128 || outputs.stream().anyMatch(MaterialStack::empty)
                || evidence == null || evidence.isBlank() || evidence.length() > 32768
                || op.claims().stream().anyMatch(c -> c.remaining() != 0)) throw new IllegalArgumentException("MATERIAL_SAMPLE_INVALID");
        if (!op.worldDebits().isEmpty() || !op.externalDebits().isEmpty()) throw new IllegalStateException("MIXED_PROCESSING_SCOPE_NOT_ALLOWED");
        if (!op.samplingEvidence().isEmpty()) throw new IllegalStateException("RANDOM_RESULT_ALREADY_FIXED");
        return persist(new Operation(op.version(), op.operationId(), op.owner(), op.taskId(), op.planHash(), op.policy(), State.PRODUCED,
                op.claims(), List.of(), outputs, Collections.nCopies(outputs.size(), 0), null, "SAMPLED_INPUTS_COMMITTED", outputs, evidence,
                op.worldDebits(), op.externalDebits(), op.nativeSaves(), op.revision() + 1));
    }

    public synchronized Operation beginExtraction(Actor actor, UUID id, int index, MaterialStack before,
                                                   long poolUnits, String witness) throws IOException {
        Operation op = get(actor, id);
        if (index < 0 || index >= op.claims().size()) throw new IllegalArgumentException("MATERIAL_CLAIM_INDEX");
        return beginExtraction(actor, id, index, before, poolUnits, witness, op.claims().get(index).remaining());
    }
    public synchronized Operation beginExtraction(Actor actor, UUID id, int index, MaterialStack before,
                                                   long poolUnits, String witness, int count) throws IOException {
        mixedOwnership.inputOpen(actor, id);
        requireNativeResourceAdmission();
        var nativeClaim = get(actor, id);
        if (index >= 0 && index < nativeClaim.claims().size() && nativeScopeHeld(nativeClaim.claims().get(index).stock().backendId()))
            throw new IllegalStateException("NATIVE_RESOURCE_SCOPE_BUSY");
        Operation op = get(actor, id);
        require(op, State.RESERVED);
        if (index < 0 || index >= op.claims().size()) throw new IllegalArgumentException("MATERIAL_CLAIM_INDEX");
        Claim claim = op.claims().get(index);
        if (count < 1 || count > claim.remaining() || count > claim.stock().maxTransferCount()
                || !before.itemJson().equals(claim.stock().stack().itemJson())
                || before.count() < claim.remaining() || poolUnits < claim.reservedUnits()
                || witness == null || witness.isBlank()) throw new IllegalStateException("MATERIAL_SOURCE_CHANGED");
        return persist(copy(op, State.EXTRACT_INTENT, op.claims(), op.escrow(), op.products(), op.delivered(),
                new Intent("extract", index, count, witness, before, poolUnits), ""));
    }

    /** Includes partial extraction. The returned actual items remain escrow, never assumed missing or re-created. */
    public synchronized Operation extracted(Actor actor, UUID id, MaterialStack actual,
                                            long observedPoolUnitsAfter) throws IOException {
        Operation op = get(actor, id);
        require(op, State.EXTRACT_INTENT);
        return extractionOutcome(op, actual, observedPoolUnitsAfter, false);
    }
    /** Keeps a late native return even if cancellation has already raised the reconciliation barrier. */
    public synchronized Operation extractionEvidence(Actor actor, UUID id, String witness, MaterialStack actual, long unitsAfter) throws IOException {
        Operation op = evidence(actor, id, witness, "extract", State.EXTRACT_INTENT);
        return extractionOutcome(op, actual, unitsAfter, op.uncertain());
    }
    private Operation extractionOutcome(Operation op, MaterialStack actual, long observedPoolUnitsAfter, boolean retainedBarrier) throws IOException {
        Intent intent = op.intent();
        Claim claim = op.claims().get(intent.index());
        if (actual.count() > intent.count() || !actual.empty() && !actual.itemJson().equals(intent.before().itemJson())
                || observedPoolUnitsAfter < 0 || intent.poolUnitsBefore() - observedPoolUnitsAfter
                != Math.multiplyExact((long) actual.count(), claim.stock().unitsPerItem())) {
            var quarantined = new ArrayList<>(op.escrow());
            if (!actual.empty()) quarantined.add(actual);
            return persist(copy(op, State.RECOVERY_REQUIRED, op.claims(), quarantined, op.products(),
                    op.delivered(), observedIntent(intent), "EXTRACTION_CONSERVATION_UNCERTAIN:after=" + observedPoolUnitsAfter));
        }
        var claims = new ArrayList<>(op.claims());
        claims.set(intent.index(), new Claim(claim.stock(), claim.requested(), claim.extracted() + actual.count()));
        var escrow = new ArrayList<>(op.escrow());
        if (!actual.empty()) escrow.add(actual);
        boolean done = claims.stream().allMatch(row -> row.remaining() == 0);
        return persist(copy(op, retainedBarrier ? State.RECOVERY_REQUIRED : done ? State.ESCROWED : State.RESERVED, claims, escrow, List.of(), List.of(), null,
                retainedBarrier ? "LATE_EXTRACTION_EVIDENCE_RECORDED" : actual.count() < intent.count() ? "PARTIAL_EXTRACTION" : ""));
    }

    /** Only use when the dispatcher can prove no native mutation or callback was entered. */
    public synchronized Operation rejectedBeforeExecution(Actor actor, UUID id, String reason) throws IOException {
        Operation op = get(actor, id);
        if (nativeProductionPending(op)) return callbackRejectedBeforeDispatch(actor, id, op.intent().witness(), 0, reason);
        State state = stateBeforeDispatch(op.state());
        return persist(withSaves(copy(op, state, op.claims(), op.escrow(), op.products(), op.delivered(), null, reason),
                op.nativeSaves().stream().filter(s -> !s.kind().equals(op.intent().kind()) || !s.witness().equals(op.intent().witness())).toList()));
    }
    private static State stateBeforeDispatch(State state) {
        return switch (state) {
            case EXTRACT_INTENT -> State.RESERVED;
            case DELIVER_INTENT -> State.CALLBACK_DONE;
            case CALLBACK_INTENT -> State.PRODUCED;
            case WORLD_INTENT -> State.ESCROWED;
            default -> throw new IllegalStateException("MATERIAL_INTENT_NOT_REJECTABLE");
        };
    }
    public synchronized Operation rejectionEvidence(Actor actor, UUID id, String witness, String kind, String reason) throws IOException {
        State original = kind.equals("extract") ? State.EXTRACT_INTENT : kind.equals("deliver") ? State.DELIVER_INTENT
                : kind.equals("callback") ? State.CALLBACK_INTENT : kind.equals("world") ? State.WORLD_INTENT : null;
        if (original == null) throw new IllegalArgumentException("MATERIAL_EVIDENCE_KIND");
        Operation op = evidence(actor, id, witness, kind, original);
        if (kind.equals("callback") && nativeProductionPending(op))
            return callbackRejectedBeforeDispatch(actor, id, witness, 0, reason);
        if (!op.uncertain()) return rejectedBeforeExecution(actor, id, reason);
        return persist(withSaves(copy(op, State.RECOVERY_REQUIRED, op.claims(), op.escrow(), op.products(), op.delivered(), null, "NATIVE_NON_DISPATCH_CONFIRMED"),
                op.nativeSaves().stream().filter(s -> !s.kind().equals(kind) || !s.witness().equals(witness)).toList()));
    }
    private Operation evidence(Actor actor, UUID id, String witness, String kind, State expected) {
        Operation op = get(actor, id);
        if (op.state() != expected && !op.uncertain() || op.intent() == null || !op.intent().kind().equals(kind) || !op.intent().witness().equals(witness))
            throw new IllegalStateException("MATERIAL_EVIDENCE_ALREADY_RECORDED_OR_CHANGED");
        return op;
    }
    private static Intent observedIntent(Intent intent) {
        return new Intent(intent.kind() + "_observed", intent.index(), intent.count(), intent.witness(), intent.before(), intent.poolUnitsBefore(), intent.materials());
    }

    /**
     * Trusted adapter computes and samples against the full escrow before this method.
     * The chosen result is persisted before callbacks or delivery; retries cannot re-roll it.
     */
    public synchronized Operation produce(Actor actor, UUID id, List<MaterialStack> outputs, String evidence) throws IOException {
        mixedOwnership.ifOwned(actor, id, evidence != null && evidence.startsWith("mixed-recipe:") ? evidence.substring("mixed-recipe:".length()) : "");
        Operation op = get(actor, id);
        require(op, State.ESCROWED);
        if (!op.worldDebits().isEmpty() || !op.externalDebits().isEmpty()) throw new IllegalStateException("MIXED_PROCESSING_SCOPE_NOT_ALLOWED");
        if (outputs == null || outputs.size() > 128
                || outputs.stream().anyMatch(MaterialStack::empty) || evidence == null || evidence.isBlank())
            throw new IllegalArgumentException("MATERIAL_TRANSFORMATION_INVALID");
        if (!op.samplingEvidence().isEmpty() && !op.sampledOutputs().equals(outputs)) throw new IllegalStateException("RANDOM_RESULT_CHANGED");
        return persist(copy(op, State.PRODUCED, op.claims(), List.of(), outputs,
                Collections.nCopies(outputs.size(), 0), null, evidence));
    }

    public synchronized Operation beginCallback(Actor actor, UUID id, String category) throws IOException {
        Operation op = get(actor, id);
        require(op, State.PRODUCED);
        return persist(copy(op, State.CALLBACK_INTENT, op.claims(), op.escrow(), op.products(), op.delivered(),
                new Intent("callback", -1, 0, Objects.requireNonNull(category), MaterialStack.EMPTY, 0), ""));
    }
    /** A bounded callback batch records every accepted part before its final execution receipt. */
    public synchronized Operation beginCallback(Actor actor, UUID id, String witness, int expectedParts) throws IOException {
        if (expectedParts < 1 || expectedParts > 256 || witness == null || witness.isBlank() || witness.length() > 32768)
            throw new IllegalArgumentException("MATERIAL_CALLBACK_PARTS_INVALID");
        Operation op = get(actor, id); require(op, State.PRODUCED);
        return persist(copy(op, State.CALLBACK_INTENT, op.claims(), op.escrow(), op.products(), op.delivered(),
                new Intent("callback", -1, expectedParts, witness, MaterialStack.EMPTY, 0), ""));
    }
    private static final String NATIVE_PRODUCTION_PENDING = "native-production-pending-v1:";
    private static final String NATIVE_PRODUCTION_COMMITTED = "native-production-committed-v1:";
    public static boolean nativeProductionPending(Operation op) { return op.samplingEvidence().startsWith(NATIVE_PRODUCTION_PENDING); }
    /** Shared-data assemblers retain the actual inputs until their real native output has been observed. */
    public synchronized Operation beginNativeProduction(Actor actor, UUID id, String witness) throws IOException {
        mixedOwnership.standalone(actor, id); Operation op = get(actor, id); require(op, State.ESCROWED);
        if (!op.inputsSaved() || op.escrow().isEmpty() || !op.products().isEmpty() || !op.samplingEvidence().isEmpty()
                || !op.worldDebits().isEmpty() || !op.externalDebits().isEmpty()
                || witness == null || witness.isBlank() || witness.length() > 32768)
            throw new IllegalStateException("NATIVE_PRODUCTION_INPUTS_NOT_READY");
        var next = copy(op, State.CALLBACK_INTENT, op.claims(), op.escrow(), List.of(), List.of(),
                new Intent("callback", -1, 1, witness, MaterialStack.EMPTY, 0), "NATIVE_PRODUCTION_INTENT");
        return persist(withProductionEvidence(next, List.of(), NATIVE_PRODUCTION_PENDING + witness));
    }
    public synchronized Operation nativeProductionEvidence(Actor actor, UUID id, String witness, List<MaterialStack> outputs) throws IOException {
        Operation op = evidence(actor, id, witness, "callback", State.CALLBACK_INTENT);
        if (!nativeProductionPending(op) || op.intent().count() != 1 || op.escrow().isEmpty()
                || !op.products().isEmpty() || outputs == null || outputs.size() > 128 || outputs.stream().anyMatch(MaterialStack::empty)
                || op.nativeSaves().stream().noneMatch(save -> save.kind().equals("callback-part")
                        && save.witness().equals(callbackPartWitness(witness, 0))))
            throw new IllegalStateException("NATIVE_PRODUCTION_OUTPUT_EVIDENCE_REQUIRED");
        var next = copy(op, op.uncertain() ? State.RECOVERY_REQUIRED : outputs.isEmpty() ? State.COMPLETED : State.CALLBACK_DONE,
                op.claims(), List.of(), outputs, Collections.nCopies(outputs.size(), 0), null,
                op.uncertain() ? op.reason() + "\nLATE_NATIVE_PRODUCTION_OBSERVED" : "NATIVE_PRODUCTION_OBSERVED");
        next = withSaves(next, op.nativeSaves().stream().filter(save -> !save.kind().equals("callback") || !save.witness().equals(witness)).toList());
        return persist(withProductionEvidence(next, outputs, NATIVE_PRODUCTION_COMMITTED + witness));
    }
    private static Operation withProductionEvidence(Operation op, List<MaterialStack> observed, String evidence) {
        return new Operation(op.version(), op.operationId(), op.owner(), op.taskId(), op.planHash(), op.policy(), op.state(),
                op.claims(), op.escrow(), op.products(), op.delivered(), op.intent(), op.reason(), observed, evidence,
                op.worldDebits(), op.externalDebits(), op.nativeSaves(), op.revision());
    }
    /** Trusted dispatcher proof: no invocation at this index, and no later part has been submitted. */
    public synchronized Operation callbackRejectedBeforeDispatch(Actor actor, UUID id, String witness, int index, String reason) throws IOException {
        Operation op = evidence(actor, id, witness, "callback", State.CALLBACK_INTENT);
        if (index < 0 || index >= op.intent().count()) throw new IllegalArgumentException("MATERIAL_CALLBACK_PART_INVALID");
        for (int part = 0; part < op.intent().count(); part++) {
            String partWitness = callbackPartWitness(witness, part);
            var known = op.nativeSaves().stream().filter(save -> save.witness().equals(partWitness)).toList();
            if (part < index && (known.size() != 1 || !known.getFirst().kind().equals("callback-part"))
                    || part >= index && !known.isEmpty()) throw new IllegalStateException("MATERIAL_CALLBACK_DISPATCH_NOT_PROVEN");
        }
        boolean production = nativeProductionPending(op);
        if (production && index != 0) throw new IllegalStateException("NATIVE_PRODUCTION_DISPATCH_NOT_PROVEN");
        State nextState = op.uncertain() ? State.RECOVERY_REQUIRED : production ? State.ESCROWED
                : op.products().isEmpty() ? State.CANCELLED : State.CALLBACK_DONE;
        var next = copy(op, nextState, op.claims(), op.escrow(), op.products(), op.delivered(), null,
                "NATIVE_NON_DISPATCH_CONFIRMED\nCALLBACK_STOPPED_AT:" + index + ":" + reason);
        next = withSaves(next, op.nativeSaves().stream().filter(save -> !save.kind().equals("callback") || !save.witness().equals(witness)).toList());
        if (production) next = withProductionEvidence(next, List.of(), "");
        return persist(next);
    }
    public static boolean nativeProductionBackingSaved(Operation op) {
        if (!op.samplingEvidence().startsWith(NATIVE_PRODUCTION_COMMITTED)) return true;
        var parts = op.nativeSaves().stream().filter(save -> save.kind().startsWith("callback-part")).toList();
        return op.inputsSaved() && !parts.isEmpty() && parts.stream().allMatch(save ->
                save.kind().equals("callback-part") && !save.receipt().isEmpty());
    }
    public static String callbackPartWitness(String witness, int index) { return AiJson.digest(witness + ":part:" + index); }
    /** Records the actual accepted callback even if its requesting task has stopped waiting. */
    public synchronized Operation callbackPartEvidence(Actor actor, UUID id, String witness, int index,
            boolean completed, boolean entered, String code, Set<String> pools) throws IOException {
        return callbackPartEvidence(actor, id, witness, index, completed, entered, code, pools, "");
    }
    public synchronized Operation callbackPartEvidence(Actor actor, UUID id, String witness, int index,
            boolean completed, boolean entered, String code, Set<String> pools, String observations) throws IOException {
        Operation op = evidence(actor, id, witness, "callback", State.CALLBACK_INTENT);
        if (op.intent().count() < 1 || index < 0 || index >= op.intent().count() || completed && !entered
                || code == null || code.length() > 256 || observations == null || observations.length() > 32768)
            throw new IllegalArgumentException("MATERIAL_CALLBACK_PART_INVALID");
        String part = callbackPartWitness(witness, index);
        if (op.nativeSaves().stream().anyMatch(save -> save.witness().equals(part)))
            throw new IllegalStateException("MATERIAL_CALLBACK_PART_ALREADY_RECORDED");
        var saves = new ArrayList<>(op.nativeSaves());
        if (entered) saves.add(new NativeSave(completed ? "callback-part" : "callback-part-unknown", part, Set.copyOf(pools), ""));
        boolean uncertain = op.uncertain() || !completed;
        String reason = op.reason() + (op.reason().isEmpty() ? "" : "\n") + "CALLBACK_PART_OBSERVED:" + index
                + (code.isEmpty() ? "" : ":" + code) + (observations.isEmpty() ? "" : ":" + observations);
        return persist(withSaves(copy(op, uncertain ? State.RECOVERY_REQUIRED : State.CALLBACK_INTENT,
                op.claims(), op.escrow(), op.products(), op.delivered(), op.intent(), reason), saves));
    }
    /** Closes only a fully observed batch; native disk receipts remain separate obligations. */
    public synchronized Operation callbackEvidence(Actor actor, UUID id, String witness) throws IOException {
        Operation op = evidence(actor, id, witness, "callback", State.CALLBACK_INTENT);
        if (nativeProductionPending(op)) throw new IllegalStateException("NATIVE_PRODUCTION_OUTPUT_EVIDENCE_REQUIRED");
        if (op.intent().count() < 1) throw new IllegalStateException("MATERIAL_CALLBACK_PARTS_MISSING");
        for (int index = 0; index < op.intent().count(); index++) {
            String part = callbackPartWitness(witness, index);
            if (op.nativeSaves().stream().noneMatch(save -> save.kind().equals("callback-part") && save.witness().equals(part)))
                throw new IllegalStateException("MATERIAL_CALLBACK_PARTS_MISSING");
        }
        var saves = op.nativeSaves().stream().filter(save -> !save.kind().equals("callback") || !save.witness().equals(witness)).toList();
        State state = op.uncertain() ? State.RECOVERY_REQUIRED : op.products().isEmpty() ? State.COMPLETED : State.CALLBACK_DONE;
        return persist(withSaves(copy(op, state, op.claims(), op.escrow(), op.products(), op.delivered(), null,
                op.uncertain() ? op.reason() : "CALLBACK_BATCH_OBSERVED\n" + op.reason()), saves));
    }
    public synchronized Operation callbackCompleted(Actor actor, UUID id) throws IOException {
        Operation op = get(actor, id);
        require(op, State.CALLBACK_INTENT);
        if (op.intent().count() > 0) throw new IllegalStateException("MATERIAL_CALLBACK_PART_EVIDENCE_REQUIRED");
        return persist(copy(op, op.products().isEmpty() ? State.COMPLETED : State.CALLBACK_DONE,
                op.claims(), op.escrow(), op.products(), op.delivered(), null, ""));
    }
    public synchronized Operation noCallbacksRequired(Actor actor, UUID id, String evidence) throws IOException {
        Operation op = get(actor, id); require(op, State.PRODUCED);
        if (evidence == null || evidence.isBlank()) throw new IllegalArgumentException("MATERIAL_CALLBACK_EVIDENCE_REQUIRED");
        return persist(copy(op, op.products().isEmpty() ? State.COMPLETED : State.CALLBACK_DONE,
                op.claims(), op.escrow(), op.products(), op.delivered(), null, evidence));
    }

    public synchronized Operation beginDelivery(Actor actor, UUID id, int product, int count, String witness,
                                                 MaterialStack before) throws IOException {
        return beginDelivery(actor, id, product, count, witness, before, Set.of());
    }
    public synchronized Operation beginDelivery(Actor actor, UUID id, int product, int count, String witness,
                                                 MaterialStack before, Set<String> destinationPools) throws IOException {
        requireNativeResourceAdmission();
        Operation op = get(actor, id);
        require(op, State.CALLBACK_DONE);
        if (!nativeProductionBackingSaved(op)) throw new IllegalStateException("NATIVE_PRODUCTION_STORE_NOT_SAVED");
        if (product < 0 || product >= op.products().size() || count < 1
                || count > op.products().get(product).count() - op.delivered().get(product)
                || witness == null || witness.isBlank() || destinationPools == null || destinationPools.size() > 2
                || destinationPools.stream().anyMatch(pool -> pool == null || pool.isBlank() || pool.length() > 512))
            throw new IllegalArgumentException("MATERIAL_DELIVERY_INVALID");
        if (destinationPools.stream().anyMatch(pool -> nativeScopes.values().stream().anyMatch(lease ->
                lease.scopes.contains(pool) || lease.scopes.contains(MaterialSourceScope.key(pool)))
                || nativeWorldHeld(pool) || nativeWorldHeld(MaterialSourceScope.key(pool))))
            throw new IllegalStateException("NATIVE_RESOURCE_SCOPE_BUSY");
        Operation next = copy(op, State.DELIVER_INTENT, op.claims(), op.escrow(), op.products(), op.delivered(),
                new Intent("deliver", product, count, witness, before, 0), "");
        var saves = new ArrayList<>(next.nativeSaves());
        saves.set(saves.size() - 1, new NativeSave("deliver", witness, destinationPools, ""));
        return persist(withSaves(next, saves));
    }

    /** The destination adapter checks native before/after counts, item identity and returned remainder. */
    public synchronized Operation delivered(Actor actor, UUID id, int accepted, boolean conservationVerified) throws IOException {
        Operation op = get(actor, id);
        require(op, State.DELIVER_INTENT);
        return deliveryOutcome(op, accepted, conservationVerified, false);
    }
    public synchronized Operation deliveryEvidence(Actor actor, UUID id, String witness, int accepted, boolean conservationVerified) throws IOException {
        Operation op = evidence(actor, id, witness, "deliver", State.DELIVER_INTENT);
        return deliveryOutcome(op, accepted, conservationVerified, op.uncertain());
    }
    private Operation deliveryOutcome(Operation op, int accepted, boolean conservationVerified, boolean retainedBarrier) throws IOException {
        if (!conservationVerified || accepted < 0 || accepted > op.intent().count())
            return persist(copy(op, State.RECOVERY_REQUIRED, op.claims(), op.escrow(), op.products(), op.delivered(), observedIntent(op.intent()), "DELIVERY_CONSERVATION_UNCERTAIN"));
        var delivered = new ArrayList<>(op.delivered());
        int index = op.intent().index();
        delivered.set(index, Math.addExact(delivered.get(index), accepted));
        boolean done = true;
        for (int i = 0; i < delivered.size(); i++) done &= delivered.get(i) == op.products().get(i).count();
        return persist(copy(op, retainedBarrier ? State.RECOVERY_REQUIRED : done ? State.COMPLETED : State.CALLBACK_DONE, op.claims(), op.escrow(),
                op.products(), delivered, null, retainedBarrier ? "LATE_DELIVERY_EVIDENCE_RECORDED" : accepted == 0 ? "OUTPUT_FULL" : ""));
    }

    /** Cancellation returns only real escrow; it never reconstructs consumed ingredients from output recipes. */
    public synchronized Operation cancel(Actor actor, UUID id) throws IOException {
        mixedOwnership.standalone(actor, id);
        return cancelState(actor, id);
    }
    synchronized Operation cancelMixed(Actor actor, UUID id, String receipt) throws IOException {
        mixedOwnership.require(actor, id, receipt); return cancelState(actor, id);
    }
    private Operation cancelState(Actor actor, UUID id) throws IOException {
        Operation op = get(actor, id);
        if (op.settled() || op.uncertain()) return op;
        if (op.intent() != null) return uncertain(op, "CANCEL_REQUIRES_RECONCILIATION");
        if (op.state() == State.PRODUCED) {
            if (op.nativeSaves().stream().anyMatch(save -> save.kind().contains("unknown")))
                return uncertain(op, "PRODUCED_CALLBACK_REQUIRES_RECONCILIATION");
            return persist(copy(op, op.products().isEmpty() ? State.CANCELLED : State.CALLBACK_DONE,
                    op.claims(), op.escrow(), op.products(), op.delivered(), null, "RETURNING_PRODUCED_WITHOUT_NEW_CALLBACKS"));
        }
        if (op.state() == State.RESERVED || op.state() == State.ESCROWED) {
            // Remaining soft claims are released; physical escrow is still an owned return product.
            var held = op.escrow().stream().filter(stack -> !stack.empty()).toList();
            return persist(copy(op, held.isEmpty() ? State.CANCELLED : State.CALLBACK_DONE,
                    op.claims().stream().filter(c -> c.extracted() > 0)
                            .map(c -> new Claim(c.stock(), c.extracted(), c.extracted())).toList(),
                    List.of(), held, Collections.nCopies(held.size(), 0), null, "RETURNING_INPUTS"));
        }
        return op; // Produced output belongs to the player; deliver it, never undo the recipe by invention.
    }
    public synchronized Operation markUncertain(Actor actor, UUID id, String reason) throws IOException {
        return uncertain(get(actor, id), reason);
    }
    public record RecoveryReview(UUID operationId, long revision, State restorePhase, String reviewHash,
                                 long escrowUnits, long undeliveredUnits) {}

    /** Positive durable native receipts plus the exact last known ledger state, never inventory-count inference. */
    public synchronized RecoveryReview recoveryReview(Actor actor, UUID id) {
        Operation op = get(actor, id), basis = lastKnownOperations.get(id);
        if (op.reason().equals("NATIVE_NON_DISPATCH_CONFIRMED") && basis != null && basis.intent() != null) {
            // The durable dispatcher rejection proves this particular intent never entered native code.
            // Normalize only that intent; every earlier effect still needs its own positive save proof.
            Intent rejected = basis.intent();
            var saves = basis.nativeSaves().stream().filter(s -> !s.kind().equals(rejected.kind()) || !s.witness().equals(rejected.witness())).toList();
            basis = withSaves(copy(basis, stateBeforeDispatch(basis.state()), basis.claims(), basis.escrow(), basis.products(), basis.delivered(), null, ""), saves);
        }
        if (!op.uncertain() || !Set.of("RESTART_UNCERTAIN", "NATIVE_SAVE_NOT_CONFIRMED", "NATIVE_NON_DISPATCH_CONFIRMED").contains(op.reason())
                || op.intent() != null || basis == null || basis.intent() != null || basis.uncertain()
                || !op.saved() || !sameKnownContents(op, basis))
            throw new IllegalStateException("RECOVERY_POSITIVE_NATIVE_PROOF_REQUIRED");
        String hash = AiJson.digest(actor.owner() + ":" + id + ":" + basis.state() + ":" + AiJson.canonical(AiJson.GSON.toJsonTree(op)));
        return new RecoveryReview(id, op.revision(), basis.state(), hash,
                op.escrow().stream().mapToLong(MaterialStack::count).sum(), op.remainingProducts().stream().mapToLong(MaterialStack::count).sum());
    }
    public synchronized Operation reconcile(Actor actor, UUID id, long revision, String reviewHash) throws IOException {
        mixedOwnership.standalone(actor, id);
        return reconcileState(actor, id, revision, reviewHash);
    }
    synchronized Operation reconcileMixed(Actor actor, UUID id, long revision, String reviewHash, String receipt) throws IOException {
        mixedOwnership.require(actor, id, receipt); return reconcileState(actor, id, revision, reviewHash);
    }
    private Operation reconcileState(Actor actor, UUID id, long revision, String reviewHash) throws IOException {
        var review = recoveryReview(actor, id);
        if (review.revision() != revision || !review.reviewHash().equals(reviewHash))
            throw new IllegalStateException("RECOVERY_REVIEW_CHANGED");
        Operation op = get(actor, id);
        return persist(copy(op, review.restorePhase(), op.claims(), op.escrow(), op.products(), op.delivered(), null,
                "RECONCILED_FROM_DURABLE_NATIVE_RECEIPTS"));
    }
    private static boolean sameKnownContents(Operation current, Operation known) {
        var a = AiJson.GSON.toJsonTree(current).getAsJsonObject(); var b = AiJson.GSON.toJsonTree(known).getAsJsonObject();
        for (var value : List.of(a, b)) {
            for (String field : List.of("state", "reason", "revision")) value.remove(field);
            value.getAsJsonArray("nativeSaves").forEach(save -> save.getAsJsonObject().remove("receipt"));
        }
        return a.equals(b);
    }

    /** Durable intent before passing one owned escrow unit to the native world interaction. */
    public synchronized Operation beginWorldUse(Actor actor, UUID id, UUID step, int escrowIndex) throws IOException {
        requireNativeResourceAdmission();
        Operation op = get(actor, id); require(op, State.ESCROWED);
        if (step == null || escrowIndex < 0 || escrowIndex >= op.escrow().size() || op.escrow().get(escrowIndex).empty())
            throw new IllegalArgumentException("WORLD_MATERIAL_INPUT_INVALID");
        if (op.worldDebits().stream().anyMatch(debit -> debit.stepId().equals(step))) throw new IllegalStateException("WORLD_STEP_ALREADY_RECORDED");
        if (op.worldDebits().size() >= 8192) throw new IllegalStateException("WORLD_STEP_LIMIT");
        return persist(copy(op, State.WORLD_INTENT, op.claims(), op.escrow(), op.products(), op.delivered(),
                new Intent("world", escrowIndex, 1, step.toString(), op.escrow().get(escrowIndex), 0), ""));
    }

    /** Native caller supplies the actual remaining one-unit stack and a server-generated world receipt. */
    public synchronized Operation worldUsed(Actor actor, UUID id, UUID step, MaterialStack nativeRemaining,
                                            boolean knownOutcome, String receipt) throws IOException {
        Operation op = get(actor, id); require(op, State.WORLD_INTENT);
        return worldOutcome(op, step, nativeRemaining, knownOutcome, receipt, false);
    }
    public synchronized Operation worldEvidence(Actor actor, UUID id, UUID step, MaterialStack nativeRemaining,
                                                boolean knownOutcome, String receipt) throws IOException {
        Operation op = evidence(actor, id, step.toString(), "world", State.WORLD_INTENT);
        return worldOutcome(op, step, nativeRemaining, knownOutcome, receipt, op.uncertain());
    }
    private Operation worldOutcome(Operation op, UUID step, MaterialStack nativeRemaining,
                                   boolean knownOutcome, String receipt, boolean retainedBarrier) throws IOException {
        Intent intent = op.intent();
        if (!intent.witness().equals(step.toString())) throw new SecurityException("WORLD_STEP_MISMATCH");
        if (!knownOutcome || nativeRemaining == null || nativeRemaining.count() > 1
                || !nativeRemaining.empty() && !nativeRemaining.itemJson().equals(intent.before().itemJson())
                || receipt == null || !receipt.matches("[0-9a-f]{64}"))
            return persist(copy(op, State.RECOVERY_REQUIRED, op.claims(), op.escrow(), op.products(), op.delivered(),
                    observedIntent(intent), "WORLD_MATERIAL_OUTCOME_UNCERTAIN"));
        int consumed = 1 - nativeRemaining.count();
        var escrow = new ArrayList<>(op.escrow());
        if (!escrow.get(intent.index()).equals(intent.before())) return uncertain(op, "WORLD_ESCROW_CHANGED");
        escrow.set(intent.index(), intent.before().withCount(intent.before().count() - consumed));
        var debits = new ArrayList<>(op.worldDebits());
        debits.add(new WorldDebit(step, consumed, intent.before().fingerprint(), receipt));
        return persist(new Operation(op.version(), op.operationId(), op.owner(), op.taskId(), op.planHash(), op.policy(), retainedBarrier ? State.RECOVERY_REQUIRED : State.ESCROWED,
                op.claims(), escrow, op.products(), op.delivered(), null, consumed == 0 ? "WORLD_NATIVE_REJECTED" : "WORLD_INPUT_CONSUMED",
                op.sampledOutputs(), op.samplingEvidence(), debits, op.externalDebits(), op.nativeSaves(), op.revision() + 1));
    }
    public synchronized Operation worldCompleted(Actor actor, UUID id) throws IOException {
        Operation op = get(actor, id); require(op, State.ESCROWED);
        if (op.worldDebits().isEmpty() || op.escrow().stream().anyMatch(stack -> !stack.empty())) throw new IllegalStateException("WORLD_ESCROW_NOT_SETTLED");
        return persist(copy(op, State.COMPLETED, op.claims(), List.of(), List.of(), List.of(), null, "WORLD_MATERIALS_COMMITTED"));
    }
    /** Only the owning world journal may reopen settlement after every native debit and store receipt agrees. */
    public synchronized Operation reconcileSavedWorld(Actor actor, UUID id, firefly520.fireflymc.ai.blueprint.SavedPlacementProofs.Review review,
            List<firefly520.fireflymc.ai.blueprint.BuildJournal.Step> steps) throws IOException {
        mixedOwnership.standalone(actor, id); var op = get(actor, id);
        if (!id.equals(review.operationId())) throw new SecurityException("WORLD_MATERIAL_OPERATION_CHANGED");
        review.requireCurrent(actor, op, steps);
        if (op.state() == State.COMPLETED || op.state() == State.ESCROWED) return op;
        return persist(copy(op, State.ESCROWED, op.claims(), op.escrow(), op.products(), op.delivered(), null, "SAVED_WORLD_RECEIPTS_RECONCILED_NO_NATIVE_REPLAY"));
    }

    /** Multi-input entity/fluid effect. No ingredient is consumed until positive native evidence is recorded. */
    public synchronized Operation beginExternalUse(Actor actor, UUID id, UUID effectId, String kind,
                                                    List<MaterialStack> inputs) throws IOException {
        requireNativeResourceAdmission();
        Operation op = get(actor, id); require(op, State.ESCROWED);
        if (effectId == null || !Set.of("entity_conversion", "fluid_conversion").contains(kind) || inputs == null || inputs.isEmpty()
                || inputs.size() > 128 || inputs.stream().anyMatch(MaterialStack::empty)
                || inputs.stream().mapToLong(MaterialStack::count).sum() > 512 || !op.worldDebits().isEmpty())
            throw new IllegalArgumentException("EXTERNAL_INPUTS_INVALID");
        if (op.externalDebits().stream().anyMatch(row -> row.effectId().equals(effectId))) throw new IllegalStateException("EXTERNAL_EFFECT_ALREADY_RECORDED");
        if (op.externalDebits().size() >= 128) throw new IllegalStateException("EXTERNAL_EFFECT_LIMIT");
        subtractEscrow(op.escrow(), inputs); // Prove exact component quantities before writing the intent.
        return persist(copy(op, State.EXTERNAL_INTENT, op.claims(), op.escrow(), op.products(), op.delivered(),
                new Intent("external:" + kind, -1, inputs.stream().mapToInt(MaterialStack::count).sum(), effectId.toString(), MaterialStack.EMPTY, 0, inputs), ""));
    }

    /** Trusted adapter receipt includes native entity/fluids evidence; rejection never fabricates consumed ingredients. */
    public synchronized Operation externalEvidence(Actor actor, UUID id, UUID effectId, String kind, boolean known,
                                                    boolean accepted, List<MaterialStack> actualReturns, String receipt) throws IOException {
        Operation op = evidence(actor, id, effectId.toString(), "external:" + kind, State.EXTERNAL_INTENT);
        if (actualReturns == null || actualReturns.size() > 128 || actualReturns.stream().anyMatch(MaterialStack::empty)
                || actualReturns.stream().mapToLong(MaterialStack::count).sum() > 8192 || receipt == null || !receipt.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("EXTERNAL_EVIDENCE_INVALID");
        if (!accepted && !actualReturns.isEmpty()) known = false;
        var debits = new ArrayList<>(op.externalDebits());
        debits.add(new ExternalDebit(effectId, kind, known, accepted, op.intent().materials(), actualReturns, receipt));
        var remaining = new ArrayList<>(known && accepted ? subtractEscrow(op.escrow(), op.intent().materials()) : op.escrow());
        if (known && accepted) remaining.addAll(actualReturns);
        return persist(new Operation(op.version(), op.operationId(), op.owner(), op.taskId(), op.planHash(), op.policy(),
                op.uncertain() || !known ? State.RECOVERY_REQUIRED : State.ESCROWED, op.claims(), remaining, op.products(), op.delivered(),
                known ? null : observedIntent(op.intent()), known ? accepted ? "EXTERNAL_INPUTS_COMMITTED" : "EXTERNAL_NATIVE_REJECTED" : "EXTERNAL_EFFECT_UNCERTAIN",
                op.sampledOutputs(), op.samplingEvidence(), op.worldDebits(), debits, op.nativeSaves(), op.revision() + 1));
    }

    /** Releases only actual leftover/returned items into the normal callback-and-delivery sequence. */
    public synchronized Operation externalProductsReady(Actor actor, UUID id) throws IOException {
        Operation op = get(actor, id); require(op, State.ESCROWED);
        if (op.intent() != null || op.externalDebits().isEmpty() || op.externalDebits().stream().anyMatch(row -> !row.known())
                || op.externalDebits().stream().noneMatch(ExternalDebit::accepted)) throw new IllegalStateException("EXTERNAL_RECEIPTS_REQUIRED");
        var products = op.escrow().stream().filter(stack -> !stack.empty()).toList();
        return persist(copy(op, State.PRODUCED, op.claims(), List.of(), products, Collections.nCopies(products.size(), 0), null, "EXTERNAL_ACTUAL_REMAINDERS"));
    }
    private static List<MaterialStack> subtractEscrow(List<MaterialStack> escrow, List<MaterialStack> inputs) {
        var wanted = new HashMap<String, Integer>();
        for (var input : inputs) wanted.merge(input.itemJson(), input.count(), Math::addExact);
        var remaining = new ArrayList<MaterialStack>();
        for (var held : escrow) {
            int take = Math.min(held.count(), wanted.getOrDefault(held.itemJson(), 0));
            wanted.computeIfPresent(held.itemJson(), (key, count) -> count - take);
            remaining.add(held.withCount(held.count() - take));
        }
        if (wanted.values().stream().anyMatch(count -> count != 0)) throw new IllegalStateException("EXTERNAL_MATERIAL_NOT_OWNED");
        return remaining;
    }

    private Operation uncertain(Operation op, String reason) throws IOException {
        return persist(copy(op, State.RECOVERY_REQUIRED, op.claims(), op.escrow(), op.products(), op.delivered(), op.intent(), reason));
    }
    private static void require(Operation op, State expected) {
        if (op.state() != expected) throw new IllegalStateException("MATERIAL_PHASE_" + op.state());
    }
    private static Operation copy(Operation op, State state, List<Claim> claims, List<MaterialStack> escrow,
                                  List<MaterialStack> products, List<Integer> delivered, Intent intent, String reason) {
        var saves = new ArrayList<>(op.nativeSaves());
        if (intent != null && !Objects.equals(op.intent(), intent) && !intent.kind().endsWith("_observed")) {
            if (saves.stream().anyMatch(s -> s.kind().equals(intent.kind()) && s.witness().equals(intent.witness())))
                throw new IllegalStateException("NATIVE_EFFECT_WITNESS_REUSED");
            var pools = new HashSet<String>();
            if (intent.kind().equals("extract")) {
                Stock stock = claims.get(intent.index()).stock();
                pools.add(stock.poolId()); pools.add(MaterialSourceScope.key(stock.backendId()));
            }
            saves.add(new NativeSave(intent.kind(), intent.witness(), pools, ""));
        }
        return new Operation(op.version(), op.operationId(), op.owner(), op.taskId(), op.planHash(), op.policy(), state,
                claims, escrow, products, delivered, intent, reason, op.sampledOutputs(), op.samplingEvidence(), op.worldDebits(), op.externalDebits(), saves, op.revision() + 1);
    }
    private static Operation withSaves(Operation op, List<NativeSave> saves) {
        return new Operation(op.version(), op.operationId(), op.owner(), op.taskId(), op.planHash(), op.policy(), op.state(),
                op.claims(), op.escrow(), op.products(), op.delivered(), op.intent(), op.reason(), op.sampledOutputs(),
                op.samplingEvidence(), op.worldDebits(), op.externalDebits(), saves, op.revision());
    }
    /** Only a trusted native save observer supplies this receipt, never a client/model or count comparison. */
    public synchronized void nativeSaved(Actor actor, UUID id, String kind, String witness, String receipt) throws IOException {
        Operation op = get(actor, id);
        if (receipt == null || !receipt.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("NATIVE_SAVE_RECEIPT_INVALID");
        var saves = new ArrayList<>(op.nativeSaves());
        for (int i = 0; i < saves.size(); i++) {
            NativeSave save = saves.get(i);
            if (!save.kind().equals(kind) || !save.witness().equals(witness)) continue;
            if (!save.receipt().isEmpty()) return;
            saves.set(i, new NativeSave(kind, witness, save.pools(), receipt));
            persist(withSaves(copy(op, op.state(), op.claims(), op.escrow(), op.products(), op.delivered(), op.intent(), op.reason()), saves));
            return; // A late save proof never clears RECOVERY_REQUIRED or replays the native call.
        }
        throw new IllegalStateException("NATIVE_SAVE_EFFECT_NOT_FOUND");
    }
    private Operation persist(Operation operation) throws IOException {
        if (broken) throw new IOException("MATERIAL_JOURNAL_UNAVAILABLE");
        var payload = AiJson.GSON.toJsonTree(operation).getAsJsonObject();
        if (payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8 * 1024 * 1024)
            throw new IOException("MATERIAL_OPERATION_SIZE_LIMIT");
        try {
            var store = open(operation.owner()); var previous = operations.get(operation.operationId());
            // A first write after loading emits a fresh full baseline. Older snapshots may omit
            // newly-added defaults; subsequent deltas must refer to the exact serialized schema.
            var encoded = canonicalBaselines.contains(operation.operationId())
                    ? MaterialJournalCodec.encode(previous == null ? null : AiJson.GSON.toJsonTree(previous).getAsJsonObject(), payload)
                    : new MaterialJournalCodec.Encoded(MaterialJournalCodec.FULL, payload);
            store.append(List.of(new ConversationStore.Draft(operation.taskId(), encoded.kind(),
                    null, encoded.data(), operation.operationId().toString())));
            operations.put(operation.operationId(), operation);
            if (operation.uncertain() || previous != null && previous.uncertain()) publishQuarantine();
            canonicalBaselines.add(operation.operationId());
            if (!operation.uncertain()) lastKnownOperations.put(operation.operationId(), operation);
            return operation;
        } catch (IOException | RuntimeException failure) {
            broken = true; // Do not perform another effect after an ambiguous disk acknowledgement.
            throw failure;
        }
    }
    private Map<String, Long> reserved() {
        if (broken) throw new IllegalStateException("MATERIAL_JOURNAL_UNAVAILABLE");
        requireNativeResourceAdmission();
        Map<String, Long> result = new HashMap<>();
        for (Operation op : operations.values()) {
            if (op.settled()) continue;
            if (op.uncertain()) for (NativeSave save : op.nativeSaves())
                for (String pool : save.pools()) result.put(pool, Long.MAX_VALUE);
            for (Claim claim : op.claims()) {
                // An uncertain physical step quarantines the whole pool, including aliases/other players.
                if (op.uncertain()) {
                    result.put(claim.stock().poolId(), Long.MAX_VALUE);
                    result.put(MaterialSourceScope.key(claim.stock().backendId()), Long.MAX_VALUE);
                }
                else if (result.getOrDefault(claim.stock().poolId(), 0L) != Long.MAX_VALUE)
                    result.merge(claim.stock().poolId(), claim.reservedUnits(), Math::addExact);
            }
        }
        for (var lease : nativeScopes.values()) for (String scope : lease.scopes) result.put(scope, Long.MAX_VALUE);
        return result;
    }
    private static LinkedHashMap<String, Stock> normalize(List<Stock> observed) {
        if (observed == null || observed.size() > 16384) throw new IllegalArgumentException("MATERIAL_SCAN_LIMIT");
        var result = new LinkedHashMap<String, Stock>();
        var pools = new HashMap<String, Long>();
        for (Stock stock : observed) {
            Long capacity = pools.putIfAbsent(stock.poolId(), stock.poolUnits());
            if (capacity != null && capacity != stock.poolUnits()) throw new IllegalStateException("MATERIAL_POOL_CHANGED");
            Stock previous = result.putIfAbsent(stock.key(), stock);
            if (previous != null && (previous.unitsPerItem() != stock.unitsPerItem()
                    || previous.stack().count() != stock.stack().count()))
                throw new IllegalStateException("MATERIAL_ALIAS_CONFLICT");
            if (previous != null && stock.extractableCount() > previous.extractableCount()) result.put(stock.key(), stock);
        }
        return result;
    }
}
