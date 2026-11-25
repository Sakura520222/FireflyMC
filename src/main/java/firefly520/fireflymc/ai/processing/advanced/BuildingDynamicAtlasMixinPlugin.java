package firefly520.fireflymc.ai.processing.advanced;

import java.util.*;
import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;

/** Enables exactly the inspected server contracts; ordinary atlas crafting is unchanged. */
public final class BuildingDynamicAtlasMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String name) { BuildingDynamicAtlasHooks.ready(); }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        var loading = FMLLoader.getLoadingModList();
        return loading != null && loading.getMods().stream().anyMatch(mod -> "map_atlases".equals(mod.getModId())
                && "1.21-6.6.0".equals(mod.getVersion().toString()));
    }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) { BuildingDynamicAtlasHooks.applied(mixin); }
}
