package firefly520.fireflymc.mixin;

import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Map;

/** Cache-only binding: never initializes a missing Mod store or performs a disk read. */
@Mixin(value = DimensionDataStorage.class, remap = false)
public interface AiSavedDataCacheAccess {
    @Accessor("cache") Map<String, SavedData> fireflymc$existingStores();
}
