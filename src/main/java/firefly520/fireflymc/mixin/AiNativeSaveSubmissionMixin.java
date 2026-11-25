package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.material.NativeSaveSubmissionScope;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.concurrent.CompletableFuture;

/** Submission completion is only an IO concurrency fence; existing writer hooks prove durability. */
@Mixin(value = {ChunkStorage.class, SimpleRegionStorage.class}, remap = false)
public abstract class AiNativeSaveSubmissionMixin implements NativeSaveSubmissionScope.Hook {
    @Inject(method = "write(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)Ljava/util/concurrent/CompletableFuture;", at = @At("RETURN"), require = 1)
    private void fireflymc$submitted(ChunkPos position, CompoundTag data, CallbackInfoReturnable<CompletableFuture<Void>> callback) {
        NativeSaveSubmissionScope.submitted(callback.getReturnValue());
    }
}
