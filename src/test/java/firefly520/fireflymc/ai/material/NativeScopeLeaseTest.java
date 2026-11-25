package firefly520.fireflymc.ai.material;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NativeScopeLeaseTest {
    @TempDir Path directory;
    final Actor first = Actor.player(UUID.randomUUID(), true, true, 0);
    final Actor second = Actor.player(UUID.randomUUID(), true, true, 0);
    final MaterialStack stone = new MaterialStack("{\"id\":\"minecraft:stone\",\"count\":1}", 1);
    MaterialLedger.Stock stock(String backend) {
        return new MaterialLedger.Stock("shared", 0, backend, backend + ":slot:0", 0, stone.withCount(8), 1, 8, 8);
    }
    UUID reserve(MaterialLedger ledger, Actor owner, MaterialLedger.Stock stock) throws Exception {
        UUID id = UUID.randomUUID(); ledger.reserve(owner, UUID.randomUUID(), id, AiJson.digest("actual reservation"),
                List.of(stock), List.of(new MaterialLedger.Need(stock.key(), 2))); return id;
    }
    @Test void nativeTransportCannotBorrowAnotherPlayersAlreadyReservedMaterials() throws Exception {
        var ledger = new MaterialLedger(directory); var stock = stock("chest:shared"); reserve(ledger, first, stock);
        assertEquals("NATIVE_RESOURCE_SCOPE_RESERVED", assertThrows(IllegalStateException.class,
                () -> ledger.acquireNativeScope(second, UUID.randomUUID(), List.of(stock.backendId()))).getMessage());
        assertEquals(6L, ledger.available(List.of(stock)).get(stock.key()));
    }
    @Test void nativeOwnershipBlocksNewClaimsAndBlockAttachmentAliasesUntilTheExactLeaseReleases() throws Exception {
        var ledger = new MaterialLedger(directory);
        String original = "block/minecraft:overworld/1,64,1:" + UUID.randomUUID();
        String alias = "block/minecraft:overworld/1,64,1:" + UUID.randomUUID();
        var lease = ledger.acquireNativeScope(first, UUID.randomUUID(), List.of(original)); var stock = stock(alias);
        assertEquals(0L, ledger.available(List.of(stock)).get(stock.key()));
        assertThrows(IllegalStateException.class, () -> reserve(ledger, second, stock));
        assertThrows(SecurityException.class, () -> ledger.releaseNativeScope(second, lease));
        ledger.requireNativeScope(first, lease); ledger.releaseNativeScope(first, lease);
        assertEquals(8L, ledger.available(List.of(stock)).get(stock.key()));
    }
    @Test void extensionIsAtomicAndOldGenerationCannotReleaseANewerDestinationsLock() throws Exception {
        var ledger = new MaterialLedger(directory); UUID id = UUID.randomUUID();
        var original = ledger.acquireNativeScope(first, id, List.of("maid:first"));
        reserve(ledger, second, stock("chest:busy"));
        assertThrows(IllegalStateException.class, () -> ledger.acquireNativeScope(first, id, List.of("chest:busy")));
        ledger.requireNativeScope(first, original);
        var expanded = ledger.acquireNativeScope(first, id, List.of("chest:free"));
        assertNotEquals(original.generation(), expanded.generation());
        assertThrows(IllegalStateException.class, () -> ledger.releaseNativeScope(first, original));
        assertSame(expanded, ledger.acquireNativeScope(first, id, List.of("maid:first")));
        ledger.releaseNativeScope(first, expanded);
    }
    @Test void offlineJournalRestorationQuarantinesExistingClaimsAndOverlappingNativeJournals() throws Exception {
        var ledger = new MaterialLedger(directory); var stock = stock("chest:shared"); UUID claim = reserve(ledger, first, stock);
        var restored = ledger.restoreNativeScope(UUID.fromString(second.owner()), UUID.randomUUID(), List.of(stock.backendId()));
        assertTrue(restored.recovered());
        assertThrows(IllegalStateException.class, () -> ledger.acquireNativeScope(second, restored.operationId(), List.of(stock.backendId())));
        assertEquals(0L, ledger.availablePools(List.of(stock)).get(stock.poolId()));
        assertThrows(IllegalStateException.class, () -> ledger.beginExtraction(first, claim, 0, stone.withCount(8), 8, "native-call"));
        var overlap = ledger.restoreNativeScope(UUID.fromString(first.owner()), UUID.randomUUID(), List.of(stock.backendId()));
        assertThrows(IllegalStateException.class, () -> ledger.requireNativeScope(second, restored));
        ledger.releaseNativeScope(first, overlap); ledger.releaseNativeScope(second, restored);
        assertEquals(6L, ledger.available(List.of(stock)).get(stock.key()));
    }
    @Test void newDeliveryCannotEnterAnIsolatedDestinationButPreviouslyAcceptedDeliveryStillRecordsItsActualReturn() throws Exception {
        var ledger = new MaterialLedger(directory); var source = stock("player:" + first.owner()); UUID id = reserve(ledger, first, source);
        ledger.beginExtraction(first, id, 0, stone.withCount(8), 8, "extract-once");
        ledger.extracted(first, id, stone.withCount(2), 6);
        ledger.nativeSaved(first, id, "extract", "extract-once", AiJson.digest("input native proof fixture"));
        ledger.cancel(first, id);
        Set<String> target = Set.of(MaterialSourceScope.key("chest:target"));
        ledger.beginDelivery(first, id, 0, 1, "accepted-delivery", MaterialStack.EMPTY, target);
        var quarantine = ledger.restoreNativeScope(UUID.fromString(second.owner()), UUID.randomUUID(), List.of("chest:target"));
        var recorded = ledger.deliveryEvidence(first, id, "accepted-delivery", 1, true);
        assertEquals(1, recorded.remainingProducts().stream().mapToInt(MaterialStack::count).sum());
        ledger.nativeSaved(first, id, "deliver", "accepted-delivery", AiJson.digest("actual output native proof fixture"));
        assertThrows(IllegalStateException.class, () -> ledger.beginDelivery(first, id, 0, 1, "new-delivery", stone, target));
        ledger.releaseNativeScope(second, quarantine);
        assertEquals(MaterialLedger.State.DELIVER_INTENT, ledger.beginDelivery(first, id, 0, 1, "new-delivery", stone, target).state());
    }
}
