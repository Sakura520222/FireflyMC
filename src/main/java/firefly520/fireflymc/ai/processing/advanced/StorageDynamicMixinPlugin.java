package firefly520.fireflymc.ai.processing.advanced;

import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** FML metadata and JDK-only bootstrap. Never loads a target or the storage runtime in postApply. */
public final class StorageDynamicMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String prefix) { StorageDynamicHooks.prepare(); }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        return matched("sophisticatedstorage", "1.5.80", "1.21.1-1.5.80.1999")
                && matched("sophisticatedcore", "1.4.77", "1.21.1-1.4.77.2173");
    }
    private static boolean matched(String id, String version, String build) {
        try {
            Object loading = Class.forName("net.neoforged.fml.loading.FMLLoader").getMethod("getLoadingModList").invoke(null);
            for (Object mod : (List<?>) loading.getClass().getMethod("getMods").invoke(loading)) {
                if (!id.equals(mod.getClass().getMethod("getModId").invoke(mod))) continue;
                if (!version.equals(mod.getClass().getMethod("getVersion").invoke(mod).toString())) return false;
                Object info = mod.getClass().getMethod("getOwningFile").invoke(mod);
                Object file = info.getClass().getMethod("getFile").invoke(info);
                Path path = (Path) file.getClass().getMethod("getFilePath").invoke(file);
                try (var jar = new JarFile(path.toFile())) {
                    return jar.getManifest() != null && build.equals(jar.getManifest().getMainAttributes().getValue("Implementation-Version"));
                }
            }
        } catch (ReflectiveOperationException | java.io.IOException | RuntimeException unavailable) { return false; }
        return false;
    }
    @Override public void acceptTargets(Set<String> own, Set<String> other) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) { StorageDynamicHooks.applied(target); }
}
