package firefly520.fireflymc.client.screen;

/** Separates accepting a first rules dialog from dismissing an already accepted reminder. */
final class RulesAcknowledgement {
    private final boolean requiresExplicitConsent;
    private boolean sent;

    RulesAcknowledgement(boolean requiresExplicitConsent) {
        this.requiresExplicitConsent = requiresExplicitConsent;
    }

    boolean confirm() {
        if (sent) return false;
        sent = true;
        return true;
    }

    boolean dismiss() {
        return !requiresExplicitConsent && confirm();
    }
}
