package firefly520.fireflymc.ai.material;

import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Fixed mixed-recipe commit across real item/fluid custody. Keep one instance per live workspace.
 * An intent fences partial journal commits; reopening requires an explicit proof-bound recovery.
 * This journal never performs native extraction, refunds, callbacks or delivery on its own.
 */
public final class MixedResourceJournal {
    public enum State { INPUTS_PENDING, COMMIT_INTENT, COMMITTED, FLUID_DELIVERY_INTENT, RETURN_INTENT, RETURNING, RETURNED, RECOVERY_REQUIRED }
    public enum ReturnMode { NONE, INPUTS, OUTPUTS }
    public record FluidInput(UUID operationId, FluidAmount amount) {
        public FluidInput { if (operationId == null || amount == null || amount.empty()) throw new IllegalArgumentException("MIXED_FLUID_INPUT_INVALID"); }
    }
    public record Definition(UUID id, UUID taskId, String planHash, List<MaterialStack> itemInputs,
                             List<FluidInput> fluidInputs, List<MaterialStack> itemOutputs,
                             List<FluidAmount> fluidOutputs, String recipeEvidence) {
        public Definition {
            itemInputs = List.copyOf(itemInputs); fluidInputs = List.copyOf(fluidInputs);
            itemOutputs = List.copyOf(itemOutputs); fluidOutputs = List.copyOf(fluidOutputs);
            if (id == null || taskId == null || planHash == null || !planHash.matches("[0-9a-f]{64}")
                    || itemInputs.isEmpty() || itemInputs.size() > 128 || itemInputs.stream().anyMatch(MaterialStack::empty)
                    || fluidInputs.size() > 64 || itemOutputs.size() > 128 || fluidOutputs.size() > 64
                    || itemOutputs.stream().anyMatch(MaterialStack::empty) || fluidOutputs.stream().anyMatch(FluidAmount::empty)
                    || itemInputs.stream().mapToLong(MaterialStack::count).sum() > 8192
                    || itemOutputs.stream().mapToLong(MaterialStack::count).sum() > 8192
                    || fluidInputs.stream().mapToLong(input -> input.amount().millibuckets()).sum() > 1000000
                    || fluidOutputs.stream().mapToLong(FluidAmount::millibuckets).sum() > 1000000
                    || fluidInputs.stream().map(FluidInput::operationId).distinct().count() != fluidInputs.size()
                    || recipeEvidence == null || recipeEvidence.isBlank() || recipeEvidence.length() > 4096)
                throw new IllegalArgumentException("MIXED_RECIPE_DEFINITION_INVALID");
        }
    }
    public record FluidDelivery(int outputIndex, FluidLedger.Effect effect) {}
    public record Operation(int version, String owner, Definition definition, State state, String receipt,
                            String reason, long revision, List<FluidDelivery> fluidDeliveries,
                            ReturnMode returnMode, State recoveryPhase) {
        public Operation {
            fluidDeliveries = fluidDeliveries == null ? List.of() : List.copyOf(fluidDeliveries);
            returnMode = returnMode == null ? ReturnMode.NONE : returnMode;
            recoveryPhase = recoveryPhase == null ? state : recoveryPhase;
        }
        public Operation(int version, String owner, Definition definition, State state, String receipt,
                         String reason, long revision, List<FluidDelivery> fluidDeliveries) {
            this(version, owner, definition, state, receipt, reason, revision, fluidDeliveries, ReturnMode.NONE, state);
        }
        public int deliveredMb(int outputIndex) {
            return fluidDeliveries.stream().filter(delivery -> delivery.outputIndex() == outputIndex
                    && delivery.effect().observed() && delivery.effect().entered() && delivery.effect().verified())
                    .mapToInt(delivery -> delivery.effect().actual().millibuckets()).reduce(0, Math::addExact);
        }
        public boolean fluidDeliveriesSaved() {
            return fluidDeliveries.stream().map(FluidDelivery::effect).allMatch(effect -> effect.observed() && effect.verified()
                    && (!effect.entered() || !effect.proof().isEmpty()));
        }
    }
    /** Only existing custody is exposed. Output rows retain their definition index, including EMPTY. */
    public record ReturnView(UUID operationId, State phase, List<MaterialStack> items,
                             List<FluidInput> inputFluids, List<FluidAmount> outputFluids) {
        public ReturnView { items = List.copyOf(items); inputFluids = List.copyOf(inputFluids); outputFluids = List.copyOf(outputFluids); }
    }
    public record RecoveryReview(UUID operationId, long revision, String reviewHash, boolean materialAlreadyProduced,
                                 List<UUID> alreadyConsumedFluids, List<UUID> stillHeldFluids) {
        public RecoveryReview { alreadyConsumedFluids = List.copyOf(alreadyConsumedFluids); stillHeldFluids = List.copyOf(stillHeldFluids); }
    }
    private record Evidence(MaterialLedger.Operation item, List<FluidLedger.Operation> fluids,
                            Map<UUID, FluidLedger.RecoveryReview> fluidReviews, MaterialLedger.RecoveryReview itemReview) {}
    private final Workspace workspace;
    private final MaterialLedger itemLedger;
    private final FluidLedger fluidLedger;
    private final ConversationStore journal;
    private final long privacyEpoch;
    private final Map<UUID, Operation> operations = new LinkedHashMap<>();
    private volatile boolean broken;
    private volatile Set<UUID> ownedOperations = Set.of();
    private volatile List<Operation> resourceView = List.of();
    private volatile Set<String> blockedBackends = Set.of(), blockedOwners = Set.of();

    public MixedResourceJournal(Workspace workspace, MaterialLedger items, FluidLedger fluids) throws IOException {
        this.workspace = workspace;
        itemLedger = Objects.requireNonNull(items); fluidLedger = Objects.requireNonNull(fluids);
        synchronized (workspace.conversation()) {
            privacyEpoch = workspace.conversation().privacyEpoch();
            Path folder = workspace.directory().resolve("mixed-materials"), file = folder.resolve("journal.jsonl");
            if (Files.isSymbolicLink(folder) || Files.isSymbolicLink(file)) throw new IOException("MIXED_UNSAFE_PATH");
            journal = new ConversationStore(file);
            for (var event : journal.getSnapshot(0).events()) {
                if (!event.kind().equals("mixed_material_operation")) throw new IOException("MIXED_JOURNAL_EVENT");
                var value = AiJson.GSON.fromJson(event.data(), Operation.class); validate(value);
                var previous = operations.get(value.definition().id());
                if (value.revision() != (previous == null ? 1 : previous.revision() + 1)
                        || previous != null && (!previous.definition().equals(value.definition())
                            || previous.returnMode() != ReturnMode.NONE && value.returnMode() != previous.returnMode())) throw new IOException("MIXED_JOURNAL_IDENTITY");
                // Older records have no recoveryPhase. Their preceding durable phase is evidence.
                if (value.state() == State.RECOVERY_REQUIRED && value.recoveryPhase() == State.RECOVERY_REQUIRED && previous != null)
                    value = new Operation(value.version(), value.owner(), value.definition(), value.state(), value.receipt(),
                            value.reason(), value.revision(), value.fluidDeliveries(), value.returnMode(), previous.recoveryPhase());
                operations.put(value.definition().id(), value);
                bindOwnership(value);
            }
            publishOwnership();
            var owner = new Actor(workspace.owner(), true, false, 0);
            for (var value : List.copyOf(operations.values())) if (value.state() != State.RECOVERY_REQUIRED && !linkedSettlement(owner, value))
                persist(copy(value, State.RECOVERY_REQUIRED, "REOPENED_MIXED_RESOURCE_REVIEW_REQUIRED"));
        }
    }
    /** Lock-free ownership fence for standalone processing return/reconcile entry points. */
    public boolean owns(UUID materialOrFluidOperation) { return ownedOperations.contains(materialOrFluidOperation); }
    public boolean journalAvailable() { return !broken && workspace.conversation().privacyEpoch() == privacyEpoch; }
    public boolean recoveryBlocksBackend(String backend) { return broken || blockedBackends.contains(backend); }
    public boolean recoveryBlocksPlayer(String owner) { return broken || blockedOwners.contains(owner); }
    public synchronized Optional<Operation> find(Actor actor, UUID id) { access(actor); return Optional.ofNullable(operations.get(id)); }
    public synchronized boolean settled(Actor actor, UUID id) { return linkedSettlement(actor, get(actor, id)); }
    /** Privacy gate must not take this journal's monitor while holding the main-conversation lock. */
    public boolean resourcesSettled(Actor actor) {
        actor.requireAccess(workspace.owner());
        if (broken || workspace.conversation().privacyEpoch() != privacyEpoch) return false;
        for (var op : resourceView) if (!linkedSettlement(actor, op)) return false;
        return true;
    }
    public boolean resourcesSettledForTask(Actor actor, UUID task) {
        actor.requireAccess(workspace.owner());
        if (broken || workspace.conversation().privacyEpoch() != privacyEpoch) return false;
        for (var op : resourceView) if (op.definition().taskId().equals(task) && !linkedSettlement(actor, op)) return false;
        return true;
    }
    public boolean resourceOperationSettled(Actor actor, UUID id) {
        access(actor);
        return resourceView.stream().filter(op -> op.definition().id().equals(id)).findFirst()
                .map(op -> linkedSettlement(actor, op)).orElse(false);
    }
    private boolean linkedSettlement(Actor actor, Operation op) {
        if (op.returnMode() != ReturnMode.NONE) {
            if (op.state() != State.RETURNED || !op.fluidDeliveriesSaved()) return false;
            try { return returnSettled(inspectReturn(actor, op, true), op); }
            catch (RuntimeException unavailable) { return false; }
        }
        if (op.state() != State.COMMITTED || !op.fluidDeliveriesSaved()) return false;
        for (int i = 0; i < op.definition().fluidOutputs().size(); i++)
            if (op.deliveredMb(i) != op.definition().fluidOutputs().get(i).millibuckets()) return false;
        try {
            Evidence evidence = inspect(actor, op, itemLedger, fluidLedger, true);
            return evidence.item().state() == MaterialLedger.State.COMPLETED && evidence.item().saved()
                    && evidence.fluids().stream().allMatch(fluid -> fluid.state() == FluidLedger.State.COMPLETED && fluid.saved());
        } catch (RuntimeException unavailable) { return false; }
    }
    public synchronized Operation create(Actor actor, Definition definition) throws IOException {
        access(actor); var previous = operations.get(definition.id());
        if (previous != null) {
            if (!previous.definition().equals(definition)) throw new SecurityException("MIXED_OPERATION_REUSED"); return previous;
        }
        if (operations.size() >= 128) throw new IllegalStateException("MIXED_OPERATION_LIMIT");
        if (owns(definition.id()) || definition.fluidInputs().stream().anyMatch(input -> owns(input.operationId())))
            throw new SecurityException("MIXED_RESOURCE_ALREADY_OWNED");
        String receipt = AiJson.digest("mixed-materials-v1:" + workspace.owner() + ":" + AiJson.canonical(definition));
        return persist(new Operation(1, actor.owner(), definition, State.INPUTS_PENDING, receipt, "", 1, List.of()));
    }
    public synchronized Operation get(Actor actor, UUID id) {
        access(actor); var value = operations.get(id); if (value == null) throw new IllegalArgumentException("MIXED_OPERATION_NOT_FOUND"); return value;
    }
    public synchronized List<Operation> list(Actor actor) { access(actor); return List.copyOf(operations.values()); }
    /** Read-only published snapshot for callers already holding the primary-conversation lock. */
    public List<Operation> snapshot(Actor actor) { access(actor); return resourceView; }
    /** Records cancellation before either linked ledger changes; never dispatches a native refund. */
    public synchronized ReturnView beginReturn(Actor actor, UUID id) throws IOException {
        var op = get(actor, id);
        if (op.returnMode() != ReturnMode.NONE) return continueReturn(actor, id);
        State basis = op.state() == State.RECOVERY_REQUIRED ? op.recoveryPhase() : op.state();
        ReturnMode mode = basis == State.INPUTS_PENDING ? ReturnMode.INPUTS : ReturnMode.OUTPUTS;
        boolean uncertain = op.state() == State.RECOVERY_REQUIRED || basis == State.COMMIT_INTENT
                || basis == State.FLUID_DELIVERY_INTENT;
        op = persist(new Operation(op.version(), op.owner(), op.definition(), uncertain ? State.RECOVERY_REQUIRED : State.RETURN_INTENT,
                op.receipt(), "RETURN_REQUESTED", op.revision() + 1, op.fluidDeliveries(), mode, basis));
        return uncertain ? blockedReturnView(op) : continueReturn(actor, id);
    }
    public synchronized ReturnView continueReturn(Actor actor, UUID id) throws IOException {
        var op = get(actor, id); require(op.returnMode() != ReturnMode.NONE, "MIXED_RETURN_NOT_REQUESTED");
        if (op.state() == State.RECOVERY_REQUIRED) return blockedReturnView(op);
        if (op.state() == State.FLUID_DELIVERY_INTENT)
            return blockedReturnView(persist(copy(op, State.RECOVERY_REQUIRED, "MIXED_RETURN_NATIVE_RESULT_PENDING")));
        require(Set.of(State.RETURN_INTENT, State.RETURNING, State.RETURNED).contains(op.state()), "MIXED_PHASE_CHANGED");
        try {
            var linkedItem = itemLedger.find(actor, id).orElse(null);
            boolean pending = linkedItem != null && linkedItem.intent() != null;
            for (var input : op.definition().fluidInputs()) {
                var fluid = fluidLedger.find(actor, input.operationId()).orElse(null);
                pending |= fluid != null && fluid.effects().stream().anyMatch(effect -> !effect.observed());
            }
            if (pending) return blockedReturnView(persist(copy(op, State.RECOVERY_REQUIRED, "MIXED_RETURN_NATIVE_RESULT_PENDING")));
            Evidence evidence = inspectReturn(actor, op, false);
            if (evidence.itemReview() != null || !evidence.fluidReviews().isEmpty())
                return blockedReturnView(persist(copy(op, State.RECOVERY_REQUIRED, "MIXED_RETURN_LINKED_RECOVERY_REQUIRED")));
            if (op.returnMode() == ReturnMode.INPUTS) {
                if (evidence.item() != null) itemLedger.cancelMixed(actor, id, op.receipt());
                for (var fluid : evidence.fluids()) fluidLedger.cancelMixed(actor, fluid.id(), op.receipt());
            }
            evidence = inspectReturn(actor, op, false);
            State next = returnSettled(evidence, op) ? State.RETURNED : State.RETURNING;
            if (op.state() != next) op = persist(copy(op, next, "RETURN_ONLY_EXISTING_CUSTODY"));
            return returnView(op, evidence);
        } catch (IOException | RuntimeException failure) {
            if (!broken) try { persist(copy(get(actor, id), State.RECOVERY_REQUIRED, "PARTIAL_MIXED_RETURN_REQUIRES_REVIEW")); }
            catch (IOException | RuntimeException recordingFailure) { failure.addSuppressed(recordingFailure); }
            throw failure;
        }
    }
    /** The caller must freshly authorize the target and use the returned intent before native fill. */
    public synchronized FluidLedger.Operation beginFluidReturn(Actor actor, UUID id, UUID fluidId, String witness,
                                                               FluidInventoryService.Source target, FluidAmount intended) throws IOException {
        var op = get(actor, id); require(op.state() == State.RETURNING && op.returnMode() == ReturnMode.INPUTS, "MIXED_PHASE_CHANGED");
        Evidence evidence = inspectReturn(actor, op, false);
        require(evidence.itemReview() == null && evidence.fluidReviews().isEmpty(), "MIXED_LINKED_RECOVERY_REQUIRED");
        require(evidence.fluids().stream().anyMatch(fluid -> fluid.id().equals(fluidId) && fluid.cancellationRequested()
                && fluid.recipeDebits().isEmpty()), "MIXED_FLUID_RETURN_NOT_OWNED");
        return fluidLedger.beginReturnMixed(actor, fluidId, op.receipt(), witness, target, intended);
    }
    /** Only a newly approved immutable review may reopen extraction after recovery; reconciliation cannot. */
    public synchronized Operation approveRemainingInputs(Actor actor, UUID id, long revision, String reviewHash) throws IOException {
        var op = get(actor, id);
        require(op.state() == State.INPUTS_PENDING && op.returnMode() == ReturnMode.NONE && op.revision() == revision,
                "MIXED_RESUME_APPROVAL_CHANGED");
        require(reviewHash != null && reviewHash.matches("[0-9a-f]{64}"), "MIXED_RESUME_APPROVAL_INVALID");
        var evidence = inspectRecovery(actor, op);
        require(evidence.itemReview() == null && evidence.fluidReviews().isEmpty() && evidence.item().inputsSaved()
                && evidence.fluids().stream().allMatch(FluidLedger.Operation::inputsSaved), "MIXED_INPUT_SAVE_REQUIRED");
        op = persist(copy(op, State.INPUTS_PENDING, "REMAINDER_APPROVED:" + reviewHash));
        itemLedger.approveMixedRemainingInputs(actor, id, op.receipt());
        for (var input : op.definition().fluidInputs()) fluidLedger.approveMixedRemainingInputs(actor, input.operationId(), op.receipt());
        return op;
    }

    /** Adapter revalidation must happen immediately before this call; the definition/hash is immutable. */
    public synchronized Operation waitForInputSave(Actor actor, UUID id) throws IOException {
        var op = get(actor, id); require(op.state() == State.INPUTS_PENDING, "MIXED_PHASE_CHANGED");
        inspect(actor, op, itemLedger, fluidLedger, false);
        if (op.reason().equals("WAITING_INPUT_SAVE")) return op;
        return persist(copy(op, State.INPUTS_PENDING, "WAITING_INPUT_SAVE"));
    }
    /** Adapter revalidation must happen immediately before this call; the definition/hash is immutable. */
    public synchronized Operation prepareCommit(Actor actor, UUID id, MaterialLedger items, FluidLedger fluids) throws IOException {
        var op = get(actor, id); require(op.state() == State.INPUTS_PENDING, "MIXED_PHASE_CHANGED");
        Evidence inputs = inspect(actor, op, items, fluids, false);
        require(inputs.item().inputsSaved() && inputs.fluids().stream().allMatch(FluidLedger.Operation::inputsSaved), "MIXED_INPUT_SAVE_REQUIRED");
        return persist(copy(op, State.COMMIT_INTENT, "FIXED_INPUTS_AND_OUTPUTS_READY"));
    }
    /**
     * No native calls are replayed. A partially completed pure commit stays behind the durable intent;
     * if interrupted by IO failure/restart, only explicit recovery may complete its remaining writes.
     */
    public synchronized Operation commit(Actor actor, UUID id, MaterialLedger items, FluidLedger fluids) throws IOException {
        var op = get(actor, id); require(op.state() == State.COMMIT_INTENT, "MIXED_PHASE_CHANGED");
        try { return finishCommit(actor, op, items, fluids); }
        catch (IOException | RuntimeException failure) {
            if (!broken) try { persist(copy(get(actor, id), State.RECOVERY_REQUIRED, "PARTIAL_MIXED_COMMIT_REQUIRES_REVIEW")); }
            catch (IOException | RuntimeException recordingFailure) { failure.addSuppressed(recordingFailure); }
            throw failure;
        }
    }
    private Operation finishCommit(Actor actor, Operation op, MaterialLedger items, FluidLedger fluids) throws IOException {
        inspect(actor, op, items, fluids, true);
        for (var input : op.definition().fluidInputs())
            fluids.consumeForRecipe(actor, input.operationId(), op.definition().id(), op.receipt(), input.amount().millibuckets());
        var item = items.get(actor, op.definition().id());
        if (item.state() == MaterialLedger.State.ESCROWED)
            items.produce(actor, item.operationId(), op.definition().itemOutputs(), "mixed-recipe:" + op.receipt());
        // Fluid products are now real private virtual-processing custody owned by this record.
        // Their delivery is a separate, journalled native operation; nothing is poured or dropped here.
        return persist(copy(op, State.COMMITTED, "REAL_INPUTS_CONSUMED_OUTPUTS_IN_PRIVATE_CUSTODY"));
    }
    public synchronized RecoveryReview recoveryReview(Actor actor, UUID id, MaterialLedger items, FluidLedger fluids) {
        var op = get(actor, id); require(op.state() == State.RECOVERY_REQUIRED, "MIXED_PHASE_CHANGED");
        require(op.fluidDeliveriesSaved(), "MIXED_FLUID_DELIVERY_PROOF_REQUIRED");
        require(items == itemLedger && fluids == fluidLedger, "MIXED_LEDGER_INSTANCE_CHANGED");
        Evidence evidence = inspectRecovery(actor, op);
        require((evidence.item() == null || evidence.item().saved()) && evidence.fluids().stream().allMatch(FluidLedger.Operation::saved),
                "MIXED_RECOVERY_SAVE_PROOF_REQUIRED");
        var consumed = new ArrayList<UUID>(); var held = new ArrayList<UUID>();
        for (var input : op.definition().fluidInputs()) {
            var fluid = fluids.find(actor, input.operationId()).orElse(null);
            if (fluid == null) continue;
            (fluid.consumedMb() == input.amount().millibuckets() ? consumed : held).add(input.operationId());
        }
        return new RecoveryReview(id, op.revision(), AiJson.digest(op.receipt() + ":" + op.revision() + ":" + AiJson.canonical(evidence)),
                op.returnMode() != ReturnMode.INPUTS && evidence.item() != null && Set.of(MaterialLedger.State.PRODUCED, MaterialLedger.State.CALLBACK_DONE, MaterialLedger.State.COMPLETED)
                        .contains(effectiveItemState(evidence)), consumed, held);
    }
    public synchronized Operation reconcile(Actor actor, UUID id, long revision, String hash, MaterialLedger items, FluidLedger fluids) throws IOException {
        var review = recoveryReview(actor, id, items, fluids);
        require(review.revision() == revision && review.reviewHash().equals(hash), "MIXED_REVIEW_CHANGED");
        var op = get(actor, id); Evidence evidence = inspectRecovery(actor, op);
        if (evidence.itemReview() != null) items.reconcileMixed(actor, id, evidence.itemReview().revision(), evidence.itemReview().reviewHash(), op.receipt());
        for (var entry : evidence.fluidReviews().entrySet())
            fluids.reconcileMixed(actor, entry.getKey(), entry.getValue().revision(), entry.getValue().reviewHash(), op.receipt());
        // Reconciliation completes only missing pure ledger writes, never extraction or delivery.
        if (op.returnMode() == ReturnMode.INPUTS) {
            persist(copy(op, State.RETURN_INTENT, "RECONCILED_RETURN_NO_NATIVE_REPLAY"));
            continueReturn(actor, id); return get(actor, id);
        }
        if (op.recoveryPhase() == State.INPUTS_PENDING) {
            // No durable commit intent ever authorized consuming these inputs. A save receipt
            // proves custody only; it cannot revalidate a recipe, dimension or revoked grant.
            return persist(copy(op, State.INPUTS_PENDING, "RECONCILED_INPUT_CUSTODY_ONLY"));
        }
        var result = finishCommit(actor, op, items, fluids);
        if (op.returnMode() == ReturnMode.OUTPUTS) {
            persist(copy(result, State.RETURN_INTENT, "RECONCILED_OUTPUT_RETURN_NO_NATIVE_REPLAY"));
            continueReturn(actor, id); return get(actor, id);
        }
        return result;
    }
    public synchronized List<FluidAmount> heldFluidOutputs(Actor actor, UUID id) {
        var op = get(actor, id); require(op.state() == State.COMMITTED
                || op.returnMode() == ReturnMode.OUTPUTS && Set.of(State.RETURNING, State.RETURNED).contains(op.state()), "MIXED_COMMIT_NOT_CONFIRMED");
        var held = new ArrayList<FluidAmount>();
        for (int i = 0; i < op.definition().fluidOutputs().size(); i++) {
            var fluid = op.definition().fluidOutputs().get(i); held.add(fluid.withAmount(fluid.millibuckets() - op.deliveredMb(i)));
        }
        return List.copyOf(held); // Preserve product indexes, including EMPTY for a fully delivered row.
    }
    public synchronized Operation beginFluidDelivery(Actor actor, UUID id, int outputIndex, String witness,
                                                       FluidInventoryService.Source target, FluidAmount intended) throws IOException {
        var op = get(actor, id); require(op.state() == State.COMMITTED
                || op.state() == State.RETURNING && op.returnMode() == ReturnMode.OUTPUTS, "MIXED_PHASE_CHANGED");
        Evidence linked = inspect(actor, op, itemLedger, fluidLedger, true);
        require(linked.itemReview() == null && linked.fluidReviews().isEmpty()
                && linked.item().state() != MaterialLedger.State.ESCROWED, "MIXED_LINKED_RECOVERY_REQUIRED");
        require(outputIndex >= 0 && outputIndex < op.definition().fluidOutputs().size() && witness != null
                && witness.matches("[0-9a-f-]{36}") && op.fluidDeliveries().size() < 4096
                && op.fluidDeliveries().stream().noneMatch(delivery -> delivery.effect().witness().equals(witness)), "MIXED_FLUID_DELIVERY_INVALID");
        var output = op.definition().fluidOutputs().get(outputIndex);
        require(target != null && intended != null && !intended.empty() && intended.unitJson().equals(output.unitJson())
                && intended.millibuckets() <= output.millibuckets() - op.deliveredMb(outputIndex), "MIXED_FLUID_CUSTODY_UNAVAILABLE");
        var deliveries = new ArrayList<>(op.fluidDeliveries());
        deliveries.add(new FluidDelivery(outputIndex, new FluidLedger.Effect(witness, true, target, intended,
                false, false, false, FluidAmount.EMPTY, null, "")));
        return persist(withDeliveries(op, State.FLUID_DELIVERY_INTENT, deliveries, ""));
    }
    /** The recorder outlives the task waiter; cancellation cannot discard an accepted native fill. */
    public synchronized Operation fluidDeliveryObserved(Actor actor, UUID id, String witness, FluidInventoryService.Outcome outcome) throws IOException {
        var op = get(actor, id); int index = pendingDelivery(op, witness); var previous = op.fluidDeliveries().get(index);
        var effect = previous.effect();
        boolean verified = outcome.entered() ? outcome.verified() && outcome.after() != null
                && (outcome.actual().empty() || outcome.actual().unitJson().equals(effect.intended().unitJson()))
                && outcome.actual().millibuckets() <= effect.intended().millibuckets()
                && effect.source().snapshot().verifies(outcome.after(), outcome.actual(), true)
                : outcome.actual().empty() && effect.source().snapshot().equals(outcome.after());
        var deliveries = new ArrayList<>(op.fluidDeliveries());
        deliveries.set(index, new FluidDelivery(previous.outputIndex(), new FluidLedger.Effect(witness, true, effect.source(), effect.intended(),
                true, outcome.entered(), verified, outcome.actual(), outcome.after(), effect.proof())));
        boolean uncertain = !verified || op.state() == State.RECOVERY_REQUIRED;
        return persist(withDeliveries(op, uncertain ? State.RECOVERY_REQUIRED : op.returnMode() == ReturnMode.OUTPUTS ? State.RETURNING : State.COMMITTED, deliveries,
                !verified ? "MIXED_FLUID_DELIVERY_UNCERTAIN" : uncertain ? "LATE_MIXED_FLUID_DELIVERY_RECORDED" : outcome.reason()));
    }
    public synchronized Operation fluidDeliveryRejected(Actor actor, UUID id, String witness, String reason) throws IOException {
        var op = get(actor, id); var effect = op.fluidDeliveries().get(pendingDelivery(op, witness)).effect();
        return fluidDeliveryObserved(actor, id, witness, new FluidInventoryService.Outcome(false, true, FluidAmount.EMPTY, effect.source().snapshot(), reason, null));
    }
    public synchronized Operation fluidDeliverySaved(Actor actor, UUID id, String witness, String proof) throws IOException {
        require(proof != null && proof.matches("[0-9a-f]{64}"), "MIXED_FLUID_SAVE_PROOF_INVALID");
        var op = get(actor, id); var deliveries = new ArrayList<>(op.fluidDeliveries());
        for (int i = 0; i < deliveries.size(); i++) {
            var delivery = deliveries.get(i); var effect = delivery.effect(); if (!effect.witness().equals(witness)) continue;
            require(effect.observed() && effect.entered(), "MIXED_FLUID_NATIVE_EFFECT_NOT_OBSERVED");
            if (!effect.proof().isEmpty()) { require(effect.proof().equals(proof), "MIXED_FLUID_SAVE_PROOF_CHANGED"); return op; }
            deliveries.set(i, new FluidDelivery(delivery.outputIndex(), new FluidLedger.Effect(witness, true, effect.source(), effect.intended(),
                    effect.observed(), effect.entered(), effect.verified(), effect.actual(), effect.after(), proof)));
            return persist(withDeliveries(op, op.state(), deliveries, op.reason()));
        }
        throw new IllegalStateException("MIXED_FLUID_DELIVERY_NOT_FOUND");
    }
    public synchronized Operation requireRecovery(Actor actor, UUID id, String reason) throws IOException {
        var op = get(actor, id); return persist(copy(op, State.RECOVERY_REQUIRED, Objects.requireNonNull(reason)));
    }
    public synchronized String exportJournal(Actor actor) { access(actor); return AiJson.GSON.toJson(journal.getSnapshot(0).events()); }

    private Evidence inspectRecovery(Actor actor, Operation op) {
        require(itemLedger.journalAvailable() && fluidLedger.journalAvailable(), "MIXED_LINKED_JOURNAL_UNAVAILABLE");
        if (op.returnMode() == ReturnMode.INPUTS) return inspectReturn(actor, op, true);
        if (op.returnMode() != ReturnMode.NONE || op.recoveryPhase() != State.INPUTS_PENDING)
            return inspect(actor, op, itemLedger, fluidLedger, true);
        // Before the commit intent, recovering a positively saved prefix only reopens input custody.
        // Missing reservations, unknown results, returns and recipe debits cannot acquire this path.
        var definition = op.definition(); var item = itemLedger.get(actor, definition.id());
        require(item.taskId().equals(definition.taskId()) && item.planHash().equals(definition.planHash())
                && item.intent() == null && item.products().isEmpty() && item.delivered().isEmpty()
                && item.worldDebits().isEmpty() && item.externalDebits().isEmpty(), "MIXED_ITEM_BINDING_CHANGED");
        var itemReview = item.uncertain() ? itemLedger.recoveryReview(actor, item.operationId()) : null;
        var itemState = itemReview == null ? item.state() : itemReview.restorePhase();
        require(Set.of(MaterialLedger.State.RESERVED, MaterialLedger.State.ESCROWED).contains(itemState)
                && itemTotals(item.claims().stream().map(claim -> claim.stock().stack().withCount(claim.requested())).toList())
                    .equals(itemTotals(definition.itemInputs()))
                && itemTotals(item.escrow()).equals(itemTotals(item.claims().stream().filter(claim -> claim.extracted() > 0)
                    .map(claim -> claim.stock().stack().withCount(claim.extracted())).toList())), "MIXED_ITEM_CUSTODY_CHANGED");
        var inputs = new ArrayList<FluidLedger.Operation>(); var reviews = new LinkedHashMap<UUID, FluidLedger.RecoveryReview>();
        for (var input : definition.fluidInputs()) {
            var fluid = fluidLedger.get(actor, input.operationId()); inputs.add(fluid);
            require(fluid.task().equals(definition.taskId()) && fluid.planHash().equals(definition.planHash())
                    && fluid.requested().equals(input.amount()) && !fluid.cancellationRequested() && fluid.filled() == 0
                    && fluid.recipeDebits().isEmpty() && fluid.escrowMb() == fluid.extracted(), "MIXED_FLUID_BINDING_CHANGED");
            var review = fluid.state() == FluidLedger.State.RECOVERY_REQUIRED ? fluidLedger.recoveryReview(actor, fluid.id()) : null;
            if (review != null) reviews.put(fluid.id(), review);
            require(Set.of(FluidLedger.State.RESERVED, FluidLedger.State.ESCROWED).contains(review == null ? fluid.state() : review.restorePhase()),
                    "MIXED_FLUID_CUSTODY_CHANGED");
        }
        return new Evidence(item, List.copyOf(inputs), Map.copyOf(reviews), itemReview);
    }

    private Evidence inspectReturn(Actor actor, Operation op, boolean proofRequired) {
        require(itemLedger.journalAvailable() && fluidLedger.journalAvailable(), "MIXED_LINKED_JOURNAL_UNAVAILABLE");
        if (op.returnMode() == ReturnMode.OUTPUTS) {
            Evidence evidence = inspect(actor, op, itemLedger, fluidLedger, true);
            require(effectiveItemState(evidence) != MaterialLedger.State.ESCROWED, "MIXED_COMMIT_NOT_CONFIRMED");
            if (proofRequired) require(evidence.item().saved() && evidence.fluids().stream().allMatch(FluidLedger.Operation::saved)
                    && op.fluidDeliveriesSaved(), "MIXED_RETURN_SAVE_PROOF_REQUIRED");
            return evidence;
        }
        require(op.returnMode() == ReturnMode.INPUTS && op.fluidDeliveries().isEmpty(), "MIXED_RETURN_MODE_CHANGED");
        var definition = op.definition();
        var item = itemLedger.find(actor, definition.id()).orElse(null);
        MaterialLedger.RecoveryReview itemReview = null;
        if (item != null) {
            require(item.taskId().equals(definition.taskId()) && item.planHash().equals(definition.planHash())
                    && item.intent() == null && item.worldDebits().isEmpty() && item.externalDebits().isEmpty(), "MIXED_ITEM_BINDING_CHANGED");
            if (item.uncertain()) itemReview = itemLedger.recoveryReview(actor, item.operationId());
            var state = itemReview == null ? item.state() : itemReview.restorePhase();
            var extracted = itemTotals(item.claims().stream().filter(claim -> claim.extracted() > 0)
                    .map(claim -> claim.stock().stack().withCount(claim.extracted())).toList());
            var claimed = itemTotals(item.claims().stream().map(claim -> claim.stock().stack().withCount(claim.requested())).toList());
            var planned = itemTotals(definition.itemInputs());
            require(claimed.entrySet().stream().allMatch(entry -> entry.getValue() <= planned.getOrDefault(entry.getKey(), 0L)), "MIXED_ITEM_CLAIMS_CHANGED");
            boolean inputs = state == MaterialLedger.State.RESERVED || state == MaterialLedger.State.ESCROWED;
            require(inputs ? item.products().isEmpty() && itemTotals(item.escrow()).equals(extracted)
                    : Set.of(MaterialLedger.State.CALLBACK_DONE, MaterialLedger.State.COMPLETED, MaterialLedger.State.CANCELLED).contains(state)
                        && item.escrow().isEmpty() && itemTotals(item.products()).equals(extracted), "MIXED_RETURN_ITEM_CUSTODY_CHANGED");
            if (proofRequired) require(item.saved(), "MIXED_RETURN_SAVE_PROOF_REQUIRED");
        }
        var inputs = new ArrayList<FluidLedger.Operation>();
        var reviews = new LinkedHashMap<UUID, FluidLedger.RecoveryReview>();
        for (var input : definition.fluidInputs()) {
            var fluid = fluidLedger.find(actor, input.operationId()).orElse(null);
            if (fluid == null) continue; // Cancellation before reservation never invents an input.
            require(fluid.task().equals(definition.taskId()) && fluid.planHash().equals(definition.planHash())
                    && fluid.requested().equals(input.amount()) && fluid.recipeDebits().isEmpty(), "MIXED_RETURN_FLUID_BINDING_CHANGED");
            FluidLedger.RecoveryReview review = fluid.state() == FluidLedger.State.RECOVERY_REQUIRED ? fluidLedger.recoveryReview(actor, fluid.id()) : null;
            var state = review == null ? fluid.state() : review.restorePhase();
            require(Set.of(FluidLedger.State.RESERVED, FluidLedger.State.ESCROWED, FluidLedger.State.CANCELLED).contains(state), "MIXED_RETURN_NATIVE_RESULT_PENDING");
            if (review != null) reviews.put(fluid.id(), review);
            if (proofRequired) require(fluid.saved(), "MIXED_RETURN_SAVE_PROOF_REQUIRED");
            inputs.add(fluid);
        }
        return new Evidence(item, List.copyOf(inputs), Map.copyOf(reviews), itemReview);
    }
    private static boolean returnSettled(Evidence evidence, Operation op) {
        if (evidence.itemReview() != null || !evidence.fluidReviews().isEmpty()
                || evidence.item() != null && (!evidence.item().settled() || !evidence.item().saved())
                || evidence.fluids().stream().anyMatch(fluid -> !fluid.settled() || !fluid.saved()) || !op.fluidDeliveriesSaved()) return false;
        if (op.returnMode() == ReturnMode.OUTPUTS) for (int i = 0; i < op.definition().fluidOutputs().size(); i++)
            if (op.deliveredMb(i) != op.definition().fluidOutputs().get(i).millibuckets()) return false;
        return true;
    }
    private static ReturnView blockedReturnView(Operation op) {
        return new ReturnView(op.definition().id(), op.state(), List.of(), List.of(), List.of());
    }
    private static ReturnView returnView(Operation op, Evidence evidence) {
        var inputs = new ArrayList<FluidInput>(); var outputs = new ArrayList<FluidAmount>();
        if (op.returnMode() == ReturnMode.INPUTS) for (var fluid : evidence.fluids())
            if (fluid.escrowMb() > 0) inputs.add(new FluidInput(fluid.id(), fluid.requested().withAmount(fluid.escrowMb())));
        if (op.returnMode() == ReturnMode.OUTPUTS) for (int i = 0; i < op.definition().fluidOutputs().size(); i++) {
            var output = op.definition().fluidOutputs().get(i); outputs.add(output.withAmount(output.millibuckets() - op.deliveredMb(i)));
        }
        return new ReturnView(op.definition().id(), op.state(), evidence.item() == null ? List.of() : evidence.item().remainingProducts(), inputs, outputs);
    }

    private Evidence inspect(Actor actor, Operation op, MaterialLedger items, FluidLedger fluids, boolean partialAllowed) {
        require(items == itemLedger && fluids == fluidLedger, "MIXED_LEDGER_INSTANCE_CHANGED");
        require(items.journalAvailable() && fluids.journalAvailable(), "MIXED_LINKED_JOURNAL_UNAVAILABLE");
        var definition = op.definition(); var item = items.get(actor, definition.id());
        require(item.taskId().equals(definition.taskId()) && item.planHash().equals(definition.planHash())
                && item.intent() == null && item.externalDebits().isEmpty() && item.worldDebits().isEmpty(), "MIXED_ITEM_BINDING_CHANGED");
        MaterialLedger.RecoveryReview itemReview = item.uncertain() ? items.recoveryReview(actor, item.operationId()) : null;
        var itemState = itemReview == null ? item.state() : itemReview.restorePhase();
        boolean held = itemState == MaterialLedger.State.ESCROWED;
        require(held ? itemTotals(item.escrow()).equals(itemTotals(definition.itemInputs()))
                : partialAllowed && Set.of(MaterialLedger.State.PRODUCED, MaterialLedger.State.CALLBACK_DONE, MaterialLedger.State.COMPLETED).contains(itemState)
                    && itemTotals(item.products()).equals(itemTotals(definition.itemOutputs())), "MIXED_ITEM_CUSTODY_CHANGED");
        var inputs = new ArrayList<FluidLedger.Operation>(); var reviews = new LinkedHashMap<UUID, FluidLedger.RecoveryReview>();
        for (var input : definition.fluidInputs()) {
            var fluid = fluids.get(actor, input.operationId()); inputs.add(fluid);
            require(fluid.task().equals(definition.taskId()) && fluid.planHash().equals(definition.planHash())
                    && fluid.requested().equals(input.amount()) && fluid.extracted() == input.amount().millibuckets()
                    && fluid.filled() == 0 && !fluid.cancellationRequested(), "MIXED_FLUID_BINDING_CHANGED");
            FluidLedger.RecoveryReview review = fluid.state() == FluidLedger.State.RECOVERY_REQUIRED ? fluids.recoveryReview(actor, fluid.id()) : null;
            if (review != null) reviews.put(fluid.id(), review);
            var state = review == null ? fluid.state() : review.restorePhase();
            var expected = new FluidLedger.RecipeDebit(definition.id(), op.receipt(), input.amount().millibuckets());
            require(fluid.recipeDebits().isEmpty() ? state == FluidLedger.State.ESCROWED && fluid.escrowMb() == input.amount().millibuckets()
                    : partialAllowed && state == FluidLedger.State.COMPLETED && fluid.recipeDebits().equals(List.of(expected)), "MIXED_FLUID_CUSTODY_CHANGED");
            if (!held) require(fluid.recipeDebits().equals(List.of(expected)), "MIXED_ITEM_PRODUCED_BEFORE_FLUID_DEBIT");
        }
        require(partialAllowed || itemReview == null && reviews.isEmpty(), "MIXED_LINKED_RECOVERY_REQUIRED");
        return new Evidence(item, List.copyOf(inputs), Map.copyOf(reviews), itemReview);
    }
    private static MaterialLedger.State effectiveItemState(Evidence evidence) { return evidence.itemReview() == null ? evidence.item().state() : evidence.itemReview().restorePhase(); }
    private static Map<String, Long> itemTotals(List<MaterialStack> stacks) {
        var totals = new HashMap<String, Long>(); stacks.stream().filter(stack -> !stack.empty()).forEach(stack -> totals.merge(stack.itemJson(), (long) stack.count(), Math::addExact)); return totals;
    }
    private Operation persist(Operation value) throws IOException {
        if (broken) throw new IOException("MIXED_JOURNAL_UNAVAILABLE"); validate(value);
        synchronized (workspace.conversation()) {
            if (workspace.conversation().privacyEpoch() != privacyEpoch) throw new IllegalStateException("WORKSPACE_PRIVACY_EPOCH_CHANGED");
            var data = AiJson.GSON.toJsonTree(value).getAsJsonObject();
            if (data.toString().length() > 4 * 1048576) throw new IOException("MIXED_JOURNAL_SIZE_LIMIT");
            try {
                journal.append(List.of(new ConversationStore.Draft(value.definition().taskId(), "mixed_material_operation", null, data, value.definition().id().toString())));
                operations.put(value.definition().id(), value); bindOwnership(value); publishOwnership(); return value;
            } catch (IOException | RuntimeException failure) { broken = true; throw failure; }
        }
    }
    private void publishOwnership() {
        var ids = new HashSet<UUID>();
        var backends = new HashSet<String>(); var owners = new HashSet<String>();
        for (var op : operations.values()) {
            ids.add(op.definition().id()); op.definition().fluidInputs().forEach(input -> ids.add(input.operationId()));
            if (op.state() == State.RECOVERY_REQUIRED) {
                owners.add(op.owner()); op.fluidDeliveries().forEach(delivery -> backends.add(delivery.effect().source().backend()));
                // The coordinator may fence a still-running linked native action before its recorder
                // completes. Include those backends even when the linked ledger is not uncertain yet.
                var actor = new Actor(op.owner(), true, false, 0);
                itemLedger.find(actor, op.definition().id()).ifPresent(item -> item.claims().forEach(claim -> backends.add(claim.stock().backendId())));
                for (var input : op.definition().fluidInputs()) fluidLedger.find(actor, input.operationId()).ifPresent(fluid -> {
                    backends.add(fluid.origin().backend()); fluid.effects().forEach(effect -> backends.add(effect.source().backend()));
                });
            }
        }
        ownedOperations = Set.copyOf(ids);
        resourceView = List.copyOf(operations.values());
        blockedBackends = Set.copyOf(backends); blockedOwners = Set.copyOf(owners);
    }
    private void bindOwnership(Operation op) {
        boolean closed = op.returnMode() != ReturnMode.NONE || op.state() != State.INPUTS_PENDING;
        itemLedger.bindMixed(op.owner(), op.definition().id(), op.receipt(), closed);
        op.definition().fluidInputs().forEach(input -> fluidLedger.bindMixed(op.owner(), input.operationId(), op.receipt(), closed));
    }
    private void validate(Operation value) throws IOException {
        if (value == null || value.version() != 1 || !workspace.owner().equals(value.owner()) || value.definition() == null
                || value.state() == null || value.reason() == null || value.revision() < 1
                || !AiJson.digest("mixed-materials-v1:" + workspace.owner() + ":" + AiJson.canonical(value.definition())).equals(value.receipt()))
            throw new IOException("MIXED_JOURNAL_INVALID");
        if (value.fluidDeliveries().size() > 4096) throw new IOException("MIXED_FLUID_DELIVERY_LIMIT");
        var witnesses = new HashSet<String>(); int pending = 0; var accounted = new int[value.definition().fluidOutputs().size()];
        if (Set.of(State.RETURN_INTENT, State.RETURNING, State.RETURNED).contains(value.state()) && value.returnMode() == ReturnMode.NONE
                || value.returnMode() == ReturnMode.INPUTS && (!value.fluidDeliveries().isEmpty()
                    || Set.of(State.COMMIT_INTENT, State.COMMITTED, State.FLUID_DELIVERY_INTENT).contains(value.state()))
                || value.returnMode() != ReturnMode.NONE && value.state() == State.INPUTS_PENDING) throw new IOException("MIXED_RETURN_ENCODING_INVALID");
        if (!value.fluidDeliveries().isEmpty() && !Set.of(State.COMMITTED, State.FLUID_DELIVERY_INTENT, State.RECOVERY_REQUIRED,
                State.RETURN_INTENT, State.RETURNING, State.RETURNED).contains(value.state()))
            throw new IOException("MIXED_FLUID_DELIVERY_BEFORE_COMMIT");
        for (var delivery : value.fluidDeliveries()) {
            var effect = delivery.effect();
            if (delivery.outputIndex() < 0 || delivery.outputIndex() >= value.definition().fluidOutputs().size() || effect == null
                    || !effect.filling() || effect.witness() == null || !effect.witness().matches("[0-9a-f-]{36}")
                    || !witnesses.add(effect.witness()) || effect.source() == null || effect.intended() == null || effect.actual() == null
                    || effect.intended().empty() || effect.proof() == null || !effect.proof().isEmpty() && !effect.proof().matches("[0-9a-f]{64}")
                    || !effect.intended().unitJson().equals(value.definition().fluidOutputs().get(delivery.outputIndex()).unitJson())
                    || effect.intended().millibuckets() > value.definition().fluidOutputs().get(delivery.outputIndex()).millibuckets() - accounted[delivery.outputIndex()])
                throw new IOException("MIXED_FLUID_DELIVERY_ENCODING_INVALID");
            if (!effect.observed()) {
                pending++;
                if (delivery != value.fluidDeliveries().getLast() || effect.entered() || effect.verified() || !effect.actual().empty()
                        || effect.after() != null || !effect.proof().isEmpty()) throw new IOException("MIXED_PENDING_FLUID_ENCODING_INVALID");
            } else if (!effect.verified()) {
                if (value.state() != State.RECOVERY_REQUIRED || delivery != value.fluidDeliveries().getLast()) throw new IOException("MIXED_UNCERTAIN_FLUID_ENCODING_INVALID");
            } else if (effect.entered()) {
                if ((!effect.actual().empty() && !effect.actual().unitJson().equals(effect.intended().unitJson()))
                        || effect.actual().millibuckets() > effect.intended().millibuckets()
                        || !effect.source().snapshot().verifies(effect.after(), effect.actual(), true)) throw new IOException("MIXED_FLUID_CONSERVATION_INVALID");
                accounted[delivery.outputIndex()] = Math.addExact(accounted[delivery.outputIndex()], effect.actual().millibuckets());
            } else if (!effect.actual().empty() || !effect.source().snapshot().equals(effect.after()) || !effect.proof().isEmpty())
                throw new IOException("MIXED_FLUID_REJECTION_ENCODING_INVALID");
        }
        if (pending > 1 || pending != 0 && !Set.of(State.FLUID_DELIVERY_INTENT, State.RECOVERY_REQUIRED).contains(value.state())
                || pending == 0 && value.state() == State.FLUID_DELIVERY_INTENT) throw new IOException("MIXED_FLUID_PENDING_STATE_INVALID");
        for (int i = 0; i < value.definition().fluidOutputs().size(); i++)
            if (value.deliveredMb(i) > value.definition().fluidOutputs().get(i).millibuckets()) throw new IOException("MIXED_FLUID_OVERDELIVERY");
    }
    private void access(Actor actor) {
        actor.requireAccess(workspace.owner());
        if (workspace.conversation().privacyEpoch() != privacyEpoch) throw new IllegalStateException("WORKSPACE_PRIVACY_EPOCH_CHANGED");
        if (broken) throw new IllegalStateException("MIXED_JOURNAL_UNAVAILABLE");
    }
    private static Operation copy(Operation value, State state, String reason) {
        return withDeliveries(value, state, value.fluidDeliveries(), reason);
    }
    private static Operation withDeliveries(Operation value, State state, List<FluidDelivery> deliveries, String reason) {
        return new Operation(value.version(), value.owner(), value.definition(), state, value.receipt(), reason, value.revision() + 1,
                deliveries, value.returnMode(), state == State.RECOVERY_REQUIRED ? value.recoveryPhase() : state);
    }
    private static int pendingDelivery(Operation value, String witness) {
        require(Set.of(State.FLUID_DELIVERY_INTENT, State.RECOVERY_REQUIRED).contains(value.state()) && !value.fluidDeliveries().isEmpty(), "MIXED_FLUID_DELIVERY_NOT_PENDING");
        int index = value.fluidDeliveries().size() - 1; var delivery = value.fluidDeliveries().get(index);
        require(!delivery.effect().observed() && delivery.effect().witness().equals(witness), "MIXED_FLUID_DELIVERY_ALREADY_RECORDED"); return index;
    }
    private static void require(boolean valid, String code) { if (!valid) throw new IllegalStateException(code); }
}
