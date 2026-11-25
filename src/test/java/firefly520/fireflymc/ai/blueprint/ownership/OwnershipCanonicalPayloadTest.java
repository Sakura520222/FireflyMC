package firefly520.fireflymc.ai.blueprint.ownership;

import java.util.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OwnershipCanonicalPayloadTest {
    private CompoundTag payload() {
        var payload = new CompoundTag(); payload.putInt("xPos", 0); payload.putInt("zPos", 0);
        payload.putString("Status", "minecraft:full"); payload.putLongArray("palette", new long[] {1, 2}); return payload;
    }
    private CompoundTag marked() {
        var payload = payload(); var state = new OwnershipChunkState();
        var owner = new OwnershipToken.Owner(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "b".repeat(64));
        state.claim(UUID.randomUUID(), "minecraft:overworld", 0L, owner, UUID.randomUUID(), "air", "stone");
        payload.put(OwnershipNbt.FIELD, OwnershipNbt.write(state.snapshot(), 0, 0, "minecraft:overworld", CanonicalChunkPayload.digest(payload)));
        return payload;
    }
    @Test void canonicalCompoundDigestIgnoresInsertionOrderButNotTheActualPayload() {
        var first = new CompoundTag(); first.putInt("a", 1); first.putString("b", "x");
        var second = new CompoundTag(); second.putString("b", "x"); second.putInt("a", 1);
        assertEquals(CanonicalChunkPayload.digest(first), CanonicalChunkPayload.digest(second));
        second.putInt("a", 2); assertNotEquals(CanonicalChunkPayload.digest(first), CanonicalChunkPayload.digest(second));
    }
    @Test void theRootMarkerIsExcludedButNestedSameNamedDataIsPartOfThePayload() {
        var first = payload(); var second = first.copy(); second.putString(OwnershipNbt.FIELD, "metadata");
        assertEquals(CanonicalChunkPayload.digest(first), CanonicalChunkPayload.digest(second));
        var nested = new CompoundTag(); nested.putString(OwnershipNbt.FIELD, "world-data"); second.put("nested", nested);
        assertNotEquals(CanonicalChunkPayload.digest(first), CanonicalChunkPayload.digest(second));
    }
    @Test void exactCanonicalMarkerReadsWithoutGrantingAnyAuthority() {
        var payload = marked(); var stored = OwnershipNbt.readCanonical(payload, 0, 0, "minecraft:overworld");
        assertEquals(1, stored.claims().size()); var loaded = new OwnershipChunkState(); loaded.restore(stored, true, false);
        assertFalse(loaded.hasClaims());
    }
    @Test void payloadTamperingAndDimensionOrChunkRelocationAreRejected() {
        var payload = marked(); payload.putLongArray("palette", new long[] {2, 1});
        assertThrows(IllegalArgumentException.class, () -> OwnershipNbt.readCanonical(payload, 0, 0, "minecraft:overworld"));
        var saved = marked();
        assertThrows(IllegalArgumentException.class, () -> OwnershipNbt.readCanonical(saved, 0, 0, "minecraft:the_nether"));
        assertThrows(IllegalArgumentException.class, () -> OwnershipNbt.readCanonical(saved, 1, 0, "minecraft:overworld"));
    }
    @Test void invalidMetadataTypesAndImpossibleClaimGenerationAreRejected() {
        var payload = marked(); var marker = payload.getCompound(OwnershipNbt.FIELD);
        var wrong = new ListTag(); wrong.add(StringTag.valueOf("wrong")); marker.put("claims", wrong);
        assertThrows(IllegalArgumentException.class, () -> OwnershipNbt.readCanonical(payload, 0, 0, "minecraft:overworld"));
        var another = marked(); another.getCompound(OwnershipNbt.FIELD).putLong("revision", 0);
        assertThrows(IllegalArgumentException.class, () -> OwnershipNbt.readCanonical(another, 0, 0, "minecraft:overworld"));
    }
}
