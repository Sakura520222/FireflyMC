package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.blueprint.ownership.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(value = PalettedContainer.class, remap = false)
public abstract class AiOwnershipPaletteMixin<T> implements OwnershipAccess.Palette {
    @Shadow protected abstract T get(int index);
    @Unique private OwnershipSectionBinding fireflymc$binding;
    @Override public OwnershipSectionBinding fireflymc$ownershipPalette() { return fireflymc$binding; }
    @Override public void fireflymc$bindOwnershipPalette(OwnershipSectionBinding binding) {
        if (fireflymc$binding != null && fireflymc$binding != binding) { fireflymc$binding.escape(); binding.escape(); }
        fireflymc$binding = binding; OwnershipNativeHooks.bindBits((PalettedContainer<?>) (Object) this, binding);
    }
    @Inject(method = "getAndSet(ILjava/lang/Object;)Ljava/lang/Object;", at = @At("HEAD"))
    private void fireflymc$ownershipGetAndSet(int index, T state, CallbackInfoReturnable<T> callback) {
        if (fireflymc$binding != null) fireflymc$binding.transition(index, get(index), state);
    }
    @Inject(method = "set(ILjava/lang/Object;)V", at = @At("HEAD"))
    private void fireflymc$ownershipSet(int index, T state, CallbackInfo callback) {
        if (fireflymc$binding != null) fireflymc$binding.transition(index, get(index), state);
    }
    @Inject(method = "onResize", at = @At("RETURN"))
    private void fireflymc$ownershipResize(int bits, T state, CallbackInfoReturnable<Integer> callback) {
        OwnershipNativeHooks.bindBits((PalettedContainer<?>) (Object) this, fireflymc$binding);
    }
    @Inject(method = "read", at = @At("HEAD"))
    private void fireflymc$ownershipBulkRead(FriendlyByteBuf buffer, CallbackInfo callback) {
        if (fireflymc$binding != null) fireflymc$binding.bulk();
    }
    @Inject(method = "read", at = @At("RETURN"))
    private void fireflymc$ownershipReadFinished(FriendlyByteBuf buffer, CallbackInfo callback) {
        OwnershipNativeHooks.bindBits((PalettedContainer<?>) (Object) this, fireflymc$binding);
    }
}
