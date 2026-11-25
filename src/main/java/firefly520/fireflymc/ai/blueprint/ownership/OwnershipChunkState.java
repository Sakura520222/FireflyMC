package firefly520.fireflymc.ai.blueprint.ownership;

import java.util.*;

/** Pure synchronized metadata; it never changes blocks, grants materials or infers ownership from state. */
public final class OwnershipChunkState {
    public static final int MAX_CLAIMS = 4096;
    public record Snapshot(UUID chunkNonce, long revision, List<OwnershipToken> claims, boolean unsafe, String reason) {
        public Snapshot {
            Objects.requireNonNull(chunkNonce); Objects.requireNonNull(reason); claims = List.copyOf(claims);
            if (revision < 0 || claims.size() > MAX_CLAIMS || reason.length() > 160)
                throw new IllegalArgumentException("OWNERSHIP_SNAPSHOT_INVALID");
        }
    }
    private UUID chunkNonce = UUID.randomUUID();
    private long revision;
    private final Map<Long, OwnershipToken> claims = new LinkedHashMap<>();
    private boolean unsafe, existed;
    private String reason = "";
    public synchronized Snapshot snapshot() { return new Snapshot(chunkNonce, revision, List.copyOf(claims.values()), unsafe, reason); }
    public synchronized boolean hasMetadata() { return existed || !claims.isEmpty(); }
    public synchronized boolean hasClaims() { return !claims.isEmpty(); }
    public synchronized long revision() { return revision; }
    synchronized OwnershipToken claimAt(long position) { return claims.get(position); }
    synchronized UUID nonce() { return chunkNonce; }
    public synchronized void changed(long position) {
        advance(); claims.remove(position); if (existed) reason = "OWNERSHIP_CELL_CHANGED";
    }
    /** A known bulk mutation retires existing claims but permits a later fully observed native action. */
    public synchronized void invalidateAll(String code) {
        advance(); claims.clear(); chunkNonce = UUID.randomUUID(); if (existed) reason = code;
    }
    public synchronized void unknown(String code) {
        advance(); claims.clear(); unsafe = true; reason = code;
    }
    public synchronized OwnershipToken claim(UUID runtime, String dimension, long position, OwnershipToken.Owner owner,
            UUID nonce, String before, String after) {
        if (unsafe) throw new IllegalStateException(reason);
        if (claims.size() >= MAX_CLAIMS && !claims.containsKey(position)) throw new IllegalStateException("OWNERSHIP_CHUNK_CAPACITY");
        if (revision == Long.MAX_VALUE) { unknown("OWNERSHIP_GENERATION_EXHAUSTED"); throw new IllegalStateException(reason); }
        existed = true; revision++;
        var token = new OwnershipToken(1, runtime, chunkNonce, dimension, position, revision, nonce, owner, before, after);
        claims.put(position, token); return token;
    }
    public synchronized void require(OwnershipToken token, UUID runtime, String actualAfterState) {
        if (unsafe) throw new IllegalStateException(reason);
        if (!token.runtimeSession().equals(runtime)) throw new IllegalStateException("OWNERSHIP_RESTART_CONTINUITY_UNPROVEN");
        if (!token.chunkNonce().equals(chunkNonce) || !token.equals(claims.get(token.position()))) throw new IllegalStateException("OWNERSHIP_NONCE_OR_GENERATION_CHANGED");
        if (!token.afterState().equals(actualAfterState)) throw new IllegalStateException("OWNERSHIP_CURRENT_STATE_CHANGED");
    }
    public synchronized void restore(Snapshot stored, boolean coherentPayload, boolean alteredDuringLoad) {
        restoreWithinRuntime(stored, coherentPayload, alteredDuringLoad, null, ignored -> true);
    }
    /** Only an existing runtime's negative index may permit same-JVM canonical reload. */
    public synchronized void restoreWithinRuntime(Snapshot stored, boolean coherentPayload, boolean alteredDuringLoad,
            UUID runtime, java.util.function.Predicate<OwnershipToken> revoked) {
        boolean escaped = unsafe;
        existed = true;
        if (!coherentPayload || alteredDuringLoad || stored.unsafe() || escaped) {
            claims.clear(); chunkNonce = UUID.randomUUID(); advance();
            reason = escaped ? reason : alteredDuringLoad ? "OWNERSHIP_CHANGED_DURING_CHUNK_LOAD" : "OWNERSHIP_PAYLOAD_UNVERIFIED";
            // An invalid old payload cannot acquire authority. A fresh later audited action may claim
            // again unless a mutable reference escaped or a mutation path is still unsupported.
            unsafe = escaped; return;
        }
        if (stored.claims().size() > MAX_CLAIMS) throw new IllegalArgumentException("OWNERSHIP_CHUNK_CAPACITY");
        var next = new LinkedHashMap<Long, OwnershipToken>();
        for (var token : stored.claims()) {
            if (!token.chunkNonce().equals(stored.chunkNonce()) || token.generation() > stored.revision()
                    || next.putIfAbsent(token.position(), token) != null) throw new IllegalArgumentException("OWNERSHIP_STORED_TOKEN_INVALID");
        }
        // Co-location alone does not prove continuity. A still-live runtime additionally retains
        // every negative revocation; a different process (or unavailable index) cannot grant rights.
        boolean continuous = runtime != null && next.values().stream().allMatch(token -> token.runtimeSession().equals(runtime));
        claims.clear();
        if (continuous) {
            next.values().removeIf(revoked); claims.putAll(next); chunkNonce = stored.chunkNonce(); revision = stored.revision();
            reason = next.size() == stored.claims().size() ? stored.reason() : "OWNERSHIP_LOADED_CLAIMS_REVOKED";
        }
        else { chunkNonce = UUID.randomUUID(); revision = 0; reason = next.isEmpty() ? "" : "OWNERSHIP_RESTART_CONTINUITY_UNPROVEN"; }
    }
    private void advance() {
        if (revision == Long.MAX_VALUE) { unsafe = true; claims.clear(); reason = "OWNERSHIP_GENERATION_EXHAUSTED"; }
        else revision++;
    }
}
