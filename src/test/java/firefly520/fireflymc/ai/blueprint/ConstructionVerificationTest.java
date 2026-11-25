package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.blueprint.ConstructionVerification.*;
import firefly520.fireflymc.ai.storage.AiJson;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConstructionVerificationTest {
    private static final BlueprintDocument.Position POSITION = new BlueprintDocument.Position(1, 70, 1);
    private ConstructionVerification report(int total, int verified, int unknown, List<Function> functions, boolean truncated) {
        return new ConstructionVerification(1, UUID.randomUUID(), AiJson.digest("fixed-plan"), "minecraft:overworld", 1,
                1234, 15, total, verified, unknown, List.of(), functions, truncated);
    }
    @Test void completeStructureDoesNotClaimActivationWhenNativeFuelOrInputIsMissing() {
        var row = new Function(POSITION, "minecraft:furnace", "NATIVE_THERMAL_MACHINE", FunctionalState.WAITING_ACTIVATION,
                List.of("NATIVE_INPUT_REQUIRED", "NATIVE_FUEL_REQUIRED"), Map.of("costPolicy", "NATIVE_WORLD_MACHINE_COSTS"));
        var report = report(1, 1, 0, List.of(row), false);
        assertTrue(report.structureComplete()); assertEquals("STRUCTURE_COMPLETE", report.structureState());
        assertEquals(FunctionalState.WAITING_ACTIVATION, report.functionality()); assertTrue(report.summary().contains("功能待激活"));
        assertTrue(report.summary().contains("未自动补给或豁免费用"));
    }
    @Test void missingWorldCellsOrTruncatedChecksCannotBecomeFunctionalReady() {
        var row = new Function(POSITION, "minecraft:crafting_table", "NATIVE_MANUAL_WORKSTATION", FunctionalState.READY, List.of(), Map.of());
        var partial = report(2, 1, 0, List.of(row), false);
        assertFalse(partial.structureComplete()); assertEquals("PARTIALLY_BUILT", partial.structureState());
        assertEquals(FunctionalState.UNVERIFIED, partial.functionality());
        assertEquals("STRUCTURE_UNVERIFIED", report(2, 1, 1, List.of(row), false).structureState());
        assertEquals(FunctionalState.UNVERIFIED, report(1, 1, 0, List.of(row), true).functionality());
    }
    @Test void replacedOrUnboundMachineNeverInheritsAReadyClaimAndReportRemainsVersioned() {
        var row = new Function(POSITION, "minecraft:furnace", "NATIVE_MACHINE", FunctionalState.UNVERIFIED,
                List.of("NO_CURRENT_NATIVE_IDENTITY_FROM_THIS_ACCEPTED_PLACEMENT_INVENTORY_NOT_READ"), Map.of("publicLitState", "false"));
        var before = report(1, 1, 0, List.of(row), false);
        var restored = AiJson.GSON.fromJson(AiJson.GSON.toJson(before), ConstructionVerification.class);
        assertEquals(before, restored); assertEquals(before.digest(), restored.digest());
        assertEquals(FunctionalState.UNVERIFIED, restored.functionality());
        assertTrue(restored.summary().contains("未宣称机器可运行"));
    }
}
