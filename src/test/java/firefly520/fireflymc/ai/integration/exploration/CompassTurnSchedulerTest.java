package firefly520.fireflymc.ai.integration.exploration;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompassTurnSchedulerTest {
    private final UUID first = new UUID(0, 1), second = new UUID(0, 2);
    @Test void nativeListOrderCannotLetTheFirstPlayersWorkerConsumeEveryTick() {
        var scheduler = new CompassTurnScheduler(); var ready = List.of(first, second);
        assertTrue(scheduler.claim(1, first, ready)); assertFalse(scheduler.claim(1, second, ready));
        assertFalse(scheduler.claim(2, first, ready)); assertTrue(scheduler.claim(2, second, ready));
        assertTrue(scheduler.claim(3, first, ready)); assertFalse(scheduler.claim(3, first, ready));
    }
    @Test void aRemovedCandidateCannotHoldAnUnclaimedTickOrLeakIntoTheNextServerTick() {
        var scheduler = new CompassTurnScheduler();
        assertFalse(scheduler.claim(10, second, List.of(first, second)));
        assertTrue(scheduler.claim(10, second, List.of(second)));
        assertFalse(scheduler.claim(11, first, List.of()));
        assertTrue(scheduler.claim(11, first, List.of(first)));
    }
}
