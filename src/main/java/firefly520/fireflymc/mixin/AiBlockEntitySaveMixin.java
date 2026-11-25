package firefly520.fireflymc.mixin;

import com.mojang.serialization.DataResult;
import firefly520.fireflymc.ai.material.NativeSaveObserver;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Optional;
import java.util.function.Consumer;

/** A chunk can save successfully after dropping a failed BE; bind the actual successful BE tag. */
@Mixin(value = BlockEntity.class, remap = false)
public abstract class AiBlockEntitySaveMixin {
    @Unique private ThreadLocal<Boolean> fireflymc$validSave;
    @Inject(method = "saveWithFullMetadata", at = @At("HEAD"))
    private void fireflymc$begin(HolderLookup.Provider registries, CallbackInfoReturnable<CompoundTag> ci) {
        if (!NativeSaveObserver.snapshot(this).empty()) {
            if (fireflymc$validSave == null) fireflymc$validSave = new ThreadLocal<>();
            fireflymc$validSave.set(true);
        } else if (fireflymc$validSave != null) fireflymc$validSave.remove();
    }
    @Redirect(method = "saveWithoutMetadata", at = @At(value = "INVOKE",
            target = "Lcom/mojang/serialization/DataResult;resultOrPartial(Ljava/util/function/Consumer;)Ljava/util/Optional;"))
    private <T> Optional<T> fireflymc$rejectPartial(DataResult<T> result, Consumer<String> error) {
        if (result.error().isPresent() && fireflymc$validSave != null && fireflymc$validSave.get() != null) fireflymc$validSave.set(false);
        return result.resultOrPartial(error);
    }
    @Inject(method = "saveWithFullMetadata", at = @At("RETURN"))
    private void fireflymc$capture(HolderLookup.Provider registries, CallbackInfoReturnable<CompoundTag> ci) {
        if (fireflymc$validSave == null) return;
        if (Boolean.TRUE.equals(fireflymc$validSave.get())) NativeSaveObserver.serialized(this, ci.getReturnValue());
        fireflymc$validSave.remove();
    }
}
