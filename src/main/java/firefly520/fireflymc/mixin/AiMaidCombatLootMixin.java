package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.integration.maid.MaidCombatScope;
import java.util.Collection;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.neoforge.common.CommonHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CommonHooks.class, remap = false)
public abstract class AiMaidCombatLootMixin implements MaidCombatScope.LootHook {
    @Inject(method = "onLivingDrops", at = @At("RETURN"))
    private static void fireflymc$nativeLoot(LivingEntity target, DamageSource source, Collection<ItemEntity> drops, boolean hit,
                                          CallbackInfoReturnable<Boolean> result) {
        MaidCombatScope.drops(target, source, drops, result.getReturnValue());
    }
}
