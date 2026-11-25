package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.processing.NativeExperienceAward;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ExperienceOrb.class, remap = false)
public abstract class AiExperienceOrbAwardMixin {
    @Redirect(method = "tryMergeToExisting", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/ExperienceOrb;count:I", opcode = Opcodes.GETFIELD))
    private static int fireflymc$beforeMerge(ExperienceOrb orb) {
        int count = ((AiExperienceOrbAccess) orb).fireflymc$count();
        NativeExperienceAward.beforeMerge(orb, count); return count;
    }
    @Inject(method = "tryMergeToExisting", at = @At("RETURN"))
    private static void fireflymc$afterMerge(ServerLevel level, Vec3 pos, int value, CallbackInfoReturnable<Boolean> cir) {
        NativeExperienceAward.afterMerge(level, value, cir.getReturnValueZ());
    }
    @Redirect(method = "award", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private static boolean fireflymc$spawn(ServerLevel level, Entity entity) {
        return NativeExperienceAward.spawn(level, entity, () -> level.addFreshEntity(entity));
    }
}
