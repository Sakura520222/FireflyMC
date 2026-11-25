package firefly520.fireflymc.ai;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.agent.AgentRuntime;
import firefly520.fireflymc.ai.context.ContextCompactor;
import firefly520.fireflymc.ai.model.*;
import firefly520.fireflymc.ai.security.*;
import firefly520.fireflymc.ai.task.*;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class YoloRuntimeTest {
    @TempDir Path directory;
    private final Actor a = Actor.player(UUID.randomUUID(), true, true, 0);
    private final Actor b = Actor.player(UUID.randomUUID(), true, true, 0);
    private static ModelGateway.Reply reply(String text) { return new ModelGateway.Reply(ModelMessage.text("assistant", text), 10, 10); }
    private static ModelGateway.Reply call(String id) {
        return new ModelGateway.Reply(new ModelMessage("assistant", "", List.of(new ModelMessage.ToolCall(id, "echo", "{}")), null, null), 10, 10);
    }
    private static void until(BooleanSupplier test) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (!test.getAsBoolean()) { if (System.nanoTime() > until) fail("Task did not reach expected state"); Thread.sleep(10); }
    }
    private final class Loop implements AutoCloseable, AgentRuntime.Environment {
        final WorkspaceManager manager = new WorkspaceManager(directory);
        final TaskScheduler scheduler = new TaskScheduler(2, 8, 32);
        final Set<String> automatic = ConcurrentHashMap.newKeySet();
        final AtomicInteger executed = new AtomicInteger();
        final List<String> notices = new CopyOnWriteArrayList<>();
        final AgentRuntime runtime;
        Loop(ModelGateway gateway) throws Exception {
            var tools = new ToolRegistry();
            tools.register(new AgentTool() {
                @Override public Descriptor descriptor() {
                    return new Descriptor("echo", "test operation", new JsonObject(), Risk.WORLD_CHANGE, "test", 0,
                            ExecutionThread.IO, 1000, true, "");
                }
                @Override public Result execute(Context context, JsonObject args) { executed.incrementAndGet(); return Result.ok("completed"); }
            });
            WorkspaceTools.register(tools); QuestionStore.register(tools);
            runtime = new AgentRuntime(manager, scheduler, gateway, tools,
                    new ContextCompactor(new ContextCompactor.Settings(32000, 512, 1024, 512, .75, 2), "test"),
                    new AgentRuntime.Limits(12, 20, 100000, 30000, 512, true, false), this, new SecretFilter(List.of()));
        }
        @Override public Actor actor(String owner) { return a.owner().equals(owner) ? a : b; }
        @Override public boolean yoloMode(String owner) { return automatic.contains(owner); }
        @Override public boolean enabled(String tool) { return true; }
        @Override public CompletableFuture<AgentTool.Result> execute(AgentTool tool, AgentTool.Context context, JsonObject args, String approval, ModelMessage.ToolCall call) {
            try { return CompletableFuture.completedFuture(tool.execute(context, args)); }
            catch (Exception failure) { return CompletableFuture.failedFuture(failure); }
        }
        @Override public void changed(WorkspaceManager.Workspace workspace, AgentTask task, String notice) { notices.add(notice); }
        AgentTask.State state(Actor actor, UUID id) { try { return manager.getWorkspace(actor).conversation().task(id).state(); } catch (Exception e) { throw new RuntimeException(e); } }
        @Override public void close() throws Exception { runtime.close(); assertTrue(runtime.awaitStopped(10000)); manager.close(); }
    }
    @Test void enabledYoloExecutesEveryToolRoundWithoutAnApprovalWaitOrPrompt() throws Exception {
        try (var loop = new Loop((request, control) -> {
            assertTrue(request.messages().stream().anyMatch(message -> message.text().contains("当前执行模式：YOLO")));
            long results = request.messages().stream().filter(message -> "tool".equals(message.role())).count();
            return results < 3 ? call("call-" + results) : reply("verified done");
        })) {
            loop.automatic.add(a.owner()); var task = loop.runtime.submit(a, UUID.randomUUID(), "execute");
            until(() -> loop.state(a, task.id()) == AgentTask.State.COMPLETED);
            assertEquals(3, loop.executed.get()); assertTrue(loop.notices.stream().noneMatch(notice -> notice.contains("批准操作")));
            assertTrue(loop.manager.getWorkspace(a).conversation().getSnapshot(0).events().stream()
                    .noneMatch(event -> event.kind().equals("task") && "WAITING_APPROVAL".equals(event.data().get("state").getAsString())));
        }
    }
    @Test void enablingYoloReleasesAnExistingWaitOnlyForThatPlayer() throws Exception {
        try (var loop = new Loop((request, control) -> request.messages().stream().anyMatch(message -> "tool".equals(message.role()))
                ? reply("done") : call("first"))) {
            var first = loop.runtime.submit(a, UUID.randomUUID(), "first player's operation");
            var other = loop.runtime.submit(b, UUID.randomUUID(), "other player's operation");
            until(() -> loop.state(a, first.id()) == AgentTask.State.WAITING_APPROVAL && loop.state(b, other.id()) == AgentTask.State.WAITING_APPROVAL);
            loop.automatic.add(a.owner()); loop.runtime.resumeAutomaticApprovals(a);
            until(() -> loop.state(a, first.id()) == AgentTask.State.COMPLETED);
            assertEquals(1, loop.executed.get()); assertEquals(AgentTask.State.WAITING_APPROVAL, loop.state(b, other.id()));
        }
    }
    @Test void disablingYoloIsRecheckedBeforeTheNextOperation() throws Exception {
        var ref = new AtomicReference<Loop>();
        try (var loop = new Loop((request, control) -> {
            long results = request.messages().stream().filter(message -> "tool".equals(message.role())).count();
            if (results > 0) ref.get().automatic.remove(a.owner());
            return call("operation-" + results);
        })) {
            ref.set(loop); loop.automatic.add(a.owner()); var task = loop.runtime.submit(a, UUID.randomUUID(), "two operations");
            until(() -> loop.state(a, task.id()) == AgentTask.State.WAITING_APPROVAL);
            assertEquals(1, loop.executed.get());
        }
    }
}
