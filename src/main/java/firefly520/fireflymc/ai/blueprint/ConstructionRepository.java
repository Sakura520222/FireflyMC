package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Immutable private artifacts indexed in the existing permanent conversation. */
public final class ConstructionRepository {
    private final Workspace workspace;
    public ConstructionRepository(Workspace workspace) { this.workspace = workspace; }
    public void save(Actor actor, ConstructionPlan plan) throws IOException {
        synchronized (workspace.conversation()) {
            validate(actor, plan);
            if (workspace.conversation().task(plan.taskId()) == null || workspace.conversation().task(plan.taskId()).state().terminal())
                throw new IllegalStateException("BUILD_TASK_ENDED");
            String data = AiJson.GSON.toJson(plan);
            if (data.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8 * 1024 * 1024) throw new IllegalArgumentException("BUILD_PLAN_SIZE_LIMIT");
            Path path = workspace.artifact(plan.id());
            if (Files.exists(path)) throw new IllegalStateException("BUILD_PLAN_ALREADY_EXISTS");
            AiJson.write(path, plan);
            var index = new com.google.gson.JsonObject(); index.addProperty("planId", plan.id().toString()); index.addProperty("hash", plan.hash());
            index.addProperty("blueprintId", plan.blueprintId().toString()); index.addProperty("blueprintRevision", plan.blueprintRevision());
            index.addProperty("placements", plan.placements().size());
            workspace.conversation().append(List.of(new Draft(plan.taskId(), "construction_plan", null, index, null)));
        }
    }
    public ConstructionPlan load(Actor actor, UUID id, String hash) throws IOException {
        synchronized (workspace.conversation()) {
            actor.requireAccess(workspace.owner());
            boolean indexed = workspace.conversation().getSnapshot(workspace.conversation().privacyEpoch()).events().stream()
                    .anyMatch(event -> event.kind().equals("construction_plan") && id.toString().equals(event.data().get("planId").getAsString())
                            && hash.equals(event.data().get("hash").getAsString()));
            Path path = workspace.artifact(id);
            if (!indexed || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 8 * 1024 * 1024)
                throw new IllegalArgumentException("BUILD_PLAN_NOT_FOUND");
            var plan = AiJson.GSON.fromJson(Files.readString(path), ConstructionPlan.class);
            validate(actor, plan);
            if (!plan.id().equals(id) || !plan.hash().equals(hash)) throw new SecurityException("BUILD_PLAN_CHANGED");
            return plan;
        }
    }
    private void validate(Actor actor, ConstructionPlan plan) {
        actor.requireAccess(workspace.owner());
        if (!plan.owner().equals(actor.owner()) || !plan.serverInstanceId().equals(workspace.metadata().serverInstanceId())
                || !plan.conversationId().equals(workspace.primaryConversationId()) || !plan.intact())
            throw new SecurityException("BUILD_PLAN_IDENTITY_OR_HASH_CHANGED");
    }
}
