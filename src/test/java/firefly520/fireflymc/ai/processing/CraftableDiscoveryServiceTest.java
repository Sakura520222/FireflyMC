package firefly520.fireflymc.ai.processing;

import com.google.gson.JsonParser;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CraftableDiscoveryServiceTest {
    @Test void oneCallNeedsOnlyAnOptionalSearchTermAndHasNoModelManagedCursor() {
        assertEquals("", CraftableDiscoveryService.request(new com.google.gson.JsonObject()));
        assertEquals("minecraft:oak", CraftableDiscoveryService.request(JsonParser.parseString("{\"query\":\" Minecraft:Oak \"}").getAsJsonObject()));
        for (String oldCursor : java.util.List.of("{\"offset\":512}", "{\"recipeGeneration\":7}", "{\"snapshotId\":\"" + UUID.randomUUID() + "\"}"))
            assertThrows(IllegalArgumentException.class, () -> CraftableDiscoveryService.request(JsonParser.parseString(oldCursor).getAsJsonObject()));
    }

    @Test void obsoletePaginationArgumentsCannotReintroduceModelManagedScanning() {
        assertThrows(IllegalArgumentException.class, () -> CraftableDiscoveryService.request(
                JsonParser.parseString("{\"offset\":0.9}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> CraftableDiscoveryService.request(
                JsonParser.parseString("{\"offset\":4294967296}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> CraftableDiscoveryService.request(
                JsonParser.parseString("{\"offset\":20001}").getAsJsonObject()));
    }

    @Test void completeDisplayReportIncludesEveryItemWithoutTheOldTwelveEntryLimit() {
        var entries = new com.google.gson.JsonArray();
        for (int i = 0; i < 80; i++) {
            var entry = new com.google.gson.JsonObject(); entry.addProperty("displayName", "物品" + i);
            entry.addProperty("outputCount", 4); entry.add("inputs", new com.google.gson.JsonArray()); entries.add(entry);
        }
        String report = CraftableDiscoveryService.report(entries);
        assertTrue(report.contains("物品0")); assertTrue(report.contains("物品79"));
        assertFalse(report.contains("翻页")); assertFalse(report.contains("快照")); assertFalse(report.contains("仅本人可见"));
    }

    @Test void adapterErrorBodiesAndPrivateComponentDetailsDoNotBecomeDiscoveryReasons() {
        assertEquals("RECIPE_NOT_UNLOCKED", CraftableDiscoveryService.failureCode(new SecurityException("RECIPE_NOT_UNLOCKED")));
        assertEquals("ADAPTER_PREVIEW_REJECTED_OR_DYNAMIC_CONTEXT_UNSUPPORTED",
                CraftableDiscoveryService.failureCode(new RuntimeException("private components or provider body")));
    }
}
