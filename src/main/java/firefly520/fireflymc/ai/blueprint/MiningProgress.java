package firefly520.fireflymc.ai.blueprint;

/** Virtual mining still pays native survival time: at most one contribution per actual server tick. */
public final class MiningProgress {
    private int lastTick = Integer.MIN_VALUE;
    private int activeTicks;
    private double progress;
    public double advance(int tick, double nativeRate) {
        if (tick < lastTick || Double.isNaN(nativeRate) || nativeRate < 0) throw new IllegalArgumentException("MINING_PROGRESS_INVALID");
        if (tick != lastTick) { activeTicks = Math.incrementExact(activeTicks); lastTick = tick; recheck(nativeRate); }
        return progress;
    }
    /** Vanilla recomputes elapsed mining against the current rate, including mining fatigue. */
    public double recheck(double nativeRate) {
        if (Double.isNaN(nativeRate) || nativeRate < 0 || activeTicks < 1) throw new IllegalArgumentException("MINING_PROGRESS_INVALID");
        progress = Math.min(1, activeTicks * nativeRate); return progress;
    }
    public double value() { return progress; }
    public boolean counted(int tick) { return tick == lastTick; }
    public boolean complete() { return progress >= 1; }
}
