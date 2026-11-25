package firefly520.fireflymc.ai.integration.trees;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Models the transform-time boundary: Minecraft and the tree runtime cannot load here. */
class DynamicTreeMixinBootstrapTest {
    private static final String PLUGIN = "firefly520.fireflymc.ai.integration.trees.DynamicTreeMixinPlugin";
    private static final String MARKERS = "firefly520.fireflymc.ai.integration.trees.DynamicTreeHooks";

    @Test void postApplyRecordsHooksWithoutLoadingMinecraftOrNativeRuntime() throws Exception {
        var loader = new BootstrapBoundaryLoader();
        Class<?> pluginClass = loader.loadClass(PLUGIN);
        Object plugin = pluginClass.getConstructor().newInstance();
        pluginClass.getMethod("onLoad", String.class).invoke(plugin, "firefly520.fireflymc.mixin");
        var postApply = pluginClass.getMethod("postApply", String.class, ClassNode.class, String.class, IMixinInfo.class);
        var available = loader.loadClass(MARKERS).getDeclaredMethod("available"); available.setAccessible(true);
        assertEquals(false, available.invoke(null));
        for (String target : new String[]{"com.dtteam.dynamictrees.systems.FutureBreak",
                "com.dtteam.dynamictrees.entity.FallingTreeEntity", "net.minecraft.server.level.ServerLevel",
                "net.minecraft.world.entity.item.ItemEntity"})
            postApply.invoke(plugin, target, null, "fixture.Mixin", null);
        assertEquals(false, available.invoke(null), "A missing before-collision guard cannot report ready");
        postApply.invoke(plugin, "com.dtteam.dynamictrees.entity.animation.FalloverAnimationHandler", null, "fixture.Mixin", null);
        assertEquals(false, available.invoke(null), "The causal scheduled-root hook must also be installed");
        postApply.invoke(plugin, "net.minecraft.world.ticks.LevelTicks", null, "fixture.Mixin", null);
        assertEquals(true, available.invoke(null));
    }

    @Test void missingModMetadataDoesNotInstallMinecraftTreeHooksOrLoadRuntime() throws Exception {
        var loader = new BootstrapBoundaryLoader(); Class<?> type = loader.loadClass(PLUGIN);
        Object plugin = type.getConstructor().newInstance();
        var shouldApply = type.getMethod("shouldApplyMixin", String.class, String.class);
        assertEquals(false, shouldApply.invoke(plugin, "net.minecraft.server.level.ServerLevel", "fixture.TreeTickMixin"));
        assertEquals(false, shouldApply.invoke(plugin, "net.minecraft.world.entity.item.ItemEntity", "fixture.TreeItemMixin"));
        assertEquals(false, shouldApply.invoke(plugin, "com.dtteam.dynamictrees.entity.FallingTreeEntity", "fixture.FallingMixin"));
    }

    private static final class BootstrapBoundaryLoader extends ClassLoader {
        BootstrapBoundaryLoader() { super(DynamicTreeMixinBootstrapTest.class.getClassLoader()); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("net.minecraft.") || name.startsWith("net.neoforged.")
                    || name.equals("firefly520.fireflymc.ai.integration.trees.DynamicTreeNativeScope"))
                throw new ClassNotFoundException("Forbidden during transform: " + name);
            if (!name.equals(PLUGIN) && !name.equals(MARKERS)) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    String resource = name.replace('.', '/') + ".class";
                    try (InputStream input = getParent().getResourceAsStream(resource)) {
                        if (input == null) throw new ClassNotFoundException(name);
                        byte[] bytes = input.readAllBytes(); loaded = defineClass(name, bytes, 0, bytes.length);
                    } catch (IOException unavailable) { throw new ClassNotFoundException(name, unavailable); }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }
}
