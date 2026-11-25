package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.processing.advanced.BuildingDynamicAtlasHeaders;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapId;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "pepjebs.mapatlases.utils.MapDataHolder", remap = false)
public abstract class BuildingDynamicAtlasFindMixin {
    @Inject(method = "find(Lnet/minecraft/world/level/saveddata/maps/MapId;Lpepjebs/mapatlases/utils/MapType;Lnet/minecraft/world/level/Level;)Lpepjebs/mapatlases/utils/MapDataHolder;",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private static void fireflymc$headerOnly(MapId id, @Coerce Object type, Level level, CallbackInfoReturnable<Object> callback) {
        if (BuildingDynamicAtlasHeaders.active()) callback.setReturnValue(BuildingDynamicAtlasHeaders.find(id, type, level));
    }
}
