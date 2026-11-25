package firefly520.fireflymc.ai.material;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import java.util.function.LongSupplier;

/** Native handler effects after authorization and durable intent. Never manufactures a missing remainder. */
public final class NativeMaterialTransfer {
    public record Taken(MaterialStack actual, long unitsAfter) {}
    public record Inserted(int accepted, boolean verified) {}
    private NativeMaterialTransfer() {}
    public static Taken extract(IItemHandler handler, int slot, int count, HolderLookup.Provider registries, LongSupplier poolUnits) {
        if (count < 1) throw new IllegalArgumentException("EXTRACTION_COUNT_INVALID");
        ItemStack actual = handler.extractItem(slot, count, false);
        return new Taken(ItemSnapshotCodec.capture(actual, registries), poolUnits.getAsLong());
    }
    public static Inserted insert(IItemHandler handler, int slot, MaterialStack product,
                                  MaterialStack before, HolderLookup.Provider registries) {
        if (!ItemSnapshotCodec.capture(handler.getStackInSlot(slot), registries).equals(before))
            throw new IllegalStateException("OUTPUT_SLOT_CHANGED");
        MaterialStack remaining = ItemSnapshotCodec.capture(handler.insertItem(slot,
                ItemSnapshotCodec.restore(product, registries), false), registries);
        MaterialStack after = ItemSnapshotCodec.capture(handler.getStackInSlot(slot), registries);
        int accepted = product.count() - remaining.count();
        boolean verified = accepted >= 0 && (remaining.empty() || remaining.itemJson().equals(product.itemJson()))
                && (accepted == 0 ? after.equals(before) : after.itemJson().equals(product.itemJson())
                && (before.empty() || before.itemJson().equals(after.itemJson())) && after.count() - before.count() == accepted);
        return new Inserted(accepted, verified);
    }
}
