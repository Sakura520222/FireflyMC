package firefly520.fireflymc.ai.blueprint;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BlueprintDocumentTest {
    private static final BlueprintDocument.Position ZERO = new BlueprintDocument.Position(0, 0, 0);
    private static final BlueprintDocument.State STONE = new BlueprintDocument.State("minecraft:stone", Map.of());

    @Test void immutableContentKeepsExactIdsPropertiesAndRelativeCoordinates() {
        Map<String, String> properties = new HashMap<>(Map.of("axis", "x"));
        var state = new BlueprintDocument.State("minecraft:oak_log", properties);
        var blocks = new ArrayList<>(List.of(new BlueprintDocument.Block(new BlueprintDocument.Position(-2, 1, 3), state)));
        var document = document(new BlueprintDocument.Position(100, 64, 200), blocks);
        properties.put("axis", "z");
        blocks.clear();
        assertEquals("x", document.blocks().getFirst().state().properties().get("axis"));
        assertEquals(new BlueprintDocument.Position(98, 65, 203), document.origin().add(document.blocks().getFirst().position()));
        assertThrows(UnsupportedOperationException.class, () -> document.blocks().clear());
    }

    @Test void duplicateTargetsAndOutOfWorldOrOverflowedCoordinatesAreRejected() {
        var block = new BlueprintDocument.Block(ZERO, STONE);
        assertThrows(IllegalArgumentException.class, () -> document(ZERO, List.of(block, block)));
        assertThrows(IllegalArgumentException.class, () -> document(new BlueprintDocument.Position(30_000_000, 0, 0),
                List.of(new BlueprintDocument.Block(new BlueprintDocument.Position(1, 0, 0), STONE))));
        assertThrows(IllegalArgumentException.class,
                () -> new BlueprintDocument.Position(Integer.MAX_VALUE, 0, 0).add(new BlueprintDocument.Position(1, 0, 0)));
    }

    @Test void countAndAggregateDataBudgetsRejectBeforeAnUnboundedCopy() {
        var block = new BlueprintDocument.Block(ZERO, STONE);
        assertThrows(IllegalArgumentException.class,
                () -> document(ZERO, Collections.nCopies(BlueprintDocument.MAX_BLOCKS + 1, block)));
        Map<String, String> verbose = new HashMap<>();
        for (int i = 0; i < 32; i++) verbose.put("p" + i, "v".repeat(128));
        var state = new BlueprintDocument.State("minecraft:stone", verbose);
        var many = new ArrayList<BlueprintDocument.Block>();
        for (int i = 0; i < 1000; i++) many.add(new BlueprintDocument.Block(new BlueprintDocument.Position(i, 0, 0), state));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> document(ZERO, many));
        assertEquals("BLUEPRINT_DATA_LIMIT", failure.getMessage());
    }

    @Test void malformedNamesIdsAndPropertiesNeverBecomeDefaultStates() {
        assertThrows(IllegalArgumentException.class, () -> new BlueprintDocument.State("MissingNamespace", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new BlueprintDocument.State("minecraft:stone", Map.of("x", "a\nb")));
        assertThrows(IllegalArgumentException.class, () -> new BlueprintDocument(1, "bad\nname", "CLIENT_FORGEMATICA",
                "0.4.2", "", ZERO, List.of(new BlueprintDocument.Block(ZERO, STONE))));
        assertThrows(IllegalArgumentException.class, () -> new BlueprintDocument(2, "test", "SERVER_PLAN",
                "1", "", ZERO, List.of(new BlueprintDocument.Block(ZERO, STONE))));
    }

    private static BlueprintDocument document(BlueprintDocument.Position origin, List<BlueprintDocument.Block> blocks) {
        return new BlueprintDocument(1, "test", "CLIENT_FORGEMATICA", "0.4.2+mc1.21.1", "", origin, blocks);
    }
}
