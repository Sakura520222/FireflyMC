package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.material.NativeSaveObserver;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ChunkSerializer.class, remap = false)
public abstract class AiChunkSaveMixin {
    @Inject(method = "write", at = @At("RETURN"))
    private static void fireflymc$capture(ServerLevel level, ChunkAccess chunk, CallbackInfoReturnable<CompoundTag> ci) {
        NativeSaveObserver.serialized(chunk, ci.getReturnValue());
    }
}
