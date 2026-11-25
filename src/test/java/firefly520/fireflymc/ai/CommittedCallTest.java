package firefly520.fireflymc.ai;

import firefly520.fireflymc.ai.task.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommittedCallTest {
    @Test void cancelledQueuedInvocationNeverMutatesAndCannotRunLater() throws Exception {
        var call = new CommittedCall<Integer>(); var control = new TaskControl(10000); var mutations = new AtomicInteger();
        control.stop("CANCELLED"); call.run(control, mutations::incrementAndGet);
        var rejected = assertThrows(CommittedCall.Rejected.class, () -> call.awaitEvidence(1000));
        assertInstanceOf(TaskControl.Stopped.class, rejected.getCause());
        call.run(new TaskControl(10000), mutations::incrementAndGet);
        assertEquals(0, mutations.get());
    }
    @Test void cancellationAfterNativeDispatchStillReturnsTheRealTakenMaterialBeforeNextCheckpoint() throws Exception {
        var control = new TaskControl(10000); var call = new CommittedCall<Integer>();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var physicalItems = new AtomicInteger(3);
        try (var dispatcher = Executors.newSingleThreadExecutor(); var waiter = Executors.newSingleThreadExecutor()) {
            try {
            dispatcher.submit(() -> call.run(control, () -> { entered.countDown(); release.await(); physicalItems.decrementAndGet(); return 1; }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var returnedEvidence = waiter.submit(() -> call.awaitEvidence(5000));
            control.stop("CANCELLED"); assertFalse(returnedEvidence.isDone());
            release.countDown(); int actuallyTaken = returnedEvidence.get(5, TimeUnit.SECONDS);
            assertEquals(1, actuallyTaken); assertEquals(2, physicalItems.get());
            // The owner can durably account this exact unit, then stop; it is never replaced with a fake zero result.
            var escrow = new AtomicInteger(); escrow.addAndGet(actuallyTaken);
            assertThrows(TaskControl.Stopped.class, control::check); assertEquals(1, escrow.get());
            } finally { release.countDown(); }
        }
    }
    @Test void timedOutQueueEntryIsPermanentlyRejectedBeforeDispatch() throws Exception {
        var call = new CommittedCall<Integer>(); var changes = new AtomicInteger();
        assertThrows(CommittedCall.Rejected.class, () -> call.awaitEvidence(1));
        call.run(new TaskControl(1000), changes::incrementAndGet);
        assertEquals(0, changes.get());
    }
    @Test void timeoutOfAnAlreadyRunningInvocationIsUncertainAndDoesNotCancelItsEvidence() throws Exception {
        var call = new CommittedCall<Integer>(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var dispatcher = Executors.newSingleThreadExecutor()) {
            try {
            dispatcher.submit(() -> call.run(new TaskControl(10000), () -> { entered.countDown(); release.await(); return 7; }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> call.awaitEvidence(1));
            release.countDown(); assertEquals(7, call.awaitEvidence(5000));
            } finally { release.countDown(); }
        }
    }
}
