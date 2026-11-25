package firefly520.fireflymc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import firefly520.fireflymc.ai.integration.maid.MaidCombatScope;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = ExperienceOrb.class, remap = false)
public abstract class AiMaidCombatExperienceMixin implements MaidCombatScope.ExperienceHook {
    @WrapMethod(method = "award")
    private static void fireflymc$nativeXp(ServerLevel level, Vec3 position, int amount, Operation<Void> original) {
        MaidCombatScope.experience(level, position, amount, () -> original.call(level, position, amount));
    }
}
