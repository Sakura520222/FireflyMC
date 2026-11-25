package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.blueprint.ownership.*;
import net.minecraft.world.level.chunk.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ChunkAccess.class, remap = false)
public abstract class AiOwnershipChunkAccessMixin implements OwnershipAccess.Chunk {
    @Shadow @Final protected LevelChunkSection[] sections;
    @Unique private OwnershipChunkState fireflymc$ownershipState;
    @Override public synchronized OwnershipChunkState fireflymc$ownership() {
        if (fireflymc$ownershipState == null) fireflymc$ownershipState = new OwnershipChunkState();
        return fireflymc$ownershipState;
    }
    @Override public LevelChunkSection[] fireflymc$ownershipSections() { return sections; }
    @Inject(method = "getSections", at = @At("RETURN"))
    private void fireflymc$ownershipSectionsReturned(CallbackInfoReturnable<LevelChunkSection[]> callback) {
        OwnershipNativeHooks.sectionsReturned((ChunkAccess) (Object) this);
    }
}
