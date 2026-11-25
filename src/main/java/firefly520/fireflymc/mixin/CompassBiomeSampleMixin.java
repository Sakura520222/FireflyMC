package firefly520.fireflymc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import firefly520.fireflymc.ai.integration.exploration.NativeCompassSearchScope;
import firefly520.fireflymc.ai.integration.exploration.CompassSampleGuardedWorker;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "com.chaosthedude.naturescompass.util.BiomeSearchWorker", remap = false)
public abstract class CompassBiomeSampleMixin implements CompassSampleGuardedWorker {
    @WrapOperation(method = {"doWork", "checkConnections"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/BiomeSource;getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;", remap = false), remap = false)
    private Holder<Biome> fireflymc$boundedNoiseSample(BiomeSource source, int x, int y, int z, Climate.Sampler sampler, Operation<Holder<Biome>> original) {
        NativeCompassSearchScope.beforeBiome(x, y, z); return original.call(source, x, y, z, sampler);
    }
}
