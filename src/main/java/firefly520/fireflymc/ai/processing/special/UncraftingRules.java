package firefly520.fireflymc.ai.processing.special;

/** Twilight's damage and list semantics are materials/game rules, not waived XP charges. */
public final class UncraftingRules {
    private UncraftingRules() {}

    public static int damagedParts(int recoverableParts, int damage, int maximumDamage) {
        if (recoverableParts < 0 || recoverableParts > 9 || damage < 0 || damage > maximumDamage || maximumDamage < 1)
            throw new IllegalArgumentException("UNCRAFTING_DAMAGE_INVALID");
        // Preserve native float arithmetic before Math.ceil, including boundary rounding.
        return (int) Math.ceil(Math.max(4, recoverableParts) * ((float) damage / maximumDamage));
    }

    public static boolean listAllows(boolean inverted, boolean contains) { return inverted == contains; }

    public static int selectedVariant(int cycle, int candidates) {
        if (candidates < 1 || candidates > 4096 || cycle < -4096 || cycle > 4096)
            throw new IllegalArgumentException("UNCRAFTING_VARIANT_LIMIT");
        return Math.floorMod(cycle, candidates);
    }
}
