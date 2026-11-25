package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.blueprint.NativeKleeSlabScope;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.blay09.mods.kleeslabs.BlockBreakHandler", remap = false)
public abstract class AiKleeSlabMixin {
    @Inject(method = "onBreakBlock", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void firefly$enter(@Coerce Object event, CallbackInfo ci) { if (!NativeKleeSlabScope.enter(event)) ci.cancel(); }
    @WrapOperation(method = "onBreakBlock", at = @At(value = "INVOKE", target = "Lnet/blay09/mods/kleeslabs/BlockBreakHandler;rayTrace(Lnet/minecraft/world/entity/LivingEntity;D)Lnet/minecraft/world/phys/BlockHitResult;"), remap = false, require = 0)
    private static BlockHitResult firefly$hit(LivingEntity entity, double reach, Operation<BlockHitResult> original) {
        var hit = NativeKleeSlabScope.hit(entity); return hit == null ? original.call(entity, reach) : hit;
    }
    @WrapOperation(method = "onBreakBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"), remap = false, require = 0)
    private static boolean firefly$loot(Level level, Entity entity, Operation<Boolean> original) { return NativeKleeSlabScope.spawn(level, entity, () -> original.call(level, entity)); }
    @WrapOperation(method = "onBreakBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"), remap = false, require = 0)
    private static boolean firefly$write(Level level, BlockPos pos, BlockState state, int flags, Operation<Boolean> original) {
        return NativeKleeSlabScope.write(level, pos, state, flags, () -> original.call(level, pos, state, flags));
    }
    @Inject(method = "onBreakBlock", at = @At("TAIL"), remap = false, require = 0)
    private static void firefly$finished(@Coerce Object event, CallbackInfo ci) { NativeKleeSlabScope.finished(event); }
}
