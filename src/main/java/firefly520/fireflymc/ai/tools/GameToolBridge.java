package firefly520.fireflymc.ai.tools;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.security.*;
import net.minecraft.server.MinecraftServer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.*;

/** The only Agent-to-game dispatch boundary. Queued work checks cancellation again on the main thread. */
public final class GameToolBridge {
    private final MinecraftServer server;
    private final Function<String, Actor> resolveActor;
    private final Predicate<String> enabled;
    private final BooleanSupplier stopping;
    private final Executor io;
    private final Predicate<String> yoloMode;

    public GameToolBridge(MinecraftServer server, Function<String, Actor> resolveActor,
                          Predicate<String> enabled, BooleanSupplier stopping) {
        this(server, resolveActor, enabled, stopping, Runnable::run);
    }

    public GameToolBridge(MinecraftServer server, Function<String, Actor> resolveActor,
                          Predicate<String> enabled, BooleanSupplier stopping, Executor io) {
        this(server, resolveActor, enabled, stopping, io, owner -> false);
    }

    public GameToolBridge(MinecraftServer server, Function<String, Actor> resolveActor,
                          Predicate<String> enabled, BooleanSupplier stopping, Executor io, Predicate<String> yoloMode) {
        this.server = server;
        this.resolveActor = resolveActor;
        this.enabled = enabled;
        this.stopping = stopping;
        this.io = io;
        this.yoloMode = yoloMode;
    }

    public CompletableFuture<AgentTool.Result> execute(AgentTool tool, AgentTool.Context context,
            JsonObject args, String approval, ModelMessage.ToolCall call) {
        CompletableFuture<AgentTool.Result> future = new CompletableFuture<>();
        if (!server.isDedicatedServer()) {
            return CompletableFuture.completedFuture(AgentTool.Result.error("NOT_SUPPORTED", "AI 仅在专用服务端可用。"));
        }
        Runnable action = () -> {
            try {
                if (future.isCancelled() || stopping.getAsBoolean()) {
                    future.complete(rejected("AI_STOPPING"));
                    return;
                }
                try { context.control().check(); }
                catch (firefly520.fireflymc.ai.task.TaskControl.Stopped stopped) {
                    future.complete(rejected(stopped.getMessage())); return;
                }
                Actor current = resolveActor.apply(context.actor().owner());
                var verdict = new ToolPolicyEngine().check(current, context.workspace().owner(), tool.descriptor(),
                        enabled.test(call.name()), call, approval, yoloMode.test(context.workspace().owner()));
                if (verdict.decision() != ToolPolicyEngine.Decision.ALLOW) {
                    JsonObject dispatch = new JsonObject();
                    dispatch.addProperty("dispatch", "REJECTED_BEFORE_EXECUTION");
                    future.complete(new AgentTool.Result(false, verdict.reason(), "执行前状态变化，未执行。",
                            dispatch, "", "", false));
                    return;
                }
                future.complete(tool.execute(new AgentTool.Context(context.workspace(), current, context.control(),
                        context.taskId(), context.modelResultBytes()), args));
            } catch (Exception e) { future.completeExceptionally(e); }
        };
        if (tool.descriptor().executionThread() == AgentTool.ExecutionThread.SERVER) server.execute(action);
        else {
            try { io.execute(action); }
            catch (java.util.concurrent.RejectedExecutionException e) {
                future.complete(rejected("TOOL_QUEUE_FULL"));
            }
        }
        return future;
    }
    private static AgentTool.Result rejected(String reason) {
        JsonObject dispatch = new JsonObject(); dispatch.addProperty("dispatch", "REJECTED_BEFORE_EXECUTION");
        return new AgentTool.Result(false, reason, "工具派发前已停止，未执行游戏操作。", dispatch, "", "", false);
    }
}
