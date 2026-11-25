package firefly520.fireflymc.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Optional;

/** Reads only already loaded native sections; it never loads or invents POI data. */
@Mixin(value = SectionStorage.class, remap = false)
public interface AiPoiStorageAccess {
    @Accessor("storage") Long2ObjectMap<Optional<?>> fireflymc$loadedSections();
}
