package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.conversation.ConversationStore;
import java.util.*;

/** Private compact workbench projection; raw tool results and native arguments stay in the complete journal. */
public final class BuildProgressProjection {
    public record Progress(UUID planId, UUID taskId, int plannedOperations, int verifiedOperations,
                           int unsettledOperations, boolean recoveryRequired) {}
    private static final class Plan {
        final UUID id, task;
        final int planned;
        final Map<UUID, BuildJournal.State> steps = new HashMap<>();
        Plan(UUID id, UUID task, int planned) { this.id = id; this.task = task; this.planned = planned; }
        Progress view() {
            return new Progress(id, task, planned, (int) steps.values().stream().filter(state -> state == BuildJournal.State.VERIFIED).count(),
                    (int) steps.values().stream().filter(state -> state != BuildJournal.State.VERIFIED && state != BuildJournal.State.REJECTED).count(),
                    steps.containsValue(BuildJournal.State.RECOVERY_REQUIRED));
        }
    }
    private final String owner;
    private final LinkedHashMap<UUID, Plan> byTask = new LinkedHashMap<>();
    private long seen, privacyEpoch = -1;
    public BuildProgressProjection(String owner) { UUID.fromString(owner); this.owner = owner; }
    public synchronized Map<UUID, Progress> view(ConversationStore conversation) {
        var snapshot = conversation.getSnapshot(seen);
        long epoch = conversation.privacyEpoch();
        if (epoch != privacyEpoch) {
            byTask.clear(); seen = epoch; privacyEpoch = epoch; snapshot = conversation.getSnapshot(seen);
        }
        for (var event : snapshot.events()) {
            if (event.taskId() == null) continue;
            if (event.kind().equals("construction_plan")) {
                var data = event.data(); UUID id = UUID.fromString(data.get("planId").getAsString());
                int count = data.get("placements").getAsInt();
                if (count < 1 || count > 512) throw new IllegalStateException("BUILD_PROGRESS_INVALID");
                byTask.remove(event.taskId());
                byTask.put(event.taskId(), new Plan(id, event.taskId(), count));
                while (byTask.size() > 512) byTask.remove(byTask.keySet().iterator().next());
            } else if (event.kind().equals("build_step")) {
                var data = event.data();
                if (!owner.equals(data.get("owner").getAsString()) || !event.taskId().toString().equals(data.get("taskId").getAsString()))
                    throw new SecurityException("BUILD_PROGRESS_OWNER_MISMATCH");
                var plan = byTask.get(event.taskId());
                if (plan == null || !plan.id.toString().equals(data.get("planId").getAsString())) continue;
                UUID id = UUID.fromString(data.get("operationId").getAsString());
                if (!plan.steps.containsKey(id) && plan.steps.size() >= plan.planned) throw new IllegalStateException("BUILD_PROGRESS_STEP_LIMIT");
                plan.steps.put(id, BuildJournal.State.valueOf(data.get("state").getAsString()));
            }
        }
        seen = snapshot.revision();
        var result = new LinkedHashMap<UUID, Progress>(); byTask.forEach((task, plan) -> result.put(task, plan.view()));
        return Map.copyOf(result);
    }
}
