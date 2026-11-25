package firefly520.fireflymc.ai.processing.advanced;

import firefly520.fireflymc.ai.material.MaterialLedger;
import firefly520.fireflymc.ai.material.MaterialStack;
import firefly520.fireflymc.ai.storage.AiJson;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdvancedProcessingPlanTest {
    private static final MaterialStack INPUT = new MaterialStack("{\"id\":\"minecraft:coal\",\"count\":1}", 1);
    private static final MaterialStack OUTPUT = new MaterialStack("{\"id\":\"minecraft:diamond\",\"count\":1}", 1);
    private static MaterialLedger.Stock stock() { return new MaterialLedger.Stock("player", 1, "inventory", "physical/slot0", 0, INPUT, 1, 1, 1); }
    private static AdvancedProcessingPlan plan(String owner, int consumed, List<MaterialLedger.Need> needs) {
        return new AdvancedProcessingPlan(1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), owner, UUID.randomUUID(),
                UUID.randomUUID(), 0, "minecraft:overworld", 1, "test:infusion", "", 0, 1, "test-adapter", List.of(INPUT), List.of(consumed),
                List.of(OUTPUT), Map.of("policy", "MATERIALS_ONLY"), List.of(), List.of(), false, List.of(stock()), needs, "");
    }
    @Test void persistedHashBindsOwnerContextAndConcreteSourceCounts() {
        var plan = plan(UUID.randomUUID().toString(), 1, List.of(new MaterialLedger.Need(stock().key(), 1))).signed();
        var decoded = AiJson.GSON.fromJson(AiJson.GSON.toJson(plan), AdvancedProcessingPlan.class);
        assertEquals(plan, decoded); assertTrue(decoded.intact());
        var changed = AiJson.GSON.toJsonTree(plan).getAsJsonObject(); changed.addProperty("owner", UUID.randomUUID().toString());
        assertFalse(AiJson.GSON.fromJson(changed, AdvancedProcessingPlan.class).intact());
        changed = AiJson.GSON.toJsonTree(plan).getAsJsonObject(); changed.addProperty("recipeGeneration", 2);
        assertFalse(AiJson.GSON.fromJson(changed, AdvancedProcessingPlan.class).intact());
    }
    @Test void materialAndRoleDisagreementOrDuplicateClaimsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> plan(UUID.randomUUID().toString(), 0, List.of(new MaterialLedger.Need(stock().key(), 1))));
        assertThrows(IllegalArgumentException.class, () -> plan(UUID.randomUUID().toString(), 1, List.of(new MaterialLedger.Need(stock().key(), 2))));
        assertThrows(IllegalArgumentException.class, () -> plan(UUID.randomUUID().toString(), 1,
                List.of(new MaterialLedger.Need(stock().key(), 1), new MaterialLedger.Need(stock().key(), 1))));
    }
}
