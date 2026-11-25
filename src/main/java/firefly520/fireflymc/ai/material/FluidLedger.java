package firefly520.fireflymc.ai.material;

import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Durable mB custody. Native fluid mutation and journal writes are not one atomic transaction. */
public final class FluidLedger {
    public enum State { RESERVED, DRAIN_INTENT, ESCROWED, FILL_INTENT, COMPLETED, CANCELLED, RECOVERY_REQUIRED }
    public record Effect(String witness, boolean filling, FluidInventoryService.Source source, FluidAmount intended,
                         boolean observed, boolean entered, boolean verified, FluidAmount actual, FluidTankSnapshot after, String proof) {}
    public record RecipeDebit(UUID materialOperation, String receipt, int millibuckets) {
        public RecipeDebit {
            if (materialOperation == null || receipt == null || !receipt.matches("[0-9a-f]{64}")
                    || millibuckets < 1 || millibuckets > 1000000) throw new IllegalArgumentException("FLUID_RECIPE_DEBIT_INVALID");
        }
    }
    public record Operation(int version, UUID id, String owner, UUID task, String planHash,
                            FluidInventoryService.Source origin, FluidAmount requested, int extracted, int filled,
                            boolean cancellationRequested, State state, List<Effect> effects, String reason, long revision,
                            List<RecipeDebit> recipeDebits) {
        public Operation {
            effects = List.copyOf(effects);
            recipeDebits = recipeDebits == null ? List.of() : List.copyOf(recipeDebits);
        }
        public int consumedMb() { return recipeDebits.stream().mapToInt(RecipeDebit::millibuckets).reduce(0, Math::addExact); }
        public int escrowMb() { return extracted - filled - consumedMb(); }
        public boolean settled() { return state == State.COMPLETED || state == State.CANCELLED; }
        public boolean saved() { return effects.stream().allMatch(effect -> effect.observed() && effect.verified()
                && (!effect.entered() || !effect.proof().isEmpty())); }
        public boolean inputsSaved() { return effects.stream().filter(effect -> !effect.filling()).allMatch(effect -> effect.observed()
                && effect.verified() && (!effect.entered() || !effect.proof().isEmpty())); }
    }
    public record RecoveryReview(UUID operationId, long revision, State restorePhase, int escrowMb, String reviewHash) {}
    private final Path workspaceRoot;
    private final Map<String, ConversationStore> journals = new HashMap<>();
    private final Map<UUID, Operation> operations = new LinkedHashMap<>();
    private volatile boolean broken;
    private final MixedResourceOwnership mixedOwnership = new MixedResourceOwnership();
    public boolean journalAvailable() { return !broken; }
    void bindMixed(String owner, UUID id, String receipt) { mixedOwnership.bind(owner, id, receipt); }
    void bindMixed(String owner, UUID id, String receipt, boolean inputClosed) { mixedOwnership.bind(owner, id, receipt, inputClosed); }
    private volatile Set<String> blockedBackends = Set.of(), blockedOwners = Set.of();

    public FluidLedger(Path aiRoot) throws IOException {
        workspaceRoot = aiRoot.toAbsolutePath().normalize().resolve("workspace");
        if (Files.isSymbolicLink(workspaceRoot)) throw new IOException("FLUID_UNSAFE_PATH");
        if (Files.exists(workspaceRoot)) try (var directories = Files.list(workspaceRoot)) {
            for (Path directory : directories.toList()) {
                try { UUID.fromString(directory.getFileName().toString()); } catch (IllegalArgumentException unknown) { continue; }
                if (Files.exists(directory.resolve("fluids/journal.jsonl"))) open(directory.getFileName().toString());
            }
        }
        for (var op : List.copyOf(operations.values())) {
            if (op.state() != State.RECOVERY_REQUIRED && (!op.settled() && !op.effects().isEmpty() || op.settled() && !op.saved()))
                persist(copy(op, op.extracted(), op.filled(), op.cancellationRequested(), State.RECOVERY_REQUIRED, op.effects(), "RESTART_UNCERTAIN"));
        }
        publishQuarantine();
    }
    private ConversationStore open(String owner) throws IOException {
        UUID.fromString(owner); var existing = journals.get(owner); if (existing != null) return existing;
        Path directory = workspaceRoot.resolve(owner), folder = directory.resolve("fluids"), file = folder.resolve("journal.jsonl");
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(folder) || Files.isSymbolicLink(file)) throw new IOException("FLUID_UNSAFE_PATH");
        var journal = new ConversationStore(file);
        for (var event : journal.getSnapshot(0).events()) {
            if (!event.kind().equals("fluid_operation")) throw new IOException("FLUID_JOURNAL_EVENT");
            Operation op = AiJson.GSON.fromJson(event.data(), Operation.class); validate(op);
            var previous = operations.get(op.id());
            if (!op.owner().equals(owner) || op.revision() != (previous == null ? 1 : previous.revision() + 1)
                    || previous != null && (!previous.owner().equals(owner) || !samePlan(previous, op)))
                throw new IOException("FLUID_JOURNAL_IDENTITY");
            operations.put(op.id(), op);
        }
        journals.put(owner, journal); return journal;
    }
    public synchronized Optional<Operation> find(Actor actor, UUID id) {
        actor.requireAccess(actor.owner()); var op = operations.get(id);
        if (op != null) actor.requireAccess(op.owner()); return Optional.ofNullable(op);
    }
    public synchronized Operation get(Actor actor, UUID id) { return find(actor, id).orElseThrow(() -> new IllegalArgumentException("FLUID_OPERATION_NOT_FOUND")); }
    public synchronized List<Operation> list(Actor actor) {
        actor.requireAccess(actor.owner()); return operations.values().stream().filter(op -> op.owner().equals(actor.owner())).toList();
    }
    public boolean recoveryBlocksBackend(String backend) { return broken || blockedBackends.contains(backend); }
    public boolean recoveryBlocksPlayer(String owner) { return broken || blockedOwners.contains(owner); }
    public synchronized long available(FluidInventoryService.Source source, FluidAmount kind) {
        if (recoveryBlocksBackend(source.backend())) return 0;
        long held = operations.values().stream().filter(op -> !op.settled() && !op.cancellationRequested()
                && op.origin().backend().equals(source.backend()) && op.requested().unitJson().equals(kind.unitJson()))
                .mapToLong(op -> op.requested().millibuckets() - op.extracted()).sum();
        return Math.max(0, source.snapshot().amount(kind.fingerprint()) - held);
    }
    synchronized void approveMixedRemainingInputs(Actor actor, UUID id, String receipt) {
        mixedOwnership.approveRemainingInputs(actor, id, receipt);
    }

    public synchronized Operation reserve(Actor actor, UUID task, UUID id, String planHash,
                                           FluidInventoryService.Source source, FluidAmount amount) throws IOException {
        actor.requireAccess(actor.owner());
        if (!operations.containsKey(id)) mixedOwnership.inputOpen(actor, id);
        if (task == null || id == null || planHash == null || !planHash.matches("[0-9a-f]{64}")
                || source == null || amount == null || amount.empty() || amount.millibuckets() > 1000000)
            throw new IllegalArgumentException("FLUID_PLAN_INVALID");
        var previous = operations.get(id);
        if (previous != null) {
            if (!previous.owner().equals(actor.owner()) || !previous.task().equals(task) || !previous.planHash().equals(planHash)
                    || !previous.origin().equals(source) || !previous.requested().equals(amount)) throw new SecurityException("FLUID_OPERATION_REUSED");
            return previous;
        }
        if (recoveryBlocksPlayer(actor.owner()) || recoveryBlocksBackend(source.backend())) throw new IllegalStateException("FLUID_RECOVERY_REQUIRED");
        if (list(actor).stream().filter(op -> !op.settled()).count() >= 32) throw new IllegalStateException("FLUID_PENDING_LIMIT");
        if (available(source, amount) < amount.millibuckets()) throw new IllegalStateException("FLUID_MISSING");
        return persist(new Operation(1, id, actor.owner(), task, planHash, source, amount, 0, 0, false, State.RESERVED, List.of(), "", 1, List.of()));
    }
    public synchronized Operation begin(Actor actor, UUID id, String witness, FluidInventoryService.Source current,
                                         FluidAmount intended, boolean filling) throws IOException {
        if (filling) mixedOwnership.standalone(actor, id);
        return beginState(actor, id, witness, current, intended, filling);
    }
    synchronized Operation beginReturnMixed(Actor actor, UUID id, String receipt, String witness,
                                            FluidInventoryService.Source current, FluidAmount intended) throws IOException {
        mixedOwnership.require(actor, id, receipt);
        return beginState(actor, id, witness, current, intended, true);
    }
    private Operation beginState(Actor actor, UUID id, String witness, FluidInventoryService.Source current,
                                 FluidAmount intended, boolean filling) throws IOException {
        if (!filling) mixedOwnership.inputOpen(actor, id);
        var op = get(actor, id);
        if (op.state() != (filling ? State.ESCROWED : State.RESERVED)) throw new IllegalStateException("FLUID_PHASE_CHANGED");
        if (witness == null || !witness.matches("[0-9a-f-]{36}") || op.effects().size() >= 256
                || op.effects().stream().anyMatch(effect -> effect.witness().equals(witness))) throw new IllegalArgumentException("FLUID_EFFECT_WITNESS_INVALID");
        if (intended.empty() || !intended.unitJson().equals(op.requested().unitJson())
                || intended.millibuckets() > (filling ? op.escrowMb() : op.requested().millibuckets() - op.extracted()))
            throw new IllegalArgumentException("FLUID_INTENT_AMOUNT_INVALID");
        if (recoveryBlocksBackend(current.backend()) || recoveryBlocksPlayer(op.owner())) throw new IllegalStateException("FLUID_RECOVERY_REQUIRED");
        if (!filling && (op.cancellationRequested() || !sameAuthorization(current, op.origin())
                || current.snapshot().amount(intended.fingerprint()) < intended.millibuckets())) throw new IllegalStateException("FLUID_SOURCE_CHANGED");
        var effects = new ArrayList<>(op.effects());
        effects.add(new Effect(witness, filling, current, intended, false, false, false, FluidAmount.EMPTY, null, ""));
        return persist(copy(op, op.extracted(), op.filled(), op.cancellationRequested(), filling ? State.FILL_INTENT : State.DRAIN_INTENT, effects, ""));
    }
    /** Records late native results even after the waiter's cancellation; uncertainty remains explicit. */
    public synchronized Operation observed(Actor actor, UUID id, String witness, FluidInventoryService.Outcome outcome) throws IOException {
        var op = get(actor, id); int index = pending(op, witness); var effect = op.effects().get(index);
        boolean verified = !outcome.entered() || outcome.verified() && outcome.after() != null
                && (outcome.actual().empty() || outcome.actual().unitJson().equals(effect.intended().unitJson()))
                && outcome.actual().millibuckets() <= effect.intended().millibuckets()
                && effect.source().snapshot().verifies(outcome.after(), outcome.actual(), effect.filling());
        if (!outcome.entered() && (!outcome.actual().empty() || !effect.source().snapshot().equals(outcome.after()))) verified = false;
        var effects = new ArrayList<>(op.effects());
        effects.set(index, new Effect(witness, effect.filling(), effect.source(), effect.intended(), true, outcome.entered(), verified,
                outcome.actual(), outcome.after(), effect.proof()));
        int drained = op.extracted(), filled = op.filled();
        if (verified && outcome.entered()) {
            if (effect.filling()) filled = Math.addExact(filled, outcome.actual().millibuckets());
            else drained = Math.addExact(drained, outcome.actual().millibuckets());
        }
        State state = !verified || op.state() == State.RECOVERY_REQUIRED ? State.RECOVERY_REQUIRED
                : phase(op.requested().millibuckets(), drained, filled + op.consumedMb(), op.cancellationRequested());
        return persist(copy(op, drained, filled, op.cancellationRequested(), state, effects,
                !verified ? "FLUID_CONSERVATION_UNCERTAIN" : state == State.RECOVERY_REQUIRED ? "LATE_NATIVE_EVIDENCE" : outcome.reason()));
    }
    /** Use only when the accepted dispatcher proves it never entered the native action. */
    public synchronized Operation rejected(Actor actor, UUID id, String witness, String reason) throws IOException {
        var op = get(actor, id); var effect = op.effects().get(pending(op, witness));
        return observed(actor, id, witness, new FluidInventoryService.Outcome(false, true, FluidAmount.EMPTY, effect.source().snapshot(), reason, null));
    }
    public synchronized Operation nativeSaved(Actor actor, UUID id, String witness, String proof) throws IOException {
        if (proof == null || !proof.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("FLUID_NATIVE_PROOF_INVALID");
        var op = get(actor, id); var effects = new ArrayList<>(op.effects());
        for (int i = 0; i < effects.size(); i++) {
            var effect = effects.get(i); if (!effect.witness().equals(witness)) continue;
            if (!effect.observed() || !effect.entered()) throw new IllegalStateException("FLUID_EFFECT_NOT_OBSERVED");
            if (!effect.proof().isEmpty()) {
                if (!effect.proof().equals(proof)) throw new IllegalStateException("FLUID_NATIVE_PROOF_CHANGED"); return op;
            }
            effects.set(i, new Effect(effect.witness(), effect.filling(), effect.source(), effect.intended(), effect.observed(),
                    effect.entered(), effect.verified(), effect.actual(), effect.after(), proof));
            return persist(copy(op, op.extracted(), op.filled(), op.cancellationRequested(), op.state(), effects, op.reason()));
        }
        throw new IllegalStateException("FLUID_EFFECT_NOT_FOUND");
    }
    public synchronized Operation cancel(Actor actor, UUID id) throws IOException {
        mixedOwnership.standalone(actor, id); return cancelState(actor, id);
    }
    synchronized Operation cancelMixed(Actor actor, UUID id, String receipt) throws IOException {
        mixedOwnership.require(actor, id, receipt); return cancelState(actor, id);
    }
    private Operation cancelState(Actor actor, UUID id) throws IOException {
        var op = get(actor, id); if (op.settled()) return op;
        if (op.cancellationRequested()) return op;
        boolean pending = op.effects().stream().anyMatch(effect -> !effect.observed());
        State state = pending || op.state() == State.RECOVERY_REQUIRED ? State.RECOVERY_REQUIRED
                : phase(op.requested().millibuckets(), op.extracted(), op.filled() + op.consumedMb(), true);
        return persist(copy(op, op.extracted(), op.filled(), true, state, op.effects(), pending ? "NATIVE_EFFECT_PENDING" : "CANCELLED_KEEP_REAL_CUSTODY"));
    }
    public synchronized RecoveryReview recoveryReview(Actor actor, UUID id) {
        var op = get(actor, id);
        if (op.state() != State.RECOVERY_REQUIRED || !op.saved() || op.effects().stream().anyMatch(effect -> !effect.verified()))
            throw new IllegalStateException("FLUID_POSITIVE_NATIVE_PROOF_REQUIRED");
        State restore = phase(op.requested().millibuckets(), op.extracted(), op.filled() + op.consumedMb(), op.cancellationRequested());
        return new RecoveryReview(id, op.revision(), restore, op.escrowMb(), AiJson.digest(AiJson.canonical(op) + ":" + restore));
    }
    public synchronized Operation reconcile(Actor actor, UUID id, long revision, String hash) throws IOException {
        mixedOwnership.standalone(actor, id); return reconcileState(actor, id, revision, hash);
    }
    synchronized Operation reconcileMixed(Actor actor, UUID id, long revision, String hash, String receipt) throws IOException {
        mixedOwnership.require(actor, id, receipt); return reconcileState(actor, id, revision, hash);
    }
    private Operation reconcileState(Actor actor, UUID id, long revision, String hash) throws IOException {
        var review = recoveryReview(actor, id);
        if (review.revision() != revision || !review.reviewHash().equals(hash)) throw new IllegalStateException("FLUID_RECOVERY_REVIEW_CHANGED");
        var op = get(actor, id);
        return persist(copy(op, op.extracted(), op.filled(), op.cancellationRequested(), review.restorePhase(), op.effects(), "RECONCILED_NO_NATIVE_REPLAY"));
    }
    /**
     * Pure journal debit of real custody for a fixed, server-verified mixed recipe. The coordinating
     * journal must hold both ledgers against independent return until its commit/reconciliation ends.
     * This does not fill a tank, create a native-save receipt, or itself manufacture any output.
     */
    public synchronized Operation consumeForRecipe(Actor actor, UUID id, UUID materialOperation, String receipt, int millibuckets) throws IOException {
        mixedOwnership.ifOwned(actor, id, receipt);
        var debit = new RecipeDebit(materialOperation, receipt, millibuckets); var op = get(actor, id);
        var previous = op.recipeDebits().stream().filter(row -> row.materialOperation().equals(materialOperation)).findFirst();
        if (previous.isPresent()) {
            if (!previous.get().equals(debit)) throw new SecurityException("FLUID_RECIPE_RECEIPT_REUSED");
            return op; // Repeating the same pure commit is not another consumption or another output.
        }
        if (op.state() != State.ESCROWED || op.cancellationRequested() || op.recipeDebits().size() >= 128
                || millibuckets > op.escrowMb()) throw new IllegalStateException("FLUID_RECIPE_CUSTODY_UNAVAILABLE");
        var debits = new ArrayList<>(op.recipeDebits()); debits.add(debit);
        State state = phase(op.requested().millibuckets(), op.extracted(), op.filled() + op.consumedMb() + millibuckets, false);
        return persist(new Operation(op.version(), op.id(), op.owner(), op.task(), op.planHash(), op.origin(), op.requested(),
                op.extracted(), op.filled(), false, state, op.effects(), "CONSUMED_BY_BOUND_RECIPE_RECEIPT", op.revision() + 1, debits));
    }
    public synchronized boolean settled(Actor actor) { return !broken && list(actor).stream().allMatch(op -> op.settled() && op.saved()); }
    public synchronized String exportJournal(Actor actor) throws IOException {
        actor.requireAccess(actor.owner()); return AiJson.GSON.toJson(open(actor.owner()).getSnapshot(0).events());
    }
    public synchronized void forgetSettled(Actor actor) {
        if (!settled(actor)) throw new IllegalStateException("FLUID_CUSTODY_REQUIRES_SETTLEMENT");
        operations.values().removeIf(op -> op.owner().equals(actor.owner())); journals.remove(actor.owner());
        mixedOwnership.forget(actor.owner()); publishQuarantine();
    }
    private Operation persist(Operation operation) throws IOException {
        if (broken) throw new IOException("FLUID_JOURNAL_UNAVAILABLE"); validate(operation);
        var data = AiJson.GSON.toJsonTree(operation).getAsJsonObject();
        if (data.toString().length() > 4 * 1048576) throw new IOException("FLUID_OPERATION_LIMIT");
        try {
            open(operation.owner()).append(List.of(new ConversationStore.Draft(operation.task(), "fluid_operation", null, data, operation.id().toString())));
            operations.put(operation.id(), operation); publishQuarantine(); return operation;
        } catch (IOException | RuntimeException failure) { broken = true; throw failure; }
    }
    private void publishQuarantine() {
        var backends = new HashSet<String>(); var owners = new HashSet<String>();
        for (var op : operations.values()) if (op.state() == State.RECOVERY_REQUIRED) {
            backends.add(op.origin().backend()); owners.add(op.owner()); op.effects().forEach(effect -> backends.add(effect.source().backend()));
        }
        blockedBackends = Set.copyOf(backends); blockedOwners = Set.copyOf(owners);
    }
    private static int pending(Operation op, String witness) {
        if (op.effects().isEmpty() || !Set.of(State.DRAIN_INTENT, State.FILL_INTENT, State.RECOVERY_REQUIRED).contains(op.state()))
            throw new IllegalStateException("FLUID_EFFECT_NOT_PENDING");
        int index = op.effects().size() - 1; var effect = op.effects().get(index);
        if (effect.observed() || !effect.witness().equals(witness)) throw new IllegalStateException("FLUID_EVIDENCE_ALREADY_RECORDED_OR_CHANGED");
        return index;
    }
    private static boolean sameAuthorization(FluidInventoryService.Source a, FluidInventoryService.Source b) {
        return a.grantId().equals(b.grantId()) && a.revision() == b.revision() && a.backend().equals(b.backend())
                && a.authorizationFingerprint().equals(b.authorizationFingerprint());
    }
    private static boolean samePlan(Operation a, Operation b) {
        return a.task().equals(b.task()) && a.planHash().equals(b.planHash()) && a.origin().equals(b.origin()) && a.requested().equals(b.requested());
    }
    private static State phase(int requested, int drained, int filled, boolean cancelled) {
        if (drained == filled && (cancelled || drained == requested)) return cancelled ? State.CANCELLED : State.COMPLETED;
        return cancelled || drained == requested ? State.ESCROWED : State.RESERVED;
    }
    private static Operation copy(Operation op, int drained, int filled, boolean cancelled, State state, List<Effect> effects, String reason) {
        return new Operation(op.version(), op.id(), op.owner(), op.task(), op.planHash(), op.origin(), op.requested(),
                drained, filled, cancelled, state, effects, reason, op.revision() + 1, op.recipeDebits());
    }
    private static void validate(Operation op) throws IOException {
        if (op == null || op.version() != 1 || op.id() == null || op.task() == null || op.origin() == null || op.requested() == null
                || op.requested().empty() || op.requested().millibuckets() > 1000000 || op.state() == null || op.revision() < 1
                || op.effects().size() > 256 || op.planHash() == null || !op.planHash().matches("[0-9a-f]{64}") || op.reason() == null
                || op.extracted() < 0 || op.extracted() > op.requested().millibuckets() || op.filled() < 0
                || op.recipeDebits().size() > 128 || op.filled() + (long) op.consumedMb() > op.extracted()
                || op.recipeDebits().stream().map(RecipeDebit::materialOperation).distinct().count() != op.recipeDebits().size())
            throw new IOException("FLUID_JOURNAL_INVALID");
        try { UUID.fromString(op.owner()); } catch (RuntimeException invalid) { throw new IOException("FLUID_JOURNAL_OWNER", invalid); }
        int pending = 0; long drained = 0, filled = 0; var witnesses = new HashSet<String>();
        for (var effect : op.effects()) {
            if (effect == null || effect.witness() == null || !effect.witness().matches("[0-9a-f-]{36}")
                    || !witnesses.add(effect.witness()) || effect.source() == null || effect.intended() == null
                    || effect.intended().empty() || !effect.intended().unitJson().equals(op.requested().unitJson())
                    || effect.intended().millibuckets() > op.requested().millibuckets() || effect.actual() == null
                    || effect.proof() == null || !effect.proof().isEmpty() && !effect.proof().matches("[0-9a-f]{64}"))
                throw new IOException("FLUID_JOURNAL_EFFECT_INVALID");
            if (!effect.observed()) {
                pending++;
                if (effect != op.effects().getLast() || effect.entered() || effect.verified() || !effect.actual().empty()
                        || effect.after() != null || !effect.proof().isEmpty()) throw new IOException("FLUID_JOURNAL_PENDING_INVALID");
            } else if (!effect.verified()) {
                if (op.state() != State.RECOVERY_REQUIRED) throw new IOException("FLUID_JOURNAL_UNCERTAIN_INVALID");
            } else if (effect.entered()) {
                if ((!effect.actual().empty() && !effect.actual().unitJson().equals(op.requested().unitJson()))
                        || effect.actual().millibuckets() > effect.intended().millibuckets()
                        || !effect.source().snapshot().verifies(effect.after(), effect.actual(), effect.filling()))
                    throw new IOException("FLUID_JOURNAL_CONSERVATION_INVALID");
                if (effect.filling()) filled += effect.actual().millibuckets(); else drained += effect.actual().millibuckets();
            } else if (!effect.actual().empty() || !effect.source().snapshot().equals(effect.after()) || !effect.proof().isEmpty())
                throw new IOException("FLUID_JOURNAL_REJECTION_INVALID");
        }
        if (pending > 1 || drained != op.extracted() || filled != op.filled()
                || pending == 0 && op.state() != State.RECOVERY_REQUIRED
                    && op.state() != phase(op.requested().millibuckets(), op.extracted(), op.filled() + op.consumedMb(), op.cancellationRequested())
                || pending != 0 && !Set.of(State.DRAIN_INTENT, State.FILL_INTENT, State.RECOVERY_REQUIRED).contains(op.state()))
            throw new IOException("FLUID_JOURNAL_TOTALS_INVALID");
    }
}
