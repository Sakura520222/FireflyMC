package firefly520.fireflymc.ai.processing.advanced;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StorageDynamicNativeScopeTest {
    @Test void previewOnlyExposesItsOwnShadowAndRejectsAnyUnselectedUuidBeforeNativeAccess() {
        Object shadow = new Object(); UUID operation = UUID.randomUUID(), owned = UUID.randomUUID(), other = UUID.randomUUID();
        assertNull(StorageDynamicNativeScope.previewStore()); assertNull(StorageDynamicNativeScope.allocatedUuid());
        assertThrows(SecurityException.class, () -> StorageDynamicNativeScope.storeForRead(owned));
        try (var scope = StorageDynamicNativeScope.open(operation, shadow, true, List.of(owned))) {
            assertSame(shadow, StorageDynamicNativeScope.previewStore());
            assertSame(shadow, StorageDynamicNativeScope.storeForRead(owned));
            assertThrows(SecurityException.class, () -> StorageDynamicNativeScope.storeForRead(other));
            assertDoesNotThrow(() -> StorageDynamicNativeScope.requireEntry(shadow, owned));
            assertDoesNotThrow(() -> StorageDynamicNativeScope.requireEntry(shadow, StorageDynamicNativeScope.reserved(operation)));
            assertThrows(SecurityException.class, () -> StorageDynamicNativeScope.requireEntry(shadow, other));
            assertThrows(SecurityException.class, () -> StorageDynamicNativeScope.requireEntry(new Object(), owned));
        }
        assertNull(StorageDynamicNativeScope.previewStore());
        assertThrows(SecurityException.class, () -> StorageDynamicNativeScope.storeForRead(owned));
        assertDoesNotThrow(() -> StorageDynamicNativeScope.requireEntry(shadow, other), "Ordinary gameplay has no AI entry restriction");
    }
    @Test void nativeAllocationIsStableForOneOperationAndCannotBeExpandedOrReusedAcrossOperations() {
        UUID operation = UUID.randomUUID(); Object store = new Object();
        for (boolean preview : List.of(true, false)) try (var scope = StorageDynamicNativeScope.open(operation, store, preview, List.of())) {
            assertSame(store, StorageDynamicNativeScope.storeForRead(StorageDynamicNativeScope.reserved(operation)));
            assertEquals(StorageDynamicNativeScope.reserved(operation), StorageDynamicNativeScope.allocatedUuid());
            assertThrows(IllegalStateException.class, StorageDynamicNativeScope::allocatedUuid);
            if (!preview) assertNull(StorageDynamicNativeScope.previewStore(), "Commit uses the actual canonical native store");
        }
        assertNotEquals(StorageDynamicNativeScope.reserved(operation), StorageDynamicNativeScope.reserved(UUID.randomUUID()));
    }
    @Test void onlyPrivateAssemblyCacheKeysAreCollectedAndNestedScopesCannotBorrowAuthority() {
        Object store = new Object(), privateStack = new Object(), laterPlayerStack = new Object();
        try (var scope = StorageDynamicNativeScope.open(UUID.randomUUID(), store, true, List.of())) {
            StorageDynamicNativeScope.observeStack(privateStack); scope.endAssembly(); StorageDynamicNativeScope.observeStack(laterPlayerStack);
            assertEquals(Set.of(privateStack), scope.detachedStacks());
            assertThrows(IllegalStateException.class, () -> StorageDynamicNativeScope.open(UUID.randomUUID(), store, false, List.of()));
        }
    }
}
