package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.blueprint.ownership.OwnershipAccess;
import net.minecraft.util.BitStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.chunk.PalettedContainer$Data", remap = false)
public interface AiOwnershipPaletteDataAccess extends OwnershipAccess.PaletteData {
    @Override @Accessor("storage") BitStorage fireflymc$ownershipBits();
}
