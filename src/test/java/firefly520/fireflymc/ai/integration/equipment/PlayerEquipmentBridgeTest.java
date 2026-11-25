package firefly520.fireflymc.ai.integration.equipment;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerEquipmentBridgeTest {
    @Test void sameBackpackBackendAcrossCuriosAndAccessoriesIsOneContentsGroup() {
        var groups = PlayerEquipmentBridge.groupAliases(List.of(
                new PlayerEquipmentBridge.Alias("contents/a", "owner/curios/back/0"),
                new PlayerEquipmentBridge.Alias("contents/a", "owner/accessories/back/0"),
                new PlayerEquipmentBridge.Alias("contents/b", "owner/curios/belt/0")));
        assertEquals(2, groups.size()); assertEquals(2, groups.getFirst().hostIds().size());
        assertFalse(groups.getFirst().contentsInspected());
    }
    @Test void duplicateHostAndUnboundedOrEscapingSlotIdentityAreRefused() {
        assertThrows(RuntimeException.class, () -> PlayerEquipmentBridge.groupAliases(List.of(
                new PlayerEquipmentBridge.Alias("contents/a", "same/slot"),
                new PlayerEquipmentBridge.Alias("contents/b", "same/slot"))));
        assertThrows(RuntimeException.class, () -> PlayerEquipmentBridge.boundedCount(256, 1));
        assertThrows(RuntimeException.class, () -> PlayerEquipmentBridge.hostLocator("curios", "back/other", 0));
        assertEquals("curios/back/0", PlayerEquipmentBridge.hostLocator("curios", "back", 0));
    }
}
