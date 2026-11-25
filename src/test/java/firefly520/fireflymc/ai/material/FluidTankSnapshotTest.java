package firefly520.fireflymc.ai.material;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FluidTankSnapshotTest {
    private FluidAmount water(int amount) { return fluid("minecraft:water", amount); }
    private FluidAmount fluid(String id, int amount) {
        return amount == 0 ? FluidAmount.EMPTY : new FluidAmount("{\"amount\":1,\"id\":\"" + id + "\"}", amount);
    }
    private FluidTankSnapshot snapshot(FluidAmount... fluids) {
        return new FluidTankSnapshot(java.util.Arrays.stream(fluids).map(fluid -> new FluidTankSnapshot.Tank(4000, fluid)).toList());
    }
    @Test void exactBackendDeltaAccountsForOneDrainSpanningSeveralTanks() {
        var before = snapshot(water(250), water(2000)); var after = snapshot(water(0), water(1250));
        assertTrue(before.verifies(after, water(1000), false));
        assertEquals(2250, before.amount(water(1).fingerprint()));
        assertEquals(1, before.kinds().size());
        assertFalse(before.verifies(after, water(999), false));
    }
    @Test void equalBackendTotalsDoNotAuthorizeUnrelatedTankMovesOrCapacityChanges() {
        var before = snapshot(water(1000), water(1000));
        assertFalse(before.verifies(snapshot(water(900), water(1100)), FluidAmount.EMPTY, false));
        assertFalse(before.verifies(snapshot(water(2000), water(0)), FluidAmount.EMPTY, true));
        assertFalse(before.verifies(new FluidTankSnapshot(List.of(new FluidTankSnapshot.Tank(8000, water(1000)),
                new FluidTankSnapshot.Tank(4000, water(1000)))), FluidAmount.EMPTY, false));
    }
    @Test void fullOrPartialFillRequiresExactActualAndCannotChangeAnotherFluid() {
        var lava = fluid("minecraft:lava", 500); var before = snapshot(water(3900), lava);
        assertTrue(before.verifies(snapshot(water(4000), lava), water(100), true));
        assertFalse(before.verifies(snapshot(water(4000), fluid("minecraft:lava", 499)), water(100), true));
        assertFalse(before.verifies(snapshot(water(4000), lava), water(200), true));
        assertFalse(before.verifies(snapshot(water(3800), lava), water(100), true));
    }
    @Test void sameFluidIdWithDifferentComponentsIsNotFungible() {
        var special = new FluidAmount("{\"amount\":1,\"components\":{\"minecraft:custom_data\":{\"quality\":1}},\"id\":\"minecraft:water\"}", 1000);
        var before = snapshot(special, water(1000));
        assertEquals(2, before.kinds().size());
        assertFalse(before.verifies(snapshot(water(0), water(1000)), water(1000), false));
        assertTrue(before.verifies(snapshot(water(0), water(1000)), special, false));
    }
    @Test void immutableSnapshotsRejectImpossibleOrUnboundedNativeAmounts() {
        assertThrows(IllegalArgumentException.class, () -> snapshot(water(4001)));
        assertThrows(IllegalArgumentException.class, () -> new FluidAmount("{\"id\":\"minecraft:water\",\"amount\":1000}", 1));
        assertThrows(IllegalArgumentException.class, () -> new FluidAmount("{\"id\":\"minecraft:water\",\"amount\":1.5}", 1));
        assertThrows(IllegalArgumentException.class, () -> new FluidTankSnapshot(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new FluidAmount("", 1));
        assertThrows(UnsupportedOperationException.class, () -> snapshot(water(1)).tanks().clear());
    }
}
