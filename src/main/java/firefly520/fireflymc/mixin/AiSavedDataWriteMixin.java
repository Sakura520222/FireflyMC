package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.material.NativeSaveObserver;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.common.IOUtilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.nio.file.Path;

@Mixin(value = IOUtilities.class, remap = false)
public abstract class AiSavedDataWriteMixin {
    @Inject(method = "writeNbtCompressed", at = @At("RETURN"))
    private static void fireflymc$written(CompoundTag data, Path path, CallbackInfo ci) {
        // IOUtilities has completed its FileChannel.force + atomic replacement at this point.
        if (NativeSaveObserver.tracked(data)) NativeSaveObserver.writtenFile(data, path);
    }
}
