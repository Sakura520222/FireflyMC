package firefly520.fireflymc.ai.processing.advanced;

import java.util.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StorageDynamicNbtTest {
    @Test void sliceDigestsIgnoreCompoundKeyOrderButRetainTypesArraysAndRealCounts() {
        var first = new CompoundTag(); first.putInt("count", 712); first.putLongArray("slots", new long[]{3, 7});
        var reordered = new CompoundTag(); reordered.putLongArray("slots", new long[]{3, 7}); reordered.putInt("count", 712);
        String uuid = UUID.randomUUID().toString();
        assertEquals(StorageDynamicNbt.digest(Map.of(uuid, StorageDynamicNbt.pack(first))), StorageDynamicNbt.digest(Map.of(uuid, StorageDynamicNbt.pack(reordered))));
        var changed = reordered.copy(); changed.putLong("count", 712);
        assertNotEquals(StorageDynamicNbt.digest(Map.of(uuid, StorageDynamicNbt.pack(first))), StorageDynamicNbt.digest(Map.of(uuid, StorageDynamicNbt.pack(changed))));
        assertEquals(first, StorageDynamicNbt.unpack(StorageDynamicNbt.pack(first)));
    }
    @Test void recursiveOrExcessivelyDeepNativeTagsCannotEscapeThePrivateSnapshotBudget() {
        var cycle = new CompoundTag(); cycle.put("self", cycle); assertThrows(IllegalArgumentException.class, () -> StorageDynamicNbt.pack(cycle));
        var root = new CompoundTag(); var at = root;
        for (int i = 0; i < 34; i++) { var next = new CompoundTag(); at.put("next", next); at = next; }
        assertThrows(IllegalArgumentException.class, () -> StorageDynamicNbt.pack(root));
    }
}
