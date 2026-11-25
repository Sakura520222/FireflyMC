package firefly520.fireflymc.ai.task;

import firefly520.fireflymc.ai.conversation.ConversationStore;
import java.util.*;

/** Gameplay work is evidenced by accepted planning/effect tools, not by every chat input. */
public final class WorkTaskIndex {
    private WorkTaskIndex() { }
    public static Set<UUID> from(ConversationStore.Snapshot snapshot) {
        Set<UUID> result = new HashSet<>();
        for (var task : snapshot.tasks()) {
            if (task.state() == AgentTask.State.WAITING_APPROVAL || task.state() == AgentTask.State.RECOVERY_REQUIRED
                    || task.pending().stream().anyMatch(call -> plans(call.name()))) result.add(task.id());
        }
        for (var event : snapshot.events()) {
            if (event.taskId() == null || event.data() == null || !event.kind().equals("tool_start")) continue;
            var data = event.data();
            String name = data.has("name") ? data.get("name").getAsString() : "";
            String risk = data.has("risk") ? data.get("risk").getAsString() : "";
            if (plans(name) || risk.equals("PLAYER_STATE") || risk.equals("WORLD_CHANGE")) result.add(event.taskId());
        }
        return Set.copyOf(result);
    }
    private static boolean plans(String name) {
        return name.endsWith("_plan") || Set.of("blueprint_generate", "blueprint_import", "blueprint_transform").contains(name);
    }
}
