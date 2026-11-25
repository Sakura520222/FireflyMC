package firefly520.fireflymc.ai.material;

import firefly520.fireflymc.ai.network.AiProtocol;
import firefly520.fireflymc.ai.tools.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FluidInventoryToolsTest {
    @Test void privateComponentsStayOnServerWhileFluidIdsAmountsAndPageCursorAreReturned() {
        var fluid = new FluidAmount("{\"amount\":1,\"components\":{\"minecraft:custom_data\":{\"private\":\"do-not-send\"}},\"id\":\"minecraft:water\"}", 1234);
        var source = new FluidInventoryService.Source(UUID.randomUUID(), 1, "same-native-backend", firefly520.fireflymc.ai.storage.AiJson.digest("authorization"),
                new FluidTankSnapshot(List.of(new FluidTankSnapshot.Tank(2000, fluid), new FluidTankSnapshot.Tank(2000, fluid))));
        var snapshot = new FluidInventoryService.Snapshot(UUID.randomUUID(), UUID.randomUUID().toString(), 1000, List.of(source), List.of());
        var data = FluidInventoryTools.view(snapshot, 0); String text = data.toString();
        assertFalse(text.contains("do-not-send")); assertFalse(text.contains("unitJson")); assertFalse(text.contains(snapshot.owner()));
        assertEquals(1, data.getAsJsonArray("fluids").size());
        assertEquals(2468, data.getAsJsonArray("fluids").get(0).getAsJsonObject().get("physicalMb").getAsInt());
        assertEquals(1, data.get("nextOffset").getAsInt());
        assertEquals(0, FluidInventoryTools.view(snapshot, 1).getAsJsonArray("fluids").size());
        assertThrows(IllegalArgumentException.class, () -> FluidInventoryTools.view(snapshot, -1));
        assertThrows(IllegalArgumentException.class, () -> FluidInventoryTools.view(snapshot, 2));
    }
    @Test void onlyReadToolIsRegisteredAndEveryNewUiActionRequiresServerAdvertisement() {
        var registry = new ToolRegistry(); FluidInventoryTools.register(registry, null);
        assertEquals(1, registry.all().size()); var descriptor = registry.get("fluid_inventory").descriptor();
        assertEquals(AgentTool.Risk.READ_ONLY, descriptor.risk()); assertTrue(descriptor.needsOnlinePlayer());
        assertEquals(AgentTool.ExecutionThread.IO, descriptor.executionThread());
        assertEquals("materials.own", descriptor.requiredCapability()); assertEquals(0, descriptor.permissionLevel());
        for (var action : List.of(AiProtocol.Action.FLUIDS_VIEW, AiProtocol.Action.FLUIDS_SCAN, AiProtocol.Action.FLUID_SOURCE_PREVIEW,
                AiProtocol.Action.FLUID_SOURCE_CONFIRM, AiProtocol.Action.FLUID_SOURCE_REVOKE)) {
            assertTrue(AiProtocol.requiresAdvertisement(action)); assertTrue(AiProtocol.implementedActions().contains(action.name()));
        }
    }
}
