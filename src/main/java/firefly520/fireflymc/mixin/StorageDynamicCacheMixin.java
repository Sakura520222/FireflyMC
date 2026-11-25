package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.processing.advanced.StorageDynamicNativeScope;
import java.util.function.Function;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.inventory.StorageWrapperRepository", remap = false)
public abstract class StorageDynamicCacheMixin {
    @Inject(method = "getStorageWrapper", at = @At("HEAD"), remap = false)
    private static void fireflymc$trackOnlyPrivateNativeAssemblyStacks(ItemStack stack, Class<?> type, Function<?, ?> factory,
                                                                      CallbackInfoReturnable<Object> callback) {
        StorageDynamicNativeScope.observeStack(stack);
    }
}
