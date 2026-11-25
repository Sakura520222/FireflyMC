package firefly520.fireflymc.ai.processing.advanced;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StorageDynamicRecipesTest {
    @Test void matchingRequiresTheExactNativeClassVersionTypeAndSerializer() {
        for (var profile : StorageDynamicRecipes.profiles()) {
            assertEquals(profile, StorageDynamicRecipes.match(profile.className(), profile.version(), profile.buildVersion(), profile.typeId(), profile.serializer()).orElseThrow());
            assertTrue(StorageDynamicRecipes.match(profile.className() + "$UnknownSubclass", profile.version(), profile.buildVersion(), profile.typeId(), profile.serializer()).isEmpty());
            assertTrue(StorageDynamicRecipes.match(profile.className(), profile.version() + ".different", profile.buildVersion(), profile.typeId(), profile.serializer()).isEmpty());
            assertTrue(StorageDynamicRecipes.match(profile.className(), profile.version(), "different-build", profile.typeId(), profile.serializer()).isEmpty());
            assertTrue(StorageDynamicRecipes.match(profile.className(), profile.version(), profile.buildVersion(), "example:rule", profile.serializer()).isEmpty());
            assertTrue(StorageDynamicRecipes.match(profile.className(), profile.version(), profile.buildVersion(), profile.typeId(), "example:display_only").isEmpty());
        }
    }
    @Test void profilesNameRealStorageMechanismsAndDistinguishSharedConversionFromPureRecipes() {
        var profiles = StorageDynamicRecipes.profiles();
        assertEquals(5, profiles.stream().filter(profile -> profile.mod().equals("storagedrawers")).count());
        assertEquals(4, profiles.stream().filter(profile -> profile.mod().equals("sophisticatedbackpacks")).count());
        assertTrue(profiles.stream().anyMatch(profile -> profile.serializer().equals("sophisticatedstorage:barrel_material")));
        assertTrue(profiles.stream().anyMatch(profile -> profile.serializer().equals("sophisticatedcore:upgrade_clear")));
        assertFalse(profiles.stream().anyMatch(profile -> profile.className().endsWith("StorageDyeRecipeBase")));
        assertTrue(profiles.stream().filter(profile -> profile.className().endsWith("ShulkerBoxFromVanillaShapelessRecipe")).findFirst().orElseThrow().readsSharedStorage());
        assertFalse(profiles.stream().filter(profile -> profile.className().endsWith("BackpackUpgradeRecipe")).findFirst().orElseThrow().readsSharedStorage());
        assertEquals(profiles.size(), profiles.stream().map(StorageDynamicRecipes.Profile::className).distinct().count());
    }
    @Test void smithingRetainsItsThreePhysicalRolesAndGridRecipesUseNineConcreteSlots() {
        for (var profile : StorageDynamicRecipes.profiles()) {
            if (profile.className().endsWith("SmithingBackpackUpgradeRecipe")) {
                assertEquals(StorageDynamicRecipes.InputKind.SMITHING, profile.input()); assertEquals(3, profile.inputSize());
            } else { assertEquals(StorageDynamicRecipes.InputKind.GRID, profile.input()); assertEquals(9, profile.inputSize()); }
        }
    }
}
