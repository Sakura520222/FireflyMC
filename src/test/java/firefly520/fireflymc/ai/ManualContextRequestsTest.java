package firefly520.fireflymc.ai;

import firefly520.fireflymc.ai.context.ManualContextRequests;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.workspace.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ManualContextRequestsTest {
    @TempDir Path directory;
    private final Actor owner = Actor.player(UUID.randomUUID(), true, true, 0);

    @Test void onePendingRequestDeduplicatesNewClicksAndTerminalRetryNeverAppendsAgain() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var first = ManualContextRequests.enqueue(workspace, owner, UUID.randomUUID());
            long revision = workspace.conversation().revision();
            assertEquals(first, ManualContextRequests.enqueue(workspace, owner, UUID.randomUUID()));
            assertEquals(revision, workspace.conversation().revision());
            assertEquals(first, ManualContextRequests.pending(workspace, owner).orElseThrow());
            ManualContextRequests.complete(workspace, owner, first, "COMPACTED", 1);
            long finished = workspace.conversation().revision();
            ManualContextRequests.complete(workspace, owner, first, "COMPACTED", 1);
            assertEquals(first, ManualContextRequests.enqueue(workspace, owner, first.requestId()));
            assertEquals(finished, workspace.conversation().revision()); assertTrue(ManualContextRequests.pending(workspace, owner).isEmpty());
            assertThrows(SecurityException.class, () -> ManualContextRequests.complete(workspace, owner, first, "NO_CLOSED_HISTORY", 1));
            var next = ManualContextRequests.enqueue(workspace, owner, UUID.randomUUID()); assertNotEquals(first.requestId(), next.requestId());
        }
    }
    @Test void anotherPlayerOrUnauthenticatedActorCannotReadOrCompleteThePrivateRequest() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var request = ManualContextRequests.enqueue(workspace, owner, UUID.randomUUID());
            var other = Actor.player(UUID.randomUUID(), true, true, 0); var otherWorkspace = manager.getWorkspace(other);
            assertThrows(SecurityException.class, () -> ManualContextRequests.pending(workspace, other));
            assertThrows(SecurityException.class, () -> ManualContextRequests.complete(workspace, other, request, "COMPACTED", 1));
            assertThrows(SecurityException.class, () -> ManualContextRequests.complete(otherWorkspace, other, request, "COMPACTED", 1));
            var unauthenticated = Actor.player(UUID.fromString(owner.owner()), false, true, 0);
            assertThrows(SecurityException.class, () -> ManualContextRequests.enqueue(workspace, unauthenticated, UUID.randomUUID()));
            assertThrows(SecurityException.class, () -> ManualContextRequests.pending(workspace, unauthenticated));
            assertEquals(request, ManualContextRequests.pending(workspace, owner).orElseThrow());
        }
    }
    @Test void privacyErasureFencesOldCompletionAndDoesNotRestoreADeletedPendingRequest() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var request = ManualContextRequests.enqueue(workspace, owner, UUID.randomUUID());
            var erasure = new WorkspacePrivacyService(System::currentTimeMillis, ignored -> true);
            var preview = erasure.preview(owner, workspace); erasure.confirm(owner, workspace, preview.token());
            assertTrue(ManualContextRequests.pending(workspace, owner).isEmpty());
            assertThrows(SecurityException.class, () -> ManualContextRequests.complete(workspace, owner, request, "COMPACTED", 1));
            var fresh = ManualContextRequests.enqueue(workspace, owner, UUID.randomUUID());
            assertTrue(fresh.privacyEpoch() > request.privacyEpoch()); assertNotEquals(fresh.requestId(), request.requestId());
        }
    }
    @Test void reopeningTheSameWorkspaceRestoresOnlyItsPendingRequestWithoutStartingASecondTask() throws Exception {
        ManualContextRequests.Request request; UUID conversation; long revision;
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); request = ManualContextRequests.enqueue(workspace, owner, UUID.randomUUID());
            conversation = workspace.primaryConversationId(); revision = workspace.conversation().revision();
            assertTrue(workspace.conversation().tasks().isEmpty());
        }
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner);
            assertEquals(conversation, workspace.primaryConversationId()); assertEquals(request, ManualContextRequests.pending(workspace, owner).orElseThrow());
            assertEquals(request, ManualContextRequests.enqueue(workspace, owner, UUID.randomUUID()));
            assertEquals(revision, workspace.conversation().revision()); assertTrue(workspace.conversation().tasks().isEmpty());
            ManualContextRequests.complete(workspace, owner, request, "NO_CLOSED_HISTORY", 0);
            assertTrue(ManualContextRequests.pending(workspace, owner).isEmpty());
        }
    }
}
