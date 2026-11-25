package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.material.ItemSnapshotCodec;
import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import firefly520.fireflymc.ai.storage.AiJson;
import java.lang.invoke.*;
import java.util.*;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.entity.*;

/** Read-only exact MC 1.21.1 / NeoForge 21.1 furnace contract; no copied cost or output-capacity algorithm. */
final class NativeFurnaceReadiness {
    private record Contract(MethodHandle recipeType, MethodHandle litTime, MethodHandle items, MethodHandle burnDuration, MethodHandle canBurn) { }
    record Observation(boolean hasInput, boolean recipeMatched, boolean outputFits, int fuelBurnTicks, int litTicks, String sourceDigest) { }
    private static volatile Contract contract;
    private NativeFurnaceReadiness() {}

    private static Contract contract() throws ReflectiveOperationException {
        var known = contract; if (known != null) return known;
        var lookup = MethodHandles.privateLookupIn(AbstractFurnaceBlockEntity.class, MethodHandles.lookup());
        known = new Contract(lookup.findGetter(AbstractFurnaceBlockEntity.class, "recipeType", RecipeType.class),
                lookup.findGetter(AbstractFurnaceBlockEntity.class, "litTime", int.class),
                lookup.findGetter(AbstractFurnaceBlockEntity.class, "items", NonNullList.class),
                lookup.findVirtual(AbstractFurnaceBlockEntity.class, "getBurnDuration", MethodType.methodType(int.class, ItemStack.class)),
                lookup.findStatic(AbstractFurnaceBlockEntity.class, "canBurn", MethodType.methodType(boolean.class,
                        RegistryAccess.class, RecipeHolder.class, NonNullList.class, int.class, AbstractFurnaceBlockEntity.class)));
        contract = known; return known;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static Observation inspect(ServerLevel level, AbstractFurnaceBlockEntity real) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        Class<?> type = real.getClass();
        if (!Set.of(FurnaceBlockEntity.class, BlastFurnaceBlockEntity.class, SmokerBlockEntity.class).contains(type))
            throw new IllegalArgumentException("BUILD_FURNACE_ADAPTER_REQUIRED");
        try {
            var api = contract(); var registry = level.registryAccess();
            AbstractFurnaceBlockEntity shadow = type == FurnaceBlockEntity.class ? new FurnaceBlockEntity(real.getBlockPos(), real.getBlockState())
                    : type == BlastFurnaceBlockEntity.class ? new BlastFurnaceBlockEntity(real.getBlockPos(), real.getBlockState())
                    : new SmokerBlockEntity(real.getBlockPos(), real.getBlockState());
            var items = NonNullList.withSize(3, ItemStack.EMPTY);
            var shadowItems = (NonNullList<ItemStack>) api.items().invoke(shadow);
            for (int slot = 0; slot < 3; slot++) {
                items.set(slot, RecipeInputIsolation.copy(real.getItem(slot), registry));
                // Populate only this unregistered local copy. Native setItem runs recipe/world callbacks.
                shadowItems.set(slot, RecipeInputIsolation.copy(items.get(slot), registry));
            }
            shadow.setLevel(level);
            RecipeType recipeType = (RecipeType) api.recipeType().invoke(real);
            String before = digest(items, registry), realBefore = digest(real, registry);
            Optional<RecipeHolder> recipe = level.getRecipeManager().getRecipeFor(recipeType,
                    new SingleRecipeInput(RecipeInputIsolation.copy(items.getFirst(), registry)), level);
            if (recipe.isPresent() && !Set.of(SmeltingRecipe.class, BlastingRecipe.class, SmokingRecipe.class).contains(recipe.get().value().getClass()))
                throw new IllegalStateException("BUILD_FURNACE_RECIPE_ADAPTER_REQUIRED");
            boolean fits = (boolean) api.canBurn().invoke(registry, recipe.orElse(null), items, shadow.getMaxStackSize(), shadow);
            int duration = (int) api.burnDuration().invoke(shadow, RecipeInputIsolation.copy(items.get(1), registry));
            int lit = (int) api.litTime().invoke(real);
            if (!before.equals(digest(items, registry)) || !before.equals(digest(shadow, registry)) || !realBefore.equals(digest(real, registry)))
                throw new IllegalStateException("BUILD_FURNACE_READ_CALLBACK_MUTATED_INPUT");
            if (duration < 0 || lit < 0) throw new IllegalStateException("BUILD_FURNACE_NATIVE_STATE_INVALID");
            return new Observation(!items.getFirst().isEmpty(), recipe.isPresent(), fits, duration, lit, realBefore);
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable unavailable) { throw new IllegalStateException("BUILD_FURNACE_NATIVE_CONTRACT_UNAVAILABLE", unavailable); }
    }
    private static String digest(AbstractFurnaceBlockEntity furnace, RegistryAccess registry) {
        return digest(List.of(furnace.getItem(0), furnace.getItem(1), furnace.getItem(2)), registry);
    }
    private static String digest(List<ItemStack> items, RegistryAccess registry) {
        return AiJson.digest(AiJson.canonical(items.stream().map(item -> ItemSnapshotCodec.capture(item, registry)).toList()));
    }
}
