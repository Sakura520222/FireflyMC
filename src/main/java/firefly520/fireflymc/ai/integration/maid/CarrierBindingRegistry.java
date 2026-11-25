package firefly520.fireflymc.ai.integration.maid;

import static firefly520.fireflymc.ai.integration.equipment.ReadOnlyNativeAccess.require;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Main-thread session leases between FireflyMC tasks; never a lock over another mod's native AI. */
final class CarrierBindingRegistry {
    record Observation(UUID owner, UUID maid, String dimension, String taskId,
                       boolean alive, boolean idle, boolean combat, boolean seated) {}
    private final Map<UUID, MaidCarrierBridge.Binding> bindings = new HashMap<>();
    private final Map<UUID, MaidCarrierBridge.Lease> leases = new HashMap<>();
    private long revision;

    MaidCarrierBridge.Binding bind(UUID owner, Observation observation) {
        requireOwner(owner, observation);
        require(!leases.containsKey(owner), "CARRIER_OPERATION_BUSY");
        require(bindings.size() < 1024 || bindings.containsKey(owner), "CARRIER_BINDING_LIMIT");
        var binding = new MaidCarrierBridge.Binding(owner, observation.maid(), observation.dimension(),
                observation.taskId(), ++revision);
        bindings.put(owner, binding);
        return binding;
    }

    MaidCarrierBridge.Binding binding(UUID owner) { return bindings.get(owner); }

    String validate(UUID owner, Observation observation) {
        var binding = bindings.get(owner);
        if (binding == null) return "CARRIER_NOT_BOUND";
        String reason = changeReason(owner, binding, observation);
        if (reason != null) invalidate(owner);
        return reason;
    }

    MaidCarrierBridge.Lease acquire(UUID owner, UUID operationId, Observation observation) {
        return acquire(owner, operationId, observation, false);
    }

    MaidCarrierBridge.Lease acquireControl(UUID owner, UUID operationId, Observation observation) {
        return acquire(owner, operationId, observation, true);
    }

    private MaidCarrierBridge.Lease acquire(UUID owner, UUID operationId, Observation observation, boolean permitSeated) {
        Objects.requireNonNull(operationId);
        String changed = validate(owner, observation);
        require(changed == null, changed);
        require(observation.idle() && !observation.combat() && (permitSeated || !observation.seated()), "NATIVE_MAID_BUSY");
        var current = leases.get(owner);
        if (current != null) {
            require(current.operationId().equals(operationId), "CARRIER_OPERATION_BUSY");
            return current;
        }
        var binding = bindings.get(owner);
        var lease = new MaidCarrierBridge.Lease(UUID.randomUUID(), owner, binding.maidId(),
                operationId, binding.revision());
        leases.put(owner, lease);
        return lease;
    }

    String validateLease(UUID owner, MaidCarrierBridge.Lease supplied, Observation observation) {
        var current = leases.get(owner);
        if (supplied == null || !supplied.equals(current) || !owner.equals(supplied.owner())) return "CARRIER_LEASE_MISMATCH";
        String changed = validate(owner, observation);
        if (changed != null) return changed;
        if (!observation.idle() || observation.combat() || observation.seated()) {
            leases.remove(owner);
            return "NATIVE_MAID_INTERRUPTED";
        }
        return null;
    }

    boolean release(UUID owner, MaidCarrierBridge.Lease supplied) {
        return supplied != null && owner.equals(supplied.owner()) && leases.remove(owner, supplied);
    }

    void invalidate(UUID owner) {
        leases.remove(owner);
        bindings.remove(owner);
    }

    private static void requireOwner(UUID owner, Observation observation) {
        require(owner != null && observation != null && owner.equals(observation.owner()), "MAID_OWNER_MISMATCH");
        require(observation.alive(), "MAID_UNAVAILABLE");
        require(observation.maid() != null && observation.dimension() != null && observation.taskId() != null,
                "MAID_OBSERVATION_INVALID");
    }

    private static String changeReason(UUID owner, MaidCarrierBridge.Binding binding, Observation observation) {
        if (observation == null || !observation.alive()) return "MAID_UNAVAILABLE";
        if (!owner.equals(observation.owner())) return "MAID_OWNER_CHANGED";
        if (!binding.maidId().equals(observation.maid())) return "MAID_ID_CHANGED";
        if (!binding.dimension().equals(observation.dimension())) return "MAID_DIMENSION_CHANGED";
        if (!binding.nativeTaskId().equals(observation.taskId())) return "MAID_TASK_CHANGED";
        return null;
    }
}
