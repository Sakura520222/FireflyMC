package firefly520.fireflymc.ai.recovery;

import com.google.gson.JsonObject;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryPreviewStoreTest {
    final AtomicLong clock = new AtomicLong(1000);
    final RecoveryPreviewStore store = new RecoveryPreviewStore(clock::get, 100);
    final RecoveryPreviewStore.Scope scope = new RecoveryPreviewStore.Scope(UUID.randomUUID().toString(), UUID.randomUUID(), UUID.randomUUID(), 7, UUID.randomUUID());
    RecoveryPreviewStore.Preview preview() {
        JsonObject args = new JsonObject(); args.addProperty("operationId", UUID.randomUUID().toString());
        return store.create(scope, UUID.randomUUID(), UUID.randomUUID(), "MATERIAL_RETURN", "revision-1",
                "processing_return", args, "归还当前已知托管。");
    }
    @Test void approvalBindsEveryIdentityAndSessionAndDoesNotExposeMutableArguments() {
        var value = preview();
        var foreign = new RecoveryPreviewStore.Scope(UUID.randomUUID().toString(), scope.serverInstanceId(), scope.conversationId(), 7, scope.session());
        var otherServer = new RecoveryPreviewStore.Scope(scope.owner(), UUID.randomUUID(), scope.conversationId(), 7, scope.session());
        var otherConversation = new RecoveryPreviewStore.Scope(scope.owner(), scope.serverInstanceId(), UUID.randomUUID(), 7, scope.session());
        var otherEpoch = new RecoveryPreviewStore.Scope(scope.owner(), scope.serverInstanceId(), scope.conversationId(), 8, scope.session());
        var otherSession = new RecoveryPreviewStore.Scope(scope.owner(), scope.serverInstanceId(), scope.conversationId(), 7, UUID.randomUUID());
        for (var changed : new RecoveryPreviewStore.Scope[]{foreign, otherServer, otherConversation, otherEpoch, otherSession})
            assertThrows(IllegalStateException.class, () -> store.pending(changed, value.token(), value.digest()));
        assertThrows(IllegalStateException.class, () -> store.pending(scope, value.token(), "changed body"));
        value.arguments().addProperty("count", 999);
        assertFalse(store.pending(scope, value.token(), value.digest()).arguments().has("count"));
    }
    @Test void revisionsExpiryAndNonceAreCheckedBeforeSingleUseConsumption() {
        var value = preview();
        assertThrows(IllegalStateException.class, () -> store.consume(scope, value.token(), value.digest(), "revision-2"));
        assertEquals(value, store.consume(scope, value.token(), value.digest(), "revision-1"));
        assertThrows(IllegalStateException.class, () -> store.consume(scope, value.token(), value.digest(), "revision-1"));
        var expires = preview(); clock.set(expires.expiresAt());
        assertThrows(IllegalStateException.class, () -> store.consume(scope, expires.token(), expires.digest(), "revision-1"));
    }
    @Test void disconnectInvalidatesOnlyThatConnectionAndOwnerPreviewLimitIsBounded() {
        var first = preview(); store.invalidate(scope.owner(), UUID.randomUUID());
        assertNotNull(store.pending(scope, first.token(), first.digest()));
        for (int index = 1; index < 8; index++) preview();
        assertThrows(IllegalStateException.class, this::preview);
        store.invalidate(scope.owner(), scope.session());
        assertThrows(IllegalStateException.class, () -> store.pending(scope, first.token(), first.digest()));
        assertNotNull(preview());
    }
}
