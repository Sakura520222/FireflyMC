package firefly520.fireflymc.client.ai.integration.recipe;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.material.Fluid;
import java.lang.reflect.*;
import java.util.*;
import static firefly520.fireflymc.ai.recipe.view.RecipeViewData.*;
import static firefly520.fireflymc.client.ai.integration.recipe.RecipeReflection.*;

/** Exact EMI 1.1.24 API, with native screen readback. Never invokes transfer, cheat or inventory functions. */
final class EmiRecipeAccess implements RecipeViewerAccess {
    private final Class<?> api = type("dev.emi.emi.api.EmiApi");
    private final Class<?> manager = type("dev.emi.emi.api.recipe.EmiRecipeManager");
    private final Class<?> recipe = type("dev.emi.emi.api.recipe.EmiRecipe");
    private final Class<?> category = type("dev.emi.emi.api.recipe.EmiRecipeCategory");
    private final Class<?> ingredient = type("dev.emi.emi.api.stack.EmiIngredient");
    private final Class<?> stack = type("dev.emi.emi.api.stack.EmiStack");
    private final Method recipeManager = method(api, "getRecipeManager", true, manager);
    private final Method loaded = method(type("dev.emi.emi.runtime.EmiReloadManager"), "isLoaded", true, boolean.class);
    private final Method of = method(stack, "of", true, stack, ItemStack.class);
    private final Method byInput = method(manager, "getRecipesByInput", false, List.class, stack);
    private final Method byOutput = method(manager, "getRecipesByOutput", false, List.class, stack);
    private final Method byId = method(manager, "getRecipe", false, recipe, ResourceLocation.class);
    private final Method getCategory = method(recipe, "getCategory", false, category);
    private final Method categoryId = method(category, "getId", false, ResourceLocation.class);
    private final Method recipeId = method(recipe, "getId", false, ResourceLocation.class);
    private final Method backing = method(recipe, "getBackingRecipe", false, RecipeHolder.class);
    private final Method inputs = method(recipe, "getInputs", false, List.class);
    private final Method outputs = method(recipe, "getOutputs", false, List.class);
    private final Method alternatives = method(ingredient, "getEmiStacks", false, List.class);
    private final Method amount = method(ingredient, "getAmount", false, long.class);
    private final Method chance = method(ingredient, "getChance", false, float.class);
    private final Method key = method(stack, "getKey", false, Object.class);
    private final Method itemId = method(stack, "getId", false, ResourceLocation.class);
    private final Method display = method(api, "displayRecipe", true, void.class, recipe);
    private final Class<?> screen = type("dev.emi.emi.screen.RecipeScreen");
    private final Field page = readField(screen, "currentPage", List.class);
    private final Field displayedRecipe = type("dev.emi.emi.screen.WidgetGroup").getField("recipe");
    EmiRecipeAccess() throws ReflectiveOperationException {
        if (displayedRecipe.getType() != recipe) throw new NoSuchFieldException("EMI_WIDGET_RECIPE");
    }
    @Override public Object runtime() throws ReflectiveOperationException {
        if (!(boolean) call(loaded, null)) throw unavailable("EMI_RUNTIME_NOT_READY");
        return nonnull(call(recipeManager, null));
    }
    private Entry entry(Object value) throws ReflectiveOperationException {
        Object cat = nonnull(call(getCategory, value));
        String displayId = id(call(recipeId, value), true);
        RecipeHolder<?> nativeRecipe = (RecipeHolder<?>) call(backing, value);
        return new Entry(value, cat, id(call(categoryId, cat), false), displayId,
                nativeRecipe == null ? "" : RecipeReflection.backing(nativeRecipe, ""));
    }
    @Override public Cursor discover(Object runtime, ItemStack item, Role role) throws ReflectiveOperationException {
        var values = ((List<?>) call(role == Role.INPUT ? byInput : byOutput, runtime, call(of, null, item))).iterator();
        return new Cursor() {
            @Override public Entry next() throws ReflectiveOperationException { return values.hasNext() ? entry(values.next()) : null; }
            @Override public boolean exhausted() { return !values.hasNext(); }
            @Override public void close() {}
        };
    }
    @Override public Entry direct(Object runtime, String requested, String hint) throws ReflectiveOperationException {
        Object found = call(byId, runtime, ResourceLocation.parse(hint.isEmpty() ? requested : hint));
        return found == null ? null : entry(found);
    }
    @Override public Details describe(Object runtime, Entry entry) throws ReflectiveOperationException {
        boolean[] truncated = {false};
        return new Details(hints((List<?>) call(inputs, entry.recipe()), truncated),
                hints((List<?>) call(outputs, entry.recipe()), truncated), truncated[0]);
    }
    private List<IngredientHint> hints(List<?> ingredients, boolean[] truncated) throws ReflectiveOperationException {
        var result = new ArrayList<IngredientHint>();
        for (int group = 0; group < ingredients.size(); group++) {
            if (group >= MAX_HINTS || result.size() >= MAX_HINTS) { truncated[0] = true; break; }
            Object value = ingredients.get(group);
            long quantity = (long) call(amount, value);
            float probability = (float) call(chance, value);
            if (!Float.isFinite(probability) || quantity < 0 || quantity > 1_000_000_000) throw unavailable("EMI_INGREDIENT_VALUE_INVALID");
            for (Object alternative : (List<?>) call(alternatives, value)) {
                if (result.size() >= MAX_HINTS) { truncated[0] = true; break; }
                Object kind = call(key, alternative);
                IngredientKind type = kind instanceof Item ? IngredientKind.ITEM : kind instanceof Fluid ? IngredientKind.FLUID : IngredientKind.CUSTOM;
                result.add(new IngredientHint(type, id(call(itemId, alternative), true), quantity,
                        Math.clamp(probability, 0, 1), true, group, true));
            }
        }
        return List.copyOf(result);
    }
    @Override public boolean exactDisplaySupported(Object runtime) { return true; }
    @Override public void open(Object runtime, Entry entry) throws ReflectiveOperationException { call(display, null, entry.recipe()); }
    @Override public boolean visible(Object runtime, Entry entry) throws ReflectiveOperationException {
        var current = Minecraft.getInstance().screen;
        if (!screen.isInstance(current)) return false;
        for (Object widget : (List<?>) page.get(current)) if (displayedRecipe.get(widget) == entry.recipe()) return true;
        return false;
    }
}
