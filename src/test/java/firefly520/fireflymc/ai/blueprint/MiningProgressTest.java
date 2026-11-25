package firefly520.fireflymc.ai.blueprint;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MiningProgressTest {
    @Test void repeatedCallsInOneServerTickCannotAccelerateSurvivalMining() {
        var progress = new MiningProgress();
        for (int i = 0; i < 1000; i++) progress.advance(100, .1);
        assertEquals(.1, progress.value(), 1e-10); assertFalse(progress.complete());
        for (int tick = 101; tick <= 110; tick++) progress.advance(tick, .1);
        assertTrue(progress.complete()); assertEquals(1, progress.value());
    }
    @Test void pausedTicksDoNotBecomeFreeWorkAndRatesFollowNativeChanges() {
        var progress = new MiningProgress(); progress.advance(2, .05); progress.advance(200, .05);
        assertEquals(.1, progress.value(), 1e-10); progress.advance(201, .2);
        assertEquals(.6, progress.value(), 1e-10);
        assertThrows(IllegalArgumentException.class, () -> progress.advance(199, .5));
    }
    @Test void slowerNativeRateCannotKeepAnEarlierFastReadyDecision() {
        var progress = new MiningProgress(); progress.advance(5, 2);
        assertTrue(progress.complete()); progress.recheck(.05);
        assertEquals(.05, progress.value(), 1e-10); assertFalse(progress.complete());
        progress.advance(6, .05); assertEquals(.1, progress.value(), 1e-10);
    }
    @Test void impossibleRatesFailWhileZeroHardnessNativeInstantMiningIsPreserved() {
        var progress = new MiningProgress();
        assertThrows(IllegalArgumentException.class, () -> progress.advance(1, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> progress.advance(1, -.01));
        progress.advance(1, 0); assertEquals(0, progress.value());
        progress.advance(2, Double.POSITIVE_INFINITY); assertTrue(progress.complete());
    }
}
