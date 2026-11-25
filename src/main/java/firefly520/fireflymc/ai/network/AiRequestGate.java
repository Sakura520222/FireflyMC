package firefly520.fireflymc.ai.network;

import java.util.*;
import java.util.function.LongSupplier;

/** Per connection rate limit and bounded replay cache. Submission UUIDs are additionally persisted. */
public final class AiRequestGate {
    private final LongSupplier clock;
    private long interval;
    private int count;
    private final LinkedHashMap<UUID, String> requests = new LinkedHashMap<>();
    public AiRequestGate(LongSupplier clock) { this.clock = clock; }
    public synchronized boolean accept(UUID id, String digest) {
        long now = clock.getAsLong();
        if (now - interval >= 1000) { interval = now; count = 0; }
        if (++count > 16) throw new IllegalStateException("AI_RATE_LIMIT");
        String old = requests.get(id);
        if (old != null) {
            if (!old.equals(digest)) throw new SecurityException("AI_REQUEST_ID_REUSED");
            return false;
        }
        requests.put(id, digest);
        while (requests.size() > 256) requests.remove(requests.keySet().iterator().next());
        return true;
    }
}
