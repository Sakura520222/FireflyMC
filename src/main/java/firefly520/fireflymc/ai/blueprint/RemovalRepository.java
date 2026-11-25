package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Private immutable review artifacts in the existing permanent conversation. */
public final class RemovalRepository {
    private final Workspace workspace;
    private final long privacyEpoch;
    public RemovalRepository(Workspace workspace) { this.workspace = workspace; privacyEpoch = workspace.conversation().privacyEpoch(); }
    public void save(Actor actor, RemovalPlan plan) throws IOException {
        synchronized (workspace.conversation()) {
            validate(actor, plan);
            var task = workspace.conversation().task(plan.taskId());
            if (task == null || task.state().terminal()) throw new IllegalStateException("REMOVAL_TASK_ENDED");
            if (AiJson.GSON.toJson(plan).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8 * 1024 * 1024)
                throw new IllegalArgumentException("REMOVAL_PLAN_SIZE_LIMIT");
            Path path = workspace.artifact(plan.id()); if (Files.exists(path)) throw new IllegalStateException("REMOVAL_PLAN_ALREADY_EXISTS");
            AiJson.write(path, plan);
            var index = new com.google.gson.JsonObject(); index.addProperty("planId", plan.id().toString()); index.addProperty("hash", plan.hash());
            index.addProperty("mode", plan.mode().name()); index.addProperty("steps", plan.steps().size());
            workspace.conversation().append(List.of(new Draft(plan.taskId(), "removal_plan", null, index, plan.id().toString())));
        }
    }
    public RemovalPlan load(Actor actor, UUID id, String hash) throws IOException {
        synchronized (workspace.conversation()) {
            access(actor);
            boolean indexed = workspace.conversation().getSnapshot(privacyEpoch).events().stream().anyMatch(event -> event.kind().equals("removal_plan")
                    && id.toString().equals(event.data().get("planId").getAsString()) && hash.equals(event.data().get("hash").getAsString()));
            Path path = workspace.artifact(id);
            if (!indexed || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 8 * 1024 * 1024) throw new IllegalArgumentException("REMOVAL_PLAN_NOT_FOUND");
            var plan = AiJson.GSON.fromJson(Files.readString(path), RemovalPlan.class); validate(actor, plan);
            if (!plan.id().equals(id) || !plan.hash().equals(hash)) throw new SecurityException("REMOVAL_PLAN_CHANGED");
            return plan;
        }
    }
    private void validate(Actor actor, RemovalPlan plan) {
        access(actor);
        if (!plan.owner().equals(actor.owner()) || !plan.serverInstanceId().equals(workspace.metadata().serverInstanceId())
                || !plan.conversationId().equals(workspace.primaryConversationId()) || !plan.intact()) throw new SecurityException("REMOVAL_PLAN_IDENTITY_OR_HASH_CHANGED");
    }
    private void access(Actor actor) {
        actor.requireAccess(workspace.owner()); if (workspace.conversation().privacyEpoch() != privacyEpoch) throw new IllegalStateException("WORKSPACE_PRIVACY_EPOCH_CHANGED");
    }
}
