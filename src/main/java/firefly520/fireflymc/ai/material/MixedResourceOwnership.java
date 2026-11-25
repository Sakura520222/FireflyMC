package firefly520.fireflymc.ai.material;

import firefly520.fireflymc.ai.security.Actor;
import java.util.*;

/** Runtime fence rebuilt from the owner's durable mixed journal before enabling standalone controls. */
final class MixedResourceOwnership {
    private record Binding(String owner, String receipt) {}
    private final Map<UUID, Binding> bindings = new HashMap<>();
    private final Set<UUID> closedInputs = new HashSet<>();
    synchronized void bind(String owner, UUID id, String receipt, boolean inputClosed) {
        bind(owner, id, receipt);
        if (inputClosed) closedInputs.add(id);
    }
    synchronized void inputOpen(Actor actor, UUID id) {
        var binding = bindings.get(id);
        if (binding != null) {
            actor.requireAccess(binding.owner());
            if (closedInputs.contains(id)) throw new IllegalStateException("MIXED_INPUTS_CLOSED");
        }
    }
    synchronized void approveRemainingInputs(Actor actor, UUID id, String receipt) {
        require(actor, id, receipt); closedInputs.remove(id);
    }
    synchronized void forget(String owner) {
        bindings.entrySet().removeIf(entry -> entry.getValue().owner().equals(owner));
        closedInputs.removeIf(id -> !bindings.containsKey(id));
    }
    synchronized void bind(String owner, UUID id, String receipt) {
        UUID.fromString(owner);
        if (id == null || receipt == null || !receipt.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("MIXED_BINDING_INVALID");
        var value = new Binding(owner, receipt); var previous = bindings.putIfAbsent(id, value);
        if (previous != null && !previous.equals(value)) throw new SecurityException("MIXED_BINDING_CHANGED");
    }
    synchronized void standalone(Actor actor, UUID id) {
        var binding = bindings.get(id);
        if (binding != null) { actor.requireAccess(binding.owner()); throw new IllegalStateException("MIXED_RESOURCE_REVIEW_REQUIRED"); }
    }
    synchronized void require(Actor actor, UUID id, String receipt) {
        var binding = bindings.get(id);
        if (binding == null) throw new IllegalStateException("MIXED_BINDING_REQUIRED");
        actor.requireAccess(binding.owner());
        if (!binding.receipt().equals(receipt)) throw new SecurityException("MIXED_RECEIPT_CHANGED");
    }
    synchronized void ifOwned(Actor actor, UUID id, String receipt) {
        if (bindings.containsKey(id)) require(actor, id, receipt);
    }
}
