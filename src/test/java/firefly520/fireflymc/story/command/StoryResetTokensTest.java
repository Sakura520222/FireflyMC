package firefly520.fireflymc.story.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class StoryResetTokensTest {
    private static final UUID EXECUTOR = UUID.randomUUID();
    private static final UUID OTHER_EXECUTOR = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();

    @Test
    void tokenIsSingleUseAndBoundToExecutorTargetAndScope() {
        StoryResetTokens tokens = new StoryResetTokens();
        String token = tokens.issue(EXECUTOR, TARGET, StoryResetTokens.Scope.PLAYER, 1_000);

        assertFalse(tokens.consume(token, OTHER_EXECUTOR, TARGET,
            StoryResetTokens.Scope.PLAYER, 1_001));
        assertFalse(tokens.consume(token, EXECUTOR, UUID.randomUUID(),
            StoryResetTokens.Scope.PLAYER, 1_001));
        assertFalse(tokens.consume(token, EXECUTOR, TARGET,
            StoryResetTokens.Scope.WORLD, 1_001));
        assertTrue(tokens.consume(token, EXECUTOR, TARGET,
            StoryResetTokens.Scope.PLAYER, 1_001));
        assertFalse(tokens.consume(token, EXECUTOR, TARGET,
            StoryResetTokens.Scope.PLAYER, 1_001));
    }

    @Test
    void worldTokensUseTheConstantWorldTargetAndAreIndependentOfPlayerTokens() {
        StoryResetTokens tokens = new StoryResetTokens();
        String playerToken = tokens.issue(EXECUTOR, TARGET,
            StoryResetTokens.Scope.PLAYER, 1_000);
        String worldToken = tokens.issue(EXECUTOR, TARGET,
            StoryResetTokens.Scope.WORLD, 1_000);

        StoryResetTokens.IssuedToken issued = tokens.issued(worldToken, 1_000);
        assertEquals(StoryResetTokens.WORLD_TARGET, issued.targetId());
        assertFalse(tokens.consume(playerToken, EXECUTOR,
            StoryResetTokens.WORLD_TARGET, StoryResetTokens.Scope.WORLD, 1_001));
        assertTrue(tokens.consume(worldToken, EXECUTOR,
            StoryResetTokens.WORLD_TARGET, StoryResetTokens.Scope.WORLD, 1_001));
    }

    @Test
    void tokenExpiresAfterSixtySeconds() {
        StoryResetTokens tokens = new StoryResetTokens();
        String token = tokens.issue(EXECUTOR, TARGET, StoryResetTokens.Scope.PLAYER, 1_000);

        assertTrue(tokens.consume(token, EXECUTOR, TARGET,
            StoryResetTokens.Scope.PLAYER, 60_999));

        String expired = tokens.issue(EXECUTOR, TARGET, StoryResetTokens.Scope.PLAYER, 2_000);
        assertFalse(tokens.consume(expired, EXECUTOR, TARGET,
            StoryResetTokens.Scope.PLAYER, 2_060_000));
    }

    @Test
    void exactExpiryBoundaryIsRejectedAndRemovedFromLookup() {
        StoryResetTokens tokens = new StoryResetTokens();
        String token = tokens.issue(EXECUTOR, TARGET, StoryResetTokens.Scope.PLAYER, 1_000);

        assertNotNull(tokens.issued(token, 59_999));
        assertFalse(tokens.consume(token, EXECUTOR, TARGET,
            StoryResetTokens.Scope.PLAYER, 61_000));
        assertEquals(null, tokens.issued(token, 61_000));
    }

    @Test
    void unknownTokenCannotBeInspectedOrConsumed() {
        StoryResetTokens tokens = new StoryResetTokens();
        assertFalse(tokens.consume("unknown", EXECUTOR, TARGET,
            StoryResetTokens.Scope.PLAYER, 1_000));
        assertEquals(null, tokens.issued("unknown", 1_000));
    }

    @Test
    void issueProducesUnguessableSingleUseTokens() {
        StoryResetTokens tokens = new StoryResetTokens();
        String first = tokens.issue(EXECUTOR, TARGET, StoryResetTokens.Scope.PLAYER, 1_000);
        String second = tokens.issue(EXECUTOR, TARGET, StoryResetTokens.Scope.PLAYER, 1_000);

        assertNotEquals(first, second);
        assertTrue(first.matches("[A-Za-z0-9_-]{43}"));
        assertTrue(tokens.consume(first, EXECUTOR, TARGET,
            StoryResetTokens.Scope.PLAYER, 1_001));
        assertTrue(tokens.consume(second, EXECUTOR, TARGET,
            StoryResetTokens.Scope.PLAYER, 1_001));
    }

    @Test
    void clearRemovesEveryPendingToken() {
        StoryResetTokens tokens = new StoryResetTokens();
        String token = tokens.issue(EXECUTOR, TARGET, StoryResetTokens.Scope.PLAYER, 1_000);

        tokens.clear();

        assertEquals(null, tokens.issued(token, 1_001));
        assertFalse(tokens.consume(token, EXECUTOR, TARGET,
            StoryResetTokens.Scope.PLAYER, 1_001));
    }

    @Test
    void argumentsAreValidatedBeforeAnyTokenIsCreated() {
        StoryResetTokens tokens = new StoryResetTokens();

        assertThrowsExactly(NullPointerException.class, () -> tokens.issue(
            null, TARGET, StoryResetTokens.Scope.PLAYER, 1_000));
        assertThrowsExactly(NullPointerException.class, () -> tokens.issue(
            EXECUTOR, null, StoryResetTokens.Scope.PLAYER, 1_000));
        assertThrowsExactly(NullPointerException.class, () -> tokens.issue(
            EXECUTOR, TARGET, null, 1_000));
        assertThrowsExactly(IllegalArgumentException.class, () -> tokens.issue(
            EXECUTOR, TARGET, StoryResetTokens.Scope.PLAYER, -1));
    }
}
