package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.AiServerEvents;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** All native/mod save listeners finish before journals close and before the dedicated exit latch opens. */
@Mixin(value = ServerLifecycleHooks.class, remap = false)
public abstract class AiServerShutdownMixin {
    @Inject(method = "handleServerStopped", at = @At(value = "INVOKE", target = "Lnet/neoforged/bus/api/IEventBus;post(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;", shift = At.Shift.AFTER))
    private static void firefly$drain(MinecraftServer server, CallbackInfo ci) { AiServerEvents.savesFinished(server); }
}
