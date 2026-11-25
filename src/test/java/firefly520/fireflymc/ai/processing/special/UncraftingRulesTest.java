package firefly520.fireflymc.ai.processing.special;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UncraftingRulesTest {
    @Test void damageUsesFourPartMinimumAndCeilingWithoutWaivingMaterials() {
        assertEquals(0, UncraftingRules.damagedParts(3, 0, 100));
        assertEquals(1, UncraftingRules.damagedParts(3, 1, 100));
        assertEquals(2, UncraftingRules.damagedParts(3, 50, 100));
        assertEquals(5, UncraftingRules.damagedParts(9, 50, 100));
    }
    @Test void blacklistInversionAndVariantCycleRetainNativeMeaning() {
        assertTrue(UncraftingRules.listAllows(false, false));
        assertFalse(UncraftingRules.listAllows(false, true));
        assertFalse(UncraftingRules.listAllows(true, false));
        assertTrue(UncraftingRules.listAllows(true, true));
        assertEquals(2, UncraftingRules.selectedVariant(-1, 3));
        assertEquals(1, UncraftingRules.selectedVariant(4, 3));
    }
    @Test void invalidOrUnboundedInputsCannotExpandTheMaterialMatrix() {
        assertThrows(IllegalArgumentException.class, () -> UncraftingRules.damagedParts(10, 1, 100));
        assertThrows(IllegalArgumentException.class, () -> UncraftingRules.damagedParts(3, 101, 100));
        assertThrows(IllegalArgumentException.class, () -> UncraftingRules.selectedVariant(0, 4097));
        assertThrows(IllegalArgumentException.class, () -> UncraftingRules.selectedVariant(4097, 3));
    }
}
