package firefly520.fireflymc.ai.integration.maid.transport;

import firefly520.fireflymc.ai.material.MaterialStack;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.util.*;

/** A separate one-use approval to return one exact portion of verified real custody. */
public record MaidTransportReturn(UUID id, String owner, UUID taskId, UUID serverInstanceId, UUID conversationId,
        long privacyEpoch, UUID executionEpoch, long expiresAt, UUID operationId, long operationRevision,
        String operationDigest, String previousNativeProof, int custodyIndex, MaterialStack product,
        String destinationWitness, List<String> destinationPools, List<String> destinationBackends, String hash) {
    public MaidTransportReturn {
        destinationPools = List.copyOf(destinationPools); destinationBackends = List.copyOf(destinationBackends); UUID.fromString(owner);
        Objects.requireNonNull(id); Objects.requireNonNull(taskId); Objects.requireNonNull(serverInstanceId); Objects.requireNonNull(conversationId);
        Objects.requireNonNull(executionEpoch); Objects.requireNonNull(operationId); Objects.requireNonNull(product);
        if (privacyEpoch < 0 || expiresAt < 1 || operationRevision < 1 || custodyIndex < 0 || custodyIndex > 127 || product.empty()
                || !operationDigest.matches("[0-9a-f]{64}") || !previousNativeProof.matches("[0-9a-f]{64}")
                || destinationWitness == null || destinationWitness.length() > 8192 || destinationPools.isEmpty() || destinationPools.size() > 128
                || destinationBackends.isEmpty() || destinationBackends.size() > 32
                || destinationBackends.stream().anyMatch(backend -> backend == null || backend.isBlank() || backend.length() > 1024)
                || hash == null || !hash.isEmpty() && !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("MAID_TRANSPORT_RETURN_INVALID");
    }
    public MaidTransportReturn signed() { return withHash(AiJson.digest(AiJson.canonical(withHash("")))); }
    public boolean intact() { return hash.equals(AiJson.digest(AiJson.canonical(withHash("")))); }
    private MaidTransportReturn withHash(String value) {
        return new MaidTransportReturn(id, owner, taskId, serverInstanceId, conversationId, privacyEpoch, executionEpoch, expiresAt, operationId,
                operationRevision, operationDigest, previousNativeProof, custodyIndex, product, destinationWitness, destinationPools, destinationBackends, value);
    }
    public void requireIdentity(Workspace workspace, Actor actor, UUID epoch) {
        actor.requireAccess(owner); actor.requireAccess(workspace.owner());
        if (!intact() || !serverInstanceId.equals(workspace.metadata().serverInstanceId()) || !conversationId.equals(workspace.primaryConversationId())
                || privacyEpoch != workspace.conversation().privacyEpoch() || !executionEpoch.equals(epoch) || System.currentTimeMillis() >= expiresAt)
            throw new SecurityException("MAID_TRANSPORT_RETURN_CHANGED");
    }
}
