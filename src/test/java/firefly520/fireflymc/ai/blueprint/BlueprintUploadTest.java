package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.storage.AiJson;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlueprintUploadTest {
    private static String body() {
        return AiJson.GSON.toJson(new BlueprintDocument(1, "测试", "forgematica", "test", "", new BlueprintDocument.Position(0, 64, 0),
                List.of(new BlueprintDocument.Block(new BlueprintDocument.Position(0, 0, 0), new BlueprintDocument.State("minecraft:stone", Map.of())))));
    }
    @Test void orderedChunksCanRetryButCannotChangeOrSkipAcceptedText() {
        var transfer = new BlueprintUpload(() -> 1); UUID session = UUID.randomUUID(); String body = body();
        var ticket = transfer.begin("a", session, 0, body.length(), AiJson.digest(body));
        assertThrows(IllegalStateException.class, () -> transfer.append("a", session, 0, ticket.id(), 2, body.substring(2)));
        var first = transfer.append("a", session, 0, ticket.id(), 0, body.substring(0, 10));
        assertEquals(first, transfer.append("a", session, 0, ticket.id(), 0, body.substring(0, 10)));
        assertThrows(SecurityException.class, () -> transfer.append("a", session, 0, ticket.id(), 0, "xxxxxxxxxx"));
        transfer.append("a", session, 0, ticket.id(), 10, body.substring(10));
        assertEquals("测试", transfer.finish("a", session, 0, ticket.id()).name());
        transfer.completed("a", session, 0, ticket.id());
        assertThrows(IllegalStateException.class, () -> transfer.finish("a", session, 0, ticket.id()));
    }
    @Test void ownerSessionPrivacyAndExpiryAllBindTheUpload() {
        var clock = new AtomicLong(); var transfer = new BlueprintUpload(clock::get); UUID session = UUID.randomUUID(); String body = body();
        var ticket = transfer.begin("a", session, 4, body.length(), AiJson.digest(body));
        assertThrows(SecurityException.class, () -> transfer.append("b", session, 4, ticket.id(), 0, body));
        assertThrows(SecurityException.class, () -> transfer.append("a", UUID.randomUUID(), 4, ticket.id(), 0, body));
        assertThrows(SecurityException.class, () -> transfer.append("a", session, 5, ticket.id(), 0, body));
        clock.set(20 * 60 * 1000L + 1);
        assertThrows(IllegalStateException.class, () -> transfer.append("a", session, 4, ticket.id(), 0, body));
    }
    @Test void digestAndUnrecognizedNbtOrEntityFieldsAreRejected() {
        var transfer = new BlueprintUpload(() -> 0); UUID session = UUID.randomUUID(); String body = body();
        var ticket = transfer.begin("a", session, 0, body.length(), AiJson.digest("changed"));
        transfer.append("a", session, 0, ticket.id(), 0, body);
        assertThrows(IllegalArgumentException.class, () -> transfer.finish("a", session, 0, ticket.id()));
        transfer.disconnect("a");
        var data = AiJson.object(body, 8192, 16); data.addProperty("entities", "must not import"); String unsafe = data.toString();
        var second = transfer.begin("a", session, 0, unsafe.length(), AiJson.digest(unsafe));
        transfer.append("a", session, 0, second.id(), 0, unsafe);
        assertEquals("BLUEPRINT_UNSAFE_CONTENT", assertThrows(IllegalArgumentException.class,
                () -> transfer.finish("a", session, 0, second.id())).getMessage());
    }
}
