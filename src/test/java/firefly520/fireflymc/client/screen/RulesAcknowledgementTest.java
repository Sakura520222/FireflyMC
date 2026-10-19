package firefly520.fireflymc.client.screen;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RulesAcknowledgementTest {
    @Test
    void closingAnUnacceptedFirstDialogNeverConfirmsConsent() {
        var acknowledgement = new RulesAcknowledgement(true);
        assertFalse(acknowledgement.dismiss());
        assertTrue(acknowledgement.confirm());
        assertFalse(acknowledgement.dismiss());
        assertFalse(acknowledgement.confirm());
    }

    @Test
    void dismissingARepeatDialogAcknowledgesTheNewServerSessionOnce() {
        var acknowledgement = new RulesAcknowledgement(false);
        assertTrue(acknowledgement.dismiss());
        assertFalse(acknowledgement.dismiss());
        assertFalse(acknowledgement.confirm());
    }

    @Test
    void countdownAndScreenCloseCannotSendDuplicateConfirmations() {
        var acknowledgement = new RulesAcknowledgement(false);
        assertTrue(acknowledgement.confirm());
        assertFalse(acknowledgement.dismiss());
    }
}
