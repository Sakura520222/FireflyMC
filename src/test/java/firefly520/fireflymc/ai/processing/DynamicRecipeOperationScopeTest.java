package firefly520.fireflymc.ai.processing;

import firefly520.fireflymc.ai.processing.advanced.DynamicRecipeOperationScope;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DynamicRecipeOperationScopeTest {
    @Test void previewValidationAndCommitCanUseOneIdentityWithoutLeakingItToAnotherThread() throws Exception {
        UUID operation = UUID.randomUUID();
        assertNull(DynamicRecipeOperationScope.current());
        try (var outer = DynamicRecipeOperationScope.open(operation)) {
            assertEquals(operation, DynamicRecipeOperationScope.current());
            assertNull(CompletableFuture.supplyAsync(DynamicRecipeOperationScope::current).get());
            try (var inner = DynamicRecipeOperationScope.open(operation)) { assertEquals(operation, DynamicRecipeOperationScope.current()); }
            assertEquals(operation, DynamicRecipeOperationScope.current());
            assertThrows(IllegalStateException.class, () -> DynamicRecipeOperationScope.open(UUID.randomUUID()));
        }
        assertNull(DynamicRecipeOperationScope.current());
    }
    @Test void wrongCloseOrderCannotEraseTheNativeOperationBoundary() {
        UUID operation = UUID.randomUUID();
        var outer = DynamicRecipeOperationScope.open(operation); var inner = DynamicRecipeOperationScope.open(operation);
        assertThrows(IllegalStateException.class, outer::close);
        assertEquals(operation, DynamicRecipeOperationScope.current());
        inner.close(); outer.close(); outer.close(); assertNull(DynamicRecipeOperationScope.current());
    }
}
