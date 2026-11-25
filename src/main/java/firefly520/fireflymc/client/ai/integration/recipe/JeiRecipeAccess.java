package firefly520.fireflymc.client.ai.integration.recipe;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Stream;
import static firefly520.fireflymc.ai.recipe.view.RecipeViewData.*;
import static firefly520.fireflymc.client.ai.integration.recipe.RecipeReflection.*;

/** JEI 19.39.0.369 display/query bridge. EMI's JEMI proxy is detected because it drops exact recipe lists. */
final class JeiRecipeAccess implements RecipeViewerAccess {
    private final Class<?> runtimeType = type("mezz.jei.api.runtime.IJeiRuntime");
    private final Class<?> managerType = type("mezz.jei.api.recipe.IRecipeManager");
    private final Class<?> guiType = type("mezz.jei.api.runtime.IRecipesGui");
    private final Class<?> categoryType = type("mezz.jei.api.recipe.category.IRecipeCategory");
    private final Class<?> recipeType = type("mezz.jei.api.recipe.RecipeType");
    private final Class<?> categoriesType = type("mezz.jei.api.recipe.IRecipeCategoriesLookup");
    private final Class<?> lookupType = type("mezz.jei.api.recipe.IRecipeLookup");
    private final Class<?> helpersType = type("mezz.jei.api.helpers.IJeiHelpers");
    private final Class<?> factoryType = type("mezz.jei.api.recipe.IFocusFactory");
    private final Class<?> focusType = type("mezz.jei.api.recipe.IFocus");
    private final Class<?> roleType = type("mezz.jei.api.recipe.RecipeIngredientRole");
    private final Class<?> ingredientType = type("mezz.jei.api.ingredients.IIngredientType");
    private final Class<?> supplierType = type("mezz.jei.api.ingredients.IIngredientSupplier");
    private final Class<?> typedType = type("mezz.jei.api.ingredients.ITypedIngredient");
    private final Method getRuntime = method(type("mezz.jei.common.Internal"), "getJeiRuntime", true, runtimeType);
    private final Method manager = method(runtimeType, "getRecipeManager", false, managerType);
    private final Method gui = method(runtimeType, "getRecipesGui", false, guiType);
    private final Method helpers = method(runtimeType, "getJeiHelpers", false, helpersType);
    private final Method factory = method(helpersType, "getFocusFactory", false, factoryType);
    private final Method focus = method(factoryType, "createFocus", false, focusType, roleType, ingredientType, Object.class);
    private final Method categories = method(managerType, "createRecipeCategoryLookup", false, categoriesType);
    private final Method categoryFocus = method(categoriesType, "limitFocus", false, categoriesType, Collection.class);
    private final Method categoryStream = method(categoriesType, "get", false, Stream.class);
    private final Method categoryRecipeType = method(categoryType, "getRecipeType", false, recipeType);
    private final Method uid = method(recipeType, "getUid", false, ResourceLocation.class);
    private final Method lookup = method(managerType, "createRecipeLookup", false, lookupType, recipeType);
    private final Method recipeFocus = method(lookupType, "limitFocus", false, lookupType, Collection.class);
    private final Method recipeStream = method(lookupType, "get", false, Stream.class);
    private final Method ingredients = method(managerType, "getRecipeIngredients", false, supplierType, categoryType, Object.class);
    private final Method byRole = method(supplierType, "getIngredients", false, List.class, roleType);
    private final Method value = method(typedType, "getIngredient", false, Object.class);
    private final Method show = method(guiType, "showRecipes", false, void.class, categoryType, List.class, List.class);
    private final Class<?> nativeScreen = type("mezz.jei.gui.recipes.RecipesGui");
    private final Class<?> layoutsType = type("mezz.jei.gui.recipes.RecipeGuiLayouts");
    private final Field layouts = readField(nativeScreen, "layouts", layoutsType);
    private final Field visibleLayouts = readField(layoutsType, "recipeLayoutsWithButtons", List.class);
    private final Class<?> layoutType = type("mezz.jei.api.gui.IRecipeLayoutDrawable");
    private final Method getLayout = method(type("mezz.jei.gui.recipes.IRecipeLayoutWithButtons"), "getRecipeLayout", false, layoutType);
    private final Method getLayoutRecipe = method(layoutType, "getRecipe", false, Object.class);
    JeiRecipeAccess() throws ReflectiveOperationException {}
    @Override public Object runtime() throws ReflectiveOperationException {
        try { return nonnull(call(getRuntime, null)); }
        catch (InvocationTargetException notReady) {
            if (notReady.getCause() instanceof IllegalStateException) throw unavailable("JEI_RUNTIME_NOT_READY");
            throw notReady;
        }
    }
    private Object role(Role role) throws ReflectiveOperationException { return roleType.getField(role.name()).get(null); }
    @Override public Cursor discover(Object runtime, ItemStack item, Role role) throws ReflectiveOperationException {
        Object manager = call(this.manager, runtime);
        Object factory = call(this.factory, call(helpers, runtime));
        Object itemType = type("mezz.jei.api.constants.VanillaTypes").getField("ITEM_STACK").get(null);
        List<?> focuses = List.of(call(focus, factory, role(role), itemType, item));
        Object lookup = call(categoryFocus, call(categories, manager), focuses);
        Stream<?> categories = (Stream<?>) call(categoryStream, lookup);
        var categoryIterator = categories.iterator();
        return new Cursor() {
            Stream<?> recipes = Stream.empty();
            Iterator<?> iterator = Collections.emptyIterator();
            Object category;
            String categoryId;
            boolean done;
            @Override public Entry next() throws ReflectiveOperationException {
                if (iterator.hasNext()) {
                    Object recipe = iterator.next();
                    String backingId = backing(recipe, "");
                    return new Entry(recipe, category, categoryId, backingId, backingId);
                }
                recipes.close();
                if (!categoryIterator.hasNext()) { done = true; return null; }
                category = categoryIterator.next();
                Object type = call(categoryRecipeType, category);
                categoryId = id(call(uid, type), false);
                Object recipeLookup = call(recipeFocus, call(JeiRecipeAccess.this.lookup, manager, type), focuses);
                recipes = (Stream<?>) call(recipeStream, recipeLookup); iterator = recipes.iterator();
                return null;
            }
            @Override public boolean exhausted() { return done; }
            @Override public void close() { recipes.close(); categories.close(); done = true; }
        };
    }
    @Override public Details describe(Object runtime, Entry entry) throws ReflectiveOperationException {
        Object supplier = call(ingredients, call(manager, runtime), entry.category(), entry.recipe());
        List<?> inputs = (List<?>) call(byRole, supplier, role(Role.INPUT));
        List<?> outputs = (List<?>) call(byRole, supplier, role(Role.OUTPUT));
        return new Details(hints(inputs), hints(outputs), inputs.size() > MAX_HINTS || outputs.size() > MAX_HINTS);
    }
    private List<IngredientHint> hints(List<?> list) throws ReflectiveOperationException {
        var result = new ArrayList<IngredientHint>();
        for (int i = 0; i < Math.min(MAX_HINTS, list.size()); i++) result.add(hint(call(value, list.get(i)), -1, false));
        return result;
    }
    @Override public boolean exactDisplaySupported(Object runtime) throws ReflectiveOperationException {
        return nativeScreen.isInstance(call(gui, runtime));
    }
    @Override public void open(Object runtime, Entry entry) throws ReflectiveOperationException {
        if (!exactDisplaySupported(runtime)) throw unavailable("JEI_EXACT_DISPLAY_UNAVAILABLE");
        call(show, call(gui, runtime), entry.category(), List.of(entry.recipe()), List.of());
    }
    @Override public boolean visible(Object runtime, Entry entry) throws ReflectiveOperationException {
        Object screen = Minecraft.getInstance().screen;
        if (!nativeScreen.isInstance(screen) || screen != call(gui, runtime)) return false;
        Object layoutSet = layouts.get(screen);
        for (Object buttons : (List<?>) visibleLayouts.get(layoutSet)) {
            Object actual = call(getLayoutRecipe, call(getLayout, buttons));
            if (actual == entry.recipe() || actual.equals(entry.recipe())) return true;
        }
        return false;
    }
}
