package firefly520.fireflymc.ai;

import firefly520.fireflymc.ai.blueprint.*;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.material.MaterialStack;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.AgentTask;
import firefly520.fireflymc.ai.workspace.*;
import firefly520.fireflymc.client.ai.integration.map.MapRoute;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Journal-coordinate projection, not evidence of a placed block or a traversable path. */
class TaskCoordinateRoutesTest {
    @TempDir Path directory;
    final Actor owner = Actor.player(UUID.randomUUID(), true, true, 0), other = Actor.player(UUID.randomUUID(), true, true, 4);
    @Test void taskRouteIsBoundedAndRetainsFirstAndLastReferencesWithoutClaimingWorldState() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var task = AgentTask.create(owner.owner(), UUID.randomUUID(), "bounded route");
            workspace.conversation().append(List.of(Draft.checkpoint(task)));
            var journal = new BuildJournal(workspace, UUID.randomUUID());
            var air = new BlueprintDocument.State("minecraft:air", Map.of()); var stone = new BlueprintDocument.State("minecraft:stone", Map.of());
            for (int x = 0; x < 80; x++) journal.prepare(owner, UUID.randomUUID(), task.id(), UUID.randomUUID(), AiJson.digest("plan"), UUID.randomUUID(), 0,
                    "minecraft:overworld", List.of(new BuildJournal.Cell(new BlueprintDocument.Position(x, 64, 0), air, stone)),
                    new MaterialStack("{\"id\":\"minecraft:stone\",\"count\":1}", 1));
            long revision = workspace.conversation().revision();
            var data = TaskCoordinateRoutes.view(workspace, owner, task.id());
            assertFalse(data.get("worldVerified").getAsBoolean()); assertTrue(data.get("sampled").getAsBoolean()); assertEquals(80, data.get("referenceCount").getAsInt());
            var route = AiJson.GSON.fromJson(data.get("route"), MapRoute.class);
            assertEquals(32, route.points().size()); assertEquals(task.id(), route.taskId());
            assertEquals(0, route.points().getFirst().x()); assertEquals(15, route.points().get(15).x());
            assertEquals(64, route.points().get(16).x()); assertEquals(79, route.points().getLast().x());
            assertTrue(route.points().stream().allMatch(point -> point.taskId().equals(task.id()) && point.source().name().equals("SERVER_TASK_REFERENCE")));
            assertEquals(revision, workspace.conversation().revision(), "viewing a route does not alter any journal");
            assertEquals(data, TaskCoordinateRoutes.view(workspace, owner, task.id()));
        }
    }
    @Test void otherOwnersUnknownTasksAndNewEpochsCannotReuseTheOldRoute() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(owner); var foreign = manager.getWorkspace(other);
            var task = AgentTask.create(owner.owner(), UUID.randomUUID(), "private route"); workspace.conversation().append(List.of(Draft.checkpoint(task)));
            assertThrows(SecurityException.class, () -> TaskCoordinateRoutes.view(workspace, other, task.id()));
            assertThrows(SecurityException.class, () -> TaskCoordinateRoutes.view(foreign, other, task.id()));
            var empty = TaskCoordinateRoutes.view(workspace, owner, task.id()); assertTrue(empty.getAsJsonObject("route").getAsJsonArray("points").isEmpty());
            workspace.conversation().append(List.of(Draft.checkpoint(task.update(AgentTask.State.CANCELLED, "explicit fixture erasure after task ends"))));
            workspace.conversation().erase(workspace.conversation().revision());
            assertThrows(SecurityException.class, () -> TaskCoordinateRoutes.view(workspace, owner, task.id()));
        }
    }
}
