package firefly520.fireflymc.ai.blueprint;

import java.util.*;

/** Main-thread change witnesses. An edit followed by restoring the same state still invalidates approval. */
public final class WorldMutationTracker implements AutoCloseable {
    private record Cell(Object world, long position) {}
    public final class Lease implements AutoCloseable {
        private final UUID id;
        private final Set<Cell> cells;
        private volatile boolean valid = true;
        private volatile String reason = "";
        private volatile List<String> invalidatingOrigin = List.of();
        private boolean released;
        private Lease(UUID id, Set<Cell> cells) { this.id = id; this.cells = cells; }
        public UUID id() { return id; }
        public void check() { if (!valid) throw new IllegalStateException(reason); }
        public boolean valid() { return valid; }
        public List<String> invalidatingOrigin() { return invalidatingOrigin; }
        public void invalidate(String why) { valid = false; reason = why; }
        @Override public void close() {
            if (released) return;
            released = true; invalidate("BUILD_WORLD_LEASE_CLOSED");
            for (Cell cell : cells) {
                var owners = watched.get(cell);
                if (owners != null) { owners.remove(this); if (owners.isEmpty()) watched.remove(cell); }
            }
            leases.remove(id);
        }
    }
    public final class Scope implements AutoCloseable {
        private final Lease lease;
        private final Set<Cell> allowed;
        private boolean unexpected;
        private Scope(Lease lease, Set<Cell> allowed) { this.lease = lease; this.allowed = allowed; }
        public boolean unexpected() { return unexpected; }
        @Override public void close() { if (active == this) active = null; }
    }
    private final Map<Cell, Set<Lease>> watched = new HashMap<>();
    private final Map<UUID, Lease> leases = new HashMap<>();
    private Scope active;
    public Lease watch(UUID id, Object world, Collection<Long> positions) {
        Objects.requireNonNull(id); Objects.requireNonNull(world);
        if (positions.isEmpty() || positions.size() > 16384 || leases.size() >= 64 || leases.containsKey(id))
            throw new IllegalStateException("BUILD_WORLD_WATCH_LIMIT");
        var cells = new HashSet<Cell>(); positions.forEach(pos -> cells.add(new Cell(world, pos)));
        var lease = new Lease(id, Set.copyOf(cells)); leases.put(id, lease);
        for (Cell cell : cells) watched.computeIfAbsent(cell, ignored -> new HashSet<>()).add(lease);
        return lease;
    }
    public Scope own(Lease lease, Object world, Collection<Long> positions) {
        lease.check();
        if (active != null || leases.get(lease.id) != lease) throw new IllegalStateException("BUILD_WORLD_SCOPE_INVALID");
        var allowed = new HashSet<Cell>(); positions.forEach(pos -> allowed.add(new Cell(world, pos)));
        if (!lease.cells.containsAll(allowed)) throw new IllegalArgumentException("BUILD_OUTSIDE_APPROVED_REGION");
        return active = new Scope(lease, Set.copyOf(allowed));
    }
    public void changed(Object world, long position) {
        var cell = new Cell(world, position);
        if (active != null && !active.allowed.contains(cell)) {
            active.unexpected = true; active.lease.invalidate("BUILD_UNEXPECTED_NEIGHBOR_EFFECT");
        }
        var owners = watched.get(cell);
        if (owners == null) return;
        List<String> origin = null;
        for (Lease lease : owners) if (active == null || active.lease != lease || !active.allowed.contains(cell)) {
            if (lease.valid()) {
                if (origin == null) origin = StackWalker.getInstance().walk(frames -> frames.skip(1).limit(18)
                        .map(frame -> frame.getClassName() + "." + frame.getMethodName()).toList());
                lease.invalidatingOrigin = origin;
            }
            lease.invalidate("BUILD_WORLD_CHANGED_REVIEW_REQUIRED");
        }
    }
    /** A native placement callback cannot expand its approved footprint before the write happens. */
    public void beforeChange(Object world, long position) {
        if (active != null && !active.allowed.contains(new Cell(world, position))) {
            active.unexpected = true; active.lease.invalidate("BUILD_UNEXPECTED_NEIGHBOR_EFFECT");
            throw new SecurityException("BUILD_UNAPPROVED_WORLD_WRITE");
        }
    }
    @Override public void close() { for (Lease lease : List.copyOf(leases.values())) lease.close(); active = null; }
}
