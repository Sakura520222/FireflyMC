package firefly520.fireflymc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.mojang.datafixers.util.Pair;
import firefly520.fireflymc.ai.integration.exploration.NativeCompassSearchScope;
import firefly520.fireflymc.ai.integration.exploration.CompassSampleGuardedWorker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.chaosthedude.explorerscompass.worker.StructureSearchWorker", remap = false)
public abstract class CompassStructureSampleMixin implements CompassSampleGuardedWorker {
    @Inject(method = "getStructureGeneratingAt", at = @At("HEAD"), remap = false)
    private void fireflymc$checkLoadedCandidate(ChunkPos pos, CallbackInfoReturnable<Pair<BlockPos, Structure>> callback) {
        NativeCompassSearchScope.beforeStructure(this, pos);
    }
    @Inject(method = "getStructureGeneratingAt", at = @At("RETURN"), remap = false)
    private void fireflymc$recordNativeCandidate(ChunkPos pos, CallbackInfoReturnable<Pair<BlockPos, Structure>> callback) {
        NativeCompassSearchScope.structureMatch(pos, callback.getReturnValue());
    }
    @WrapOperation(method = "getStructureGeneratingAt", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;)Lnet/minecraft/world/level/chunk/ChunkAccess;", remap = false), remap = false)
    private ChunkAccess fireflymc$neverGenerateForOwnedSearch(ServerLevel level, int x, int z, ChunkStatus status, Operation<ChunkAccess> original) {
        return NativeCompassSearchScope.active() ? NativeCompassSearchScope.loadedChunk(level, x, z, status) : original.call(level, x, z, status);
    }
}
