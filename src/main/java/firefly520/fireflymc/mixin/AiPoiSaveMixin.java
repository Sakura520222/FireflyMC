package firefly520.fireflymc.mixin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import firefly520.fireflymc.ai.material.NativeSaveObserver;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** A column may still be written after a section codec failed or returned a partial value. */
@Mixin(value = SectionStorage.class, remap = false)
public abstract class AiPoiSaveMixin {
    @Redirect(method = "writeColumn(Lnet/minecraft/world/level/ChunkPos;Lcom/mojang/serialization/DynamicOps;)Lcom/mojang/serialization/Dynamic;",
            at = @At(value = "INVOKE", target = "Lcom/mojang/serialization/Codec;encodeStart(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;"))
    private <R, T> DataResult<T> fireflymc$captureCompleteSection(Codec<R> codec, DynamicOps<T> ops, R section) {
        DataResult<T> result = codec.encodeStart(ops, section);
        if (result.error().isEmpty()) result.result().ifPresent(value -> {
            if (value instanceof CompoundTag tag) NativeSaveObserver.serialized(section, tag);
        });
        return result;
    }
}
