package firefly520.fireflymc.story.command;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Short-lived, single-use confirmation tokens for destructive story resets.
 *
 * <p>Instances are confined to one server lifecycle. A token is bound to the issuer, target,
 * scope, and issue time; validation consumes only a token whose exact binding matches.</p>
 */
public final class StoryResetTokens {
    public static final long LIFETIME_MILLIS = 60_000;
    public static final UUID WORLD_TARGET = new UUID(0L, 0L);
    private static final UUID CONSOLE_EXECUTOR = new UUID(0L, 0L);

    private final Map<String, IssuedToken> tokens = new HashMap<>();
    private final SecureRandom random = new SecureRandom();

    public enum Scope {
        PLAYER,
        WORLD
    }

    public record IssuedToken(
        UUID executorId,
        UUID targetId,
        Scope scope,
        long expiresAt
    ) {
        public IssuedToken {
            Objects.requireNonNull(executorId, "executorId");
            Objects.requireNonNull(targetId, "targetId");
            Objects.requireNonNull(scope, "scope");
        }
    }

    public String issue(
        UUID executorId,
        UUID targetId,
        Scope scope,
        long now
    ) {
        Objects.requireNonNull(executorId, "executorId");
        Objects.requireNonNull(scope, "scope");
        requireNonNegative(now);
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        synchronized (tokens) {
            tokens.put(token, new IssuedToken(
                executorId,
                normalizeTarget(targetId, scope),
                scope,
                Math.addExact(now, LIFETIME_MILLIS)
            ));
        }
        return token;
    }

    public boolean consume(
        String token,
        UUID executorId,
        UUID targetId,
        Scope scope,
        long now
    ) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(executorId, "executorId");
        Objects.requireNonNull(scope, "scope");
        requireNonNegative(now);
        synchronized (tokens) {
            IssuedToken issued = tokens.get(token);
            if (issued == null) {
                return false;
            }
            boolean matches = now < issued.expiresAt()
                && issued.executorId().equals(executorId)
                && issued.targetId().equals(normalizeTarget(targetId, scope))
                && issued.scope() == scope;
            if (matches) {
                tokens.remove(token);
            } else if (now >= issued.expiresAt()) {
                tokens.remove(token);
            }
            return matches;
        }
    }

    IssuedToken issued(String token, long now) {
        Objects.requireNonNull(token, "token");
        requireNonNegative(now);
        synchronized (tokens) {
            IssuedToken issued = tokens.get(token);
            if (issued != null && now >= issued.expiresAt()) {
                tokens.remove(token);
                return null;
            }
            return issued == null ? null : new IssuedToken(
                issued.executorId(),
                issued.targetId(),
                issued.scope(),
                issued.expiresAt()
            );
        }
    }

    public void clear() {
        synchronized (tokens) {
            tokens.clear();
        }
    }

    static UUID consoleExecutor() {
        return CONSOLE_EXECUTOR;
    }

    private static UUID normalizeTarget(UUID targetId, Scope scope) {
        if (scope == Scope.WORLD) {
            return WORLD_TARGET;
        }
        return Objects.requireNonNull(targetId, "targetId");
    }

    private static void requireNonNegative(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("time must not be negative");
        }
    }
}
