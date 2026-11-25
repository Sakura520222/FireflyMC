package firefly520.fireflymc.ai.integration.exploration;

import java.util.function.LongSupplier;

/** Explicit bounded candidate checks. A single native call is non-preemptible; elapsed overruns remain observable. */
public record CompassSearchBudget(int radiusBlocks, int maxChecks, long maxMillis) {
    public static final long SLICE_NANOS = 2_000_000;
    public CompassSearchBudget {
        if (radiusBlocks < 1 || radiusBlocks > 2048 || maxChecks < 1 || maxChecks > 1024 || maxMillis < 1000 || maxMillis > 30000)
            throw new IllegalArgumentException("COMPASS_BUDGET_INVALID");
    }
    public static final class Stopped extends RuntimeException {
        public Stopped(String reason) { super(reason, null, false, false); }
    }
    public static final class Meter {
        private final CompassSearchBudget budget;
        private final int originX, originZ;
        private final LongSupplier clock;
        private final long started;
        private long sliceStarted, candidateStarted, maximumSlice;
        private int checks, frames, overruns;
        public Meter(CompassSearchBudget budget, int originX, int originZ, LongSupplier clock) {
            this.budget = budget; this.originX = originX; this.originZ = originZ; this.clock = clock; started = clock.getAsLong();
        }
        public void beginSlice() { checkTime(); sliceStarted = clock.getAsLong(); candidateStarted = sliceStarted; frames++; }
        /** Keep whole-frame overrun measurement while separating save-witness setup from candidate work. */
        public void candidateExecutionStarts() { checkTime(); candidateStarted = clock.getAsLong(); }
        public void beforeCheck(int x, int z) {
            checkTime();
            if (Math.abs((long) x - originX) > budget.radiusBlocks() || Math.abs((long) z - originZ) > budget.radiusBlocks())
                throw new Stopped("COMPASS_RADIUS_LIMIT");
            if (Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000) throw new Stopped("COMPASS_WORLD_BOUNDARY");
            if (checks >= budget.maxChecks()) throw new Stopped("COMPASS_CHECK_LIMIT");
            if (clock.getAsLong() - candidateStarted >= SLICE_NANOS) throw new Stopped("COMPASS_SLICE_LIMIT");
            checks++;
        }
        /** Return only the single admission that yielded before native sampling; completed checks are never reclaimed. */
        public void releaseUnexaminedCheck(int priorChecks) {
            if (priorChecks < 0 || checks != priorChecks + 1) throw new IllegalStateException("COMPASS_UNEXAMINED_CHECK_IDENTITY_CHANGED");
            checks = priorChecks;
        }
        public void endSlice() {
            long duration = Math.max(0, clock.getAsLong() - sliceStarted); maximumSlice = Math.max(maximumSlice, duration);
            if (duration > SLICE_NANOS) overruns++;
        }
        public void checkTime() { if (clock.getAsLong() - started >= budget.maxMillis() * 1_000_000L) throw new Stopped("COMPASS_TIME_LIMIT"); }
        public int checks() { return checks; }
        public int frames() { return frames; }
        public long maximumSliceNanos() { return maximumSlice; }
        public int sliceOverruns() { return overruns; }
    }
}
