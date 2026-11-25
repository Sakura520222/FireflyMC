package firefly520.fireflymc.ai.integration.exploration;

import firefly520.fireflymc.ai.security.Actor;
import java.util.*;

/**
 * One server-owned gate admits at most one preparation at a time. A lease is not world authority:
 * the caller must persist dispatch intent and recheck the approved search before native dispatch.
 */
public final class CompassPreparationGate {
    public static final class Lease {
        private final CompassPreparationGate gate;
        private final String owner;
        private final UUID operation;
        private final String dimension;
        private final long chunk;
        private final CompassPreparationBudget budget;
        private volatile boolean dispatched, cancelled;
        private Lease(CompassPreparationGate gate, String owner, UUID operation, String dimension, long chunk, CompassPreparationBudget budget) {
            this.gate = gate; this.owner = owner; this.operation = operation; this.dimension = dimension; this.chunk = chunk;
            this.budget = budget;
        }
        public String owner() { return owner; }
        public UUID operation() { return operation; }
        public String dimension() { return dimension; }
        public long chunk() { return chunk; }
        public boolean dispatched() { return dispatched; }
        public boolean cancelled() { return cancelled; }
    }

    private record Identity(String owner, UUID operation) { }
    private record Chunk(String dimension, long position) { }
    private record Policy(String dimension, CompassPreparationBudget budget) { }
    private final Map<Identity, Set<Chunk>> dispatched = new HashMap<>();
    private final Map<Identity, Policy> policies = new HashMap<>();
    private final Set<Identity> recovered = new HashSet<>();
    private Lease active;
    private boolean restorationOverflow;

    public synchronized Optional<Lease> acquire(Actor actor, UUID operation, String dimension, long chunk,
                                               CompassPreparationBudget budget) {
        actor.requireAccess(actor.owner()); Objects.requireNonNull(operation); Objects.requireNonNull(budget);
        if (dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || dimension.length() > 160)
            throw new IllegalArgumentException("COMPASS_PREPARATION_DIMENSION_INVALID");
        if (!budget.enabled()) throw new IllegalStateException("COMPASS_PREPARATION_NOT_APPROVED");
        if (restorationOverflow || !recovered.isEmpty()) throw new IllegalStateException("COMPASS_PREPARATION_RECOVERY_PENDING");
        var identity = new Identity(actor.owner(), operation);
        var policy = policies.get(identity);
        if (policy != null && !policy.equals(new Policy(dimension, budget))) throw new IllegalStateException("COMPASS_PREPARATION_POLICY_CHANGED");
        var prior = dispatched.getOrDefault(identity, Set.of());
        if (prior.contains(new Chunk(dimension, chunk))) throw new IllegalStateException("COMPASS_PREPARATION_ALREADY_DISPATCHED");
        if (prior.size() >= budget.maximumChunks()) throw new IllegalStateException("COMPASS_PREPARATION_CHUNK_LIMIT");
        if (active != null) return Optional.empty();
        if (dispatched.size() >= 256 && !dispatched.containsKey(identity)) throw new IllegalStateException("COMPASS_PREPARATION_OPERATION_LIMIT");
        active = new Lease(this, actor.owner(), operation, dimension, chunk, budget); return Optional.of(active);
    }

    /** Called only after the matching durable journal dispatch record succeeds, immediately before the native API. */
    public synchronized void dispatched(Actor actor, Lease lease) {
        require(actor, lease);
        if (lease.cancelled || lease.dispatched) throw new IllegalStateException("COMPASS_PREPARATION_LEASE_NOT_FRESH");
        lease.dispatched = true;
        policies.putIfAbsent(new Identity(lease.owner, lease.operation), new Policy(lease.dimension, lease.budget));
        dispatched.computeIfAbsent(new Identity(lease.owner, lease.operation), ignored -> new HashSet<>())
                .add(new Chunk(lease.dimension, lease.chunk));
    }

    /** Cancellation cannot retract native generation. The admitted future must actually settle before the gate reopens. */
    public synchronized boolean cancel(Actor actor, Lease lease) {
        Objects.requireNonNull(lease); actor.requireAccess(lease.owner);
        if (lease.gate == this && lease.cancelled && !lease.dispatched) return true;
        require(actor, lease); lease.cancelled = true;
        if (lease.dispatched) return false;
        active = null; return true;
    }

    /** A real completed native future releases concurrency only; this does not assert disk save or search success. */
    public synchronized void nativeCompleted(Actor actor, Lease lease) {
        require(actor, lease);
        if (!lease.dispatched) throw new IllegalStateException("COMPASS_PREPARATION_NOT_DISPATCHED");
        active = null;
    }

    /** Unknown dispatched work is a negative fence, never a retry permit. Ordinary native player searches are unaffected. */
    public synchronized void restoreUnknown(String owner, UUID operation) {
        UUID.fromString(owner); Objects.requireNonNull(operation);
        if (recovered.size() >= 256 && !recovered.contains(new Identity(owner, operation))) {
            restorationOverflow = true; throw new IllegalStateException("COMPASS_PREPARATION_RECOVERY_INDEX_LIMIT");
        }
        recovered.add(new Identity(owner, operation));
    }

    /** The controller must first accept a new revision-bound reconciliation backed by real terminal and save evidence. */
    public synchronized void reconciled(Actor actor, UUID operation) {
        actor.requireAccess(actor.owner());
        if (!recovered.remove(new Identity(actor.owner(), operation))) throw new IllegalStateException("COMPASS_PREPARATION_RECOVERY_NOT_FOUND");
    }

    public synchronized boolean pending() { return active != null || restorationOverflow || !recovered.isEmpty(); }

    /** Retiring the concurrency cache never erases the durable journal's operation/candidate replay checks. */
    public synchronized void retire(Actor actor, UUID operation) {
        actor.requireAccess(actor.owner()); var identity = new Identity(actor.owner(), operation);
        if (recovered.contains(identity) || active != null && active.owner.equals(actor.owner()) && active.operation.equals(operation))
            throw new IllegalStateException("COMPASS_PREPARATION_STILL_PENDING");
        dispatched.remove(identity); policies.remove(identity);
    }

    private void require(Actor actor, Lease lease) {
        Objects.requireNonNull(lease); actor.requireAccess(lease.owner);
        if (lease.gate != this || active != lease) throw new IllegalStateException("COMPASS_PREPARATION_LEASE_EXPIRED");
    }
}
