package firefly520.fireflymc.ai.material;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NativeWorldScopeTest {
    @TempDir Path directory;
    final Actor owner = Actor.player(UUID.randomUUID(), true, true, 0);
    final Actor other = Actor.player(UUID.randomUUID(), true, true, 0);
    final String dimension = "minecraft:overworld";
    static long point(int x, int y, int z) { return ((long) x & 0x3ffffffL) << 38 | ((long) z & 0x3ffffffL) << 12 | y & 0xfffL; }
    @Test void anExactWorldJobFencesOtherAiWritesAndInventoriesButItsOwnChecksNeverGrantAnOutsideCell() throws Exception {
        var ledger = new MaterialLedger(directory); long position = point(1, 64, 2);
        var lease = ledger.acquireNativeWorldScope(owner, UUID.randomUUID(), dimension, List.of(position));
        assertTrue(ledger.recoveryBlocksWorld(dimension, 1, 64, 2));
        assertTrue(ledger.recoveryBlocksBackend("block/" + dimension + "/1,64,2:" + UUID.randomUUID()));
        assertFalse(ledger.recoveryBlocksWorld("minecraft:the_nether", 1, 64, 2));
        assertFalse(ledger.recoveryBlocksWorld(dimension, 2, 64, 2));
        ledger.requireNativeWorldScope(owner, lease, position);
        assertThrows(SecurityException.class, () -> ledger.requireNativeWorldScope(other, lease, position));
        assertThrows(SecurityException.class, () -> ledger.requireNativeWorldScope(owner, lease, point(2, 64, 2)));
        assertThrows(IllegalStateException.class, () -> ledger.acquireNativeWorldScope(other, UUID.randomUUID(), dimension, List.of(position)));
        ledger.releaseNativeWorldScope(owner, lease);
        assertFalse(ledger.recoveryBlocksWorld(dimension, 1, 64, 2));
    }
    @Test void restartRestorationOnlyQuarantinesAndCannotTurnAnOldIntentIntoWorldAuthority() throws Exception {
        var ledger = new MaterialLedger(directory); long position = point(3, -60, 4);
        var restored = ledger.restoreNativeWorldScope(UUID.fromString(owner.owner()), UUID.randomUUID(), dimension, List.of(position));
        assertTrue(restored.recovered());
        assertThrows(IllegalStateException.class, () -> ledger.requireNativeWorldScope(owner, restored, position));
        assertThrows(IllegalStateException.class, () -> ledger.acquireNativeWorldScope(owner, restored.operationId(), dimension, List.of(position)));
        var overlapping = ledger.restoreNativeWorldScope(UUID.fromString(other.owner()), UUID.randomUUID(), dimension, List.of(position));
        ledger.releaseNativeWorldScope(owner, restored);
        assertTrue(ledger.recoveryBlocksWorld(dimension, 3, -60, 4));
        ledger.releaseNativeWorldScope(other, overlapping);
        assertFalse(ledger.recoveryBlocksWorld(dimension, 3, -60, 4));
    }
    @Test void sourceReservationsAndGenerationChangesCannotBeBypassedByDelayedNativeJobs() throws Exception {
        var ledger = new MaterialLedger(directory); long position = point(5, 64, 6);
        String backend = "block/" + dimension + "/5,64,6:" + UUID.randomUUID();
        var material = new MaterialStack("{\"id\":\"minecraft:oak_log\",\"count\":1}", 4);
        var stock = new MaterialLedger.Stock("chest", 0, backend, backend + ":slot:0", 0, material, 1, 4, 4);
        UUID reserved = UUID.randomUUID(); ledger.reserve(owner, UUID.randomUUID(), reserved, AiJson.digest("fixed-materials"),
                List.of(stock), List.of(new MaterialLedger.Need(stock.key(), 2)));
        assertThrows(IllegalStateException.class, () -> ledger.acquireNativeWorldScope(other, UUID.randomUUID(), dimension, List.of(position)));
        var negative = ledger.restoreNativeWorldScope(UUID.fromString(other.owner()), UUID.randomUUID(), dimension, List.of(position));
        assertEquals(0L, ledger.available(List.of(stock)).get(stock.key()));
        assertThrows(IllegalStateException.class, () -> ledger.beginExtraction(owner, reserved, 0, material, 4, "before-native"));
        ledger.releaseNativeWorldScope(other, negative);
        UUID operation = UUID.randomUUID(); var original = ledger.acquireNativeWorldScope(other, operation, dimension, List.of(point(8, 64, 8)));
        var expanded = ledger.acquireNativeWorldScope(other, operation, dimension, List.of(point(9, 64, 8)));
        assertNotEquals(original.generation(), expanded.generation());
        assertThrows(IllegalStateException.class, () -> ledger.releaseNativeWorldScope(other, original));
        ledger.requireNativeWorldScope(other, expanded, point(8, 64, 8)); ledger.releaseNativeWorldScope(other, expanded);
    }
}
