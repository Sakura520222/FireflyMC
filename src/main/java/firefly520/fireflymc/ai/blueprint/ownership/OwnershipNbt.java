package firefly520.fireflymc.ai.blueprint.ownership;

import java.util.*;
import net.minecraft.nbt.*;

/** The claim metadata lives inside the same canonical chunk tag as the observed block palette. */
public final class OwnershipNbt {
    public static final String FIELD = "fireflymc_ownership_v1";
    private OwnershipNbt() {}
    public static CompoundTag write(OwnershipChunkState.Snapshot state, int chunkX, int chunkZ, String dimension, String payloadHash) {
        var root = new CompoundTag(); root.putInt("schema", 1); root.putInt("chunkX", chunkX); root.putInt("chunkZ", chunkZ);
        root.putString("dimension", dimension); root.putUUID("chunkNonce", state.chunkNonce()); root.putLong("revision", state.revision());
        root.putBoolean("revoked", state.unsafe()); root.putString("reason", state.reason()); root.putString("payloadHash", payloadHash);
        var claims = new ListTag();
        if (!state.unsafe()) for (var claim : state.claims()) {
            var tag = new CompoundTag(); tag.putUUID("runtime", claim.runtimeSession()); tag.putLong("position", claim.position());
            tag.putLong("generation", claim.generation()); tag.putUUID("claimNonce", claim.claimNonce());
            tag.putString("before", claim.beforeState()); tag.putString("after", claim.afterState());
            var owner = new CompoundTag(); owner.putUUID("serverInstance", claim.owner().serverInstanceId()); owner.putUUID("player", claim.owner().player());
            owner.putUUID("task", claim.owner().taskId()); owner.putUUID("operation", claim.owner().operationId()); owner.putUUID("step", claim.owner().stepId());
            owner.putString("planHash", claim.owner().planHash()); tag.put("owner", owner); claims.add(tag);
        }
        root.put("claims", claims); return root;
    }
    public static OwnershipChunkState.Snapshot read(CompoundTag tag, int chunkX, int chunkZ, String dimension) {
        if (!tag.contains("schema", Tag.TAG_INT) || !tag.contains("chunkX", Tag.TAG_INT) || !tag.contains("chunkZ", Tag.TAG_INT)
                || !tag.contains("revision", Tag.TAG_LONG) || !tag.contains("claims", Tag.TAG_LIST)
                || !tag.contains("revoked", Tag.TAG_BYTE) || !tag.contains("reason", Tag.TAG_STRING)
                || !tag.getString("payloadHash").matches("[0-9a-f]{64}")
                || tag.getInt("schema") != 1 || tag.getInt("chunkX") != chunkX || tag.getInt("chunkZ") != chunkZ
                || !dimension.equals(tag.getString("dimension")) || !tag.hasUUID("chunkNonce") || tag.getLong("revision") < 0)
            throw new IllegalArgumentException("OWNERSHIP_CHUNK_IDENTITY_INVALID");
        UUID nonce = tag.getUUID("chunkNonce"); long revision = tag.getLong("revision"); var raw = (ListTag) tag.get("claims");
        if (raw == null || !raw.isEmpty() && raw.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("OWNERSHIP_CLAIM_TYPE_INVALID");
        var list = raw;
        if (list.size() > OwnershipChunkState.MAX_CLAIMS) throw new IllegalArgumentException("OWNERSHIP_CHUNK_CAPACITY");
        var claims = new ArrayList<OwnershipToken>();
        for (int index = 0; index < list.size(); index++) {
            var claim = list.getCompound(index); var owner = claim.getCompound("owner");
            if (!claim.contains("position", Tag.TAG_LONG) || !claim.contains("generation", Tag.TAG_LONG)
                    || !claim.contains("owner", Tag.TAG_COMPOUND) || !claim.hasUUID("runtime") || !claim.hasUUID("claimNonce"))
                throw new IllegalArgumentException("OWNERSHIP_CLAIM_TYPE_INVALID");
            var identity = new OwnershipToken.Owner(owner.getUUID("serverInstance"), owner.getUUID("player"), owner.getUUID("task"),
                    owner.getUUID("operation"), owner.getUUID("step"), owner.getString("planHash"));
            var token = new OwnershipToken(1, claim.getUUID("runtime"), nonce, dimension, claim.getLong("position"), claim.getLong("generation"),
                    claim.getUUID("claimNonce"), identity, claim.getString("before"), claim.getString("after"));
            var pos = net.minecraft.core.BlockPos.of(token.position());
            if ((pos.getX() >> 4) != chunkX || (pos.getZ() >> 4) != chunkZ) throw new IllegalArgumentException("OWNERSHIP_CELL_OUTSIDE_CHUNK");
            if (token.generation() > revision || claims.stream().anyMatch(existing -> existing.position() == token.position()))
                throw new IllegalArgumentException("OWNERSHIP_STORED_TOKEN_INVALID");
            claims.add(token);
        }
        return new OwnershipChunkState.Snapshot(nonce, revision, claims, tag.getBoolean("revoked"), tag.getString("reason"));
    }
    /** Read-only validation of the exact native tag; this method grants no undo authority. */
    public static OwnershipChunkState.Snapshot readCanonical(CompoundTag payload, int chunkX, int chunkZ, String dimension) {
        if (!payload.contains("xPos", Tag.TAG_INT) || !payload.contains("zPos", Tag.TAG_INT)
                || payload.getInt("xPos") != chunkX || payload.getInt("zPos") != chunkZ || !payload.contains(FIELD, Tag.TAG_COMPOUND))
            throw new IllegalArgumentException("OWNERSHIP_CANONICAL_CHUNK_INVALID");
        var marker = payload.getCompound(FIELD);
        if (!CanonicalChunkPayload.digest(payload).equals(marker.getString("payloadHash")))
            throw new IllegalArgumentException("OWNERSHIP_CANONICAL_PAYLOAD_CHANGED");
        return read(marker, chunkX, chunkZ, dimension);
    }
}
