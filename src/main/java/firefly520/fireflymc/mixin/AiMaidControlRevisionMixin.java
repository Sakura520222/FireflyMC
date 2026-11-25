package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.integration.maid.MaidNativeRevision;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid", remap = false)
public abstract class AiMaidControlRevisionMixin implements MaidNativeRevision.Carrier {
    @Unique private long fireflymc$controlRevision;
    @Override public long fireflymc$maidRevision() { return fireflymc$controlRevision; }
    @Override public void fireflymc$markMaidControl() { if (fireflymc$controlRevision < Long.MAX_VALUE) fireflymc$controlRevision++; }
    @Inject(method = "setHomeModeEnable", at = @At("HEAD"))
    private void fireflymc$home(boolean value, CallbackInfo info) { MaidNativeRevision.changed(this, "isHomeModeEnable", value); }
    @Inject(method = "setInSittingPose", at = @At("HEAD"))
    private void fireflymc$sitting(boolean value, CallbackInfo info) { MaidNativeRevision.changed(this, "isMaidInSittingPose", value); }
    @Inject(method = "setPickup", at = @At("HEAD"))
    private void fireflymc$pickup(boolean value, CallbackInfo info) { MaidNativeRevision.changed(this, "isPickup", value); }
    @Inject(method = "setRideable", at = @At("HEAD"))
    private void fireflymc$ride(boolean value, CallbackInfo info) { MaidNativeRevision.changed(this, "isRideable", value); }
    @Inject(method = "setTask", at = @At("HEAD"))
    private void fireflymc$task(@Coerce Object value, CallbackInfo info) { MaidNativeRevision.changed(this, "getTask", value); }
    @Inject(method = "setSchedule", at = @At("HEAD"))
    private void fireflymc$schedule(@Coerce Object value, CallbackInfo info) { MaidNativeRevision.changed(this, "getSchedule", value); }
}
