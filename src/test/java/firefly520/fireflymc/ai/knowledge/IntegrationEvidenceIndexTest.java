package firefly520.fireflymc.ai.knowledge;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationEvidenceIndexTest {
    @Test void activeRecipeCountsDoNotTurnWorldRulesOrDynamicSerializersIntoExecutableRecipes() {
        var index = new IntegrationEvidenceIndex();
        index.accept(row("aether:enchanted", "aether:enchanting", "SPECIAL_MATERIALS_ONLY_V1", true));
        index.accept(row("aether:placement_ban", "aether:placement_ban", "NO_EXECUTION_ADAPTER", false));
        index.accept(row("aether:dynamic", "minecraft:crafting", "NO_EXECUTION_ADAPTER", false));
        var view = index.view("aether"); assertEquals(3, view.get("indexedRecipeCount").getAsInt());
        assertEquals(1, view.get("executableRecipeCount").getAsInt());
        assertEquals(1, view.getAsJsonArray("mechanisms").asList().stream().filter(v -> v.getAsJsonObject().get("state").getAsString().equals("RUNTIME_READY")).count());
        assertTrue(view.getAsJsonArray("mechanisms").asList().stream().noneMatch(v -> v.getAsJsonObject().get("behaviorVerifiedHere").getAsBoolean()));
        assertEquals(0, index.view("absent_mod").get("indexedRecipeCount").getAsInt());
    }
    @Test void duplicateIndexReadsDoNotInflateCountsAndReloadRemovesOldAdapterClaims() {
        var index = new IntegrationEvidenceIndex(); var row = row("demo:one", "minecraft:crafting", "VANILLA_MATERIALS_ONLY_V1", true);
        index.accept(row); index.accept(row); assertEquals(1, index.view("demo").get("indexedRecipeCount").getAsInt());
        index.clear(); index.accept(row("demo:one", "minecraft:crafting", "NO_EXECUTION_ADAPTER", false));
        assertEquals(0, index.view("demo").get("executableRecipeCount").getAsInt());
        assertFalse(index.view("demo").toString().contains("VANILLA_MATERIALS_ONLY_V1"));
    }
    @Test void responseLimitsPreserveActualTotalsWithoutCallingTruncationComplete() {
        var index = new IntegrationEvidenceIndex();
        for (int i = 0; i < 20; i++) index.accept(row("demo:r" + i, "demo:t" + i, "NO_EXECUTION_ADAPTER", false));
        var result = index.view("demo"); assertEquals(20, result.get("indexedRecipeCount").getAsInt());
        assertEquals(12, result.getAsJsonArray("mechanisms").size()); assertTrue(result.get("mechanismsTruncated").getAsBoolean());
    }
    private JsonObject row(String id, String type, String adapter, boolean available) {
        var row = new JsonObject(); row.addProperty("recipeId", id); row.addProperty("recipeType", type); row.addProperty("serializerId", type);
        row.addProperty("executionAdapter", adapter); row.addProperty("executionAvailable", available); return row;
    }
}
