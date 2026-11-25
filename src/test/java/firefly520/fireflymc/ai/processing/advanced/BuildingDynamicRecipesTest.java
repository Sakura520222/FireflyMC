package firefly520.fireflymc.ai.processing.advanced;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuildingDynamicRecipesTest {
    @Test void onlyTheExactInstalledClassSerializerAndRecipeTypeMatchTheNativeContract() {
        var profile = BuildingDynamicRecipes.profilesAvailableForVerification().stream()
                .filter(value -> value.recipeClass().endsWith("SafeRecipe")).findFirst().orElseThrow();
        assertTrue(BuildingDynamicRecipes.contractMatches(profile, profile.version(), profile.recipeClass(), profile.serializer(), "minecraft:crafting"));
        assertFalse(BuildingDynamicRecipes.contractMatches(profile, "1.21.1-3.8.4", profile.recipeClass(), profile.serializer(), "minecraft:crafting"));
        assertFalse(BuildingDynamicRecipes.contractMatches(profile, profile.version(), profile.recipeClass() + "$Uninspected", profile.serializer(), "minecraft:crafting"));
        assertFalse(BuildingDynamicRecipes.contractMatches(profile, profile.version(), profile.recipeClass(), "supplementaries:display_only", "minecraft:crafting"));
        assertFalse(BuildingDynamicRecipes.contractMatches(profile, profile.version(), profile.recipeClass(), profile.serializer(), "minecraft:smelting"));
    }
    @Test void aRootJsonTypeOrWorldMapAllocatingRecipeIsNotAProductionProfile() {
        var classes = BuildingDynamicRecipes.profilesAvailableForVerification().stream().map(BuildingDynamicRecipes.Profile::recipeClass).toList();
        assertEquals(classes.size(), new HashSet<>(classes).size());
        assertFalse(classes.stream().anyMatch(name -> name.endsWith("WeatheredMapRecipe") || name.equals("neoforge:conditional")));
        assertTrue(BuildingDynamicRecipes.profilesAvailableForVerification().stream()
                .filter(profile -> profile.recipeClass().endsWith("AntiqueAtlasRecipe")).allMatch(BuildingDynamicRecipes.Profile::atlas));
    }
    @Test void smithingRunesKeepTheirNativeThreeInputRecipeTypeAndAuxiliaryShearsAreExplicit() {
        var profiles = BuildingDynamicRecipes.profilesAvailableForVerification();
        var rune = profiles.stream().filter(profile -> profile.recipeClass().endsWith("SmithingRuneRecipe")).findFirst().orElseThrow();
        assertEquals(BuildingDynamicRecipes.Kind.SMITHING, rune.kind());
        assertTrue(BuildingDynamicRecipes.contractMatches(rune, rune.version(), rune.recipeClass(), rune.serializer(), "minecraft:smithing"));
        assertFalse(BuildingDynamicRecipes.contractMatches(rune, rune.version(), rune.recipeClass(), rune.serializer(), "minecraft:crafting"));
        assertEquals(List.of("pepjebs.mapatlases.item.recipe.MapAtlasesCutExistingRecipe"), profiles.stream()
                .filter(BuildingDynamicRecipes.Profile::shears).map(BuildingDynamicRecipes.Profile::recipeClass).toList());
    }
}
