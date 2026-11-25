package firefly520.fireflymc.ai.processing.advanced;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.fml.ModList;
import firefly520.fireflymc.ai.processing.RecipeInputIsolation;

/** Exact optional APIs only. Never resolves a method name or class supplied by a model/client. */
final class AdvancedReflection {
    private AdvancedReflection() {}

    static boolean version(String id, String expected) {
        return ModList.get() != null && ModList.get().getModContainerById(id)
                .map(mod -> expected.equals(mod.getModInfo().getVersion().toString())).orElse(false);
    }

    static Class<?> type(String name) {
        try { return Class.forName(name, false, AdvancedReflection.class.getClassLoader()); }
        catch (ClassNotFoundException | LinkageError error) { throw failure("ADVANCED_API_UNAVAILABLE"); }
    }

    static Object call(Object target, String name, Class<?>[] parameters, Object... arguments) {
        return invoke(target.getClass(), target, name, parameters, arguments);
    }

    static Object call(Object target, String name) { return call(target, name, new Class<?>[0]); }

    static Object invoke(Class<?> owner, Object target, String name, Class<?>[] parameters, Object... arguments) {
        try {
            var method = owner.getMethod(name, parameters);
            if (Modifier.isStatic(method.getModifiers()) != (target == null)) throw failure("ADVANCED_API_MISMATCH");
            return method.invoke(target, arguments);
        } catch (InvocationTargetException error) {
            // Preserve server-side diagnostics; public tool results still expose only the fixed error code.
            throw new IllegalStateException("ADVANCED_NATIVE_OPERATION_FAILED", error.getCause());
        }
        catch (ReflectiveOperationException | LinkageError error) { throw failure("ADVANCED_API_MISMATCH"); }
    }

    static Object field(String owner, String name) {
        try { return type(owner).getField(name).get(null); }
        catch (ReflectiveOperationException | LinkageError error) { throw failure("ADVANCED_API_MISMATCH"); }
    }

    static Object field(Object owner, String name) {
        try { return owner.getClass().getField(name).get(owner); }
        catch (ReflectiveOperationException | LinkageError error) { throw failure("ADVANCED_API_MISMATCH"); }
    }

    static Object construct(String type, Class<?>[] parameters, Object... arguments) {
        try { return type(type).getConstructor(parameters).newInstance(arguments); }
        catch (ReflectiveOperationException | LinkageError error) { throw failure("ADVANCED_API_MISMATCH"); }
    }

    static void current(RecipeHolder<?> holder, ServerLevel level) {
        thread(level);
        if (holder == null || level.getRecipeManager().byKey(holder.id()).map(RecipeHolder::value).orElse(null) != holder.value())
            throw failure("PROCESSING_RECIPE_STALE");
        if (BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()) == null
                || BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().getSerializer()) == null)
            throw failure("ADVANCED_RECIPE_UNREGISTERED");
    }

    static void thread(ServerLevel level) {
        if (level == null || !level.getServer().isSameThread()) throw failure("SERVER_THREAD_REQUIRED");
    }

    static ResourceLocation id(String value) { return ResourceLocation.parse(value); }
    static List<ItemStack> emptySlots(int count) {
        List<ItemStack> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(ItemStack.EMPTY);
        return result;
    }
    static ItemStack copy(ItemStack stack, HolderLookup.Provider registries) { return RecipeInputIsolation.copy(stack, registries); }
    static boolean same(ItemStack left, ItemStack right, HolderLookup.Provider registries) {
        return firefly520.fireflymc.ai.material.ItemSnapshotCodec.capture(left, registries)
                .equals(firefly520.fireflymc.ai.material.ItemSnapshotCodec.capture(right, registries));
    }
    static IllegalStateException failure(String code) { return new IllegalStateException(code); }
}
