package firefly520.fireflymc.ai.workspace;

import com.google.gson.*;
import firefly520.fireflymc.ai.blueprint.*;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded references to this task's actual journal positions, never pathfinding or current-world proof. */
public final class TaskCoordinateRoutes {
    private TaskCoordinateRoutes() {}
    public static JsonObject view(Workspace workspace, Actor actor, UUID taskId) {
        actor.requireAccess(workspace.owner());
        synchronized (workspace.conversation()) {
            var task = workspace.conversation().task(taskId);
            if (task == null || !task.owner().equals(actor.owner())) throw new SecurityException("MAP_TASK_NOT_FOUND");
            var references = new Samples();
            for (var step : new BuildJournal(workspace, UUID.randomUUID()).list(actor)) if (step.taskId().equals(taskId))
                for (var cell : step.cells()) if (cell.target() != null)
                    references.add(point(taskId, step.operationId(), cell.position(), step.dimension(), "放置引用 · " + step.state(), step.version()));
            for (var step : new RemovalJournal(workspace, UUID.randomUUID()).list(actor)) if (step.input().taskId().equals(taskId))
                for (var cell : step.input().cells()) if (cell.target() != null)
                    references.add(point(taskId, step.input().operationId(), cell.position(), step.input().dimension(), "采集/拆除引用 · " + step.state(), step.revision()));
            var route = new JsonObject();
            route.addProperty("id", UUID.nameUUIDFromBytes((workspace.metadata().serverInstanceId() + ":" + workspace.primaryConversationId()
                    + ":" + workspace.conversation().privacyEpoch() + ":" + taskId + ":map-route").getBytes(StandardCharsets.UTF_8)).toString());
            route.addProperty("taskId", taskId.toString()); route.addProperty("revision", workspace.conversation().revision()); route.add("points", references.points());
            var data = new JsonObject(); data.addProperty("version", 1); data.add("route", route);
            data.addProperty("referenceCount", references.count); data.addProperty("sampled", references.count > 32);
            data.addProperty("worldVerified", false); data.addProperty("taskId", taskId.toString());
            data.addProperty("note", "本人任务日志的坐标引用；最多前16/后16点，非寻路、通行或当前方块证明。未执行计划及其他机制没有坐标时保留空路线，可自行选点。");
            return data;
        }
    }
    private static JsonObject point(UUID task, UUID step, BlueprintDocument.Position position, String dimension, String label, long revision) {
        var data = new JsonObject(); data.addProperty("id", UUID.nameUUIDFromBytes((step + ":" + position).getBytes(StandardCharsets.UTF_8)).toString());
        data.addProperty("taskId", task.toString()); data.addProperty("label", label); data.addProperty("dimension", dimension);
        data.addProperty("x", position.x()); data.addProperty("y", position.y()); data.addProperty("z", position.z());
        data.addProperty("source", "SERVER_TASK_REFERENCE"); data.addProperty("sourceVersion", "journal-revision-" + revision); return data;
    }
    private static final class Samples {
        final List<JsonObject> first = new ArrayList<>(); final ArrayDeque<JsonObject> last = new ArrayDeque<>(); long count;
        void add(JsonObject point) {
            count++;
            if (first.size() < 16) first.add(point);
            else { if (last.size() == 16) last.removeFirst(); last.addLast(point); }
        }
        JsonArray points() { var points = new JsonArray(); first.forEach(points::add); last.forEach(points::add); return points; }
    }
}
