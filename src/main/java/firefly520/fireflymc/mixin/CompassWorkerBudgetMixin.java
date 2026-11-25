package firefly520.fireflymc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import firefly520.fireflymc.ai.integration.exploration.*;
import org.spongepowered.asm.mixin.*;

@Pseudo
@Mixin(targets = {"com.chaosthedude.naturescompass.util.BiomeSearchWorker",
        "com.chaosthedude.explorerscompass.worker.RandomSpreadSearchWorker",
        "com.chaosthedude.explorerscompass.worker.ConcentricRingsSearchWorker",
        "com.chaosthedude.explorerscompass.worker.GenericSearchWorker"}, remap = false)
public abstract class CompassWorkerBudgetMixin implements CompassScopedWorker {
    @WrapMethod(method = "doWork", remap = false)
    private boolean fireflymc$boundedOwnedSlice(Operation<Boolean> original) { return NativeCompassSearchScope.work(this, original::call); }
}
