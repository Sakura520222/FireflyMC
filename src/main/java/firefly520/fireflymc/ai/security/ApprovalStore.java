package firefly520.fireflymc.ai.security;

import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.AgentTask;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Single-use preview proof. Tokens stay out of model context and public message projections. */
public final class ApprovalStore {
    public record Preview(UUID token, UUID taskId, String callDigest, long expiresAt, UUID consumedBy) {}
    private record Data(int version, List<Preview> previews) {}
    private final Workspace workspace;
    private final LongSupplier clock;
    public ApprovalStore(Workspace workspace, LongSupplier clock) { this.workspace = workspace; this.clock = clock; }
    private List<Preview> load() throws IOException {
        Path path = workspace.directory().resolve("approvals.json");
        if (!Files.exists(path)) return new ArrayList<>();
        Data data = AiJson.GSON.fromJson(Files.readString(path), Data.class);
        if (data.version != 1) throw new IOException("APPROVAL_VERSION");
        return new ArrayList<>(data.previews);
    }
    public Preview preview(Actor actor, AgentTask task) throws IOException {
        actor.requireAccess(workspace.owner());
        if (task.state() != AgentTask.State.WAITING_APPROVAL || !task.owner().equals(actor.owner())
                || task.nextCall() >= task.pending().size()) throw new IllegalStateException("NO_PENDING_APPROVAL");
        synchronized (workspace.conversation()) {
            String digest = ToolPolicyEngine.digest(actor.owner(), task.pending().get(task.nextCall()));
            List<Preview> previews = load();
            var existing = previews.stream().filter(p -> p.taskId.equals(task.id()) && p.callDigest.equals(digest)
                    && p.consumedBy == null && p.expiresAt > clock.getAsLong()).findFirst();
            if (existing.isPresent()) return existing.get();
            previews.removeIf(p -> p.expiresAt < clock.getAsLong() - 86400000L);
            if (previews.size() >= 256) previews.removeFirst();
            Preview created = new Preview(UUID.randomUUID(), task.id(), digest, clock.getAsLong() + 120000, null);
            previews.add(created);
            AiJson.write(workspace.directory().resolve("approvals.json"), new Data(1, previews));
            return created;
        }
    }
    /** Returns false for an already consumed matching request; the caller must not execute again. */
    public boolean consume(Actor actor, UUID token, UUID requestId, AgentTask task) throws IOException {
        actor.requireAccess(workspace.owner());
        synchronized (workspace.conversation()) {
            List<Preview> previews = load();
            Preview found = previews.stream().filter(p -> p.token.equals(token)).findFirst()
                    .orElseThrow(() -> new SecurityException("UNKNOWN_APPROVAL"));
            if (!found.taskId.equals(task.id()) || !task.owner().equals(actor.owner()))
                throw new SecurityException("APPROVAL_OWNER");
            if (found.consumedBy != null) {
                if (found.consumedBy.equals(requestId)) return false;
                throw new SecurityException("APPROVAL_ALREADY_USED");
            }
            if (clock.getAsLong() >= found.expiresAt || task.state() != AgentTask.State.WAITING_APPROVAL
                    || task.nextCall() >= task.pending().size() || !found.callDigest.equals(
                    ToolPolicyEngine.digest(actor.owner(), task.pending().get(task.nextCall()))))
                throw new SecurityException("APPROVAL_STALE");
            previews.set(previews.indexOf(found), new Preview(found.token, found.taskId, found.callDigest, found.expiresAt, requestId));
            AiJson.write(workspace.directory().resolve("approvals.json"), new Data(1, previews));
            return true;
        }
    }
}
