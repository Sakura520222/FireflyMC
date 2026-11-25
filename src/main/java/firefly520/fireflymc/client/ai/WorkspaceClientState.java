package firefly520.fireflymc.client.ai;

import com.google.gson.*;
import firefly520.fireflymc.ai.network.AiProtocol;
import java.util.*;

/** In-memory projection only. Server identity/session changes invalidate all private cached views. */
public final class WorkspaceClientState {
    public UUID session = AiProtocol.NO_SESSION;
    public String availability = "NOT_SUPPORTED";
    public String serverId = "";
    public String conversationId = "";
    public String name = "小萤";
    public String draft = "";
    public String notice = "";
    public String error = "";
    public long revision;
    public long lastSeq;
    public long uiVersion;
    public boolean viewingHistory;
    public long viewEpoch;
    public long nextHistoryBeforeSeq;
    public boolean hasEarlierHistory;
    private record MessageParts(String messageId, int length, String text) {}
    private final Map<Long, MessageParts> messageParts = new HashMap<>();
    private final Set<Long> failedMessageReads = new HashSet<>();
    public final NavigableMap<Long, JsonObject> events = new TreeMap<>();
    public List<JsonObject> tasks = List.of();
    public List<JsonObject> questions = List.of();
    public JsonObject preferences = new JsonObject();
    public JsonObject detail = new JsonObject();
    public JsonObject materialView = new JsonObject();
    public JsonObject recipeView = new JsonObject();
    public JsonObject integrationView = new JsonObject();
    public UUID integrationRequest;
    public JsonObject buildRegionView = new JsonObject();
    public List<JsonObject> notes = List.of();
    public int nextMemoryOffset, totalMemory;
    public List<String> shareTargets = List.of();
    public Set<String> supportedActions = Set.of();
    public final Map<String, JsonObject> streams = new HashMap<>();

    public boolean ready() { return "READY".equals(availability); }
    public long earlierHistory() {
        if (!viewingHistory) { viewingHistory = true; viewEpoch++; }
        return viewEpoch;
    }
    public long latestHistory() { viewingHistory = false; viewEpoch++; failedMessageReads.clear(); uiVersion++; return viewEpoch; }
    public long taskHistory() {
        viewingHistory = true; viewEpoch++; events.clear(); messageParts.clear(); failedMessageReads.clear();
        nextHistoryBeforeSeq = 0; hasEarlierHistory = false; uiVersion++; return viewEpoch;
    }
    public void applyPage(JsonObject page) { applyPage(page, false); }
    public void applyPage(JsonObject page, boolean historical) {
        String incoming = page.get("conversationId").getAsString();
        String incomingServer = page.get("serverInstanceId").getAsString();
        if (!conversationId.isEmpty() && (!conversationId.equals(incoming) || !serverId.equals(incomingServer))) {
            events.clear();
            messageParts.clear(); failedMessageReads.clear(); nextHistoryBeforeSeq = 0; hasEarlierHistory = false;
            tasks = List.of();
            questions = List.of();
            notes = List.of();
            nextMemoryOffset = 0; totalMemory = 0;
            preferences = new JsonObject();
            detail = new JsonObject();
            materialView = new JsonObject();
            recipeView = new JsonObject();
            integrationView = new JsonObject();
            integrationRequest = null;
            buildRegionView = new JsonObject();
            streams.clear();
            shareTargets = List.of();
            supportedActions = Set.of();
            lastSeq = 0;
            revision = 0;
            viewingHistory = false; viewEpoch++;
        }
        conversationId = incoming;
        serverId = incomingServer;
        boolean currentSnapshot = page.get("revision").getAsLong() >= revision;
        if (currentSnapshot && page.has("buildRegion")) acceptBuildRegion(page.getAsJsonObject("buildRegion"));
        revision = Math.max(revision, page.get("revision").getAsLong());
        lastSeq = Math.max(lastSeq, page.get("lastSeq").getAsLong());
        long firstVisible = events.isEmpty() ? 0 : events.firstKey(), lastVisible = events.isEmpty() ? Long.MAX_VALUE : events.lastKey();
        for (JsonElement element : page.getAsJsonArray("events")) {
            JsonObject event = element.getAsJsonObject();
            long sequence = event.get("seq").getAsLong();
            if (viewingHistory && !historical && (sequence < firstVisible || sequence > lastVisible)) continue;
            var previous = events.get(sequence);
            var incomingEvent = event.deepCopy();
            if (previous != null && previous.has("messageId") && event.has("messageId")
                    && previous.get("messageId").equals(event.get("messageId"))
                    && event.has("truncated") && event.get("truncated").getAsBoolean()
                    && (messageParts.containsKey(sequence) || previous.has("truncated") && !previous.get("truncated").getAsBoolean())) {
                for (String key : List.of("text", "blocks", "truncated"))
                    if (previous.has(key)) incomingEvent.add(key, previous.get(key).deepCopy());
            }
            events.put(sequence, incomingEvent);
        }
        while (events.size() > 2048) {
            if (viewingHistory) events.pollLastEntry(); else events.pollFirstEntry();
        }
        messageParts.keySet().retainAll(events.keySet()); failedMessageReads.retainAll(events.keySet());
        if (page.has("nextBeforeSeq")) {
            long before = page.get("nextBeforeSeq").getAsLong();
            if (nextHistoryBeforeSeq == 0 || before < nextHistoryBeforeSeq) {
                nextHistoryBeforeSeq = before;
                hasEarlierHistory = page.has("hasEarlierHistory") && page.get("hasEarlierHistory").getAsBoolean();
            }
        }
        if (!currentSnapshot) { uiVersion++; return; }
        tasks = page.getAsJsonArray("tasks").asList().stream().map(e -> e.getAsJsonObject().deepCopy()).toList();
        questions = page.has("questions") ? page.getAsJsonArray("questions").asList().stream()
                .map(e -> e.getAsJsonObject().deepCopy()).toList() : List.of();
        page.getAsJsonObject("preferences").entrySet().forEach(e -> preferences.add(e.getKey(), e.getValue().deepCopy()));
        shareTargets = page.has("shareTargets") ? page.getAsJsonArray("shareTargets").asList().stream()
                .map(JsonElement::getAsString).toList() : List.of();
        if (page.has("streams")) {
            Set<String> active = new HashSet<>();
            for (var element : page.getAsJsonArray("streams")) {
                var data = element.getAsJsonObject();
                String task = data.get("taskId").getAsString();
                active.add(task);
                if (!data.has("snapshotRequired")) stream(data);
                else if (streams.containsKey(task) && !streams.get(task).get("streamId").equals(data.get("streamId"))) streams.remove(task);
            }
            streams.keySet().removeIf(id -> !active.contains(id));
        }
        name = preferences.get("name").getAsString();
        error = "";
        uiVersion++;
    }
    public void clearPrivate() {
        session = AiProtocol.NO_SESSION;
        availability = "NOT_SUPPORTED";
        serverId = "";
        conversationId = "";
        name = "小萤";
        events.clear();
        messageParts.clear(); failedMessageReads.clear(); nextHistoryBeforeSeq = 0; hasEarlierHistory = false;
        tasks = List.of();
        questions = List.of();
        notes = List.of();
        nextMemoryOffset = 0; totalMemory = 0;
        shareTargets = List.of();
        supportedActions = Set.of();
        streams.clear();
        preferences = new JsonObject();
        detail = new JsonObject();
        materialView = new JsonObject();
        recipeView = new JsonObject();
        integrationView = new JsonObject();
        integrationRequest = null;
        buildRegionView = new JsonObject();
        revision = 0;
        lastSeq = 0;
        notice = "";
        error = "";
        draft = "";
        viewingHistory = false; viewEpoch++;
        uiVersion++;
    }
    public void erasePrivateViews(long erasedRevision) {
        UUID connectionSession = session;
        String connectionAvailability = availability, instance = serverId, conversation = conversationId;
        Set<String> actions = supportedActions;
        clearPrivate();
        session = connectionSession; availability = connectionAvailability;
        serverId = instance; conversationId = conversation; supportedActions = actions;
        revision = erasedRevision; lastSeq = erasedRevision;
    }
    public boolean acceptBuildRegion(JsonObject data) {
        long incomingEpoch = data.get("privacyEpoch").getAsLong(), incomingRevision = data.get("revision").getAsLong();
        if (buildRegionView.has("privacyEpoch")) {
            long currentEpoch = buildRegionView.get("privacyEpoch").getAsLong();
            if (incomingEpoch < currentEpoch || incomingEpoch == currentEpoch && incomingRevision < buildRegionView.get("revision").getAsLong()) return false;
            if (incomingEpoch == currentEpoch && data.has("projectionRevision") && buildRegionView.has("projectionRevision")
                    && data.get("projectionRevision").getAsLong() < buildRegionView.get("projectionRevision").getAsLong()) return false;
        }
        var incoming = data.deepCopy();
        if (!incoming.has("survey") && buildRegionView.has("survey") && incoming.has("hash") && buildRegionView.has("hash")
                && incoming.get("hash").equals(buildRegionView.get("hash"))) incoming.add("survey", buildRegionView.get("survey").deepCopy());
        buildRegionView = incoming; return true;
    }
    public List<JsonObject> messages() {
        // Keep all synced evidence for retrieval/export; tool traffic is not a chat bubble.
        return events.values().stream().filter(e -> e.has("role") && e.has("text")
                && !(e.has("intermediate") && e.get("intermediate").getAsBoolean())
                && !"tool".equals(e.get("role").getAsString())
                && !(e.has("kind") && "question_answer_input".equals(e.get("kind").getAsString()))
                && visibleBody(e)
                && (!(e.has("kind") && "task_notice".equals(e.get("kind").getAsString()))
                || e.has("essential") && e.get("essential").getAsBoolean())).toList();
    }
    private static boolean visibleBody(JsonObject event) {
        String text = event.get("text").getAsString();
        if (!text.isBlank() && !text.stripLeading().startsWith("{")) return true;
        if (event.has("blocks") && event.get("blocks").isJsonArray()) try {
            String document = firefly520.fireflymc.ai.presentation.ResponseBlocks.document(event.getAsJsonArray("blocks"));
            return !firefly520.fireflymc.ai.presentation.ResponseBlocks.plainText(document).isBlank();
        } catch (RuntimeException malformed) { /* The inert literal body remains the fallback. */ }
        return !text.isBlank();
    }
    public Optional<JsonObject> task(String id) {
        return tasks.stream().filter(t -> t.get("taskId").getAsString().equals(id)).findFirst();
    }
    public boolean yoloMode() { return preferences.has("yoloMode") && preferences.get("yoloMode").getAsBoolean(); }

    /** Chunks are fetched automatically; they never replace the separate artifact/search view. */
    public Optional<JsonObject> nextMessageRead() {
        for (var entry : events.descendingMap().entrySet()) {
            var event = entry.getValue(); long seq = entry.getKey();
            if (failedMessageReads.contains(seq) || !event.has("messageId") || !event.has("role")
                    || "tool".equals(event.get("role").getAsString()) || !event.has("truncated") || !event.get("truncated").getAsBoolean()
                    || event.has("kind") && ("question_answer_input".equals(event.get("kind").getAsString())
                    || "task_notice".equals(event.get("kind").getAsString()) && (!event.has("essential") || !event.get("essential").getAsBoolean()))) continue;
            var args = new JsonObject(); args.addProperty("seq", seq); args.addProperty("inline", true);
            var partial = messageParts.get(seq); args.addProperty("offset", partial == null ? 0 : partial.text().length());
            return Optional.of(args);
        }
        return Optional.empty();
    }
    public void messageReadFailed(long seq) { failedMessageReads.add(seq); }
    public boolean applyMessagePart(JsonObject page) {
        long seq = page.get("seq").getAsLong(); var event = events.get(seq);
        if (event == null || !event.has("messageId") || !event.get("messageId").equals(page.get("messageId"))) return false;
        int offset = page.get("offset").getAsInt(), next = page.get("nextOffset").getAsInt(), length = page.get("length").getAsInt();
        String chunk = page.get("text").getAsString(); var previous = messageParts.get(seq);
        if (offset < 0 || next < offset || next > length || next - offset != chunk.length()
                || next == offset && next < length || offset > 0 && (previous == null || previous.text().length() != offset
                || previous.length() != length || !previous.messageId().equals(page.get("messageId").getAsString()))) {
            messageReadFailed(seq); return false;
        }
        String full = (offset == 0 ? "" : previous.text()) + chunk;
        if (next == length) {
            event.addProperty("text", full); event.addProperty("truncated", false);
            var blocks = firefly520.fireflymc.ai.presentation.ResponseBlocks.parse(full);
            if (blocks.size() == 1 && "MarkdownBlock".equals(blocks.getFirst().type()) && full.equals(blocks.getFirst().text()))
                event.remove("blocks");
            else event.add("blocks", firefly520.fireflymc.ai.storage.AiJson.GSON.toJsonTree(blocks));
            messageParts.remove(seq);
        } else {
            messageParts.put(seq, new MessageParts(page.get("messageId").getAsString(), length, full));
            if (!full.stripLeading().startsWith("{")) {
                event.addProperty("text", full); event.remove("blocks");
            }
        }
        uiVersion++; return true;
    }
    public void stream(JsonObject data) {
        String task = data.get("taskId").getAsString();
        if (tasks.stream().anyMatch(t -> task.equals(t.get("taskId").getAsString())
                && Set.of("COMPLETED", "FAILED", "CANCELLED").contains(t.get("state").getAsString()))) return;
        JsonObject previous = streams.get(task);
        if (previous == null || !previous.get("streamId").equals(data.get("streamId"))
                || previous.get("sequence").getAsLong() < data.get("sequence").getAsLong()) {
            streams.put(task, data.deepCopy());
            uiVersion++;
        }
    }
    public void preferences(JsonObject full) {
        preferences = full.deepCopy();
        name = full.get("name").getAsString();
        uiVersion++;
    }
}
