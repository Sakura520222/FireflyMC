package firefly520.fireflymc.ai.integration.exploration;

/** An explicit opt-in budget for native STRUCTURE_STARTS work, never FULL terrain generation. */
public record CompassPreparationBudget(int maximumChunks, long maximumMillis) {
    public static final int HARD_MAXIMUM_CHUNKS = 8;

    public CompassPreparationBudget {
        if (maximumChunks < 0 || maximumChunks > HARD_MAXIMUM_CHUNKS
                || maximumMillis < 1000 || maximumMillis > 30000) {
            throw new IllegalArgumentException("COMPASS_PREPARATION_BUDGET_INVALID");
        }
    }

    public static CompassPreparationBudget disabled() { return new CompassPreparationBudget(0, 15000); }
    public boolean enabled() { return maximumChunks > 0; }

    /** Only admission is timed out. A late native completion/save must still be accepted as evidence. */
    public Deadline start(java.util.function.LongSupplier nanoTime) { return new Deadline(this, nanoTime); }
    public static final class Deadline {
        private final java.util.function.LongSupplier clock;
        private final long started, duration;
        private Deadline(CompassPreparationBudget budget, java.util.function.LongSupplier clock) {
            this.clock = java.util.Objects.requireNonNull(clock); started = clock.getAsLong(); duration = budget.maximumMillis() * 1_000_000;
        }
        public boolean expired() { return clock.getAsLong() - started >= duration; }
        public void beforeDispatch() { if (expired()) throw new IllegalStateException("COMPASS_PREPARATION_TIME_LIMIT"); }
    }
}
