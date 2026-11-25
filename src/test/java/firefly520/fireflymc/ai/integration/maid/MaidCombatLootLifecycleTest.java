package firefly520.fireflymc.ai.integration.maid;

import firefly520.fireflymc.ai.material.MaterialStack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaidCombatLootLifecycleTest {
    private static final MaterialStack FLESH = new MaterialStack("{\"id\":\"minecraft:rotten_flesh\",\"count\":1}", 4);
    @Test void nativeItemMergeMustConserveTheActualMaterialAndCount() {
        assertTrue(MaidCombatLootLifecycle.validMerge(FLESH, FLESH.withCount(3), FLESH.withCount(7), MaterialStack.EMPTY));
        assertTrue(MaidCombatLootLifecycle.validMerge(FLESH, FLESH.withCount(3), FLESH.withCount(5), FLESH.withCount(2)));
        assertFalse(MaidCombatLootLifecycle.validMerge(FLESH, FLESH.withCount(3), FLESH.withCount(8), MaterialStack.EMPTY));
        assertFalse(MaidCombatLootLifecycle.validMerge(FLESH, FLESH.withCount(3), FLESH.withCount(6), MaterialStack.EMPTY));
        var other = new MaterialStack("{\"id\":\"minecraft:diamond\",\"count\":1}", 3);
        assertFalse(MaidCombatLootLifecycle.validMerge(FLESH, other, FLESH.withCount(7), MaterialStack.EMPTY));
        assertFalse(MaidCombatLootLifecycle.validMerge(FLESH, FLESH.withCount(3), other.withCount(7), MaterialStack.EMPTY));
    }
    @Test void partialNativePickupMayOnlyReduceTheOriginalStack() {
        assertTrue(MaidCombatLootLifecycle.validRemainder(FLESH, FLESH.withCount(1)));
        assertTrue(MaidCombatLootLifecycle.validRemainder(FLESH, MaterialStack.EMPTY));
        assertFalse(MaidCombatLootLifecycle.validRemainder(FLESH, FLESH.withCount(5)));
        assertFalse(MaidCombatLootLifecycle.validRemainder(MaterialStack.EMPTY, FLESH));
        assertFalse(MaidCombatLootLifecycle.validRemainder(FLESH, new MaterialStack("{\"id\":\"minecraft:diamond\",\"count\":1}", 1)));
    }
    @Test void nativeWorkRequiresALiveAcceptedControlAndHonorsEveryStopReason() {
        var control = new firefly520.fireflymc.ai.task.TaskControl(10000);
        assertDoesNotThrow(() -> MaidCombatService.checkWorkControl(control));
        for (String reason : java.util.List.of("TASK_SLICE_FINISHED", "CANCELLED", "USER_PAUSED", "AI_STOPPING", "PLAYER_OFFLINE", "BUDGET_EXHAUSTED")) {
            var stopped = new firefly520.fireflymc.ai.task.TaskControl(10000); stopped.stop(reason);
            var failure = assertThrows(firefly520.fireflymc.ai.task.TaskControl.Stopped.class, () -> MaidCombatService.checkWorkControl(stopped));
            assertEquals(reason, failure.getMessage());
        }
    }
}
