package firefly520.fireflymc.ai.integration.maid;

import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Prepared, validated artifact identities. Runtime ACK never takes a journal monitor or reads a file. */
final class MaidRecoveryIndex {
    private record Identity(UUID task, UUID operation, String hash) {}
    private final String owner;
    private final UUID server, conversation;
    private final long epoch;
    private final MaidControlJournal journal;
    private final Map<UUID, Identity> modes = new ConcurrentHashMap<>(), reviews = new ConcurrentHashMap<>();
    private final Map<String, Identity> reviewHashes = new ConcurrentHashMap<>();
    private final Set<String> accepted = ConcurrentHashMap.newKeySet();

    MaidRecoveryIndex(Workspace workspace, MaidControlJournal journal) {
        owner = workspace.owner(); server = workspace.metadata().serverInstanceId(); conversation = workspace.primaryConversationId();
        epoch = workspace.conversation().privacyEpoch(); this.journal = journal;
    }
    void mode(MaidControlPlan plan) {
        requireScope(plan.owner(), plan.serverInstanceId(), plan.conversationId(), plan.privacyEpoch());
        if (!plan.intact()) throw new SecurityException("MAID_CONTROL_PLAN_CHANGED");
        put(modes, plan.id(), new Identity(plan.taskId(), plan.id(), plan.hash()));
    }
    void review(MaidControlReconcile plan) {
        requireScope(plan.owner(), plan.serverInstanceId(), plan.conversationId(), plan.privacyEpoch());
        if (!plan.intact()) throw new SecurityException("MAID_RECONCILE_IDENTITY_CHANGED");
        put(reviews, plan.id(), new Identity(plan.taskId(), plan.operationId(), plan.hash()));
        var identity = new Identity(plan.taskId(), plan.operationId(), plan.hash());
        var old = reviewHashes.putIfAbsent(plan.hash(), identity);
        if (old != null && !old.equals(identity)) throw new SecurityException("MAID_RECOVERY_ARTIFACT_IDENTITY_CHANGED");
    }
    void accepted(UUID task, UUID operation, String hash) {
        var identity = reviewHashes.get(hash);
        if (identity != null && identity.task().equals(task) && identity.operation().equals(operation))
            accepted.add(hash);
    }
    Optional<Boolean> settledCall(Workspace workspace, Actor actor, UUID task, ModelMessage.ToolCall call) {
        if (!Set.of("maid_control_execute", "maid_control_reconcile").contains(call.name())) return Optional.empty();
        try {
            actor.requireAccess(owner); actor.requireAccess(workspace.owner());
            requireScope(workspace.owner(), workspace.metadata().serverInstanceId(), workspace.primaryConversationId(), workspace.conversation().privacyEpoch());
            var args = AiJson.object(call.arguments(), 65536, 16);
            boolean mode = call.name().equals("maid_control_execute");
            if (!args.keySet().equals(mode ? Set.of("planId", "planHash") : Set.of("reviewId", "reviewHash"))) return Optional.of(false);
            UUID id = UUID.fromString(args.get(mode ? "planId" : "reviewId").getAsString());
            String hash = args.get(mode ? "planHash" : "reviewHash").getAsString();
            if (!hash.matches("[0-9a-f]{64}")) return Optional.of(false);
            var identity = (mode ? modes : reviews).get(id);
            if (identity == null || !identity.task().equals(task) || !identity.hash().equals(hash) || !mode && !accepted.contains(hash)) return Optional.of(false);
            var original = modes.get(identity.operation());
            var operation = journal.publishedOperation(actor, identity.operation());
            return Optional.of(original != null && operation != null && operation.taskId().equals(task) && original.task().equals(task)
                    && original.hash().equals(operation.planHash()) && journal.operationSettled(actor, identity.operation()));
        } catch (RuntimeException invalid) { return Optional.of(false); }
    }
    private void requireScope(String owner, UUID server, UUID conversation, long epoch) {
        if (!this.owner.equals(owner) || !this.server.equals(server) || !this.conversation.equals(conversation) || this.epoch != epoch)
            throw new SecurityException("MAID_RECOVERY_SCOPE_CHANGED");
    }
    private static void put(Map<UUID, Identity> index, UUID id, Identity identity) {
        var existing = index.putIfAbsent(id, identity);
        if (existing != null && !existing.equals(identity)) throw new SecurityException("MAID_RECOVERY_ARTIFACT_IDENTITY_CHANGED");
    }
}
