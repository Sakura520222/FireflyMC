package firefly520.fireflymc.ai.integration.exploration;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.util.*;

/** Fresh approval for a positively saved known terminal outcome. No search, refund or unknown-effect override. */
public record CompassReconciliation(int version, UUID id, UUID operationId, String owner, UUID taskId,
        UUID serverInstanceId, UUID conversationId, long privacyEpoch, UUID executionEpoch,
        CompassSearchJournal.Operation evidence, CompassPreparationJournal.Operation preparation, String hash) {
    public CompassReconciliation(int version, UUID id, UUID operationId, String owner, UUID taskId,
            UUID serverInstanceId, UUID conversationId, long privacyEpoch, UUID executionEpoch,
            CompassSearchJournal.Operation evidence, String hash) {
        this(version, id, operationId, owner, taskId, serverInstanceId, conversationId, privacyEpoch, executionEpoch, evidence, null, hash);
    }
    public CompassReconciliation {
        Objects.requireNonNull(id); Objects.requireNonNull(operationId); UUID.fromString(owner); Objects.requireNonNull(taskId);
        Objects.requireNonNull(serverInstanceId); Objects.requireNonNull(conversationId); Objects.requireNonNull(executionEpoch); Objects.requireNonNull(evidence);
        if (version != 1 || privacyEpoch < 0 || id.equals(operationId) || !operationId.equals(evidence.id()) || !owner.equals(evidence.owner()) || !taskId.equals(evidence.taskId())
                || evidence.state() != CompassSearchJournal.State.RECOVERY_REQUIRED || !evidence.terminalKnown() || !evidence.saved()
                || preparation != null && (!preparation.id().equals(operationId) || !preparation.owner().equals(owner) || !preparation.taskId().equals(taskId)
                || !preparation.admissionClosed() || preparation.candidates().stream().anyMatch(candidate -> !candidate.settled() && (!candidate.nativeReady() || candidate.saveProof().isEmpty())))
                || hash == null || !hash.isEmpty() && !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("COMPASS_RECONCILIATION_INVALID");
    }
    public long operationRevision() { return evidence.revision(); }
    public CompassReconciliation signed() { return withHash(AiJson.digest(AiJson.canonical(withHash("")))); }
    public boolean intact() { return hash.equals(AiJson.digest(AiJson.canonical(withHash("")))); }
    private CompassReconciliation withHash(String value) {
        return new CompassReconciliation(version, id, operationId, owner, taskId, serverInstanceId, conversationId, privacyEpoch, executionEpoch, evidence, preparation, value);
    }
    public void requireIdentity(Workspace workspace, Actor actor, UUID currentEpoch) {
        actor.requireAccess(owner); actor.requireAccess(workspace.owner());
        if (!intact() || !serverInstanceId.equals(workspace.metadata().serverInstanceId()) || !conversationId.equals(workspace.primaryConversationId())
                || privacyEpoch != workspace.conversation().privacyEpoch() || !executionEpoch.equals(currentEpoch))
            throw new SecurityException("COMPASS_RECONCILIATION_IDENTITY_CHANGED");
    }
    public void requireCurrent(CompassSearchJournal.Operation actual) {
        if (!evidence.equals(actual)) throw new IllegalStateException("COMPASS_RECONCILIATION_VERSION_CHANGED");
        if (actual.state() != CompassSearchJournal.State.RECOVERY_REQUIRED || !actual.terminalKnown() || !actual.saved())
            throw new IllegalStateException("COMPASS_POSITIVE_TERMINAL_PROOF_REQUIRED");
    }
}
