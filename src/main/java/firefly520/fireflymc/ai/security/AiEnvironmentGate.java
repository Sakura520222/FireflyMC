package firefly520.fireflymc.ai.security;

import java.util.function.Supplier;

/** Pure policy shared by lifecycle and every network request; IP address and OP status are irrelevant. */
public final class AiEnvironmentGate {
    public enum Availability { NOT_SUPPORTED, NOT_READY, DISABLED, AUTH_REQUIRED, READY }
    private AiEnvironmentGate() {}

    public static Availability evaluate(boolean dedicated, boolean configured, boolean enabled,
            boolean uiSupported, boolean onlineMode, boolean modVerified, boolean rulesConfirmed,
            boolean passwordRequired, boolean passwordVerified) {
        if (!dedicated || !uiSupported) return Availability.NOT_SUPPORTED;
        if (!configured) return Availability.NOT_READY;
        if (!enabled) return Availability.DISABLED;
        if (!modVerified || !rulesConfirmed || (passwordRequired || !onlineMode) && !passwordVerified)
            return Availability.AUTH_REQUIRED;
        return Availability.READY;
    }

    public static <T> T onDedicatedServer(boolean dedicated, Supplier<T> initializer) {
        return dedicated ? initializer.get() : null;
    }
}
