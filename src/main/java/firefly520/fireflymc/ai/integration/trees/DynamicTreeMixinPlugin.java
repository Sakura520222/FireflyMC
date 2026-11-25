package firefly520.fireflymc.ai.integration.trees;

import java.util.*;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;

/** Optional exact-class targets: no DT classes appear in FireflyMC's common linkage graph. */
public final class DynamicTreeMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) { DynamicTreeHooks.prepare(); }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        try {
            Class<?> loadingType = Class.forName("net.neoforged.fml.loading.LoadingModList");
            Object loading = loadingType.getMethod("get").invoke(null);
            var versions = new HashMap<String, String>();
            Class<?> contract = Class.forName("net.neoforged.neoforgespi.language.IModInfo");
            for (Object mod : (List<?>) loadingType.getMethod("getMods").invoke(loading))
                versions.put((String) contract.getMethod("getModId").invoke(mod), String.valueOf(contract.getMethod("getVersion").invoke(mod)));
            return "1.7.2".equals(versions.get("dynamictrees"))
                    && (!versions.containsKey("dynamictreesplus") || "1.3.2".equals(versions.get("dynamictreesplus")))
                    && (targetClassName.startsWith("net.minecraft.") || present(targetClassName));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return false; }
    }
    private static boolean present(String name) {
        try { org.spongepowered.asm.service.MixinService.getService().getBytecodeProvider().getClassNode(name); return true; }
        catch (Exception unavailable) { return false; }
    }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) { DynamicTreeHooks.applied(target); }
}
