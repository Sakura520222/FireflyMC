package firefly520.fireflymc.ai;

import firefly520.fireflymc.ai.task.TaskScheduler;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskSchedulerTest {
    @Test void newlyWaitingPlayerRunsBeforeAnotherTurnFromCurrentPlayer() throws Exception {
        var scheduler = new TaskScheduler(1, 4, 8);
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(3);
        try {
            scheduler.submit("a", UUID.randomUUID(), () -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                order.add("a1");
                done.countDown();
            }, false);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            scheduler.submit("a", UUID.randomUUID(), () -> { order.add("a2"); done.countDown(); }, false);
            scheduler.submit("b", UUID.randomUUID(), () -> { order.add("b"); done.countDown(); }, false);
            release.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("a1", "b", "a2"), order);
        } finally {
            release.countDown();
            scheduler.close();
            assertTrue(scheduler.awaitTermination(5000));
        }
    }

    @Test void ownersRotateFairlyAndWaitingOwnerDoesNotConsumeGlobalWorker() throws Exception {
        var scheduler = new TaskScheduler(1, 4, 8);
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(3);
        try {
            scheduler.submit("a", UUID.randomUUID(), () -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                order.add("a1");
                scheduler.block("a");
                done.countDown();
            }, false);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            scheduler.submit("a", UUID.randomUUID(), () -> { order.add("a2"); done.countDown(); }, false);
            scheduler.submit("b", UUID.randomUUID(), () -> { order.add("b"); done.countDown(); }, false);
            release.countDown();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!order.contains("b") && System.nanoTime() < end) Thread.sleep(5);
            assertEquals(List.of("a1", "b"), order);
            scheduler.submit("a", UUID.randomUUID(), () -> order.add("resume"), true);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("a1", "b", "resume", "a2"), order);
        } finally {
            release.countDown();
            scheduler.close();
            assertTrue(scheduler.awaitTermination(5000));
        }
    }

    @Test void admissionIsBoundedPerPlayerAndGlobally() throws Exception {
        var scheduler = new TaskScheduler(1, 2, 3);
        CountDownLatch release = new CountDownLatch(1), started = new CountDownLatch(1);
        try {
            scheduler.submit("a", UUID.randomUUID(), () -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, false);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            scheduler.submit("a", UUID.randomUUID(), () -> {}, false);
            assertThrows(RejectedExecutionException.class,
                    () -> scheduler.submit("a", UUID.randomUUID(), () -> {}, false));
            scheduler.submit("b", UUID.randomUUID(), () -> {}, false);
            assertThrows(RejectedExecutionException.class,
                    () -> scheduler.submit("c", UUID.randomUUID(), () -> {}, false));
            assertEquals(2, scheduler.queued());
        } finally {
            release.countDown();
            scheduler.close();
            assertTrue(scheduler.awaitTermination(5000));
        }
    }
}
