package firefly520.fireflymc.ai.processing;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class IngredientAllocatorTest {
    @Test void scarceSpecificIngredientIsNotConsumedByAnEarlierBroadTag() {
        var stocks = List.of(new IngredientAllocator.Stock(1, "oak", 1), new IngredientAllocator.Stock(1, "birch", 1));
        int[] answer = IngredientAllocator.allocate(List.of(new int[]{0, 1}, new int[]{0}), stocks,
                Map.of("oak", 1L, "birch", 1L), 1, 100);
        assertArrayEquals(new int[]{1, 0}, answer);
    }
    @Test void compressedAliasesCannotFundTwoIngredientsFromTheSameUnderlyingUnits() {
        var stocks = List.of(new IngredientAllocator.Stock(9, "iron", 9), new IngredientAllocator.Stock(1, "iron", 81));
        assertThrows(IllegalStateException.class, () -> IngredientAllocator.allocate(List.of(new int[]{0}, new int[]{1}),
                stocks, Map.of("iron", 81L), 1, 100));
    }
    @Test void unsatisfiableRepeatedTagsTerminateWithinTheSearchBudget() {
        var stocks = List.of(new IngredientAllocator.Stock(10, "one", 1), new IngredientAllocator.Stock(10, "two", 1));
        assertThrows(IllegalStateException.class, () -> IngredientAllocator.allocate(
                Collections.nCopies(9, new int[]{0, 1}), stocks, Map.of("one", 10L, "two", 10L), 3, 10));
    }
    @Test void aBatchCanConsumeIdenticalMaterialsSplitAcrossTwoInventorySlots() {
        var stocks = List.of(new IngredientAllocator.Stock(8, "slot-a", 1), new IngredientAllocator.Stock(8, "slot-b", 1));
        var result = IngredientAllocator.allocateBatch(List.of(new int[]{0, 1}), stocks, List.of("same-components", "same-components"),
                Map.of("slot-a", 8L, "slot-b", 8L), 16, 1000);
        assertArrayEquals(new int[]{8, 8}, result[0]);
    }
    @Test void aSinglePrototypeCannotSilentlyMergeDifferentEquipmentComponents() {
        var stocks = List.of(new IngredientAllocator.Stock(1, "slot-a", 1), new IngredientAllocator.Stock(1, "slot-b", 1));
        assertThrows(IllegalStateException.class, () -> IngredientAllocator.allocateBatch(List.of(new int[]{0, 1}), stocks,
                List.of("enchanted", "unenchanted"), Map.of("slot-a", 1L, "slot-b", 1L), 2, 1000));
    }
    @Test void reusableMaterialHasOnePhysicalReservationWhileConsumableInputsScale() {
        var stocks = List.of(new IngredientAllocator.Stock(16, "wheat", 1), new IngredientAllocator.Stock(1, "water", 1));
        var allocated = IngredientAllocator.allocateBatchCounts(List.of(new int[]{0}, new int[]{1}), stocks,
                List.of("wheat-components", "water-components"), Map.of("wheat", 16L, "water", 1L), new int[]{16, 1}, 1000);
        assertArrayEquals(new int[]{16, 0}, allocated[0]);
        assertArrayEquals(new int[]{0, 1}, allocated[1]);
    }
}
