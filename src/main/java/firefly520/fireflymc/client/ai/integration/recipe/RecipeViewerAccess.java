package firefly520.fireflymc.client.ai.integration.recipe;

import firefly520.fireflymc.ai.recipe.view.RecipeViewData;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.fluids.FluidStack;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Objects;

import static firefly520.fireflymc.ai.recipe.view.RecipeViewData.*;

interface RecipeViewerAccess {
    record Entry(Object recipe, Object category, String categoryId, String displayId, String backingId) {}
    record Details(List<IngredientHint> inputs, List<IngredientHint> outputs, boolean truncated) {}
    interface Cursor extends AutoCloseable {
        /** One bounded step: null may mean category advancement; check exhausted() separately. */
        Entry next() throws ReflectiveOperationException;
        boolean exhausted();
        @Override void close();
    }
    Object runtime() throws ReflectiveOperationException;
    Cursor discover(Object runtime, ItemStack item, Role role) throws ReflectiveOperationException;
    default Entry direct(Object runtime, String recipeId, String displayHint) throws ReflectiveOperationException { return null; }
    Details describe(Object runtime, Entry entry) throws ReflectiveOperationException;
    boolean exactDisplaySupported(Object runtime) throws ReflectiveOperationException;
    void open(Object runtime, Entry entry) throws ReflectiveOperationException;
    boolean visible(Object runtime, Entry entry) throws ReflectiveOperationException;
}

final class RecipeReflection {
    private RecipeReflection() {}
    static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, false, RecipeReflection.class.getClassLoader());
    }
    static Method method(Class<?> owner, String name, boolean isStatic, Class<?> returns, Class<?>... parameters)
            throws NoSuchMethodException {
        Method method = owner.getMethod(name, parameters);
        if (Modifier.isStatic(method.getModifiers()) != isStatic || method.getReturnType() != returns)
            throw new NoSuchMethodException(owner.getName() + "." + name);
        return method;
    }
    /** Read-only UI verification; private member shape is pinned to the inspected installed version. */
    static Field readField(Class<?> owner, String name, Class<?> expected) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        if (field.getType() != expected || !field.trySetAccessible()) throw new NoSuchFieldException(name);
        return field;
    }
    static Object call(Method method, Object target, Object... arguments) throws ReflectiveOperationException {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Error error) throw error;
            throw failure;
        }
    }
    static String id(Object value, boolean nullable) {
        if (value == null && nullable) return "";
        if (!(value instanceof net.minecraft.resources.ResourceLocation location))
            throw new IllegalStateException("RECIPE_ID_SHAPE_MISMATCH");
        String result = location.toString();
        RecipeViewData.id(result, nullable);
        return result;
    }
    static String backing(Object recipe, String claimedDisplayId) {
        if (recipe instanceof RecipeHolder<?> holder) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return "";
            return mc.level.getRecipeManager().byKey(holder.id()).filter(current -> current.value() == holder.value())
                    .map(current -> current.id().toString()).orElse("");
        }
        if (!claimedDisplayId.isEmpty()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                var holder = mc.level.getRecipeManager().byKey(net.minecraft.resources.ResourceLocation.parse(claimedDisplayId));
                if (holder.isPresent() && holder.get().value() == recipe) return holder.get().id().toString();
            }
        }
        return "";
    }
    static IngredientHint hint(Object value, int group, boolean groupingKnown) {
        if (value instanceof ItemStack stack && !stack.isEmpty())
            return new IngredientHint(IngredientKind.ITEM, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    stack.getCount(), 1, true, group, groupingKnown);
        if (value instanceof FluidStack stack && !stack.isEmpty())
            return new IngredientHint(IngredientKind.FLUID, BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString(),
                    stack.getAmount(), 1, true, group, groupingKnown);
        return new IngredientHint(IngredientKind.CUSTOM, "", 0, 1, true, group, groupingKnown);
    }
    static Object nonnull(Object value) { return Objects.requireNonNull(value, "RECIPE_API_NULL"); }
    static IllegalStateException unavailable(String code) { return new IllegalStateException(code); }
}
