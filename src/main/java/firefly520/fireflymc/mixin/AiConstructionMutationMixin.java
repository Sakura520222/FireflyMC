package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.blueprint.ConstructionWorldEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observe the actual chunk write, including edits later reverted within the same tick. Never changes the result. */
@Mixin(LevelChunk.class)
public abstract class AiConstructionMutationMixin {
    @Inject(method = "setBlockState", at = @At("HEAD"), remap = false)
    private void fireflymc$guardConstructionFootprint(BlockPos pos, BlockState state, boolean moved,
                                                     CallbackInfoReturnable<BlockState> callback) {
        var chunk = (LevelChunk) (Object) this;
        if (chunk.getLevel() instanceof ServerLevel server && chunk.getBlockState(pos) != state)
            ConstructionWorldEvents.beforeChange(server, pos);
    }
    @Inject(method = "setBlockState", at = @At("RETURN"), remap = false)
    private void fireflymc$observeConstructionChange(BlockPos pos, BlockState state, boolean moved,
                                                    CallbackInfoReturnable<BlockState> callback) {
        BlockState previous = callback.getReturnValue();
        if (previous != null && previous != state && ((LevelChunk) (Object) this).getLevel() instanceof ServerLevel server) {
            ConstructionWorldEvents.changed(server, pos);
            firefly520.fireflymc.ai.blueprint.NativeConstructionSave.blockChanged(server, pos);
        }
    }
}
