package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.integration.exploration.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = {"com.chaosthedude.naturescompass.util.BiomeSearchWorker", "com.chaosthedude.explorerscompass.worker.StructureSearchWorker"}, remap = false)
public abstract class CompassWorkerRegistrationMixin implements CompassRegisteredWorker {
    @Inject(method = "start", at = @At("HEAD"), remap = false)
    private void fireflymc$captureApprovedWorker(CallbackInfo callback) { NativeCompassSearchScope.captureStarted(this); }
}
