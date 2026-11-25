package firefly520.fireflymc.ai.integration.exploration;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompassSearchBudgetTest {
    @Test void extentUsesLongArithmeticAndRefusesARealCandidateOutsideTheApprovedBoundsBeforeCountingIt() {
        var clock = new AtomicLong(); var meter = new CompassSearchBudget.Meter(new CompassSearchBudget(8, 3, 1000), 29_999_992, -29_999_992, clock::get);
        meter.beginSlice(); meter.beforeCheck(30_000_000, -30_000_000); assertEquals(1, meter.checks());
        assertEquals("COMPASS_RADIUS_LIMIT", assertThrows(CompassSearchBudget.Stopped.class, () -> meter.beforeCheck(Integer.MIN_VALUE, 0)).getMessage());
        assertEquals(1, meter.checks());
    }
    @Test void countsTimeAndCooperativeSliceBudgetAreIndependentAndDoNotGrantExtraChecksAfterFailure() {
        var clock = new AtomicLong(); var meter = new CompassSearchBudget.Meter(new CompassSearchBudget(100, 1, 1000), 0, 0, clock::get);
        meter.beginSlice(); meter.beforeCheck(0, 0);
        assertEquals("COMPASS_CHECK_LIMIT", assertThrows(CompassSearchBudget.Stopped.class, () -> meter.beforeCheck(1, 1)).getMessage());
        var slice = new CompassSearchBudget.Meter(new CompassSearchBudget(100, 10, 1000), 0, 0, clock::get); slice.beginSlice();
        clock.set(CompassSearchBudget.SLICE_NANOS);
        assertEquals("COMPASS_SLICE_LIMIT", assertThrows(CompassSearchBudget.Stopped.class, () -> slice.beforeCheck(0, 0)).getMessage());
        assertEquals(0, slice.checks()); clock.set(1_000_000_000);
        assertEquals("COMPASS_TIME_LIMIT", assertThrows(CompassSearchBudget.Stopped.class, slice::checkTime).getMessage());
    }
    @Test void aNonPreemptibleNativeOverrunIsMeasuredInsteadOfReportedAsWithinBudget() {
        var clock = new AtomicLong(); var meter = new CompassSearchBudget.Meter(new CompassSearchBudget(100, 10, 1000), 0, 0, clock::get);
        meter.beginSlice(); meter.beforeCheck(0, 0); clock.set(9_000_000); meter.endSlice();
        assertEquals(9_000_000, meter.maximumSliceNanos()); assertEquals(1, meter.sliceOverruns()); assertEquals(1, meter.frames());
    }
    @Test void coldSaveSetupDoesNotConsumeCandidateBudgetButRemainsMeasuredAndCannotExtendTheTaskDeadline() {
        var clock = new AtomicLong(); var meter = new CompassSearchBudget.Meter(new CompassSearchBudget(100, 10, 1000), 0, 0, clock::get);
        meter.beginSlice(); clock.set(5_000_000); meter.candidateExecutionStarts(); meter.beforeCheck(0, 0);
        clock.addAndGet(CompassSearchBudget.SLICE_NANOS);
        assertEquals("COMPASS_SLICE_LIMIT", assertThrows(CompassSearchBudget.Stopped.class, () -> meter.beforeCheck(1, 0)).getMessage());
        meter.endSlice(); assertEquals(7_000_000, meter.maximumSliceNanos()); assertEquals(1, meter.sliceOverruns());
        assertEquals(1, meter.checks()); clock.set(1_000_000_000);
        assertEquals("COMPASS_TIME_LIMIT", assertThrows(CompassSearchBudget.Stopped.class, meter::candidateExecutionStarts).getMessage());
    }
    @Test void nextTickCanAdmitTheSameUnexaminedCandidateWithoutExtendingTheTotalDeadlineOrCheckAllowance() {
        var clock = new AtomicLong(); var meter = new CompassSearchBudget.Meter(new CompassSearchBudget(100, 1, 1000), 0, 0, clock::get);
        meter.beginSlice(); clock.set(CompassSearchBudget.SLICE_NANOS);
        assertEquals("COMPASS_SLICE_LIMIT", assertThrows(CompassSearchBudget.Stopped.class, () -> meter.beforeCheck(8, 8)).getMessage());
        assertEquals(0, meter.checks()); meter.endSlice();
        clock.addAndGet(50_000_000); meter.beginSlice(); meter.beforeCheck(8, 8);
        assertEquals(1, meter.checks()); assertEquals(2, meter.frames());
        assertEquals("COMPASS_CHECK_LIMIT", assertThrows(CompassSearchBudget.Stopped.class, () -> meter.beforeCheck(8, 8)).getMessage());
        clock.set(1_000_000_000);
        assertEquals("COMPASS_TIME_LIMIT", assertThrows(CompassSearchBudget.Stopped.class, meter::beginSlice).getMessage());
    }
    @Test void pendingPreparationReturnsOnlyItsUnexaminedAdmissionAndCannotMintRepeatedCheckCredits() {
        var clock = new AtomicLong(); var meter = new CompassSearchBudget.Meter(new CompassSearchBudget(100, 1, 1000), 0, 0, clock::get);
        meter.beginSlice(); meter.beforeCheck(8, 8); meter.releaseUnexaminedCheck(0);
        assertEquals(0, meter.checks());
        assertThrows(IllegalStateException.class, () -> meter.releaseUnexaminedCheck(0));
        clock.addAndGet(50_000_000); meter.beginSlice(); meter.beforeCheck(8, 8);
        assertEquals(1, meter.checks());
        assertEquals("COMPASS_CHECK_LIMIT", assertThrows(CompassSearchBudget.Stopped.class, () -> meter.beforeCheck(9, 9)).getMessage());
        assertThrows(IllegalStateException.class, () -> meter.releaseUnexaminedCheck(1));
    }
}
