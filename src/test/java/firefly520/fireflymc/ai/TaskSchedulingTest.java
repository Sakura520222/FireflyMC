package firefly520.fireflymc.ai;

import firefly520.fireflymc.ai.task.TaskControl;
import firefly520.fireflymc.ai.task.TaskScheduler;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TaskSchedulingTest {
    @Test void cancelledQueuedWorkReleasesCapacityWithoutLiftingAnOwnerBarrier() throws Exception {
        try (var scheduler = new TaskScheduler(1, 1, 1)) {
            var runs = new AtomicInteger();
            scheduler.block("a");
            UUID cancelled = UUID.randomUUID();
            scheduler.submit("a", cancelled, runs::incrementAndGet, false);
            scheduler.cancelQueued("a", cancelled);
            assertEquals(0, scheduler.queued());
            UUID retained = UUID.randomUUID();
            CountDownLatch completed = new CountDownLatch(1);
            scheduler.submit("a", retained, () -> { runs.incrementAndGet(); completed.countDown(); }, false);
            assertEquals(1, scheduler.queued());
            assertEquals(0, runs.get());
            // Priority resume of the same queued task must remove the barrier without duplicating it.
            scheduler.submit("a", retained, () -> fail("must retain original work"), true);
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(1, runs.get());
        }
    }

    @Test void explicitCancellationWinsOverEarlierPauseButNotAnExistingTerminalStop() {
        var control = new TaskControl(30000);
        control.stop("USER_PAUSED");
        control.stop("CANCELLED");
        control.stop("USER_PAUSED");
        assertEquals("CANCELLED", assertThrows(TaskControl.Stopped.class, control::check).getMessage());
        var timedOut = new TaskControl(30000);
        timedOut.stop("TIMEOUT");
        timedOut.stop("CANCELLED");
        assertEquals("TIMEOUT", timedOut.stopReason());
    }
}
