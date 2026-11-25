package firefly520.fireflymc.ai.blueprint.ownership;

import java.util.*;

/**
 * Current-runtime causal provenance for one approved source plan. This cache never reads a journal,
 * infers a native write from a matching state, or reconstructs an old token. Native callers must
 * validate the returned current tokens before entering an actual interaction and supply newly
 * witnessed tokens after it. Metadata recording is a separate prerequisite for the next action.
 */
public final class BuildOwnershipChain {
    public static final int MAX_POSITIONS = 4096;
    public static final int MAX_CONTRIBUTIONS = 8192;
    private static final int MAX_ACTIONS = MAX_CONTRIBUTIONS * 2;
    private record Contribution(UUID actionId, String before, String after) {}

    /** Unforgeable cache permit, frozen before a native inverse operation. It grants no world access. */
    public static final class Permit {
        private final BuildOwnershipChain source;
        private final UUID actionId;
        private final long revision;
        private final Map<Long, String> before, after;
        private final Map<Long, Integer> remaining;
        private final Set<UUID> removedGroups;
        private final List<OwnershipToken> tokens;
        private Permit(BuildOwnershipChain source, UUID actionId, long revision,
                Map<Long, String> before, Map<Long, String> after, Map<Long, Integer> remaining,
                Set<UUID> removedGroups, List<OwnershipToken> tokens) {
            this.source = source; this.actionId = actionId; this.revision = revision;
            this.before = Map.copyOf(before); this.after = Map.copyOf(after);
            this.remaining = Map.copyOf(remaining); this.removedGroups = Set.copyOf(removedGroups);
            this.tokens = List.copyOf(tokens);
        }
        public UUID actionId() { return actionId; }
        public List<OwnershipToken> tokens() { return tokens; }
        public Map<Long, String> before() { return before; }
        public Map<Long, String> after() { return after; }
    }

    private final UUID runtime, serverInstance, player, task, plan;
    private final String planHash, dimension;
    private final Map<Long, List<Contribution>> history = new LinkedHashMap<>();
    private final Map<Long, OwnershipToken> current = new LinkedHashMap<>();
    private final Map<UUID, Set<Long>> groups = new LinkedHashMap<>();
    private final Set<UUID> actions = new HashSet<>(), pending = new HashSet<>(), proofNonces = new HashSet<>();
    private int contributions;
    private long revision;
    private String failure = "";

    public BuildOwnershipChain(UUID runtime, UUID serverInstance, UUID player, UUID sourceTask,
            UUID sourcePlan, String planHash, String dimension) {
        this.runtime = Objects.requireNonNull(runtime); this.serverInstance = Objects.requireNonNull(serverInstance);
        this.player = Objects.requireNonNull(player); this.task = Objects.requireNonNull(sourceTask); this.plan = Objects.requireNonNull(sourcePlan);
        this.planHash = Objects.requireNonNull(planHash); this.dimension = Objects.requireNonNull(dimension);
        // Reuse the authority record's exact identity validation without inventing a claim.
        owner(sourcePlan);
        if (dimension.length() > 512 || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException("OWNERSHIP_DIMENSION_INVALID");
    }

    public synchronized OwnershipToken.Owner owner(UUID action) {
        return new OwnershipToken.Owner(serverInstance, player, task, plan, Objects.requireNonNull(action), planHash);
    }

    /** Existing source contributions touched by the next placement must still have their current proof. */
    public synchronized List<OwnershipToken> prePlacement(Map<Long, String> before) {
        ready(); var wanted = states(before); var proofs = new ArrayList<OwnershipToken>();
        for (var entry : wanted.entrySet()) if (history.containsKey(entry.getKey())) {
            var token = current(entry.getKey());
            if (!top(entry.getKey()).after().equals(entry.getValue()) || !token.afterState().equals(entry.getValue()))
                throw new IllegalStateException("OWNERSHIP_SOURCE_CAUSAL_STATE_CHANGED");
            proofs.add(token);
        }
        return List.copyOf(proofs);
    }

    /** Called only with a successful native placement's newly minted causal witnesses. */
    public synchronized void placement(UUID action, Map<Long, OwnershipTransitions.Transition> expected,
            List<OwnershipToken> freshTokens) {
        ready(); freshAction(action);
        var transitions = transitions(expected); var tokens = fresh(action, transitions, freshTokens);
        if (history.size() + transitions.keySet().stream().filter(position -> !history.containsKey(position)).count() > MAX_POSITIONS
                || contributions + transitions.size() > MAX_CONTRIBUTIONS)
            throw new IllegalStateException("OWNERSHIP_CAUSAL_CACHE_CAPACITY");
        for (var entry : transitions.entrySet()) if (history.containsKey(entry.getKey())) {
            var prior = current(entry.getKey());
            if (!top(entry.getKey()).after().equals(entry.getValue().before()) || !prior.afterState().equals(entry.getValue().before()))
                throw new IllegalStateException("OWNERSHIP_SOURCE_CAUSAL_STATE_CHANGED");
            requireNewGeneration(prior, tokens.get(entry.getKey()));
        }
        for (var entry : transitions.entrySet()) {
            history.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>())
                    .add(new Contribution(action, entry.getValue().before(), entry.getValue().after()));
            current.put(entry.getKey(), tokens.get(entry.getKey()));
        }
        groups.put(action, Set.copyOf(transitions.keySet())); contributions += transitions.size();
        accepted(action, tokens.values());
    }

    /** Exact requested positions only; missing contributors never become claims by state comparison. */
    public synchronized List<OwnershipToken> requireCurrent(Set<Long> positions) {
        ready(); var wanted = positions(positions); var result = new ArrayList<OwnershipToken>();
        for (long position : wanted) result.add(current(position));
        return List.copyOf(result);
    }

    /** Freeze the exact current proof and the whole causal groups a native inverse may consume. */
    public synchronized Permit prepareUndo(UUID action, Map<Long, String> before, Map<Long, String> after) {
        ready(); freshAction(action); var initial = states(before); var target = states(after);
        if (!initial.keySet().equals(target.keySet())) throw new IllegalArgumentException("OWNERSHIP_INVERSE_FOOTPRINT_CHANGED");
        var remaining = new LinkedHashMap<Long, Integer>(); var removed = new LinkedHashMap<UUID, Set<Long>>();
        var proofs = new ArrayList<OwnershipToken>();
        for (var entry : initial.entrySet()) {
            long position = entry.getKey(); String wanted = target.get(position); var token = current(position);
            if (entry.getValue().equals(wanted) || !top(position).after().equals(entry.getValue()) || !token.afterState().equals(entry.getValue()))
                throw new IllegalStateException("OWNERSHIP_INVERSE_CAUSAL_STATE_CHANGED");
            var stack = history.get(position); int keep = -1;
            for (int index = stack.size() - 1; index >= 0; index--) {
                var cause = stack.get(index); removed.computeIfAbsent(cause.actionId(), ignored -> new HashSet<>()).add(position);
                if (cause.before().equals(wanted)) { keep = index; break; }
            }
            if (keep < 0) throw new IllegalStateException("OWNERSHIP_INVERSE_NOT_A_SOURCE_REDUCTION");
            remaining.put(position, keep); proofs.add(token);
        }
        for (var entry : removed.entrySet()) if (!entry.getValue().equals(groups.get(entry.getKey())))
            throw new IllegalStateException("OWNERSHIP_MULTIBLOCK_GROUP_INCOMPLETE");
        return new Permit(this, action, revision, initial, target, remaining, removed.keySet(), proofs);
    }

    /**
     * Continue the still-remaining source contribution using a NEW native inverse witness. The old
     * placement token is never restored. Final-state inverse tokens are returned for native retirement.
     */
    public synchronized List<OwnershipToken> completeUndo(Permit permit, List<OwnershipToken> freshInverse) {
        ready(); Objects.requireNonNull(permit);
        if (permit.source != this || permit.revision != revision)
            throw new IllegalStateException("OWNERSHIP_INVERSE_PERMIT_STALE");
        freshAction(permit.actionId);
        for (var token : permit.tokens) if (!token.equals(current.get(token.position())))
            throw new IllegalStateException("OWNERSHIP_INVERSE_PERMIT_STALE");
        var transitions = new LinkedHashMap<Long, OwnershipTransitions.Transition>();
        permit.before.forEach((position, before) -> transitions.put(position, new OwnershipTransitions.Transition(before, permit.after.get(position))));
        var tokens = fresh(permit.actionId, transitions, freshInverse);
        for (var old : permit.tokens) requireNewGeneration(old, tokens.get(old.position()));
        for (var entry : permit.remaining.entrySet()) if (entry.getValue() != 0
                && !history.get(entry.getKey()).get(entry.getValue() - 1).after().equals(tokens.get(entry.getKey()).afterState()))
            throw new IllegalStateException("OWNERSHIP_INVERSE_REMAINDER_CHANGED");
        var discarded = new ArrayList<OwnershipToken>();
        for (long position : new TreeSet<>(permit.remaining.keySet())) {
            var stack = history.get(position); int keep = permit.remaining.get(position);
            contributions -= stack.size() - keep;
            if (keep == 0) { history.remove(position); current.remove(position); discarded.add(tokens.get(position)); }
            else {
                stack.subList(keep, stack.size()).clear();
                // The inverse witness's beforeState belongs to this native undo, while its afterState
                // must be the previously proven top contribution still owned by the source plan.
                current.put(position, tokens.get(position));
            }
        }
        permit.removedGroups.forEach(groups::remove); accepted(permit.actionId, tokens.values());
        return List.copyOf(discarded);
    }

    /** The evidence IO callback acknowledges its independently appended build_ownership event. */
    public synchronized void markRecorded(UUID action, boolean success) {
        Objects.requireNonNull(action);
        if (!success) { fail("OWNERSHIP_JOURNAL_UNAVAILABLE"); return; }
        if (!failure.isEmpty()) return;
        if (!actions.contains(action)) { fail("OWNERSHIP_RECORDING_ACTION_UNKNOWN"); return; }
        pending.remove(action); // duplicate successful acknowledgements are harmless
    }

    public synchronized void fail(String reason) {
        if (!failure.isEmpty()) return;
        failure = reason != null && reason.matches("[A-Z0-9_]{1,160}") ? reason : "OWNERSHIP_CHAIN_UNAVAILABLE";
        pending.clear(); revision++;
    }
    public synchronized String reason() {
        return !failure.isEmpty() ? failure : pending.isEmpty() ? "" : "OWNERSHIP_EVIDENCE_PENDING";
    }

    private OwnershipToken current(long position) {
        var stack = history.get(position); var token = current.get(position);
        if (stack == null || stack.isEmpty() || token == null || !token.afterState().equals(stack.getLast().after()))
            throw new IllegalStateException("OWNERSHIP_SOURCE_CONTRIBUTOR_MISSING");
        return token;
    }
    private Contribution top(long position) { return history.get(position).getLast(); }
    private void ready() { String reason = reason(); if (!reason.isEmpty()) throw new IllegalStateException(reason); }
    private void freshAction(UUID action) {
        Objects.requireNonNull(action);
        if (actions.contains(action)) throw new IllegalStateException("OWNERSHIP_ACTION_ALREADY_RECORDED");
        if (actions.size() >= MAX_ACTIONS) throw new IllegalStateException("OWNERSHIP_CAUSAL_CACHE_CAPACITY");
    }
    private void accepted(UUID action, Collection<OwnershipToken> tokens) {
        actions.add(action); pending.add(action); tokens.forEach(token -> proofNonces.add(token.claimNonce())); revision++;
    }
    private Map<Long, OwnershipToken> fresh(UUID action, Map<Long, OwnershipTransitions.Transition> transitions, List<OwnershipToken> proofs) {
        Objects.requireNonNull(proofs); var tokens = new TreeMap<Long, OwnershipToken>(); var nonces = new HashSet<UUID>();
        if (proofs.size() != transitions.size() || proofNonces.size() + proofs.size() > MAX_ACTIONS)
            throw new IllegalStateException("OWNERSHIP_FRESH_NATIVE_PROOF_REQUIRED");
        for (var token : proofs) {
            if (token == null) throw new IllegalStateException("OWNERSHIP_FRESH_NATIVE_PROOF_REQUIRED");
            var transition = transitions.get(token.position());
            if (!runtime.equals(token.runtimeSession()) || !dimension.equals(token.dimension()) || !owner(action).equals(token.owner())
                    || transition == null || !transition.before().equals(token.beforeState()) || !transition.after().equals(token.afterState())
                    || tokens.putIfAbsent(token.position(), token) != null || proofNonces.contains(token.claimNonce()) || !nonces.add(token.claimNonce()))
                throw new IllegalStateException("OWNERSHIP_FRESH_NATIVE_PROOF_REQUIRED");
        }
        if (!tokens.keySet().equals(transitions.keySet())) throw new IllegalStateException("OWNERSHIP_FRESH_NATIVE_PROOF_REQUIRED");
        return tokens;
    }
    private static void requireNewGeneration(OwnershipToken previous, OwnershipToken next) {
        if (previous.claimNonce().equals(next.claimNonce()) || previous.chunkNonce().equals(next.chunkNonce()) && next.generation() <= previous.generation())
            throw new IllegalStateException("OWNERSHIP_FRESH_NATIVE_PROOF_REQUIRED");
    }
    private static SortedSet<Long> positions(Set<Long> values) {
        Objects.requireNonNull(values);
        if (values.isEmpty() || values.size() > MAX_POSITIONS || values.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("OWNERSHIP_FOOTPRINT_INVALID");
        return new TreeSet<>(values);
    }
    private static Map<Long, String> states(Map<Long, String> values) {
        Objects.requireNonNull(values);
        if (values.isEmpty() || values.size() > MAX_POSITIONS) throw new IllegalArgumentException("OWNERSHIP_FOOTPRINT_INVALID");
        var copy = new TreeMap<Long, String>();
        values.forEach((position, state) -> { Objects.requireNonNull(position); validState(state); copy.put(position, state); });
        return copy;
    }
    private static Map<Long, OwnershipTransitions.Transition> transitions(Map<Long, OwnershipTransitions.Transition> values) {
        Objects.requireNonNull(values);
        if (values.isEmpty() || values.size() > MAX_POSITIONS) throw new IllegalArgumentException("OWNERSHIP_FOOTPRINT_INVALID");
        var copy = new TreeMap<Long, OwnershipTransitions.Transition>();
        values.forEach((position, value) -> {
            Objects.requireNonNull(position); Objects.requireNonNull(value); validState(value.before()); validState(value.after());
            if (value.before().equals(value.after())) throw new IllegalArgumentException("OWNERSHIP_NOOP_IS_NOT_A_CONTRIBUTION");
            copy.put(position, value);
        });
        return copy;
    }
    private static void validState(String state) {
        if (state == null || state.isBlank() || state.length() > 4096) throw new IllegalArgumentException("OWNERSHIP_STATE_INVALID");
    }
}
