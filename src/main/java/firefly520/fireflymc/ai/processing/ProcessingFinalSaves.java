package firefly520.fireflymc.ai.processing;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.material.MaterialLedger;
import firefly520.fireflymc.ai.tools.AgentTool;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Final receipts wait for canonical native writes AND their durable journal writers, never replaying effects. */
final class ProcessingFinalSaves {
    private final MaterialLedger ledger;
    private final Function<UUID, CompletionStage<Void>> readiness;

    ProcessingFinalSaves(MaterialLedger ledger, Function<UUID, CompletionStage<Void>> readiness) {
        this.ledger = Objects.requireNonNull(ledger); this.readiness = Objects.requireNonNull(readiness);
    }

    AgentTool.Result complete(AgentTool.Context ctx, UUID id, Function<MaterialLedger.Operation, AgentTool.Result> result) throws Exception {
        var operation = current(ctx, id);
        if (!operation.settled()) {
            var partial = result.apply(operation);
            if (partial.success()) return missing(ctx, id, "FINAL_OPERATION_NOT_SETTLED");
            return partial;
        }
        if (operation.intent() != null) return missing(ctx, id, "FINAL_NATIVE_INTENT_UNRESOLVED");
        final CompletableFuture<Void> ready;
        try { ready = Objects.requireNonNull(readiness.apply(id)).toCompletableFuture(); }
        catch (RuntimeException unavailable) { return missing(ctx, id, "FINAL_NATIVE_JOURNAL_READINESS_UNAVAILABLE"); }
        if (ready.isDone()) return finished(ctx, id, ready, result);

        String owner = operation.owner(), planHash = operation.planHash();
        UUID sourceTask = operation.taskId(), callerTask = ctx.taskId(), conversation = ctx.workspace().primaryConversationId();
        UUID server = ctx.workspace().metadata().serverInstanceId();
        long privacy = ctx.workspace().conversation().privacyEpoch();
        var returned = new AtomicBoolean();
        var checkpoint = new JsonObject(); checkpoint.addProperty("operationId", id.toString());
        checkpoint.addProperty("planHash", planHash); checkpoint.addProperty("phase", "WAITING_FINAL_NATIVE_SAVE");
        // A completion/failure only wakes the existing accepted invocation; no native work belongs here.
        throw new AgentTool.Deferred("WAITING_FINAL_NATIVE_SAVE", checkpoint, ready.handle((ignored, failure) -> null), fresh -> {
            fresh.control().check(); fresh.actor().requireAccess(owner);
            var now = current(fresh, id);
            if (!owner.equals(fresh.workspace().owner()) || !Objects.equals(callerTask, fresh.taskId())
                    || !Objects.equals(sourceTask, now.taskId()) || !planHash.equals(now.planHash())
                    || !conversation.equals(fresh.workspace().primaryConversationId())
                    || !server.equals(fresh.workspace().metadata().serverInstanceId())
                    || privacy != fresh.workspace().conversation().privacyEpoch())
                throw new SecurityException("FINAL_NATIVE_WAIT_CONTEXT_CHANGED");
            if (!ready.isDone()) throw new IllegalStateException("FINAL_NATIVE_JOURNAL_NOT_READY");
            if (!returned.compareAndSet(false, true)) throw new IllegalStateException("FINAL_NATIVE_RECEIPT_ALREADY_RETURNED");
            return finished(fresh, id, ready, result);
        });
    }

    private MaterialLedger.Operation current(AgentTool.Context ctx, UUID id) {
        ctx.control().check(); ctx.actor().requireAccess(ctx.workspace().owner());
        return ledger.get(ctx.actor(), id);
    }

    private AgentTool.Result finished(AgentTool.Context ctx, UUID id, CompletableFuture<Void> ready,
                                      Function<MaterialLedger.Operation, AgentTool.Result> result) throws Exception {
        current(ctx, id);
        try { ready.join(); }
        catch (CompletionException | CancellationException failed) { return missing(ctx, id, "FINAL_NATIVE_SAVE_JOURNAL_FAILED"); }
        var operation = current(ctx, id);
        if (operation.uncertain() || operation.intent() != null || !operation.settled())
            return missing(ctx, id, "FINAL_NATIVE_STATE_REQUIRES_REVIEW");
        if (!operation.saved()) return missing(ctx, id, "FINAL_NATIVE_SAVE_PROOF_UNAVAILABLE");
        return result.apply(operation);
    }

    private AgentTool.Result missing(AgentTool.Context ctx, UUID id, String reason) throws IOException {
        var operation = current(ctx, id);
        if (!operation.uncertain()) operation = ledger.markUncertain(ctx.actor(), id, reason);
        var data = new JsonObject(); data.addProperty("operationId", id.toString()); data.addProperty("state", operation.state().name());
        data.addProperty("nativeSaveConfirmed", operation.saved()); data.addProperty("reason", operation.reason());
        data.addProperty("actuallyDeliveredUnits", operation.delivered().stream().mapToLong(Integer::longValue).sum());
        data.addProperty("heldInputUnits", operation.escrow().stream().mapToLong(stack -> stack.count()).sum());
        data.addProperty("undeliveredOutputUnits", operation.remainingProducts().stream().mapToLong(stack -> stack.count()).sum());
        return new AgentTool.Result(false, "RECOVERY_REQUIRED", "实际原料、回调与交付记录已保留；最终原生保存或日志凭据未齐全，不能宣称完成，不重放或补发。",
                data, "material-final-native-save:" + id, "", false);
    }
}
