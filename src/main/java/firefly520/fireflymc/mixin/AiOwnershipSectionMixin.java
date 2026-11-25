package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.blueprint.ownership.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LevelChunkSection.class, remap = false)
public abstract class AiOwnershipSectionMixin implements OwnershipAccess.Section {
    @Shadow @Final private PalettedContainer<BlockState> states;
    @Unique private final OwnershipSectionBinding fireflymc$binding = new OwnershipSectionBinding();
    @Override public OwnershipSectionBinding fireflymc$ownershipSection() { return fireflymc$binding; }
    @Inject(method = "<init>", at = @At("RETURN"))
    private void fireflymc$ownershipPaletteBound(CallbackInfo callback) {
        ((OwnershipAccess.Palette) states).fireflymc$bindOwnershipPalette(fireflymc$binding);
    }
}
