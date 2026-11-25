package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.blueprint.NativeConstructionSave;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ServerLevel.class, remap = false)
public abstract class AiPoiUpdateMixin {
    @Inject(method = "onBlockStateChange", at = @At("RETURN"))
    private void fireflymc$afterPoiDispatch(BlockPos pos, BlockState previous, BlockState current, CallbackInfo ci) {
        NativeConstructionSave.poiChanged((ServerLevel) (Object) this, pos, previous, current);
    }
}
