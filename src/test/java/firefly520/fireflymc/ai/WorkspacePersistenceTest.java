package firefly520.fireflymc.ai;

import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.memory.MemoryStore;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkspacePersistenceTest {
    @TempDir Path directory;
    private Actor player(UUID id) { return Actor.player(id, true, true, 0); }

    @Test void reconnectAndRestartPreserveIdentityHistoryAndSeparateMemory() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), conversation, server;
        try (var manager = new WorkspaceManager(directory)) {
            var first = manager.getWorkspace(player(a));
            conversation = first.primaryConversationId();
            server = manager.serverInstanceId();
            first.conversation().append(List.of(Draft.message(null, ModelMessage.text("user", "A-private"), null)));
            new MemoryStore(first).remember(player(a), "A-memory", "player");
            var second = manager.getWorkspace(player(b));
            assertNotEquals(conversation, second.primaryConversationId());
            assertTrue(second.conversation().getSnapshot(0).events().isEmpty());
            assertTrue(new MemoryStore(second).view(player(b)).isEmpty());
            assertThrows(SecurityException.class, () -> new MemoryStore(first).view(player(b)));
            assertThrows(SecurityException.class, () -> manager.getWorkspace(Actor.player(a, false, true, 4)));
            assertThrows(Exception.class, () -> new WorkspaceManager(directory));
        }
        try (var manager = new WorkspaceManager(directory)) {
            var first = manager.getWorkspace(player(a));
            assertEquals(server, manager.serverInstanceId());
            assertEquals(conversation, first.primaryConversationId());
            assertEquals("A-private", first.conversation().getSnapshot(0).events().getFirst().message().text());
            assertEquals("A-memory", new MemoryStore(first).view(player(a)).getFirst().text());
            new MemoryStore(first).clear(player(a));
            assertEquals(1, first.conversation().getSnapshot(0).events().size());
            assertThrows(IllegalArgumentException.class, () -> new Actor("console", true, true, 4));
        }
        try (var other = new WorkspaceManager(directory.resolve("other-world"))) {
            assertNotEquals(server, other.serverInstanceId());
            assertNotEquals(conversation, other.getWorkspace(player(a)).primaryConversationId());
        }
    }

    @Test void optimisticRevisionAllowsOnlyOneConcurrentWriterAndPublishesBatchTogether() throws Exception {
        var store = new ConversationStore(directory.resolve("timeline.jsonl"));
        try (var workers = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
            List<Future<Boolean>> outcomes = new ArrayList<>();
            for (int i = 0; i < 2; i++) outcomes.add(workers.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    store.appendConversationEvent(List.of(Draft.message(null, ModelMessage.text("user", "one"), null),
                            Draft.message(null, ModelMessage.text("assistant", "two"), null)), 0);
                    return true;
                } catch (ConcurrentModificationException expected) { return false; }
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            assertNotEquals(outcomes.get(0).get(), outcomes.get(1).get());
        }
        var reopened = new ConversationStore(directory.resolve("timeline.jsonl"));
        assertEquals(List.of(1L, 2L), reopened.getSnapshot(0).events().stream().map(ConversationStore.Event::seq).toList());
        assertEquals(1, reopened.getSnapshot(1).events().size());
    }

    @Test void incompleteTailIsPreservedButCommittedCorruptionNeverSilentlyResetsHistory() throws Exception {
        Path path = directory.resolve("timeline.jsonl");
        var store = new ConversationStore(path);
        store.append(List.of(Draft.message(null, ModelMessage.text("user", "durable"), null)));
        Files.writeString(path, "{\"partial", StandardOpenOption.APPEND);
        var reopened = new ConversationStore(path);
        assertEquals(1, reopened.revision());
        try (var files = Files.list(directory)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("incomplete-")));
        }
        Files.writeString(path, "{}\n", StandardOpenOption.APPEND);
        assertThrows(java.io.IOException.class, () -> new ConversationStore(path));
    }
}
