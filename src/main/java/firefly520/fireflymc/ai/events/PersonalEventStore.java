package firefly520.fireflymc.ai.events;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.workspace.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Opt-in observations are a separate owner-only index, never a model or task trigger. */
public final class PersonalEventStore {
    public enum Type { DEATH, ADVANCEMENT }
    private final WorkspaceManager.Workspace workspace;
    private ConversationStore index;
    public PersonalEventStore(WorkspaceManager.Workspace workspace) { this.workspace = workspace; }
    private ConversationStore index() throws IOException {
        if (index == null) index = new ConversationStore(workspace.directory().resolve("personal-events.jsonl"));
        return index;
    }
    public boolean record(Actor actor, Type type, String sourceId, JsonObject observation) throws IOException {
        actor.requireAccess(workspace.owner());
        synchronized (workspace.conversation()) {
            var settings = WorkspacePreferences.load(workspace);
            if (!(type == Type.DEATH ? settings.recordDeaths() : settings.recordAdvancements())) return false;
            if (observation.toString().length() > 2048 || sourceId.length() > 256)
                throw new IllegalArgumentException("PERSONAL_EVENT_TOO_LARGE");
            JsonObject data = observation.deepCopy();
            data.addProperty("ownerUUID", actor.owner());
            data.addProperty("sourceId", sourceId);
            data.addProperty("eventType", type.name());
            index().append(List.of(new ConversationStore.Draft(null, "observation", null, data, null)));
            return true;
        }
    }
    public List<ConversationStore.Event> page(Actor actor, long beforeSeq, int limit) throws IOException {
        actor.requireAccess(workspace.owner());
        synchronized (workspace.conversation()) {
            if (!Files.exists(workspace.directory().resolve("personal-events.jsonl"))) return List.of();
            var events = index().getSnapshot(0).events().stream().filter(e -> e.seq() < beforeSeq).toList();
            return events.subList(Math.max(0, events.size() - Math.clamp(limit, 1, 30)), events.size());
        }
    }
    public void clear(Actor actor) throws IOException {
        actor.requireAccess(workspace.owner());
        synchronized (workspace.conversation()) {
            var path = workspace.directory().resolve("personal-events.jsonl");
            if (Files.exists(path)) try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
                channel.truncate(0);
                channel.force(true);
            }
            index = null;
        }
    }
}
