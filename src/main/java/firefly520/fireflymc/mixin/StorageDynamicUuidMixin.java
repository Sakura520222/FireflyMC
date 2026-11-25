package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.processing.advanced.StorageDynamicNativeScope;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.UUID;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedstorage.item.StackStorageWrapper", remap = false)
public abstract class StorageDynamicUuidMixin {
    @WrapOperation(method = {"ensureContentsUuid", "getNewUuid"}, at = @At(value = "INVOKE", target = "Ljava/util/UUID;randomUUID()Ljava/util/UUID;", remap = false), remap = false)
    private UUID fireflymc$fixedOperationIdentity(Operation<UUID> original) {
        UUID fixed = StorageDynamicNativeScope.allocatedUuid(); return fixed == null ? original.call() : fixed;
    }
}
