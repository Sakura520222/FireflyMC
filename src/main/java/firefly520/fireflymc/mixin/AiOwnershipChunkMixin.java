package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.blueprint.ownership.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LevelChunk.class, remap = false)
public abstract class AiOwnershipChunkMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void fireflymc$ownershipBind(CallbackInfo callback) { OwnershipNativeHooks.bind((LevelChunk) (Object) this); }
    @Inject(method = "setBlockEntity", at = @At("HEAD"))
    private void fireflymc$ownershipBlockEntityAdded(BlockEntity entity, CallbackInfo callback) { fireflymc$retireCell(entity.getBlockPos()); }
    @Inject(method = "removeBlockEntity", at = @At("HEAD"))
    private void fireflymc$ownershipBlockEntityRemoved(BlockPos position, CallbackInfo callback) { fireflymc$retireCell(position); }
    @Unique private void fireflymc$retireCell(BlockPos position) {
        var chunk = (LevelChunk) (Object) this;
        if (chunk.getLevel() instanceof ServerLevel level && level.getServer().isDedicatedServer()) {
            var state = ((OwnershipAccess.Chunk) chunk).fireflymc$ownership(); boolean metadata = state.hasMetadata();
            NativeOwnership.revokeCell(chunk, position.asLong());
            state.changed(position.asLong()); if (metadata) chunk.setUnsaved(true);
        }
    }
}
