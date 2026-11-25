package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import java.util.*;

/** Historical native placement settlement. Matching current blocks never substitute for these receipts. */
public final class SavedPlacementProofs {
    public record StepProof(UUID operationId, long version, String observationDigest, String receipt, int consumed) {}
    public record Review(UUID operationId, UUID taskId, String owner, long materialRevision, String materialDigest,
                         List<StepProof> steps, String hash) {
        public Review { steps = List.copyOf(steps); }
        public boolean intact() { return hash.equals(digest(new Review(operationId, taskId, owner, materialRevision, materialDigest, steps, ""))); }
        public void requireCurrent(Actor actor, MaterialLedger.Operation operation, List<BuildJournal.Step> actual) {
            actor.requireAccess(owner);
            var current = inspect(actor, operation, actual);
            if (!intact() || !equals(current)) throw new IllegalStateException("WORLD_REVIEW_CHANGED");
        }
    }
    private SavedPlacementProofs() {}
    public static Review inspect(Actor actor, MaterialLedger.Operation operation, List<BuildJournal.Step> recordedSteps) {
        actor.requireAccess(operation.owner());
        require(Set.of(MaterialLedger.State.RECOVERY_REQUIRED, MaterialLedger.State.ESCROWED, MaterialLedger.State.COMPLETED).contains(operation.state())
                && operation.intent() == null && operation.products().isEmpty() && operation.delivered().isEmpty()
                && operation.externalDebits().isEmpty() && operation.sampledOutputs().isEmpty() && operation.samplingEvidence().isEmpty()
                && operation.claims().stream().allMatch(claim -> claim.remaining() == 0), "WORLD_MATERIAL_PHASE_UNSUPPORTED");
        require(operation.saved() && operation.inputsSaved(), "WORLD_NATIVE_SAVE_PROOF_REQUIRED");
        require(!operation.worldDebits().isEmpty() && operation.worldDebits().size() <= 8192, "WORLD_NATIVE_RECEIPTS_REQUIRED");
        var steps = new LinkedHashMap<UUID, BuildJournal.Step>();
        for (var step : recordedSteps) if (step.materialOperationId().equals(operation.operationId())) {
            require(steps.putIfAbsent(step.operationId(), step) == null, "WORLD_DUPLICATE_STEP");
            require(step.owner().equals(actor.owner()) && step.taskId().equals(operation.taskId())
                    && step.planId().equals(operation.operationId()) && step.planHash().equals(operation.planHash()), "WORLD_STEP_BINDING_CHANGED");
        }
        var proof = new ArrayList<StepProof>(); var counted = new HashSet<UUID>();
        var remaining = new HashMap<String, Long>();
        for (var claim : operation.claims()) if (claim.extracted() > 0) remaining.merge(claim.stock().stack().itemJson(), (long) claim.extracted(), Math::addExact);
        for (var debit : operation.worldDebits()) {
            var step = steps.get(debit.stepId()); require(step != null && counted.add(debit.stepId()), "WORLD_STEP_RECEIPT_MISSING");
            require(Set.of(BuildJournal.State.OBSERVED, BuildJournal.State.VERIFIED, BuildJournal.State.REJECTED,
                    BuildJournal.State.RECOVERY_REQUIRED, BuildJournal.State.RECONCILED).contains(step.state()), "WORLD_STEP_PHASE_UNSUPPORTED");
            var observed = step.observation();
            require(observed != null && !observed.uncertain() && observed.otherItems().isEmpty(), "WORLD_NATIVE_OUTCOME_UNKNOWN");
            require(step.input().count() == 1 && debit.itemFingerprint().equals(step.input().fingerprint()), "WORLD_INPUT_RECEIPT_CHANGED");
            String expected = AiJson.digest(step.executionEpoch() + ":" + step.planId() + ":" + step.operationId() + ":" + AiJson.canonical(observed));
            require(expected.equals(debit.receipt()) && (step.receipt().isEmpty() || step.receipt().equals(expected)), "WORLD_NATIVE_RECEIPT_CHANGED");
            var actual = new HashMap<BlueprintDocument.Position, BlueprintDocument.State>();
            for (var cell : observed.cells()) require(actual.put(cell.position(), cell.state()) == null, "WORLD_DUPLICATE_OBSERVATION");
            require(actual.size() == step.cells().size(), "WORLD_NATIVE_FOOTPRINT_INCOMPLETE");
            if (debit.count() == 1) {
                require(observed.success() && observed.mutationEntered() && observed.remaining().empty()
                        && step.cells().stream().allMatch(cell -> Objects.equals(cell.target() == null ? cell.before() : cell.target(), actual.get(cell.position()))),
                        "WORLD_NATIVE_PLACEMENT_UNVERIFIED");
                long left = remaining.getOrDefault(step.input().itemJson(), 0L) - 1;
                require(left >= 0, "WORLD_MATERIAL_CONSERVATION_UNVERIFIED");
                if (left == 0) remaining.remove(step.input().itemJson()); else remaining.put(step.input().itemJson(), left);
            } else {
                require(debit.count() == 0 && !observed.success() && observed.remaining().equals(step.input())
                        && step.cells().stream().allMatch(cell -> cell.before().equals(actual.get(cell.position()))), "WORLD_NATIVE_REJECTION_UNVERIFIED");
            }
            if (observed.mutationEntered()) require(operation.nativeSaves().stream().anyMatch(save -> save.kind().equals("world")
                    && save.witness().equals(step.operationId().toString()) && save.receipt().matches("[0-9a-f]{64}")), "WORLD_NATIVE_SAVE_PROOF_REQUIRED");
            proof.add(new StepProof(step.operationId(), step.version(), AiJson.digest(AiJson.canonical(observed)), expected, debit.count()));
        }
        for (var step : steps.values()) if (!counted.contains(step.operationId()))
            require((step.state() == BuildJournal.State.PREPARED || step.state() == BuildJournal.State.REJECTED
                    && step.reason().equals("UNSTARTED_WORLD_STEP_CANCELLED_NO_NATIVE_REPLAY") && step.receipt().isEmpty())
                    && step.observation() == null, "WORLD_UNACCOUNTED_NATIVE_STEP");
        var escrow = new HashMap<String, Long>();
        for (var stack : operation.escrow()) if (!stack.empty()) escrow.merge(stack.itemJson(), (long) stack.count(), Math::addExact);
        require(remaining.equals(escrow), "WORLD_MATERIAL_CONSERVATION_UNVERIFIED");
        var review = new Review(operation.operationId(), operation.taskId(), operation.owner(), operation.revision(),
                AiJson.digest(AiJson.canonical(operation)), proof, "");
        return new Review(review.operationId(), review.taskId(), review.owner(), review.materialRevision(), review.materialDigest(), review.steps(), digest(review));
    }
    private static String digest(Review review) { return AiJson.digest(AiJson.canonical(review)); }
    private static void require(boolean valid, String code) { if (!valid) throw new IllegalStateException(code); }
}
