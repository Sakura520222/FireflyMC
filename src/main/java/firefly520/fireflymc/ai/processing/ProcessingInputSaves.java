package firefly520.fireflymc.ai.processing;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.material.MaterialLedger;
import firefly520.fireflymc.ai.tools.AgentTool;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** Waits for real extraction saves without holding a tool worker or converting its escrow. */
final class ProcessingInputSaves {
    private final MaterialLedger ledger;
    private final Function<UUID, CompletionStage<Void>> readiness;

    ProcessingInputSaves(MaterialLedger ledger, Function<UUID, CompletionStage<Void>> readiness) {
        this.ledger = Objects.requireNonNull(ledger);
        this.readiness = Objects.requireNonNull(readiness);
    }

    void await(AgentTool.Context ctx, UUID id, AgentTool.Continuation continuation) throws Exception {
        var op = escrow(ctx, id);
        if (op.inputsSaved()) return;
        String owner = op.owner(), planHash = op.planHash();
        UUID taskId = op.taskId();
        long privacyEpoch = ctx.workspace().conversation().privacyEpoch();
        var ready = readiness.apply(id).toCompletableFuture();
        if (ready.isDone()) {
            requireProof(ctx, id);
            return;
        }
        JsonObject checkpoint = new JsonObject();
        checkpoint.addProperty("operationId", id.toString());
        checkpoint.addProperty("planHash", planHash);
        checkpoint.addProperty("phase", "WAITING_INPUT_SAVE");
        // The observer completes only after its ledger writer; callbacks here touch neither disk nor Minecraft.
        throw new AgentTool.Deferred("WAITING_INPUT_SAVE", checkpoint, ready.handle((ignored, failure) -> null), fresh -> {
            fresh.control().check();
            fresh.actor().requireAccess(owner);
            if (!owner.equals(fresh.workspace().owner()) || !Objects.equals(taskId, fresh.taskId())
                    || fresh.workspace().conversation().privacyEpoch() != privacyEpoch)
                throw new SecurityException("MATERIAL_INPUT_WAIT_CONTEXT_CHANGED");
            var current = escrow(fresh, id);
            if (!planHash.equals(current.planHash())) throw new SecurityException("MATERIAL_INPUT_WAIT_PLAN_CHANGED");
            requireProof(fresh, id);
            return continuation.resume(fresh);
        });
    }

    private MaterialLedger.Operation escrow(AgentTool.Context ctx, UUID id) {
        ctx.control().check();
        ctx.actor().requireAccess(ctx.workspace().owner());
        var op = ledger.get(ctx.actor(), id);
        if (!Objects.equals(op.taskId(), ctx.taskId())) throw new SecurityException("MATERIAL_TASK_MISMATCH");
        if (op.uncertain()) throw new IllegalStateException("RECOVERY_REQUIRED");
        if (op.state() != MaterialLedger.State.ESCROWED || op.intent() != null
                || op.claims().stream().anyMatch(claim -> claim.remaining() != 0))
            throw new IllegalStateException("MATERIAL_INPUT_WAIT_STATE_CHANGED");
        return op;
    }

    private void requireProof(AgentTool.Context ctx, UUID id) throws Exception {
        if (escrow(ctx, id).inputsSaved()) return;
        ledger.markUncertain(ctx.actor(), id, "INPUT_NATIVE_SAVE_PROOF_UNAVAILABLE");
        throw new IllegalStateException("RECOVERY_REQUIRED");
    }
}
