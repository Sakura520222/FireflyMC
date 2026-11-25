package firefly520.fireflymc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import firefly520.fireflymc.ai.material.NativeSaveSubmissionScope;
import net.neoforged.neoforge.common.IOUtilities;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = IOUtilities.class, remap = false)
public abstract class AiNativeSavedDataSubmissionMixin implements NativeSaveSubmissionScope.Hook {
    @WrapMethod(method = "withIOWorker(Ljava/lang/Runnable;)V", remap = false)
    private static void fireflymc$submitted(Runnable task, Operation<Void> original) {
        Runnable wrapped = NativeSaveSubmissionScope.submitted(task);
        try { original.call(wrapped); }
        catch (RuntimeException | Error failure) { NativeSaveSubmissionScope.rejected(wrapped, failure); throw failure; }
    }
}
