package firefly520.fireflymc.ai.blueprint.ownership;

import firefly520.fireflymc.ai.storage.AiJson;
import java.util.*;

/** Opaque authority for one actual native transition; equality of blocks cannot recreate it. */
public record OwnershipToken(int version, UUID runtimeSession, UUID chunkNonce, String dimension, long position,
        long generation, UUID claimNonce, Owner owner, String beforeState, String afterState) {
    public record Owner(UUID serverInstanceId, UUID player, UUID taskId, UUID operationId, UUID stepId, String planHash) {
        public Owner {
            Objects.requireNonNull(serverInstanceId); Objects.requireNonNull(player); Objects.requireNonNull(taskId);
            Objects.requireNonNull(operationId); Objects.requireNonNull(stepId);
            if (planHash == null || !planHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("OWNERSHIP_PLAN_HASH_INVALID");
        }
    }
    public OwnershipToken {
        Objects.requireNonNull(runtimeSession); Objects.requireNonNull(chunkNonce); Objects.requireNonNull(claimNonce); Objects.requireNonNull(owner);
        if (version != 1 || generation < 1 || dimension == null || dimension.length() > 512 || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                || beforeState == null || afterState == null || beforeState.isBlank() || afterState.isBlank()
                || beforeState.length() > 4096 || afterState.length() > 4096 || beforeState.equals(afterState))
            throw new IllegalArgumentException("OWNERSHIP_TOKEN_INVALID");
    }
    public String digest() { return AiJson.digest(AiJson.canonical(this)); }
}
