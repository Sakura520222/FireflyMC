package firefly520.fireflymc.ai.integration.travel;

import java.util.*;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;

/** Unsupported optional mod versions never receive a brittle private-method injection. */
public final class WaystoneTravelMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!mixinClassName.equals("firefly520.fireflymc.mixin.AiWaystoneTravelGuardMixin")) return false;
        try {
            var type = Class.forName("net.neoforged.fml.loading.LoadingModList");
            var loading = type.getMethod("get").invoke(null);
            var mods = (List<?>) type.getMethod("getMods").invoke(loading);
            var versions = new HashMap<String, String>();
            for (Object info : mods) {
                var contract = Class.forName("net.neoforged.neoforgespi.language.IModInfo");
                versions.put((String) contract.getMethod("getModId").invoke(info), String.valueOf(contract.getMethod("getVersion").invoke(info)));
            }
            return "21.1.37".equals(versions.get("waystones")) && "21.0.63".equals(versions.get("balm"));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return false; }
    }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {}
}
