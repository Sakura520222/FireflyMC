package firefly520.fireflymc.ai.blueprint;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.tools.AgentTool;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Waits for canonical writes and their journal callbacks; continuations never dispatch a native action here. */
final class NativeWorldCompletionSaves {
    @FunctionalInterface interface Saved { boolean test(AgentTool.Context context) throws Exception; }
    @FunctionalInterface interface Missing { void mark(AgentTool.Context context) throws Exception; }
    private NativeWorldCompletionSaves() {}

    static void await(AgentTool.Context ctx, UUID plan, Collection<UUID> operations,
            Function<UUID, CompletionStage<Void>> readiness, Saved saved, Missing missing,
            AgentTool.Continuation continuation) throws Exception {
        ctx.control().check(); ctx.actor().requireAccess(ctx.workspace().owner());
        var ids = List.copyOf(operations);
        if (ids.isEmpty() || ids.size() > 512 || ids.stream().distinct().count() != ids.size())
            throw new IllegalArgumentException("WORLD_SAVE_OPERATIONS_INVALID");
        var stages = ids.stream().map(id -> readiness.apply(id).toCompletableFuture()).toArray(CompletableFuture[]::new);
        var ready = CompletableFuture.allOf(stages);
        if (ready.isDone()) {
            try { if (!ready.isCompletedExceptionally() && saved.test(ctx)) return; }
            catch (Exception unavailable) {
                missing.mark(ctx); throw new IllegalStateException("RECOVERY_REQUIRED", unavailable);
            }
            missing.mark(ctx); throw new IllegalStateException("RECOVERY_REQUIRED");
        }
        String owner = ctx.actor().owner(); UUID task = ctx.taskId(), conversation = ctx.workspace().primaryConversationId();
        UUID server = ctx.workspace().metadata().serverInstanceId(); long privacy = ctx.workspace().conversation().privacyEpoch();
        var checkpoint = new JsonObject(); checkpoint.addProperty("planId", plan.toString());
        checkpoint.addProperty("operationId", ids.getFirst().toString());
        checkpoint.addProperty("operations", ids.size()); checkpoint.addProperty("phase", "WAITING_WORLD_NATIVE_SAVE");
        // Completion only schedules the runtime. It must never lock a workspace or touch Minecraft.
        throw new AgentTool.Deferred("WAITING_WORLD_NATIVE_SAVE", checkpoint, ready.handle((ignored, failure) -> null), fresh -> {
            fresh.control().check(); fresh.actor().requireAccess(owner);
            if (!fresh.workspace().owner().equals(owner) || !Objects.equals(task, fresh.taskId())
                    || !conversation.equals(fresh.workspace().primaryConversationId())
                    || !server.equals(fresh.workspace().metadata().serverInstanceId())
                    || fresh.workspace().conversation().privacyEpoch() != privacy)
                throw new SecurityException("WORLD_SAVE_WAIT_CONTEXT_CHANGED");
            await(fresh, plan, ids, readiness, saved, missing, continuation);
            return continuation.resume(fresh);
        });
    }
}
