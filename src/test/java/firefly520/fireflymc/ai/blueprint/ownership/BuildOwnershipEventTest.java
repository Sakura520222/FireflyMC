package firefly520.fireflymc.ai.blueprint.ownership;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BuildOwnershipEventTest {
    @TempDir Path directory;
    private final Actor actor = Actor.player(UUID.randomUUID(), true, true, 0);
    private BuildOwnershipEvent event(WorkspaceManager.Workspace workspace, long privacy) {
        UUID runtime = UUID.randomUUID(), sourceTask = UUID.randomUUID(), sourcePlan = UUID.randomUUID(), action = UUID.randomUUID();
        var token = new OwnershipToken(1, runtime, UUID.randomUUID(), "minecraft:overworld", 0L, 1L, UUID.randomUUID(),
                new OwnershipToken.Owner(workspace.metadata().serverInstanceId(), UUID.fromString(actor.owner()), sourceTask, sourcePlan, action, "a".repeat(64)), "air", "stone");
        return new BuildOwnershipEvent(1, runtime, workspace.metadata().serverInstanceId(), workspace.primaryConversationId(), actor.owner(), sourceTask, sourcePlan,
                "a".repeat(64), "minecraft:overworld", sourceTask, action, "PLACE", privacy, List.of(token), List.of(), "");
    }
    @Test void tokensAndReasonPersistAsAnIndependentPrimaryConversationEvent() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor); var record = event(workspace, workspace.conversation().privacyEpoch());
            record.append(workspace, actor);
            var stored = workspace.conversation().getSnapshot(0).events();
            assertEquals(1, stored.size()); assertEquals("build_ownership", stored.getFirst().kind());
            assertEquals(record.actionId().toString(), stored.getFirst().data().get("actionId").getAsString());
            assertEquals(1, stored.getFirst().data().getAsJsonArray("tokens").size());
            assertNull(stored.getFirst().message());
            assertThrows(SecurityException.class, () -> workspace.conversation().readableEvent(stored.getFirst().seq()));
        }
        try (var reopened = new WorkspaceManager(directory)) {
            assertEquals("build_ownership", reopened.getWorkspace(actor).conversation().getSnapshot(0).events().getFirst().kind());
        }
    }
    @Test void changedPrivacyOrAnotherPrimaryConversationCannotReceiveTheOldProof() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor); var record = event(workspace, workspace.conversation().privacyEpoch() + 1);
            assertThrows(IllegalStateException.class, () -> record.append(workspace, actor));
            var other = manager.getWorkspace(Actor.player(UUID.randomUUID(), true, true, 0));
            assertThrows(SecurityException.class, () -> event(workspace, workspace.conversation().privacyEpoch()).append(other, actor));
            assertTrue(workspace.conversation().getSnapshot(0).events().isEmpty());
        }
    }
    @Test void anOwnershipFailureRemainsAnExplicitReasonWithoutChangingItsNativeProof() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor); var record = event(workspace, workspace.conversation().privacyEpoch());
            var unavailable = record.withReason("OWNERSHIP_REAL_EVIDENCE_RECORDING_UNAVAILABLE"); unavailable.append(workspace, actor);
            assertEquals(record.tokens(), unavailable.tokens()); assertEquals(record.actionId(), unavailable.actionId());
            assertEquals("OWNERSHIP_REAL_EVIDENCE_RECORDING_UNAVAILABLE", workspace.conversation().getSnapshot(0).events().getFirst().data().get("reason").getAsString());
        }
    }
}
