package firefly520.fireflymc.ai.integration.exploration;

import java.util.*;

/** One admitted AI native slice per server tick, rotating independently of native worker-list order. */
public final class CompassTurnScheduler {
    private int tick = Integer.MIN_VALUE;
    private UUID selected, lastServed;
    private boolean claimed;
    public boolean claim(int currentTick, UUID id, Collection<UUID> eligible) {
        if (eligible.size() > 64) throw new IllegalArgumentException("COMPASS_SCHEDULER_LIMIT");
        var candidates = eligible.stream().distinct().sorted().toList();
        if (tick != currentTick) { tick = currentTick; claimed = false; selected = null; }
        if (!claimed && (selected == null || !candidates.contains(selected)))
            selected = candidates.stream().filter(value -> lastServed == null || value.compareTo(lastServed) > 0)
                    .findFirst().orElse(candidates.isEmpty() ? null : candidates.getFirst());
        if (claimed || !id.equals(selected)) return false;
        claimed = true; lastServed = id; return true;
    }
}
