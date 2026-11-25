package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.storage.AiJson;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConstructionPlanTest {
    private final BlueprintDocument.Position pos = new BlueprintDocument.Position(1, 64, 1);
    private final BlueprintDocument.State air = new BlueprintDocument.State("minecraft:air", Map.of());
    private final BlueprintDocument.State stone = new BlueprintDocument.State("minecraft:stone", Map.of());
    private final MaterialStack input = new MaterialStack("{\"id\":\"minecraft:stone\"}", 2);
    private final MaterialLedger.Stock source = new MaterialLedger.Stock("player", 1, "slot-0", "slot-0", 0, input, 1, 2, 2);
    private ConstructionPlan plan(List<BuildJournal.Cell> world, List<ConstructionPlan.Placement> steps, List<MaterialLedger.Need> needs) {
        return new ConstructionPlan(1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, AiJson.digest("blueprint"), AiJson.digest("review"), "minecraft:overworld", 1,
                world, steps, List.of(source), needs, AiJson.digest(AiJson.canonical(world)), ConstructionPlan.materialDigest(List.of(source), needs), "");
    }
    private ConstructionPlan.Placement placement(BlueprintDocument.Position position, BlueprintDocument.State before) {
        return new ConstructionPlan.Placement(UUID.randomUUID(), position, List.of(new BuildJournal.Cell(position, before, stone)),
                "UP", .5, .5, .5, 0, 0, input.withCount(1));
    }
    @Test void fixedWorldAndMaterialEnvelopeHasAStableHashAcrossPersistence() {
        var plan = plan(List.of(new BuildJournal.Cell(pos, air, stone)), List.of(placement(pos, air)), List.of(new MaterialLedger.Need(source.key(), 1))).signed();
        assertTrue(plan.intact()); assertEquals(plan, AiJson.GSON.fromJson(AiJson.GSON.toJson(plan), ConstructionPlan.class));
        var altered = AiJson.GSON.toJsonTree(plan).getAsJsonObject(); altered.addProperty("reviewStamp", AiJson.digest("edited-preview"));
        assertFalse(AiJson.GSON.fromJson(altered, ConstructionPlan.class).intact());
    }
    @Test void duplicateResourceClaimAndUncoveredBlueprintTargetCannotBeApproved() {
        var world = List.of(new BuildJournal.Cell(pos, air, stone));
        assertEquals("CONSTRUCTION_DUPLICATE_NEED", assertThrows(IllegalArgumentException.class, () -> plan(world,
                List.of(placement(pos, air)), List.of(new MaterialLedger.Need(source.key(), 1), new MaterialLedger.Need(source.key(), 1)))).getMessage());
        var second = new BlueprintDocument.Position(2, 64, 1);
        assertEquals("CONSTRUCTION_TARGET_NOT_COVERED", assertThrows(IllegalArgumentException.class, () -> plan(
                List.of(new BuildJournal.Cell(pos, air, stone), new BuildJournal.Cell(second, air, stone)), List.of(placement(pos, air)),
                List.of(new MaterialLedger.Need(source.key(), 1)))).getMessage());
    }
    @Test void aLaterStepCannotPretendAnEarlierWorldChangeNeverHappened() {
        assertEquals("CONSTRUCTION_STEP_ORDER_CHANGED", assertThrows(IllegalArgumentException.class, () -> plan(
                List.of(new BuildJournal.Cell(pos, air, stone)), List.of(placement(pos, air), placement(pos, air)),
                List.of(new MaterialLedger.Need(source.key(), 2)))).getMessage());
    }
}
