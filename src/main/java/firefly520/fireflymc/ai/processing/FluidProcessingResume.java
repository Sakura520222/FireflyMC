package firefly520.fireflymc.ai.processing;

import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.util.*;

/** A fresh approval for saved custody and the exact unfulfilled claims of the original plan. */
public record FluidProcessingResume(int version, UUID id, FluidProcessingPlan originalPlan,
        UUID executionEpoch, long recipeGeneration, String dimension, long operationRevision,
        MaterialLedger.Operation itemCustody, List<FluidLedger.Operation> fluidCustody,
        List<FluidProcessingAdapters.Evaluation> evaluations,
        List<FluidProcessingPlan.OutputTarget> outputTargets, String hash) {
    public FluidProcessingResume {
        Objects.requireNonNull(id); Objects.requireNonNull(originalPlan); Objects.requireNonNull(executionEpoch);
        fluidCustody = List.copyOf(fluidCustody); evaluations = List.copyOf(evaluations);
        outputTargets = List.copyOf(outputTargets);
        if (version != 1 || id.equals(originalPlan.id()) || recipeGeneration < 0 || !FluidProcessingPlan.resource(dimension) || operationRevision < 1
                || !originalPlan.intact() || hash == null || !hash.isEmpty() && !hash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("FLUID_RESUME_INVALID");
        requireSavedInputs(originalPlan, itemCustody, fluidCustody);
        requireEvaluations(originalPlan, evaluations);
        requireTargets(originalPlan.fluidOutputs(), outputTargets);
    }
    public UUID operationId() { return originalPlan.id(); }
    public UUID taskId() { return originalPlan.taskId(); }
    public String owner() { return originalPlan.owner(); }
    public UUID serverInstanceId() { return originalPlan.serverInstanceId(); }
    public UUID conversationId() { return originalPlan.conversationId(); }
    public long privacyEpoch() { return originalPlan.privacyEpoch(); }
    public String originalPlanHash() { return originalPlan.hash(); }
    public long itemRevision() { return itemCustody.revision(); }
    public Map<UUID, Long> fluidRevisions() {
        var revisions = new LinkedHashMap<UUID, Long>();
        fluidCustody.forEach(fluid -> revisions.put(fluid.id(), fluid.revision())); return Map.copyOf(revisions);
    }
    public FluidProcessingResume signed() { return withHash(AiJson.digest(AiJson.canonical(withHash("")))); }
    public boolean intact() { return hash.equals(AiJson.digest(AiJson.canonical(withHash("")))); }
    private FluidProcessingResume withHash(String value) {
        return new FluidProcessingResume(version, id, originalPlan, executionEpoch, recipeGeneration, dimension, operationRevision,
                itemCustody, fluidCustody, evaluations, outputTargets, value);
    }
    public void requireIdentity(Workspace workspace, Actor actor, UUID currentEpoch) {
        requireStorageIdentity(workspace, actor);
        requireEpoch(executionEpoch, currentEpoch);
    }
    public void requireStorageIdentity(Workspace workspace, Actor actor) {
        actor.requireAccess(workspace.owner()); actor.requireAccess(owner());
        if (!intact() || !serverInstanceId().equals(workspace.metadata().serverInstanceId())
                || !conversationId().equals(workspace.primaryConversationId())
                || privacyEpoch() != workspace.conversation().privacyEpoch())
            throw new SecurityException("FLUID_RESUME_IDENTITY_OR_HASH_CHANGED");
    }
    static void requireEpoch(UUID expected, UUID current) {
        if (!expected.equals(current)) throw new IllegalStateException("FLUID_RESUME_EXECUTION_EPOCH_CHANGED");
    }
    public void requireCurrent(MixedResourceJournal.Operation operation, MaterialLedger.Operation item,
                               List<FluidLedger.Operation> fluids) {
        requirePending(originalPlan, operation);
        if (operation.revision() != operationRevision || !itemCustody.equals(item) || !fluidCustody.equals(fluids))
            throw new IllegalStateException("FLUID_RESUME_PREVIEW_CHANGED");
        requireSavedInputs(originalPlan, item, fluids);
    }
    public boolean requiresExtraction() {
        return itemCustody.claims().stream().anyMatch(claim -> claim.remaining() > 0)
                || fluidCustody.stream().anyMatch(fluid -> fluid.extracted() < fluid.requested().millibuckets());
    }
    static MixedResourceJournal.Definition definition(FluidProcessingPlan plan) {
        return new MixedResourceJournal.Definition(plan.id(), plan.taskId(), plan.hash(), plan.itemInputs(),
                plan.fluidClaims().stream().map(claim -> new MixedResourceJournal.FluidInput(claim.operationId(), claim.amount())).toList(),
                plan.itemOutputs(), plan.fluidOutputs(), plan.evidence());
    }
    static void requirePending(FluidProcessingPlan plan, MixedResourceJournal.Operation operation) {
        if (!operation.owner().equals(plan.owner()) || !operation.definition().equals(definition(plan)))
            throw new SecurityException("FLUID_RESUME_ORIGINAL_DEFINITION_CHANGED");
        if (operation.returnMode() != MixedResourceJournal.ReturnMode.NONE)
            throw new IllegalStateException("FLUID_RESUME_CANCELLED_COMPLETE_APPROVED_RETURN");
        if (operation.state() != MixedResourceJournal.State.INPUTS_PENDING || !operation.fluidDeliveries().isEmpty())
            throw new IllegalStateException("FLUID_RESUME_RECONCILE_REQUIRED");
    }
    /** Positive receipts and exact saved custody, rather than matching world quantities, are the evidence. */
    static void requireExactInputs(FluidProcessingPlan plan, MaterialLedger.Operation item,
                                   List<FluidLedger.Operation> fluids) {
        requireSavedInputs(plan, item, fluids);
        if (item.state() != MaterialLedger.State.ESCROWED || item.claims().stream().anyMatch(claim -> claim.remaining() != 0)
                || fluids.stream().anyMatch(fluid -> fluid.state() != FluidLedger.State.ESCROWED
                    || fluid.escrowMb() != fluid.requested().millibuckets()))
            throw new IllegalStateException("FLUID_RESUME_EXACT_CUSTODY_REQUIRED_RETURN_AND_REPLAN");
    }
    /** Every extracted prefix needs positive save evidence; the remainder is never treated as held. */
    static void requireSavedInputs(FluidProcessingPlan plan, MaterialLedger.Operation item,
                                   List<FluidLedger.Operation> fluids) {
        if (item == null || !item.operationId().equals(plan.id()) || !item.owner().equals(plan.owner())
                || !item.taskId().equals(plan.taskId()) || !item.planHash().equals(plan.hash())
                || !item.policy().equals("MATERIALS_ONLY") || item.intent() != null
                || !Set.of(MaterialLedger.State.RESERVED, MaterialLedger.State.ESCROWED).contains(item.state()) || !item.products().isEmpty()
                || !item.delivered().isEmpty() || !item.worldDebits().isEmpty() || !item.externalDebits().isEmpty()
                || !FluidProcessingPlan.itemTotals(item.escrow()).equals(FluidProcessingPlan.itemTotals(item.claims().stream()
                    .filter(claim -> claim.extracted() > 0).map(claim -> claim.stock().stack().withCount(claim.extracted())).toList()))
                || item.claims().size() != plan.needs().size()
                || (item.state() == MaterialLedger.State.ESCROWED) != item.claims().stream().allMatch(claim -> claim.remaining() == 0))
            throw new IllegalStateException("FLUID_RESUME_EXACT_CUSTODY_REQUIRED_RETURN_AND_REPLAN");
        for (int index = 0; index < plan.needs().size(); index++) {
            var need = plan.needs().get(index); var claim = item.claims().get(index);
            var source = plan.sources().stream().filter(stock -> stock.key().equals(need.stockKey())).findFirst().orElseThrow();
            if (!claim.stock().equals(source) || claim.requested() != need.count())
                throw new IllegalStateException("FLUID_RESUME_ITEM_CLAIMS_CHANGED");
        }
        if (!item.inputsSaved() || item.claims().stream().anyMatch(claim -> claim.extracted() > 0) && item.nativeSaves().isEmpty() || item.nativeSaves().stream()
                .anyMatch(save -> !save.kind().equals("extract") || !save.receipt().matches("[0-9a-f]{64}")))
            throw new IllegalStateException("FLUID_RESUME_POSITIVE_INPUT_SAVE_REQUIRED");
        if (fluids.size() != plan.fluidClaims().size())
            throw new IllegalStateException("FLUID_RESUME_EXACT_CUSTODY_REQUIRED_RETURN_AND_REPLAN");
        for (int index = 0; index < plan.fluidClaims().size(); index++) {
            var claim = plan.fluidClaims().get(index); var fluid = fluids.get(index);
            if (!fluid.id().equals(claim.operationId()) || !fluid.owner().equals(plan.owner())
                    || !fluid.task().equals(plan.taskId()) || !fluid.planHash().equals(plan.hash())
                    || !fluid.origin().equals(claim.source()) || !fluid.requested().equals(claim.amount())
                    || !Set.of(FluidLedger.State.RESERVED, FluidLedger.State.ESCROWED).contains(fluid.state()) || fluid.cancellationRequested()
                    || fluid.filled() != 0 || !fluid.recipeDebits().isEmpty()
                    || fluid.escrowMb() != fluid.extracted() || fluid.extracted() > claim.amount().millibuckets()
                    || (fluid.state() == FluidLedger.State.ESCROWED) != (fluid.extracted() == claim.amount().millibuckets()))
                throw new IllegalStateException("FLUID_RESUME_EXACT_CUSTODY_REQUIRED_RETURN_AND_REPLAN");
            if (fluid.extracted() > 0 && fluid.effects().isEmpty() || !fluid.inputsSaved() || fluid.effects().stream().anyMatch(effect ->
                    effect.filling() || !effect.observed() || !effect.verified()
                    || effect.entered() && !effect.proof().matches("[0-9a-f]{64}")))
                throw new IllegalStateException("FLUID_RESUME_POSITIVE_INPUT_SAVE_REQUIRED");
        }
    }
    static void requireEvaluations(FluidProcessingPlan original, List<FluidProcessingAdapters.Evaluation> evaluated) {
        if (!original.evaluations().equals(evaluated))
            throw new IllegalStateException("FLUID_RESUME_RECIPE_CHANGED_RETURN_AND_REPLAN");
    }
    static void requireTargets(List<FluidAmount> outputs, List<FluidProcessingPlan.OutputTarget> targets) {
        if (targets.size() > 256) throw new IllegalArgumentException("FLUID_RESUME_TARGET_LIMIT");
        int[] allocated = new int[outputs.size()]; var sources = new LinkedHashMap<String, FluidInventoryService.Source>();
        var keys = new HashSet<String>();
        for (var target : targets) {
            if (target.outputIndex() >= outputs.size() || !outputs.get(target.outputIndex()).unitJson().equals(target.amount().unitJson())
                    || !keys.add(target.outputIndex() + "\n" + target.source().backend()))
                throw new IllegalArgumentException("FLUID_RESUME_OUTPUT_ALLOCATION_CHANGED");
            allocated[target.outputIndex()] = Math.addExact(allocated[target.outputIndex()], target.amount().millibuckets());
            FluidProcessingPlan.mergeSource(sources, target.source());
        }
        for (int index = 0; index < outputs.size(); index++) if (allocated[index] != outputs.get(index).millibuckets())
            throw new IllegalArgumentException("FLUID_RESUME_OUTPUT_NOT_ALLOCATED");
        var shadows = FluidProcessingPlanner.shadows(List.copyOf(sources.values()), List.of());
        for (var target : targets) if (FluidProcessingPlanner.fill(shadows.get(target.source().backend()), target.amount())
                != target.amount().millibuckets()) throw new IllegalArgumentException("FLUID_RESUME_OUTPUT_FULL");
    }
}
