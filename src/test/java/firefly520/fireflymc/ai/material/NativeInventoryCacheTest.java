package firefly520.fireflymc.ai.material;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeInventoryCacheTest {
    private CompoundTag slot(int index, int count) {
        var slot = new CompoundTag(); slot.putInt("Slot", index); slot.putString("id", "minecraft:oak_planks"); slot.putInt("count", count); return slot;
    }
    private CompoundTag inventory(CompoundTag... slots) {
        var data = new CompoundTag(); data.putInt("Size", 27); var items = new ListTag();
        for (var slot : slots) items.add(slot); data.put("Items", items); return data;
    }
    @Test void slotRecordOrderDoesNotChangePhysicalInventoryIdentity() {
        assertTrue(StorageSourceIntrospector.sameNativeSlotEncoding(inventory(slot(0, 4), slot(7, 9)), inventory(slot(7, 9), slot(0, 4))));
        assertTrue(StorageSourceIntrospector.sameNativeSlotEncoding(inventory(), inventory()));
    }
    @Test void sharedBackendDebitOrComponentChangeInvalidatesTheOtherWrappersSnapshot() {
        assertFalse(StorageSourceIntrospector.sameNativeSlotEncoding(inventory(slot(0, 8)), inventory(slot(0, 4))));
        var changed = slot(0, 8); var components = new CompoundTag(); components.putString("minecraft:custom_name", "changed"); changed.put("components", components);
        assertFalse(StorageSourceIntrospector.sameNativeSlotEncoding(inventory(slot(0, 8)), inventory(changed)));
    }
    @Test void duplicateAndInvalidSlotRecordsCannotBorrowLastWriteWinsSemantics() {
        assertThrows(RuntimeException.class, () -> StorageSourceIntrospector.sameNativeSlotEncoding(inventory(slot(0, 8), slot(0, 4)), inventory(slot(0, 4))));
        assertThrows(RuntimeException.class, () -> StorageSourceIntrospector.sameNativeSlotEncoding(inventory(slot(27, 8)), inventory()));
        var unknown = inventory(); unknown.putBoolean("unknown_layout", true);
        assertThrows(RuntimeException.class, () -> StorageSourceIntrospector.sameNativeSlotEncoding(unknown, inventory()));
    }
}
