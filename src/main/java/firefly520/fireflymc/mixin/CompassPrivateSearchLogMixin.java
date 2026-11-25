package firefly520.fireflymc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import firefly520.fireflymc.ai.integration.exploration.NativeCompassSearchScope;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

/** Native worker progress includes coordinates; owned AI searches retain their private journal instead. */
@Pseudo
@Mixin(targets = {"com.chaosthedude.naturescompass.util.BiomeSearchWorker", "com.chaosthedude.explorerscompass.worker.StructureSearchWorker"}, remap = false)
public abstract class CompassPrivateSearchLogMixin {
    @WrapOperation(method = {"start", "doWork", "succeed", "fail"}, at = @At(value = "INVOKE",
            target = "Lorg/apache/logging/log4j/Logger;info(Ljava/lang/String;)V", remap = false), remap = false, require = 0)
    private void fireflymc$privateOwnedSearchProgress(Logger logger, String message, Operation<Void> original) {
        if (!NativeCompassSearchScope.active()) original.call(logger, message);
    }
}
