package firefly520.fireflymc.story.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class StorySessionGateTest {
    @Test
    void passwordSuccessWithoutRulesConfirmationRemainsBlocked() {
        assertEquals(StorySessionGate.BlockReason.RULES_CONFIRMATION,
            StorySessionGate.blockingReason(true, true, false, true, true));
    }

    @Test
    void restartRequiresFreshHandshakeAndAuthentication() {
        assertEquals(StorySessionGate.BlockReason.MOD_VERIFICATION,
            StorySessionGate.blockingReason(true, false, false, true, false));
        assertEquals(StorySessionGate.BlockReason.PASSWORD_VERIFICATION,
            StorySessionGate.blockingReason(true, true, false, true, false));
    }

    @Test
    void disabledPasswordsStillRequireRulesAndCompleteSessionsOpen() {
        assertEquals(StorySessionGate.BlockReason.RULES_CONFIRMATION,
            StorySessionGate.blockingReason(true, true, false, false, false));
        assertEquals(StorySessionGate.BlockReason.NONE,
            StorySessionGate.blockingReason(true, true, true, false, false));
        assertEquals(StorySessionGate.BlockReason.NONE,
            StorySessionGate.blockingReason(true, true, true, true, true));
        assertEquals(StorySessionGate.BlockReason.NONE,
            StorySessionGate.blockingReason(false, false, false, true, false));
    }
}
