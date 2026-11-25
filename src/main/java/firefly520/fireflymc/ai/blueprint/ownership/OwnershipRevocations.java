package firefly520.fireflymc.ai.blueprint.ownership;

import java.util.*;

/** Bounded, negative-only runtime index. It cannot create or reconstruct a claim. */
public final class OwnershipRevocations {
    private final int capacity;
    private final Set<UUID> claims = new HashSet<>(), chunks = new HashSet<>();
    // These references only identify what to REVOKE if a native load omits its marker. Membership
    // is never consulted to grant authority; the exact canonical token is still mandatory.
    private final Map<String, Set<UUID>> targets = new HashMap<>();
    private int targetCount;
    private boolean exhausted;
    public OwnershipRevocations(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("OWNERSHIP_REVOCATION_CAPACITY_INVALID");
        this.capacity = capacity;
    }
    public synchronized void claim(UUID nonce) { add(claims, nonce); }
    public synchronized void chunk(UUID nonce) { add(chunks, nonce); }
    public synchronized boolean revoked(OwnershipToken token) {
        return exhausted || chunks.contains(token.chunkNonce()) || claims.contains(token.claimNonce());
    }
    public synchronized boolean exhausted() { return exhausted; }
    public synchronized boolean chunkRevoked(UUID nonce) { return exhausted || chunks.contains(nonce); }
    public synchronized void target(String location, UUID nonce) {
        Objects.requireNonNull(location); Objects.requireNonNull(nonce);
        if (exhausted || targets.getOrDefault(location, Set.of()).contains(nonce)) return;
        if (full()) { exhaust(); return; }
        targets.computeIfAbsent(location, ignored -> new HashSet<>()).add(nonce); targetCount++;
    }
    public synchronized void missingMarker(String location) {
        for (var nonce : List.copyOf(targets.getOrDefault(location, Set.of()))) chunk(nonce);
    }
    public synchronized void replacedSource(String location, UUID sourceNonce) {
        for (var nonce : List.copyOf(targets.getOrDefault(location, Set.of()))) if (!nonce.equals(sourceNonce)) chunk(nonce);
    }
    private void add(Set<UUID> target, UUID nonce) {
        Objects.requireNonNull(nonce);
        if (exhausted || target.contains(nonce)) return;
        if (full()) exhaust();
        else target.add(nonce);
    }
    private boolean full() { return claims.size() + chunks.size() + targetCount >= capacity; }
    private void exhaust() { exhausted = true; claims.clear(); chunks.clear(); targets.clear(); targetCount = 0; }
}
