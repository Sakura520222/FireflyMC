package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.task.TaskControl;
import firefly520.fireflymc.ai.tools.AgentTool;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NativeWorldCompletionSavesTest {
    @TempDir Path directory;
    private final Actor owner = Actor.player(UUID.randomUUID(), true, true, 0);
    private final UUID task = UUID.randomUUID(), plan = UUID.randomUUID();
    private AgentTool.Context context(WorkspaceManager manager, TaskControl control) throws Exception {
        return new AgentTool.Context(manager.getWorkspace(owner), owner, control, task);
    }
    @Test void modelCompletionWaitsForEveryCanonicalWriteAndItsJournalWriterWithoutHoldingTheCaller() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            UUID world = UUID.randomUUID(), delivery = UUID.randomUUID();
            var a = new CompletableFuture<Void>(); var b = new CompletableFuture<Void>();
            var saved = new AtomicBoolean(); var summaries = new AtomicInteger();
            var ctx = context(manager, new TaskControl(120000));
            var deferred = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(AgentTool.Deferred.class,
                    () -> NativeWorldCompletionSaves.await(ctx, plan, List.of(world, delivery), id -> id.equals(world) ? a : b,
                            fresh -> saved.get(), fresh -> fail("positive writers must remain possible"), fresh -> {
                                summaries.incrementAndGet(); return AgentTool.Result.ok("saved world result only");
                            })));
            assertEquals("WAITING_WORLD_NATIVE_SAVE", deferred.reason()); assertEquals(0, summaries.get());
            a.complete(null); assertFalse(deferred.readiness().toCompletableFuture().isDone());
            saved.set(true); b.complete(null);
            assertTrue(deferred.continuation().resume(ctx).success()); assertEquals(1, summaries.get());
        }
    }
    @Test void alreadyCompletedOrFailedFuturesCannotSubstituteForMissingDurableProofs() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            for (boolean failedWriter : List.of(false, true)) {
                var marked = new AtomicInteger(); var summaries = new AtomicInteger();
                var ready = failedWriter ? CompletableFuture.<Void>failedFuture(new java.io.IOException("receipt writer failed"))
                        : CompletableFuture.<Void>completedFuture(null);
                var error = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(IllegalStateException.class,
                        () -> NativeWorldCompletionSaves.await(context(manager, new TaskControl(10000)), plan, List.of(UUID.randomUUID()),
                                ignored -> ready, fresh -> failedWriter, fresh -> marked.incrementAndGet(), fresh -> {
                                    summaries.incrementAndGet(); return AgentTool.Result.ok("must not claim success");
                                })));
                assertEquals("RECOVERY_REQUIRED", error.getMessage()); assertEquals(1, marked.get()); assertEquals(0, summaries.get());
            }
        }
    }
    @Test void cancellationStopsOnlyTheWaiterAndCannotRetargetItsAcceptedWorldResult() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var ready = new CompletableFuture<Void>(); var control = new TaskControl(120000); var summaries = new AtomicInteger();
            var ctx = context(manager, control);
            var deferred = assertThrows(AgentTool.Deferred.class, () -> NativeWorldCompletionSaves.await(ctx, plan, List.of(UUID.randomUUID()),
                    ignored -> ready, fresh -> true, fresh -> fail("native callback still owns the write"), fresh -> {
                        summaries.incrementAndGet(); return AgentTool.Result.ok("saved");
                    }));
            control.stop("CANCELLED"); ready.complete(null);
            assertThrows(TaskControl.Stopped.class, () -> deferred.continuation().resume(ctx));
            assertFalse(ready.isCancelled()); assertEquals(0, summaries.get());
            var otherTask = new AgentTool.Context(ctx.workspace(), owner, new TaskControl(10000), UUID.randomUUID());
            assertThrows(SecurityException.class, () -> deferred.continuation().resume(otherTask));
            assertEquals(0, summaries.get());
        }
    }
    @Test void verifiedSavedResultsContinueInlineWithoutRunningAnotherNativeBody() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            NativeWorldCompletionSaves.await(context(manager, new TaskControl(10000)), plan, List.of(UUID.randomUUID()),
                    ignored -> CompletableFuture.completedFuture(null), fresh -> true, fresh -> fail("saved result"),
                    fresh -> { fail("caller alone builds final status inline; no redispatch"); return null; });
        }
    }
}
