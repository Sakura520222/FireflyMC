package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.blueprint.ownership.*;
import net.minecraft.util.SimpleBitStorage;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(value = SimpleBitStorage.class, remap = false)
public abstract class AiOwnershipBitStorageMixin implements OwnershipAccess.Storage {
    @Shadow public abstract int get(int index);
    @Unique private OwnershipSectionBinding fireflymc$binding;
    @Unique private boolean fireflymc$escaped;
    @Override public OwnershipSectionBinding fireflymc$ownershipStorage() { return fireflymc$binding; }
    @Override public void fireflymc$bindOwnershipStorage(OwnershipSectionBinding binding) {
        if (fireflymc$binding != null && fireflymc$binding != binding) { fireflymc$binding.escape(); binding.escape(); }
        fireflymc$binding = binding; if (fireflymc$escaped) binding.escape();
    }
    @Inject(method = "<init>(II[J)V", at = @At("RETURN"))
    private void fireflymc$ownershipArrayAlias(int bits, int size, long[] values, CallbackInfo callback) {
        if (values != null && !OwnershipNativeHooks.ownsConstructorArray()) fireflymc$escaped = true;
    }
    @Inject(method = "getAndSet", at = @At("HEAD"))
    private void fireflymc$ownershipGetAndSet(int index, int value, CallbackInfoReturnable<Integer> callback) {
        if (fireflymc$binding != null && get(index) != value) OwnershipNativeHooks.storageChanged(fireflymc$binding, index, "getAndSet");
    }
    @Inject(method = "set", at = @At("HEAD"))
    private void fireflymc$ownershipSet(int index, int value, CallbackInfo callback) {
        if (fireflymc$binding != null && get(index) != value) OwnershipNativeHooks.storageChanged(fireflymc$binding, index, "set");
    }
    @Inject(method = "getRaw", at = @At("RETURN"))
    private void fireflymc$ownershipRawReturned(CallbackInfoReturnable<long[]> callback) {
        if (!OwnershipNativeHooks.rawStorageReader()) {
            fireflymc$escaped = true; if (fireflymc$binding != null) fireflymc$binding.escape();
        }
    }
}
