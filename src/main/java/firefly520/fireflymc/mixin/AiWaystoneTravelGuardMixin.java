package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.integration.travel.NativeWaystoneTravelHooks;
import java.util.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only the separately version-gated travel config loads this optional target. */
@Pseudo
@Mixin(targets = "net.blay09.mods.waystones.core.WaystoneTeleportManager", remap = false)
public abstract class AiWaystoneTravelGuardMixin {
    @Unique private static String fireflymc$travelGuardVersion() { return "waystones-21.1.37-fee-and-scope-v1"; }

    @Inject(method = "lambda$tryTeleportAsync$14", at = @At(value = "INVOKE",
            target = "Lnet/blay09/mods/waystones/core/WaystoneTeleportManager;consumeRequirements(Lnet/blay09/mods/waystones/api/WaystoneTeleportContext;)V"),
            cancellable = true, require = 1)
    private static void fireflymc$refuseBeforeFees(@Coerce Object context, @Coerce Object destination, CallbackInfoReturnable<Object> callback) {
        Object refused = NativeWaystoneTravelHooks.beforeFees(context, destination);
        if (refused != null) callback.setReturnValue(refused);
    }
    @Redirect(method = "loadDestinationChunks", at = @At(value = "INVOKE",
            target = "Lnet/blay09/mods/waystones/api/event/WaystoneTeleportEvent$Prepare;getPreparationTasks()Ljava/util/List;"), require = 1)
    private static List<?> fireflymc$boundedTasks(@Coerce Object event) { return NativeWaystoneTravelHooks.preparationTasks(event); }

    @Redirect(method = "loadDestinationChunks", at = @At(value = "INVOKE",
            target = "Lnet/blay09/mods/waystones/api/event/WaystoneTeleportEvent$Prepare;getChunkPositions()Ljava/util/Set;"), require = 1)
    private static Set<?> fireflymc$boundedChunks(@Coerce Object event) { return NativeWaystoneTravelHooks.preparationChunks(event); }

    @Redirect(method = "teleportEntity", at = @At(value = "INVOKE",
            target = "Lnet/blay09/mods/waystones/api/event/WaystoneTeleportEntityEvent$Pre;getOverrideResult()Ljava/util/Optional;"), require = 1)
    private static Optional<?> fireflymc$checkResolvedLanding(@Coerce Object event) { return NativeWaystoneTravelHooks.entityOverride(event); }
}
