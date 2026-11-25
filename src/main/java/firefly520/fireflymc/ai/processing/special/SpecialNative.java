package firefly520.fireflymc.ai.processing.special;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.fml.ModList;
import firefly520.fireflymc.ai.processing.RecipeInputIsolation;

/** Reflection is restricted to adapter-owned class and member names, never model arguments. */
final class SpecialNative {
    private SpecialNative() {}

    static boolean version(String id, String version) {
        return ModList.get() != null && ModList.get().getModContainerById(id)
                .map(mod -> version.equals(mod.getModInfo().getVersion().toString())).orElse(false);
    }

    static void current(RecipeHolder<?> holder, ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        if (holder == null || level.getRecipeManager().byKey(holder.id()).map(RecipeHolder::value).orElse(null) != holder.value())
            throw new IllegalStateException("PROCESSING_RECIPE_STALE");
    }

    static Object call(Object value, String method, Class<?> returns, Class<?>[] parameters, Object... arguments) {
        try {
            var member = value.getClass().getMethod(method, parameters);
            if (Modifier.isStatic(member.getModifiers()) || member.getReturnType() != returns)
                throw new NoSuchMethodException();
            return member.invoke(value, arguments);
        } catch (InvocationTargetException failure) {
            throw new IllegalArgumentException("SPECIAL_NATIVE_RECIPE_REJECTED", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("SPECIAL_PROCESSING_API_MISMATCH", failure);
        }
    }

    static Object get(Object value, String method, Class<?> returns) {
        return call(value, method, returns, new Class<?>[0]);
    }

    static Object callStatic(String type, String method, Class<?> returns, Class<?>[] parameters, Object... arguments) {
        try {
            var member = Class.forName(type, false, SpecialNative.class.getClassLoader()).getMethod(method, parameters);
            if (!Modifier.isStatic(member.getModifiers()) || member.getReturnType() != returns) throw new NoSuchMethodException();
            return member.invoke(null, arguments);
        } catch (InvocationTargetException failure) {
            throw new IllegalArgumentException("SPECIAL_NATIVE_RECIPE_REJECTED", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("SPECIAL_PROCESSING_API_MISMATCH", failure);
        }
    }

    static Object create(String name, Class<?>[] parameters, Object... arguments) {
        try {
            return Class.forName(name, false, SpecialNative.class.getClassLoader()).getConstructor(parameters).newInstance(arguments);
        } catch (InvocationTargetException failure) {
            throw new IllegalArgumentException("SPECIAL_NATIVE_INPUT_REJECTED", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("SPECIAL_PROCESSING_API_MISMATCH", failure);
        }
    }

    static boolean instance(String name, Object value) {
        try { return Class.forName(name, false, SpecialNative.class.getClassLoader()).isInstance(value); }
        catch (ClassNotFoundException failure) { throw new IllegalStateException("SPECIAL_PROCESSING_API_MISMATCH", failure); }
    }

    static Object field(String type, String field) {
        try {
            var member = Class.forName(type, false, SpecialNative.class.getClassLoader()).getField(field);
            if (!Modifier.isStatic(member.getModifiers())) throw new NoSuchFieldException();
            return member.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("SPECIAL_PROCESSING_API_MISMATCH", failure);
        }
    }

    static ResourceLocation id(String value) { return ResourceLocation.parse(value); }

    static ItemStack stack(ItemStack value, ServerLevel level, boolean allowEmpty) {
        if (value == null || (!allowEmpty && value.isEmpty()) || (!value.isEmpty()
                && (value.getCount() < 1 || value.getCount() > value.getMaxStackSize()
                || !BuiltInRegistries.ITEM.containsKey(BuiltInRegistries.ITEM.getKey(value.getItem())))))
            throw new IllegalArgumentException("SPECIAL_PROCESSING_OUTPUT_INVALID");
        return RecipeInputIsolation.copy(value, level.registryAccess());
    }

    static List<ItemStack> units(List<ItemStack> supplied, int size, ServerLevel level) {
        if (supplied == null || supplied.size() != size || supplied.stream().anyMatch(s -> s == null))
            throw new IllegalArgumentException("SPECIAL_PROCESSING_INPUT_SIZE");
        return RecipeInputIsolation.copyAll(supplied.stream().map(s -> s.isEmpty() ? ItemStack.EMPTY : s.copyWithCount(1)).toList(), level.registryAccess());
    }
}
