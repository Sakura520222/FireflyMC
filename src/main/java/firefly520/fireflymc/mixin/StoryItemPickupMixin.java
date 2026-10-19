package firefly520.fireflymc.mixin;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Preserve NeoForge's Post event when the inventory accepted only part of a ground stack. */
@Mixin(ItemEntity.class)
public abstract class StoryItemPickupMixin {
    @Redirect(
        method = "playerTouch",
        at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/entity/player/Inventory;add(Lnet/minecraft/world/item/ItemStack;)Z"),
        remap = false
    )
    private boolean fireflymc$recognizePartialPickup(Inventory inventory, ItemStack stack) {
        int before = stack.getCount();
        boolean accepted = inventory.add(stack);
        // In 1.21.1, Inventory.add can return false after a successful partial merge: its
        // final loop iteration found no more space. Let the normal pickup/Post path run for
        // the already-moved items, retaining the remainder and vanilla pickup restrictions.
        return accepted || stack.getCount() < before;
    }
}
