package firefly520.fireflymc.ai.processing;

import net.minecraft.core.component.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.neoforged.fml.ModList;
import java.lang.reflect.*;
import java.util.*;

/** Native CFB bread fallback is a component mechanism, not an invented RecipeManager entry. */
public final class CfbToastMechanism {
    public static final String ID = "cookingforblockheads:bread_toasting";
    private CfbToastMechanism() {}
    public static boolean available() {
        return ModList.get().getModContainerById("cookingforblockheads")
                .map(mod -> mod.getModInfo().getVersion().toString().equals("21.1.24")).orElse(false);
    }
    @SuppressWarnings("unchecked")
    private static DataComponentType<Unit> toasted() {
        var type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(ResourceLocation.fromNamespaceAndPath("cookingforblockheads", "toasted"));
        if (type == null) throw new IllegalStateException("CFB_TOAST_COMPONENT_UNAVAILABLE");
        return (DataComponentType<Unit>) type;
    }
    public static boolean veryToastedEnabled() {
        try {
            Class<?> config = Class.forName("net.blay09.mods.cookingforblockheads.CookingForBlockheadsConfig", false, CfbToastMechanism.class.getClassLoader());
            Class<?> data = Class.forName("net.blay09.mods.cookingforblockheads.CookingForBlockheadsConfigData", false, CfbToastMechanism.class.getClassLoader());
            Method active = config.getMethod("getActive");
            Field enabled = data.getField("allowVeryToastedBread");
            if (active.getReturnType() != data || !Modifier.isStatic(active.getModifiers()) || enabled.getType() != boolean.class)
                throw new NoSuchMethodException();
            return enabled.getBoolean(active.invoke(null));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("CFB_CONFIG_API_MISMATCH"); }
    }
    public static boolean candidate(ItemStack input, ServerLevel level) {
        return available() && input.is(Items.BREAD) && (!input.has(toasted()) || veryToastedEnabled());
    }
    public static VerifiedTransformation verify(ItemStack supplied, ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        if (!available() || !supplied.is(Items.BREAD) || supplied.getCount() != 1) throw new IllegalArgumentException("CFB_BREAD_INPUT_REQUIRED");
        ItemStack input = RecipeInputIsolation.copy(supplied, level.registryAccess());
        // toastItem tries registered nonempty toaster recipes before entering the bread fallback.
        for (var holder : level.getRecipeManager().getRecipes()) {
            if (!CfbRecipeProcessingAdapter.supports(holder.value()) || !holder.value().getClass().getSimpleName().equals("ToasterRecipe")) continue;
            @SuppressWarnings("unchecked") Recipe<SingleRecipeInput> recipe = (Recipe<SingleRecipeInput>) holder.value();
            var source = new SingleRecipeInput(RecipeInputIsolation.copy(input, level.registryAccess()));
            if (recipe.matches(source, level) && !recipe.assemble(source, level.registryAccess()).isEmpty())
                throw new IllegalStateException("EXPLICIT_TOASTER_RECIPE_AVAILABLE");
        }
        boolean already = input.has(toasted()), enabled = veryToastedEnabled();
        ItemStack output;
        if (already) {
            if (!enabled) throw new IllegalStateException("ALREADY_TOASTED");
            output = new ItemStack(Items.CHARCOAL);
            output.set(DataComponents.CUSTOM_NAME, Component.translatable("tooltip.cookingforblockheads.very_toasted"));
        } else {
            output = RecipeInputIsolation.copy(input, level.registryAccess());
            output.set(DataComponents.CUSTOM_NAME, Component.translatable("tooltip.cookingforblockheads.toasted", input.getHoverName()));
            output.set(toasted(), Unit.INSTANCE);
        }
        return new VerifiedTransformation(null, null, null, ID + "@21.1.24", List.of(input), List.of(1), List.of(output), List.of(ItemStack.EMPTY),
                new VerifiedTransformation.ExperienceReward(0, 1), new VerifiedTransformation.CallbackPlan("CFB_TOASTER", List.of(),
                List.of("ToasterBlockEntity#toastItem", "ToasterBlockEntity#toastBread"), true), VerifiedTransformation.SamplingEvidence.deterministic(),
                Map.of("stationRequired", "false", "waitTicks", "0", "allowVeryToastedBread", Boolean.toString(enabled),
                        "alreadyToasted", Boolean.toString(already), "sourceKind", "MECHANISM"), level.registryAccess());
    }
}
