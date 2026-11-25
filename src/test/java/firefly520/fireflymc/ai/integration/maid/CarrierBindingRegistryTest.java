package firefly520.fireflymc.ai.integration.maid;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CarrierBindingRegistryTest {
    private final UUID owner = UUID.randomUUID(), maid = UUID.randomUUID();
    private CarrierBindingRegistry.Observation state(UUID owner, String dimension, String task, boolean alive,
                                                    boolean idle, boolean combat, boolean seated) {
        return new CarrierBindingRegistry.Observation(owner, maid, dimension, task, alive, idle, combat, seated);
    }
    private CarrierBindingRegistry.Observation idle() {
        return state(owner, "minecraft:overworld", "idle", true, true, false, false);
    }
    @Test void tasksCannotShareCarrierLeaseAndAnotherOwnerCannotReleaseIt() {
        var registry = new CarrierBindingRegistry(); registry.bind(owner, idle());
        UUID task = UUID.randomUUID(); var lease = registry.acquire(owner, task, idle());
        assertEquals(lease, registry.acquire(owner, task, idle()));
        assertThrows(RuntimeException.class, () -> registry.acquire(owner, UUID.randomUUID(), idle()));
        assertFalse(registry.release(UUID.randomUUID(), lease));
        assertFalse(lease.nativeAiExclusivelyLocked()); assertFalse(lease.worldActionAuthorized());
        assertTrue(registry.release(owner, lease));
    }
    @Test void nativeCombatAndSittingInterruptWithoutTakingOverItsTask() {
        var registry = new CarrierBindingRegistry(); registry.bind(owner, idle());
        var lease = registry.acquire(owner, UUID.randomUUID(), idle());
        assertEquals("NATIVE_MAID_INTERRUPTED", registry.validateLease(owner, lease,
                state(owner, "minecraft:overworld", "idle", true, true, true, false)));
        assertFalse(registry.release(owner, lease));
        assertNotNull(registry.binding(owner));
        assertThrows(RuntimeException.class, () -> registry.acquire(owner, UUID.randomUUID(),
                state(owner, "minecraft:overworld", "idle", true, true, false, true)));
    }
    @Test void ownerTaskDimensionAndDeathChangesInvalidateBindingAndLease() {
        var changes = java.util.List.of(
                state(UUID.randomUUID(), "minecraft:overworld", "idle", true, true, false, false),
                state(owner, "minecraft:the_nether", "idle", true, true, false, false),
                state(owner, "minecraft:overworld", "farm", true, false, false, false),
                state(owner, "minecraft:overworld", "idle", false, true, false, false));
        for (var changed : changes) {
            var registry = new CarrierBindingRegistry(); registry.bind(owner, idle());
            var lease = registry.acquire(owner, UUID.randomUUID(), idle());
            assertNotNull(registry.validateLease(owner, lease, changed));
            assertNull(registry.binding(owner)); assertFalse(registry.release(owner, lease));
        }
    }
    @Test void approvedModeLeaseCanStandASeatedIdleMaidButDoesNotAuthorizeInventoryOrWorldWork() {
        var sitting = state(owner, "minecraft:overworld", "idle", true, true, false, true);
        var registry = new CarrierBindingRegistry(); registry.bind(owner, sitting);
        assertThrows(RuntimeException.class, () -> registry.acquire(owner, UUID.randomUUID(), sitting));
        var control = registry.acquireControl(owner, UUID.randomUUID(), sitting);
        assertFalse(control.worldActionAuthorized()); assertFalse(control.nativeAiExclusivelyLocked());
        assertThrows(RuntimeException.class, () -> registry.acquire(owner, UUID.randomUUID(), idle()));
        assertTrue(registry.release(owner, control));
        assertThrows(RuntimeException.class, () -> registry.acquireControl(owner, UUID.randomUUID(),
                state(owner, "minecraft:overworld", "idle", true, true, true, false)));
    }
}
