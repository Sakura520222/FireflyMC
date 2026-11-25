package firefly520.fireflymc.ai.context;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.util.*;

/** Owner-bound manual requests live in the same permanent conversation, not a second queue file or task. */
public final class ManualContextRequests {
    public record Request(UUID requestId, String owner, UUID serverId, UUID conversationId, long privacyEpoch) {
        public Request {
            Objects.requireNonNull(requestId); UUID.fromString(owner); Objects.requireNonNull(serverId); Objects.requireNonNull(conversationId);
            if (privacyEpoch < 0) throw new IllegalArgumentException("CONTEXT_COMPACTION_PRIVACY_INVALID");
        }
    }
    private record Terminal(String status, long summaryRevision) { }
    private record Timeline(LinkedHashMap<UUID, Request> requests, Map<UUID, Terminal> terminal) { }
    private static final Set<String> TERMINAL = Set.of("COMPACTED", "NO_CLOSED_HISTORY");
    private ManualContextRequests() {}

    public static Request enqueue(Workspace workspace, Actor actor, UUID requestId) throws IOException {
        actor.requireAccess(workspace.owner()); Objects.requireNonNull(requestId);
        synchronized (workspace.conversation()) {
            var timeline = timeline(workspace); var pending = pending(timeline);
            if (pending.isPresent()) return pending.get();
            var existing = timeline.requests().get(requestId);
            if (existing != null) return existing;
            var request = request(workspace, requestId); var data = data(request, "QUEUED");
            workspace.conversation().append(List.of(new ConversationStore.Draft(null, "context_compaction_request", null, data, null)));
            return request;
        }
    }
    public static Optional<Request> pending(Workspace workspace, Actor actor) {
        actor.requireAccess(workspace.owner());
        synchronized (workspace.conversation()) { return pending(timeline(workspace)); }
    }
    public static void complete(Workspace workspace, Actor actor, Request request, String status, long summaryRevision) throws IOException {
        actor.requireAccess(workspace.owner());
        if (!TERMINAL.contains(status) || summaryRevision < 0 || status.equals("COMPACTED") && summaryRevision == 0)
            throw new IllegalArgumentException("CONTEXT_COMPACTION_RESULT_INVALID");
        synchronized (workspace.conversation()) {
            if (!request.equals(request(workspace, request.requestId()))) throw new SecurityException("CONTEXT_COMPACTION_SCOPE_CHANGED");
            var timeline = timeline(workspace);
            if (!request.equals(timeline.requests().get(request.requestId()))) throw new SecurityException("CONTEXT_COMPACTION_REQUEST_NOT_FOUND");
            var terminal = timeline.terminal().get(request.requestId());
            if (terminal != null) {
                if (!terminal.status().equals(status) || terminal.summaryRevision() != summaryRevision)
                    throw new SecurityException("CONTEXT_COMPACTION_RESULT_CHANGED");
                return;
            }
            var data = data(request, status); data.addProperty("completedAt", System.currentTimeMillis());
            data.addProperty("summaryRevision", summaryRevision);
            workspace.conversation().append(List.of(new ConversationStore.Draft(null, "context_compaction_result", null, data, null)));
        }
    }
    private static Request request(Workspace workspace, UUID id) {
        return new Request(id, workspace.owner(), workspace.metadata().serverInstanceId(), workspace.primaryConversationId(),
                workspace.conversation().privacyEpoch());
    }
    private static JsonObject data(Request request, String status) {
        var data = new JsonObject(); data.addProperty("requestId", request.requestId().toString());
        data.addProperty("privacyEpoch", request.privacyEpoch()); data.addProperty("status", status); return data;
    }
    private static Optional<Request> pending(Timeline timeline) {
        var active = timeline.requests().values().stream().filter(request -> !timeline.terminal().containsKey(request.requestId())).toList();
        if (active.size() > 1) throw new IllegalStateException("MULTIPLE_CONTEXT_COMPACTION_REQUESTS");
        return active.stream().findFirst();
    }
    private static Timeline timeline(Workspace workspace) {
        var requests = new LinkedHashMap<UUID, Request>(); var terminal = new HashMap<UUID, Terminal>();
        long privacy = workspace.conversation().privacyEpoch();
        for (var event : workspace.conversation().getSnapshot(0).events()) {
            if (!Set.of("context_compaction_request", "context_compaction_result").contains(event.kind())) continue;
            var data = event.data();
            if (data == null || !data.has("privacyEpoch") || data.get("privacyEpoch").getAsLong() != privacy) continue;
            UUID id = UUID.fromString(data.get("requestId").getAsString());
            if (event.kind().equals("context_compaction_request")) requests.putIfAbsent(id, request(workspace, id));
            else if (data.has("status") && TERMINAL.contains(data.get("status").getAsString())) {
                if (!requests.containsKey(id)) throw new IllegalStateException("CONTEXT_COMPACTION_RESULT_WITHOUT_REQUEST");
                var value = new Terminal(data.get("status").getAsString(), data.has("summaryRevision") ? data.get("summaryRevision").getAsLong() : 0);
                var previous = terminal.putIfAbsent(id, value);
                if (previous != null && !previous.equals(value)) throw new IllegalStateException("CONTEXT_COMPACTION_RESULT_CHANGED");
            }
        }
        return new Timeline(requests, terminal);
    }
}
