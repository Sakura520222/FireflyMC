package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.material.NativeSaveObserver;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = EntityStorage.class, remap = false)
public abstract class AiExperienceOrbEntityStorageMixin {
    @Shadow @Final private ServerLevel level;
    @Redirect(method = "storeEntities", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/LongSet;add(J)Z"))
    private boolean fireflymc$persistScopedDeletion(it.unimi.dsi.fastutil.longs.LongSet emptyChunks, long position) {
        boolean newlyEmpty = emptyChunks.add(position);
        // A never-saved award can be picked up before the first entity save. The normal empty-cache
        // shortcut must still submit this scoped canonical deletion rather than fabricate a receipt.
        return newlyEmpty || NativeSaveObserver.entityDeletionPending(level, position);
    }
    @Redirect(method = "storeEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/storage/SimpleRegionStorage;write(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> fireflymc$observeRemovalWrite(SimpleRegionStorage storage, ChunkPos position, CompoundTag data) {
        var submitted = NativeSaveObserver.entityRegionSubmission(level, position, data);
        // The writer mixin acknowledges only its actual selected payload, after native flush.
        // IOWorker can coalesce submissions, so a shared submission future is never a deletion proof.
        return storage.write(position, submitted);
    }
}
