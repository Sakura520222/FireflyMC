package firefly520.fireflymc.ai.processing.advanced;

import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import static org.junit.jupiter.api.Assertions.*;

class BuildingDynamicAtlasContractTest {
    private static final String PREFIX = "firefly520.fireflymc.ai.processing.advanced.";
    @Test void headerViewNeverReadsPixelsOrUsesWorldMapLoadingAllocationOrFileApis() throws Exception {
        var forbidden = new ArrayList<String>();
        try (var bytes = getClass().getClassLoader().getResourceAsStream(PREFIX.replace('.', '/') + "BuildingDynamicAtlasHeaders.class")) {
            assertNotNull(bytes);
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitFieldInsn(int opcode, String owner, String field, String descriptor) {
                            if (owner.endsWith("/MapItemSavedData") && Set.of("colors", "decorations", "carriedBy", "bannerMarkers", "frameMarkers").contains(field))
                                forbidden.add(name + ":" + field);
                        }
                        @Override public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean iface) {
                            if (owner.endsWith("/DimensionDataStorage") && Set.of("get", "computeIfAbsent", "set").contains(method)
                                    || owner.endsWith("/ServerLevel") && Set.of("getMapData", "setMapData", "getFreeMapId").contains(method)
                                    || owner.equals("java/nio/file/Files")) forbidden.add(name + ":" + owner + "." + method);
                        }
                    };
                }
            }, 0);
        }
        assertEquals(List.of(), forbidden);
    }
    @Test void postApplyOnlyRecordsPureFlagsWithoutLoadingMinecraftOrTheRuntime() throws Exception {
        var parent = getClass().getClassLoader();
        var isolated = new ClassLoader(parent) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("net.minecraft.") || name.equals(PREFIX + "BuildingDynamicAtlasHeaders"))
                    throw new ClassNotFoundException("forbidden bootstrap runtime dependency");
                if (name.equals(PREFIX + "BuildingDynamicAtlasMixinPlugin") || name.equals(PREFIX + "BuildingDynamicAtlasHooks")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) try (InputStream bytes = parent.getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (bytes == null) throw new ClassNotFoundException(name);
                            byte[] data = bytes.readAllBytes(); loaded = defineClass(name, data, 0, data.length);
                        } catch (IOException failure) { throw new ClassNotFoundException(name, failure); }
                        if (resolve) resolveClass(loaded); return loaded;
                    }
                }
                return super.loadClass(name, resolve);
            }
        };
        Class<?> plugin = isolated.loadClass(PREFIX + "BuildingDynamicAtlasMixinPlugin");
        Object instance = plugin.getConstructor().newInstance();
        plugin.getMethod("onLoad", String.class).invoke(instance, "firefly520.fireflymc.mixin");
        Class<?> hooks = isolated.loadClass(PREFIX + "BuildingDynamicAtlasHooks");
        assertEquals(false, hooks.getMethod("ready").invoke(null));
        var applied = plugin.getMethod("postApply", String.class, ClassNode.class, String.class, IMixinInfo.class);
        applied.invoke(instance, "native-holder", null, "BuildingDynamicAtlasFindMixin", null);
        assertEquals(false, hooks.getMethod("ready").invoke(null));
        applied.invoke(instance, "native-item", null, "BuildingDynamicAtlasCollectionMixin", null);
        assertEquals(true, hooks.getMethod("ready").invoke(null));
    }
}
