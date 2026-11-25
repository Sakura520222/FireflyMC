package firefly520.fireflymc.ai.processing.advanced;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InfusionStatsPolicyTest {
    @Test void choosesAnAllowedIntervalInsteadOfMaximizingEveryAttribute() {
        var stats = InfusionStatsPolicy.choose(new InfusionStatsPolicy.Bounds(100, 100),
                new InfusionStatsPolicy.Bounds(9.65f, 11), new InfusionStatsPolicy.Bounds(58.75f, 60), 100);
        assertEquals(new InfusionStatsPolicy.Stats(100, 9.65f, 58.75f), stats);
    }
    @Test void playerCapabilityLimitIsNotAnExperiencePayment() {
        assertThrows(IllegalArgumentException.class, () -> InfusionStatsPolicy.choose(new InfusionStatsPolicy.Bounds(80, -1),
                new InfusionStatsPolicy.Bounds(0, -1), new InfusionStatsPolicy.Bounds(0, -1), 50));
    }
    @Test void boundaryEqualityIsAcceptedButMalformedOrNonFiniteRangesAreRejected() {
        assertEquals(100, InfusionStatsPolicy.choose(new InfusionStatsPolicy.Bounds(100, 100),
                new InfusionStatsPolicy.Bounds(10, 10), new InfusionStatsPolicy.Bounds(60, 60), 100).eterna());
        assertThrows(IllegalArgumentException.class, () -> new InfusionStatsPolicy.Bounds(20, 10));
        assertThrows(IllegalArgumentException.class, () -> new InfusionStatsPolicy.Bounds(0, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> new InfusionStatsPolicy.Bounds(Float.POSITIVE_INFINITY, -1));
    }
}
