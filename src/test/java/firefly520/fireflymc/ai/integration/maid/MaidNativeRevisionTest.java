package firefly520.fireflymc.ai.integration.maid;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaidNativeRevisionTest {
    public static final class Control implements MaidNativeRevision.Carrier {
        long revision; boolean home;
        public boolean isHomeModeEnable() { return home; }
        public long fireflymc$maidRevision() { return revision; }
        public void fireflymc$markMaidControl() { if (revision < Long.MAX_VALUE) revision++; }
    }
    @Test void nativeStateChangedAndThenRevertedStillInvalidatesTheOriginalWitness() {
        var control = new Control(); long before = MaidNativeRevision.revision(control);
        MaidNativeRevision.changed(control, "isHomeModeEnable", false); assertEquals(before, MaidNativeRevision.revision(control));
        MaidNativeRevision.changed(control, "isHomeModeEnable", true); control.home = true;
        MaidNativeRevision.changed(control, "isHomeModeEnable", false); control.home = false;
        assertFalse(control.home); assertEquals(before + 2, MaidNativeRevision.revision(control));
    }
    @Test void absentHookOrExhaustedRevisionCannotSupplyAValidFreshWitness() {
        assertThrows(IllegalStateException.class, () -> MaidNativeRevision.revision(new Object()));
        var control = new Control(); control.revision = Long.MAX_VALUE;
        assertThrows(IllegalStateException.class, () -> MaidNativeRevision.revision(control));
    }
}
