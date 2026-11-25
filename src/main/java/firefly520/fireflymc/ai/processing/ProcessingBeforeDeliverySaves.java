package firefly520.fireflymc.ai.processing;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.material.MaterialLedger;
import firefly520.fireflymc.ai.tools.AgentTool;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** A real data-backed output must not reach inventory before its backing stores are canonical. */
final class ProcessingBeforeDeliverySaves {
    private final MaterialLedger ledger;
    private final Function<UUID, CompletionStage<Void>> readiness;
    ProcessingBeforeDeliverySaves(MaterialLedger ledger, Function<UUID, CompletionStage<Void>> readiness) {
        this.ledger = ledger; this.readiness = readiness;
    }
    void await(AgentTool.Context ctx, UUID id, AgentTool.Continuation continuation) throws Exception {
        ctx.control().check(); ctx.actor().requireAccess(ctx.workspace().owner());
        var op = ledger.get(ctx.actor(), id);
        if (op.uncertain() || op.intent() != null || op.state() != MaterialLedger.State.CALLBACK_DONE)
            throw new IllegalStateException("DELIVERY_NATIVE_STATE_NOT_READY");
        var ready = readiness.apply(id).toCompletableFuture();
        if (ready.isDone()) { requireProof(ctx, id, ready); return; }
        String owner = op.owner(), hash = op.planHash();
        UUID caller = ctx.taskId(), source = op.taskId(), conversation = ctx.workspace().primaryConversationId();
        UUID server = ctx.workspace().metadata().serverInstanceId(); long privacy = ctx.workspace().conversation().privacyEpoch();
        var once = new AtomicBoolean(); var checkpoint = new JsonObject();
        checkpoint.addProperty("operationId", id.toString()); checkpoint.addProperty("planHash", hash);
        throw new AgentTool.Deferred("WAITING_NATIVE_PRODUCTION_SAVE", checkpoint, ready.handle((value, failure) -> null), fresh -> {
            fresh.control().check(); fresh.actor().requireAccess(owner); var current = ledger.get(fresh.actor(), id);
            if (!owner.equals(fresh.workspace().owner()) || !Objects.equals(caller, fresh.taskId()) || !source.equals(current.taskId()) || !hash.equals(current.planHash())
                    || !conversation.equals(fresh.workspace().primaryConversationId())
                    || !server.equals(fresh.workspace().metadata().serverInstanceId())
                    || privacy != fresh.workspace().conversation().privacyEpoch()) throw new SecurityException("DELIVERY_NATIVE_CONTEXT_CHANGED");
            if (!ready.isDone()) throw new IllegalStateException("DELIVERY_NATIVE_JOURNAL_NOT_READY");
            if (!once.compareAndSet(false, true)) throw new IllegalStateException("DELIVERY_NATIVE_CONTINUATION_ALREADY_USED");
            requireProof(fresh, id, ready); return continuation.resume(fresh);
        });
    }
    private void requireProof(AgentTool.Context ctx, UUID id, CompletableFuture<Void> ready) throws Exception {
        try { ready.join(); }
        catch (CompletionException | CancellationException failed) {
            ledger.markUncertain(ctx.actor(), id, "DELIVERY_NATIVE_SAVE_JOURNAL_FAILED");
            throw new IllegalStateException("DELIVERY_NATIVE_SAVE_JOURNAL_FAILED");
        }
        var op = ledger.get(ctx.actor(), id);
        if (op.uncertain() || op.intent() != null || op.state() != MaterialLedger.State.CALLBACK_DONE
                || !op.saved() || !MaterialLedger.nativeProductionBackingSaved(op)) {
            if (!op.uncertain()) ledger.markUncertain(ctx.actor(), id, "DELIVERY_NATIVE_SAVE_PROOF_UNAVAILABLE");
            throw new IllegalStateException("DELIVERY_NATIVE_SAVE_PROOF_UNAVAILABLE");
        }
    }
}
