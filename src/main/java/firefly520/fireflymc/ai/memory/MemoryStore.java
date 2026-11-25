package firefly520.fireflymc.ai.memory;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Private user and source-linked AI notes. A note never grants tool permissions. */
public final class MemoryStore {
    public record Note(UUID id, String text, String source, long updatedAt, String confidence) {}
    private record Data(int version, List<Note> notes) {}
    private final Workspace workspace;
    public MemoryStore(Workspace workspace) { this.workspace = workspace; }

    public List<Note> view(Actor actor) throws IOException {
        actor.requireAccess(workspace.owner());
        synchronized (workspace.conversation()) {
            Path path = workspace.directory().resolve("memory.json");
            if (!Files.exists(path)) return List.of();
            Data data = AiJson.GSON.fromJson(Files.readString(path), Data.class);
            if (data == null || data.version != 1) throw new IOException("Invalid memory version");
            return List.copyOf(data.notes);
        }
    }

    public Note remember(Actor actor, String text, String source) throws IOException {
        actor.requireAccess(workspace.owner());
        if (text.isBlank() || text.length() > 1024) throw new IllegalArgumentException("MEMORY_LENGTH");
        synchronized (workspace.conversation()) {
            List<Note> notes = new ArrayList<>(view(actor));
            if (notes.size() >= 100) throw new IllegalStateException("MEMORY_LIMIT");
            Note note = new Note(UUID.randomUUID(), text, source, System.currentTimeMillis(), "USER_CONFIRMED");
            notes.add(note);
            AiJson.write(workspace.directory().resolve("memory.json"), new Data(1, notes));
            return note;
        }
    }

    /** Automatic writes are bounded, idempotent and distinguish recorded evidence from user confirmation. */
    public Note rememberAutomatically(Actor actor, String text, long sourceSeq, UUID replaceId, long expectedUpdatedAt)
            throws IOException {
        actor.requireAccess(workspace.owner());
        text = text == null ? "" : text.strip();
        if (text.isBlank() || text.length() > 1024) throw new IllegalArgumentException("MEMORY_LENGTH");
        if (text.matches("(?s).*(?:sk-[A-Za-z0-9_-]{12,}|Bearer\\s+[A-Za-z0-9_.-]{12,}).*"))
            throw new IllegalArgumentException("MEMORY_SECRET_REJECTED");
        synchronized (workspace.conversation()) {
            var event = workspace.conversation().getSnapshot(sourceSeq - 1).events().stream()
                    .filter(e -> e.seq() == sourceSeq).findFirst().orElseThrow(() -> new IllegalArgumentException("MEMORY_SOURCE_NOT_FOUND"));
            if (!(event.message() != null && Set.of("user", "tool").contains(event.message().role()))
                    && !"tool_result".equals(event.kind())) throw new IllegalArgumentException("MEMORY_SOURCE_NOT_EVIDENCE");
            List<Note> notes = new ArrayList<>(view(actor));
            for (Note note : notes) if (note.text().equals(text)) return note;
            Note previous = replaceId == null ? null : notes.stream().filter(n -> n.id().equals(replaceId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("MEMORY_NOT_FOUND"));
            if (previous != null && (!previous.confidence().equals("AI_RECORDED") || previous.updatedAt() != expectedUpdatedAt))
                throw new IllegalStateException("MEMORY_CHANGED_OR_USER_MANAGED");
            if (previous == null && notes.size() >= 100) throw new IllegalStateException("MEMORY_LIMIT");
            String source = "conversation:" + workspace.primaryConversationId() + ":seq:" + sourceSeq;
            UUID id = previous != null ? previous.id() : UUID.nameUUIDFromBytes((workspace.owner() + "\n" + source + "\n" + text)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            long updatedAt = Math.max(System.currentTimeMillis(), previous == null ? 0 : previous.updatedAt() + 1);
            Note note = new Note(id, text, source, updatedAt, "AI_RECORDED");
            if (previous != null) notes.remove(previous);
            notes.add(note);
            AiJson.write(workspace.directory().resolve("memory.json"), new Data(1, notes));
            var audit = new com.google.gson.JsonObject();
            audit.add("note", AiJson.GSON.toJsonTree(note));
            if (previous != null) audit.add("previous", AiJson.GSON.toJsonTree(previous));
            workspace.conversation().append(List.of(new firefly520.fireflymc.ai.conversation.ConversationStore.Draft(
                    event.taskId(), "memory_written", null, audit, null)));
            return note;
        }
    }

    public void forget(Actor actor, UUID id) throws IOException {
        synchronized (workspace.conversation()) {
            List<Note> notes = new ArrayList<>(view(actor));
            notes.removeIf(n -> n.id().equals(id));
            AiJson.write(workspace.directory().resolve("memory.json"), new Data(1, notes));
        }
    }

    public Note edit(Actor actor, UUID id, String text, long expectedUpdatedAt) throws IOException {
        actor.requireAccess(workspace.owner());
        if (text == null || text.isBlank() || text.length() > 1024) throw new IllegalArgumentException("MEMORY_LENGTH");
        synchronized (workspace.conversation()) {
            var notes = new ArrayList<>(view(actor));
            var previous = notes.stream().filter(n -> n.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("MEMORY_NOT_FOUND"));
            if (previous.updatedAt() != expectedUpdatedAt) throw new IllegalStateException("MEMORY_CHANGED");
            Note edited = new Note(id, text.strip(), "workbench", Math.max(System.currentTimeMillis(), previous.updatedAt() + 1), "USER_CONFIRMED");
            notes.set(notes.indexOf(previous), edited);
            AiJson.write(workspace.directory().resolve("memory.json"), new Data(1, notes));
            return edited;
        }
    }

    public void clear(Actor actor) throws IOException {
        actor.requireAccess(workspace.owner());
        synchronized (workspace.conversation()) {
            AiJson.write(workspace.directory().resolve("memory.json"), new Data(1, List.of()));
        }
    }
}
