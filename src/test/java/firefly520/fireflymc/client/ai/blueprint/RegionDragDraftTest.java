package firefly520.fireflymc.client.ai.blueprint;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.blueprint.BlueprintDocument.Position;
import firefly520.fireflymc.ai.storage.AiJson;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RegionDragDraftTest {
    private final UUID session = UUID.randomUUID();
    private JsonObject source() {
        return AiJson.object("""
                {"first":{"x":-4,"y":64,"z":-8},"second":{"x":3,"y":65,"z":2},
                 "dimension":"minecraft:overworld","privacyEpoch":0,"revision":7,"hash":"fixed-server-hash",
                 "confirmed":true,"constructionApproved":false,"survey":{"protected":0}}
                """, 4096, 12);
    }
    @Test void draggingCreatesOnlyAnUnconfirmedProposalAndNeverChangesTheServerView() {
        var current = source(); var original = current.deepCopy();
        var draft = new RegionDragDraft(current, session, "server", "conversation");
        draft.move(0, new Position(-6, 66, -9));
        assertEquals(original, current); assertFalse(draft.preview().get("confirmed").getAsBoolean());
        assertFalse(draft.preview().has("survey")); assertFalse(draft.preview().get("constructionApproved").getAsBoolean());
        var request = draft.request(current, session, "server", "conversation");
        assertEquals("set", request.get("action").getAsString()); assertEquals(7, request.get("expectedRevision").getAsLong());
        assertEquals(-6, request.getAsJsonObject("first").get("x").getAsInt());
        assertFalse(request.has("confirmed")); assertFalse(request.has("constructionApproved"));
    }
    @Test void reconnectPrivacyEraseRevisionAndDimensionChangesInvalidateTheDraft() {
        var current = source(); var draft = new RegionDragDraft(current, session, "server", "conversation");
        assertFalse(draft.matches(current, UUID.randomUUID(), "server", "conversation"));
        assertFalse(draft.matches(current, session, "different-server", "conversation"));
        assertFalse(draft.matches(current, session, "server", "different-conversation"));
        for (String field : java.util.List.of("privacyEpoch", "revision", "hash", "dimension")) {
            var changed = current.deepCopy(); changed.addProperty(field, "changed");
            assertFalse(draft.matches(changed, session, "server", "conversation"));
            assertThrows(IllegalStateException.class, () -> draft.request(changed, session, "server", "conversation"));
        }
    }
    @Test void invalidMovementKeepsLastValidCornerAndDoesNotOverflowAtWorldLimits() {
        var draft = new RegionDragDraft(source(), session, "server", "conversation");
        draft.move(0, new Position(-7, 64, -8)); var accepted = draft.preview().deepCopy();
        assertThrows(IllegalArgumentException.class, () -> draft.move(0, new Position(300, 64, 0)));
        assertThrows(IllegalArgumentException.class, () -> draft.move(0, new Position(Integer.MIN_VALUE, 64, 0)));
        assertEquals(accepted, draft.preview());
        var partial = source(); partial.remove("second");
        assertThrows(IllegalArgumentException.class, () -> new RegionDragDraft(partial, session, "server", "conversation"));
    }
}
