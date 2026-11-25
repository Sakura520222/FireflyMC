package firefly520.fireflymc.ai.conversation;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.AgentTask;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * One logical writer per conversation. A checksummed JSONL record commits a batch of events
 * (message + checkpoint, or tool output + checkpoint) together. force() precedes visibility.
 * Incomplete final writes are preserved separately; corruption of committed records fails closed.
 */
public final class ConversationStore {
    public record Event(long seq, UUID messageId, UUID taskId, long timestamp, String kind,
                        ModelMessage message, JsonObject data, String toolTransactionId) {}
    public record Draft(UUID taskId, String kind, ModelMessage message, JsonObject data,
                        String toolTransactionId) {
        public static Draft message(UUID task, ModelMessage message, String transaction) {
            return new Draft(task, "message", message, null, transaction);
        }
        public static Draft checkpoint(AgentTask task) {
            return new Draft(task.id(), "task", null, AiJson.GSON.toJsonTree(task).getAsJsonObject(), null);
        }
    }
    public record Snapshot(long revision, List<Event> events, List<AgentTask> tasks) {}
    public record EventPage(long revision, List<Event> events) {}
    private record Batch(int version, String payload, String digest) {}

    private final Path journal;
    private final List<Event> events = new ArrayList<>();
    private final Map<UUID, AgentTask> tasks = new LinkedHashMap<>();
    private final Map<UUID, Long> latestVisibleByTask = new HashMap<>();
    private final Map<UUID, Long> latestAssistantByTask = new HashMap<>();
    // Only completed, model-facing tool results are generic readable artifacts. Execution plans,
    // native item/entity snapshots and provider replay state stay in their typed server stores.
    private final Map<UUID, Long> readableArtifacts = new HashMap<>();
    private final Map<String, Long> latestByKind = new HashMap<>();
    private final Set<String> erasedRequests = new HashSet<>();
    private long baseSequence;
    private boolean broken;
    private volatile long privacyEpoch;
    private volatile long regionSequence;

    public ConversationStore(Path journal) throws IOException {
        this.journal = journal;
        Files.createDirectories(journal.getParent());
        replay();
    }

    private void replay() throws IOException {
        if (!Files.exists(journal)) return;
        long goodOffset = 0;
        try (RandomAccessFile input = new RandomAccessFile(journal.toFile(), "r")) {
            while (input.getFilePointer() < input.length()) {
                long start = input.getFilePointer();
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                int next;
                while ((next = input.read()) != -1 && next != '\n') {
                    line.write(next);
                    if (line.size() > 16 * 1024 * 1024) throw new IOException("AI journal record too large");
                }
                if (next == -1) {
                    // Keep forensic bytes; only discard an uncommitted, non-newline-terminated tail.
                    Path backup = journal.resolveSibling("incomplete-" + UUID.randomUUID() + ".bin");
                    Files.write(backup, line.toByteArray(), StandardOpenOption.CREATE_NEW);
                    goodOffset = start;
                    break;
                }
                try {
                    Batch batch = AiJson.GSON.fromJson(line.toString(StandardCharsets.UTF_8), Batch.class);
                    if (batch.version != 1 || !AiJson.digest(batch.payload).equals(batch.digest))
                        throw new IllegalArgumentException("checksum/version");
                    Event[] loaded = AiJson.GSON.fromJson(batch.payload, Event[].class);
                    for (Event event : loaded) {
                        if (events.isEmpty() && "privacy_erased".equals(event.kind) && event.seq > 0)
                            baseSequence = event.seq - 1;
                        if (event.seq != baseSequence + events.size() + 1L || event.messageId == null)
                            throw new IllegalArgumentException("sequence");
                        apply(event);
                    }
                } catch (RuntimeException e) {
                    throw new IOException("AI journal is corrupt at byte " + start, e);
                }
                goodOffset = input.getFilePointer();
            }
        }
        if (Files.size(journal) > goodOffset) {
            try (FileChannel channel = FileChannel.open(journal, StandardOpenOption.WRITE)) {
                channel.truncate(goodOffset);
                channel.force(true);
            }
        }
    }

    public synchronized List<Event> appendConversationEvent(List<Draft> drafts, long expectedRevision)
            throws IOException {
        if (broken) throw new IOException("AI journal requires reopen after a failed write");
        if (expectedRevision != revision()) throw new ConcurrentModificationException("conversation revision");
        if (drafts.isEmpty()) return List.of();
        List<Event> committed = new ArrayList<>();
        for (Draft draft : drafts) {
            committed.add(new Event(baseSequence + events.size() + committed.size() + 1L, UUID.randomUUID(),
                    draft.taskId, System.currentTimeMillis(), draft.kind, draft.message, draft.data,
                    draft.toolTransactionId));
        }
        String payload = AiJson.GSON.toJson(committed);
        byte[] bytes = (AiJson.GSON.toJson(new Batch(1, payload, AiJson.digest(payload))) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 16 * 1024 * 1024) throw new IOException("AI transaction too large");
        try (FileChannel channel = FileChannel.open(journal, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            AiJson.writeFully(channel, bytes);
            channel.force(true);
        } catch (IOException e) {
            broken = true;
            throw e;
        }
        committed.forEach(this::apply);
        return List.copyOf(committed);
    }

    public synchronized void append(List<Draft> drafts) throws IOException {
        appendConversationEvent(drafts, revision());
    }

    private void apply(Event event) {
        events.add(event);
        if (event.taskId != null && event.message != null && "message".equals(event.kind)
                && Set.of("user", "assistant").contains(event.message.role()) && !event.message.text().isBlank()) {
            latestVisibleByTask.put(event.taskId, event.seq);
            if ("assistant".equals(event.message.role())) latestAssistantByTask.put(event.taskId, event.seq);
        }
        latestByKind.put(event.kind, event.seq);
        if ("build_region".equals(event.kind)) regionSequence = event.seq;
        if ("tool_result".equals(event.kind) && event.data != null
                && event.data.has("artifactId") && event.data.has("fullResult"))
            readableArtifacts.put(UUID.fromString(event.data.get("artifactId").getAsString()), event.seq);
        if ("privacy_erased".equals(event.kind)) privacyEpoch = event.seq;
        if ("task".equals(event.kind)) {
            AgentTask task = AiJson.GSON.fromJson(event.data, AgentTask.class);
            tasks.put(task.id(), task);
        }
        if ("privacy_erased".equals(event.kind) && event.data != null && event.data.has("erasedRequests"))
            event.data.getAsJsonArray("erasedRequests").forEach(v -> erasedRequests.add(v.getAsString()));
    }

    public synchronized Snapshot getSnapshot(long afterSeq) {
        // Sequence numbers are contiguous from baseSequence, including after explicit privacy erasure.
        // Incremental UI/projector reads should copy only their suffix, not scan the complete history.
        int first = afterSeq <= baseSequence ? 0 : afterSeq >= revision() ? events.size() : (int) (afterSeq - baseSequence);
        return new Snapshot(revision(), List.copyOf(events.subList(first, events.size())),
                List.copyOf(tasks.values()));
    }

    public synchronized long revision() { return baseSequence + events.size(); }
    /** UI windows never copy the entire history or task table. Cursors stay exclusive and monotonic. */
    public synchronized EventPage eventPage(long afterSeq, long beforeSeq, int limit, boolean latest) {
        if (afterSeq < 0 || beforeSeq < 0 || limit < 1 || limit > 128) throw new IllegalArgumentException("HISTORY_PAGE_CURSOR");
        int start = afterSeq <= baseSequence ? 0 : afterSeq >= revision() ? events.size() : (int) (afterSeq - baseSequence);
        int end = beforeSeq <= baseSequence + 1 ? 0 : beforeSeq > revision() ? events.size() : (int) (beforeSeq - baseSequence - 1);
        if (start >= end) return new EventPage(revision(), List.of());
        if (latest) start = Math.max(start, end - limit); else end = Math.min(end, start + limit);
        return new EventPage(revision(), List.copyOf(events.subList(start, end)));
    }
    /** Chat pages count visible messages, never internal tool/checkpoint traffic. */
    public synchronized EventPage chatPage(long afterSeq, long beforeSeq, int limit) {
        if (afterSeq < 0 || beforeSeq < 0 || limit < 1 || limit > 128) throw new IllegalArgumentException("HISTORY_PAGE_CURSOR");
        int start = afterSeq <= baseSequence ? 0 : afterSeq >= revision() ? events.size() : (int) (afterSeq - baseSequence);
        int end = beforeSeq <= baseSequence + 1 ? 0 : beforeSeq > revision() ? events.size() : (int) (beforeSeq - baseSequence - 1);
        var selected = new ArrayList<Event>();
        for (int i = end - 1; i >= start && selected.size() < limit; i--)
            if (visibleInChat(events.get(i))) selected.add(events.get(i));
        Collections.reverse(selected);
        return new EventPage(revision(), List.copyOf(selected));
    }
    private static boolean visibleInChat(Event event) {
        if (event.message != null) return !"tool".equals(event.message.role())
                && !"question_answer_input".equals(event.kind) && !event.message.text().isBlank()
                && !("assistant".equals(event.message.role()) && !event.message.calls().isEmpty());
        if ("task_notice".equals(event.kind)) return event.data != null && event.data.has("essential")
                && event.data.get("essential").getAsBoolean();
        return Set.of("share", "client_coordinate_hint", "question", "question_answer",
                "workbench_recovery_wait", "workbench_recovery_result", "workbench_recovery_evidence").contains(event.kind);
    }
    public long privacyEpoch() { return privacyEpoch; }
    /** Volatile invalidation witness, safe for a main-thread placement lease without taking an IO lock. */
    public long regionSequence() { return regionSequence; }
    /** Trusted internal projection only; never expose this raw event through generic model/UI readers. */
    public synchronized Optional<Event> latestEvent(String kind) {
        Long seq = latestByKind.get(kind); return Optional.ofNullable(seq == null ? null : eventAt(seq));
    }
    public synchronized AgentTask task(UUID id) { return tasks.get(id); }
    /** The owner-facing task link is an exact message cursor, never a model or tool-result replay. */
    public synchronized OptionalLong latestTaskMessage(UUID taskId) {
        if (!tasks.containsKey(taskId)) throw new IllegalArgumentException("TASK_NOT_FOUND");
        Long seq = latestAssistantByTask.getOrDefault(taskId, latestVisibleByTask.get(taskId));
        return seq == null ? OptionalLong.empty() : OptionalLong.of(seq);
    }
    public synchronized List<AgentTask> tasks() { return List.copyOf(tasks.values()); }

    /** Public retrieval is a projection, never serialization of the internal journal event. */
    public synchronized Optional<JsonObject> readableEvent(long seq) {
        Event event = eventAt(seq);
        if (event == null) return Optional.empty();
        JsonObject view = new JsonObject();
        view.addProperty("seq", event.seq); view.addProperty("messageId", event.messageId.toString());
        if (event.taskId != null) view.addProperty("taskId", event.taskId.toString());
        view.addProperty("timestamp", event.timestamp); view.addProperty("kind", event.kind);
        if (("message".equals(event.kind) || "question_answer_input".equals(event.kind)) && event.message != null) {
            JsonObject message = new JsonObject();
            message.addProperty("role", event.message.role()); message.addProperty("text", event.message.text());
            message.add("calls", AiJson.GSON.toJsonTree(event.message.calls()));
            if (event.message.toolCallId() != null) message.addProperty("toolCallId", event.message.toolCallId());
            view.add("message", message);
        } else {
            List<String> fields = switch (event.kind) {
                case "tool_result" -> List.of("name", "success", "errorCode", "finishedAt", "fullResult", "artifactId");
                case "question", "question_answer" -> List.of("id", "taskId", "prompt", "choices", "blocking", "answer", "createdAt", "answeredAt",
                        "stepId", "blockingScope", "requiredBeforeStepIds", "contextDigest");
                case "task_notice" -> List.of("text");
                default -> throw new SecurityException("INTERNAL_EVENT_NOT_READABLE");
            };
            JsonObject data = new JsonObject();
            if (event.data != null) for (String field : fields)
                if (event.data.has(field)) data.add(field, event.data.get(field).deepCopy());
            view.add("data", data);
        }
        return Optional.of(view);
    }

    /** Resolve from the committed result itself: unregistered files and changed disk artifacts
     * cannot turn an otherwise valid artifact ID into access to private native components. */
    public synchronized Optional<String> readableArtifact(UUID id) {
        Long seq = readableArtifacts.get(id);
        Event event = seq == null ? null : eventAt(seq);
        return event == null ? Optional.empty() : Optional.of(event.data.get("fullResult").getAsString());
    }

    private Event eventAt(long seq) {
        if (seq <= baseSequence || seq > revision()) return null;
        return events.get((int) (seq - baseSequence - 1));
    }

    public synchronized boolean requestWasErased(UUID request) {
        return erasedRequests.contains(AiJson.digest(request.toString()));
    }

    /** Explicit owner-confirmed erasure keeps identity, monotonic sequence and opaque replay tombstones. */
    public synchronized long erase(long expectedRevision) throws IOException {
        if (broken || expectedRevision != revision() || tasks.values().stream().anyMatch(t -> !t.state().terminal()))
            throw new IllegalStateException("WORKSPACE_NOT_SAFE_TO_ERASE");
        Set<String> tombstones = new HashSet<>(erasedRequests);
        tasks.values().forEach(t -> tombstones.add(AiJson.digest(t.requestId().toString())));
        JsonObject data = new JsonObject();
        data.add("erasedRequests", AiJson.GSON.toJsonTree(tombstones));
        Event barrier = new Event(revision() + 1, UUID.randomUUID(), null, System.currentTimeMillis(),
                "privacy_erased", null, data, null);
        String payload = AiJson.GSON.toJson(List.of(barrier));
        if (payload.length() > 8 * 1024 * 1024) throw new IOException("REPLAY_INDEX_REQUIRES_MAINTENANCE");
        AiJson.write(journal, new Batch(1, payload, AiJson.digest(payload)));
        events.clear();
        tasks.clear();
        latestVisibleByTask.clear(); latestAssistantByTask.clear();
        readableArtifacts.clear();
        latestByKind.clear(); regionSequence = 0;
        baseSequence = barrier.seq - 1;
        apply(barrier);
        return revision();
    }

    public synchronized List<Event> search(String query, int limit) {
        String needle = query.toLowerCase(Locale.ROOT);
        return events.stream().filter(e -> e.message != null && (e.message.text()
                + " " + e.taskId + " " + AiJson.GSON.toJson(e.message.calls()))
                .toLowerCase(Locale.ROOT).contains(needle)).limit(Math.clamp(limit, 1, 50)).toList();
    }

    public record SearchHit(long seq, UUID messageId, UUID taskId, String kind, String text, String artifactId) {}
    /** Searches the complete durable tool result, not only the bounded model-facing excerpt. */
    public synchronized List<SearchHit> searchComplete(String query, long beforeSeq, int limit) {
        if (query == null || query.length() > 160 || beforeSeq < 0) throw new IllegalArgumentException("SEARCH_QUERY");
        String needle = query.toLowerCase(Locale.ROOT);
        List<SearchHit> result = new ArrayList<>();
        Set<String> seenToolResults = new HashSet<>();
        for (int i = events.size() - 1; i >= 0 && result.size() < Math.clamp(limit, 1, 30); i--) {
            Event event = events.get(i);
            if (event.seq() >= beforeSeq) continue;
            String text = "";
            if ("tool_result".equals(event.kind()) && event.data().has("fullResult")) text = event.data().get("fullResult").getAsString();
            else if (event.message() != null) text = event.message().text() + (event.message().calls().isEmpty() ? "" : "\n" + AiJson.GSON.toJson(event.message().calls()));
            else if ("question".equals(event.kind())) text = event.data().get("prompt").getAsString();
            else if ("question_answer".equals(event.kind())) text = event.data().get("answer").getAsString();
            else if ("task_notice".equals(event.kind())) text = event.data().get("text").getAsString();
            if (text.isEmpty() || !(text + " " + event.taskId()).toLowerCase(Locale.ROOT).contains(needle)) continue;
            if ("tool_result".equals(event.kind()) || event.message() != null && "tool".equals(event.message().role())) {
                if (!seenToolResults.add(event.taskId() + ":" + event.toolTransactionId())) continue;
            }
            result.add(new SearchHit(event.seq(), event.messageId(), event.taskId(), event.kind(), text,
                    event.data() != null && event.data().has("artifactId") ? event.data().get("artifactId").getAsString() : ""));
        }
        return List.copyOf(result);
    }
}
