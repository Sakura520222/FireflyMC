package firefly520.fireflymc.ai.material;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StorageSourceIntrospectorTest {
    private static final String CORE = "net.p3pp3rf1y.sophisticatedcore.";

    @Test void compressedBlockAndIngotSpendTheSameFiniteNuggetPool() {
        // Shipped public ratios are 1/9/81, while the real pool costs are 81/9/1.
        long block = StorageSourceIntrospector.fractionalUnits(81, 1);
        long ingot = StorageSourceIntrospector.fractionalUnits(81, 9);
        long nugget = StorageSourceIntrospector.fractionalUnits(81, 81);
        long available = 90;
        assertEquals(81, block);
        assertEquals(9, ingot);
        assertEquals(1, nugget);
        assertEquals(available, block + ingot);
        assertTrue(block + ingot + nugget > available,
                "A third view cannot supply another item once the shared pool is reserved");
    }

    @Test void unknownOrChangedFractionalRatiosCannotBeRoundedIntoAvailableStock() {
        for (int[] rates : List.of(new int[] {81, 0}, new int[] {0, 1},
                new int[] {81, 10}, new int[] {9, 81}, new int[] {-1, 1})) {
            var failure = assertThrows(StorageSourceIntrospector.ProbeFailure.class,
                    () -> StorageSourceIntrospector.fractionalUnits(rates[0], rates[1]));
            assertEquals("INVALID_FRACTIONAL_CONVERSION", failure.getMessage());
        }
    }

    @Test void virtualInputIsExcludedEvenWhenNativeAccessibleOrderIsNotLocalOrder() {
        var mapping = StorageSourceIntrospector.drawerMappings(4, 3, new int[] {2, 0, 1});
        assertEquals(List.of(1, 2, 3), mapping.stream().map(StorageSourceIntrospector.DrawerMapping::handlerSlot).toList());
        assertEquals(List.of(2, 0, 1), mapping.stream().map(StorageSourceIntrospector.DrawerMapping::drawerSlot).toList());
        assertTrue(mapping.stream().noneMatch(slot -> slot.handlerSlot() == 0));
        assertThrows(UnsupportedOperationException.class, () -> mapping.clear());
    }

    @Test void staleControllerSlotTablesDoNotProduceAUsablePartialMapping() {
        assertThrows(StorageSourceIntrospector.ProbeFailure.class,
                () -> StorageSourceIntrospector.drawerMappings(4, 3, new int[] {0, 0, 2}));
        assertThrows(StorageSourceIntrospector.ProbeFailure.class,
                () -> StorageSourceIntrospector.drawerMappings(4, 3, new int[] {0, 1, 3}));
        assertThrows(StorageSourceIntrospector.ProbeFailure.class,
                () -> StorageSourceIntrospector.drawerMappings(3, 3, new int[] {0, 1, 2}));
    }

    @Test void controllerAndDirectViewsShareMemberPoolButDifferentWorldsDoNot() {
        BlockPos resolvedControllerMember = new BlockPos(10, 65, -3);
        BlockPos directlySelectedMember = new BlockPos(10, 65, -3);
        String throughController = StorageSourceIntrospector.blockBackend("minecraft:overworld", resolvedControllerMember);
        String direct = StorageSourceIntrospector.blockBackend("minecraft:overworld", directlySelectedMember);
        assertEquals(StorageSourceIntrospector.poolIdentity(throughController, 0, true),
                StorageSourceIntrospector.poolIdentity(direct, 2, true));
        assertNotEquals(StorageSourceIntrospector.poolIdentity(direct, 0, false),
                StorageSourceIntrospector.poolIdentity(direct, 2, false));
        String otherDimension = StorageSourceIntrospector.blockBackend("minecraft:the_nether", directlySelectedMember);
        assertNotEquals(StorageSourceIntrospector.poolIdentity(direct, 0, true),
                StorageSourceIntrospector.poolIdentity(otherDimension, 0, true));
    }

    @Test void simulationSuccessCannotOverrideVoidInfiniteSealedOrSuspendedFlags() {
        var flags = List.of(
                new StorageSourceIntrospector.DrawerFlags(true, false, false, false, false),
                new StorageSourceIntrospector.DrawerFlags(false, true, false, false, false),
                new StorageSourceIntrospector.DrawerFlags(false, false, true, false, false),
                new StorageSourceIntrospector.DrawerFlags(false, false, false, true, false),
                new StorageSourceIntrospector.DrawerFlags(false, false, false, false, true),
                new StorageSourceIntrospector.DrawerFlags(false, false, false, false, false, true, false),
                new StorageSourceIntrospector.DrawerFlags(false, false, false, false, false, false, true));
        for (var profile : flags) assertNotNull(StorageSourceIntrospector.drawerRefusal(profile));
        assertNull(StorageSourceIntrospector.drawerRefusal(
                new StorageSourceIntrospector.DrawerFlags(false, false, false, false, false)));
    }

    @Test void unreviewedTransformingAndNestedUpgradesCannotEnterTheFiniteInventoryProfile() {
        for (String itemClass : List.of(CORE + "upgrades.voiding.VoidUpgradeItem",
                CORE + "upgrades.infinity.InfinityUpgradeItem",
                CORE + "upgrades.compacting.CompactingUpgradeItem",
                "net.p3pp3rf1y.sophisticatedbackpacks.upgrades.inception.InceptionUpgradeItem",
                "thirdparty.CustomUpgrade")) {
            assertNotNull(StorageSourceIntrospector.upgradeItemRefusal(itemClass));
        }
        assertNull(StorageSourceIntrospector.upgradeItemRefusal(CORE + "upgrades.filter.FilterUpgradeItem"));
        assertNull(StorageSourceIntrospector.upgradeItemRefusal(CORE + "upgrades.stack.StackUpgradeItem"));
        assertNotNull(StorageSourceIntrospector.partitionRefusal(CORE + "inventory.IInventoryPartHandler$Default", true));
        assertNotNull(StorageSourceIntrospector.partitionRefusal(
                "net.p3pp3rf1y.sophisticatedstorage.upgrades.compression.CompressionInventoryPart", false));
    }

    @Test void staticProbeCannotAdvertiseBehaviorAcceptanceOrAuthorization() {
        var probe = new StorageSourceIntrospector.Probe(StorageSourceIntrospector.Status.STATIC_API_MATCH,
                "SOURCE_REQUIRES_CALLER_AUTHORIZATION", "fixture", "static-only", false, List.of());
        assertFalse(probe.behaviorVerified());
        assertFalse(probe.authorizationVerified());
        assertThrows(IllegalArgumentException.class, () -> new StorageSourceIntrospector.Probe(
                StorageSourceIntrospector.Status.STATIC_API_MATCH, "fixture", "fixture", "fixture", true, List.of()));
    }

    @Test void missingActorIsRefusedBeforeAnyWorldOrOptionalModAccess() {
        var introspector = new StorageSourceIntrospector();
        var block = introspector.probeBlock(null, null, null, null);
        var backpack = introspector.probeItemBackpack(null, null);
        assertEquals("PLAYER_NOT_ACTIVE", block.reason());
        assertEquals("PLAYER_NOT_ACTIVE", backpack.reason());
        assertEquals(StorageSourceIntrospector.Status.REFUSED, block.status());
        assertTrue(block.slots().isEmpty());
        assertThrows(IllegalStateException.class,
                () -> introspector.captureItemHost(null, "inventory/0", 0, null, 0));
    }
}
