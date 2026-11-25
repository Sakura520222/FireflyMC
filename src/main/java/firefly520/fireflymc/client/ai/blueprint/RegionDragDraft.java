package firefly520.fireflymc.client.ai.blueprint;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.blueprint.BlueprintDocument;
import firefly520.fireflymc.ai.blueprint.BlueprintDocument.Position;
import firefly520.fireflymc.ai.blueprint.BuildRegionStore;
import firefly520.fireflymc.ai.storage.AiJson;
import java.util.Objects;
import java.util.UUID;

/** Local draft bound to the exact connection and authoritative revision observed on entry. */
public final class RegionDragDraft {
    private final JsonObject baseline;
    private final UUID session;
    private final String server, conversation;
    private final Position[] corners;
    private JsonObject preview;
    public RegionDragDraft(JsonObject source, UUID session, String server, String conversation) {
        for (String field : java.util.List.of("first", "second", "dimension", "privacyEpoch", "revision", "hash"))
            if (!source.has(field) || source.get(field).isJsonNull()) throw new IllegalArgumentException("DRAG_TWO_CORNERS_REQUIRED");
        if (server.isBlank() || conversation.isBlank()) throw new IllegalArgumentException("DRAG_WORKSPACE_REQUIRED");
        baseline = source.deepCopy(); this.session = session; this.server = server; this.conversation = conversation;
        corners = new Position[] { AiJson.GSON.fromJson(source.get("first"), Position.class), AiJson.GSON.fromJson(source.get("second"), Position.class) };
        rebuild();
    }
    public Position corner(int index) { return corners[index]; }
    public void move(int index, Position next) {
        if (index < 0 || index > 1 || Math.abs((long) next.x()) > BlueprintDocument.MAX_COORDINATE
                || Math.abs((long) next.y()) > BlueprintDocument.MAX_COORDINATE || Math.abs((long) next.z()) > BlueprintDocument.MAX_COORDINATE)
            throw new IllegalArgumentException("DRAG_COORDINATE_LIMIT");
        BuildRegionStore.bounds(next, corners[1 - index]);
        corners[index] = next; rebuild();
    }
    private void rebuild() {
        preview = baseline.deepCopy(); preview.add("first", AiJson.GSON.toJsonTree(corners[0])); preview.add("second", AiJson.GSON.toJsonTree(corners[1]));
        preview.add("bounds", AiJson.GSON.toJsonTree(BuildRegionStore.bounds(corners[0], corners[1])));
        preview.addProperty("confirmed", false); preview.addProperty("constructionApproved", false); preview.remove("survey");
    }
    public JsonObject preview() { return preview; }
    public boolean matches(JsonObject current, UUID session, String server, String conversation) {
        if (!this.session.equals(session) || !this.server.equals(server) || !this.conversation.equals(conversation)) return false;
        for (String key : java.util.List.of("privacyEpoch", "revision", "hash", "dimension"))
            if (!Objects.equals(baseline.get(key), current.get(key))) return false;
        return true;
    }
    public JsonObject request(JsonObject current, UUID session, String server, String conversation) {
        if (!matches(current, session, server, conversation)) throw new IllegalStateException("DRAG_BASELINE_CHANGED");
        var request = new JsonObject(); request.addProperty("action", "set");
        request.add("first", AiJson.GSON.toJsonTree(corners[0])); request.add("second", AiJson.GSON.toJsonTree(corners[1]));
        request.add("expectedRevision", baseline.get("revision")); request.add("privacyEpoch", baseline.get("privacyEpoch"));
        return request;
    }
}
