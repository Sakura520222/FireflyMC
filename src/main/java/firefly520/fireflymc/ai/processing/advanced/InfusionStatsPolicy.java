package firefly520.fireflymc.ai.processing.advanced;

/** A versioned deterministic virtual workstation policy; model-chosen bonuses never enter this calculation. */
public final class InfusionStatsPolicy {
    public static final String VERSION = "infusion-minimum-valid-v1";
    public record Bounds(float minimum, float maximum) {
        public Bounds {
            if (!Float.isFinite(minimum) || minimum < 0 || minimum > 100 || !Float.isFinite(maximum)
                    || maximum != -1 && (maximum < minimum || maximum > 100))
                throw new IllegalArgumentException("INFUSION_ATTRIBUTE_BOUNDS_INVALID");
        }
    }
    public record Stats(float eterna, float quanta, float arcana) {}
    private InfusionStatsPolicy() {}
    public static Stats choose(Bounds eterna, Bounds quanta, Bounds arcana, float playerMaximumEterna) {
        if (!Float.isFinite(playerMaximumEterna) || playerMaximumEterna < eterna.minimum())
            throw new IllegalArgumentException("INFUSION_PLAYER_ETERNA_LIMIT");
        return new Stats(eterna.minimum(), quanta.minimum(), arcana.minimum());
    }
}
