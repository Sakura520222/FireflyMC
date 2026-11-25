package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.material.NativeSaveSubmissionScope;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.concurrent.CompletableFuture;

/** A missing native entity store is loaded before an empty canonical deletion is requested. */
@Mixin(value = EntityStorage.class, remap = false)
public abstract class AiNativeEntityReadSubmissionMixin implements NativeSaveSubmissionScope.Hook {
    @Inject(method = "loadEntities(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;", at = @At("RETURN"), require = 1)
    private void fireflymc$submitted(ChunkPos position, CallbackInfoReturnable<CompletableFuture<?>> callback) {
        NativeSaveSubmissionScope.submitted(callback.getReturnValue());
    }
}
