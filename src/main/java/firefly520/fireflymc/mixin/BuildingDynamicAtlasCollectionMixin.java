package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.processing.advanced.BuildingDynamicAtlasHeaders;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "pepjebs.mapatlases.item.MapAtlasItem", remap = false)
public abstract class BuildingDynamicAtlasCollectionMixin {
    @Inject(method = "getMaps(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;)Lpepjebs/mapatlases/map_collection/MapCollection;",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private static void fireflymc$isolatedCollection(ItemStack stack, Level level, CallbackInfoReturnable<Object> callback) {
        if (BuildingDynamicAtlasHeaders.active()) callback.setReturnValue(BuildingDynamicAtlasHeaders.collection(stack, level));
    }
}
