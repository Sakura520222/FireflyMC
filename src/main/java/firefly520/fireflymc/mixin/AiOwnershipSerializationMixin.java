package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.blueprint.ownership.OwnershipNativeHooks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Runs before the existing native-save observer captures the exact returned canonical tag. */
@Mixin(value = ChunkSerializer.class, remap = false, priority = 1100)
public abstract class AiOwnershipSerializationMixin {
    @Inject(method = "write", at = @At("RETURN"))
    private static void fireflymc$ownershipWrite(ServerLevel level, ChunkAccess chunk, CallbackInfoReturnable<CompoundTag> callback) {
        OwnershipNativeHooks.write(level, chunk, callback.getReturnValue());
    }
    @Inject(method = "read", at = @At("RETURN"))
    private static void fireflymc$ownershipRead(ServerLevel level, PoiManager poi, RegionStorageInfo storage,
            ChunkPos position, CompoundTag payload, CallbackInfoReturnable<ProtoChunk> callback) {
        OwnershipNativeHooks.read(level, callback.getReturnValue(), payload);
    }
}
