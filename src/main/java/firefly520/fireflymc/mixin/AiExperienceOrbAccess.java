package firefly520.fireflymc.mixin;

import net.minecraft.world.entity.ExperienceOrb;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ExperienceOrb.class, remap = false)
public interface AiExperienceOrbAccess {
    @Accessor("count") int fireflymc$count();
}
