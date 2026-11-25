package firefly520.fireflymc.ai.processing.advanced;

import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import firefly520.fireflymc.ai.processing.VerifiedTransformation.SamplingEvidence;
import java.lang.invoke.*;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.neoforged.neoforge.common.CommonHooks;

/** Exact installed dynamic recipe implementations, using concrete component-bearing input grids. */
public final class BuildingDynamicRecipes {
    public enum Kind { CRAFTING, SMITHING }
    public record Profile(String mod, String version, String recipeClass, String serializer, Kind kind, boolean shears, boolean atlas) { }
    private static final Map<String, Profile> PROFILES = profiles();
    private static final String QUARK = "org.violetmoon.quark.";
    private static final String SUPP = "net.mehvahdjukaar.supplementaries.common.items.crafting.";
    private static final String ATLAS = "pepjebs.mapatlases.item.recipe.";
    private BuildingDynamicRecipes() { }

    public static List<Profile> profilesAvailableForVerification() { return List.copyOf(PROFILES.values()); }
    public static boolean contractMatches(Profile profile, String version, String recipeClass, String serializer, String type) {
        return profile != null && profile.version().equals(version) && profile.recipeClass().equals(recipeClass)
                && profile.serializer().equals(serializer)
                && type.equals(profile.kind() == Kind.SMITHING ? "minecraft:smithing" : "minecraft:crafting");
    }
    private static Profile profile(Recipe<?> recipe) {
        if (recipe == null) return null;
        var profile = PROFILES.get(recipe.getClass().getName());
        if (profile == null || !AdvancedReflection.version(profile.mod(), profile.version())) return null;
        if (profile.mod().equals("quark") && !AdvancedReflection.version("zeta", "1.1-40")) return null;
        return contractMatches(profile, profile.version(), recipe.getClass().getName(),
                String.valueOf(BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer())),
                String.valueOf(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()))) ? profile : null;
    }
    public static boolean supports(Recipe<?> recipe) { return profile(recipe) != null; }
    public static int inputSize(Recipe<?> recipe) { var profile = profile(recipe); return profile == null ? 0 : profile.kind() == Kind.SMITHING ? 3 : 9; }
    public static List<AdvancedInput> inputRequirements(RecipeHolder<?> holder, List<ItemStack> inputs, ServerPlayer player) {
        var result = verify(holder, inputs, player); var needs = new ArrayList<AdvancedInput>();
        for (int slot = 0; slot < inputs.size(); slot++) if (!inputs.get(slot).isEmpty()) {
            int index = slot; ItemStack expected = result.inputSnapshots().get(slot);
            needs.add(AdvancedInput.one(index, "native_dynamic_component", inputs.get(slot).getCount(), candidate ->
                    ItemStack.isSameItem(candidate, expected) && AdvancedReflection.same(candidate.copyWithCount(expected.getCount()), expected, player.registryAccess())));
        }
        return List.copyOf(needs);
    }
    public static AdvancedTransformation verify(RecipeHolder<?> holder, List<ItemStack> inputs, ServerPlayer player) {
        AdvancedReflection.current(holder, player.serverLevel());
        var profile = profile(holder.value()); if (profile == null) throw AdvancedReflection.failure("UNSUPPORTED_PROCESSING_ADAPTER");
        int size = profile.kind() == Kind.SMITHING ? 3 : 9;
        if (inputs.size() != size || inputs.stream().anyMatch(stack -> stack == null || stack.getCount() > 1))
            throw AdvancedReflection.failure("BUILDING_DYNAMIC_UNIT_INPUTS_REQUIRED");
        try (var headers = profile.atlas() ? BuildingDynamicAtlasHeaders.begin(player) : null) {
            if (headers != null) {
                headers.guardRawInputs(inputs);
                if (profile.recipeClass().equals(ATLAS + "AntiqueAtlasRecipe") && headers.hasFilledMapIds())
                    throw AdvancedReflection.failure("BUILDING_DYNAMIC_ANTIQUE_MAP_ALLOCATION_REQUIRED");
            }
            var supplied = RecipeInputIsolation.copyAll(inputs, player.registryAccess());
            if (profile.kind() == Kind.SMITHING) return smithing(holder, supplied, player);
            return crafting(holder, supplied, player, profile, headers);
        }
    }
    @SuppressWarnings("unchecked")
    private static AdvancedTransformation crafting(RecipeHolder<?> holder, List<ItemStack> supplied, ServerPlayer player, Profile profile, BuildingDynamicAtlasHeaders headers) {
        var working = new ArrayList<>(RecipeInputIsolation.copyAll(supplied, player.registryAccess()));
        int virtualShears = -1;
        if (profile.shears() && working.stream().noneMatch(stack -> stack.is(Items.SHEARS))) {
            for (int slot = 0; slot < 9; slot++) if (working.get(slot).isEmpty()) { virtualShears = slot; working.set(slot, new ItemStack(Items.SHEARS)); break; }
            if (virtualShears < 0) throw AdvancedReflection.failure("BUILDING_DYNAMIC_VIRTUAL_TOOL_SLOT_REQUIRED");
        }
        var expectedWorking = RecipeInputIsolation.copyAll(working, player.registryAccess());
        var positioned = CraftingInput.ofPositioned(3, 3, working); var input = positioned.input();
        var recipe = (Recipe<CraftingInput>) holder.value(); var previous = CommonHooks.getCraftingPlayer();
        CommonHooks.setCraftingPlayer(player);
        try (var flags = QuarkStateGuard.begin(holder, player)) {
            if (profile.atlas()) headers.bindInput(input);
            if (!recipe.canCraftInDimensions(3, 3) || !recipe.matches(input, player.serverLevel())) throw AdvancedReflection.failure("BUILDING_DYNAMIC_INPUT_MISMATCH");
            ItemStack output = recipe.assemble(input, player.registryAccess());
            if (output == null || output.isEmpty()) throw AdvancedReflection.failure("BUILDING_DYNAMIC_RESULT_EMPTY");
            if (profile.atlas()) headers.finalizeOutput(output);
            output = AdvancedReflection.copy(output, player.registryAccess());
            var nativeRemainders = recipe.getRemainingItems(input);
            if (nativeRemainders.size() != input.size()) throw AdvancedReflection.failure("BUILDING_DYNAMIC_REMAINDERS_INVALID");
            var remainders = AdvancedReflection.emptySlots(9);
            for (int y = 0; y < input.height(); y++) for (int x = 0; x < input.width(); x++) {
                int slot = (y + positioned.top()) * 3 + x + positioned.left();
                if (!AdvancedReflection.same(input.getItem(x, y), expectedWorking.get(slot), player.registryAccess()))
                    throw AdvancedReflection.failure("BUILDING_DYNAMIC_INPUT_MUTATED");
                ItemStack returned = nativeRemainders.get(y * input.width() + x);
                if (returned == null) throw AdvancedReflection.failure("BUILDING_DYNAMIC_REMAINDERS_INVALID");
                // A virtual auxiliary tool never becomes a real returned item. A supplied real
                // shear is returned unchanged because processing tool wear is waived.
                if (slot == virtualShears) continue;
                if (profile.shears() && supplied.get(slot).is(Items.SHEARS)) returned = supplied.get(slot);
                if (supplied.get(slot).isEmpty() && !returned.isEmpty()) throw AdvancedReflection.failure("BUILDING_DYNAMIC_VIRTUAL_REMAINDER_FORBIDDEN");
                remainders.set(slot, AdvancedReflection.copy(returned, player.registryAccess()));
            }
            if (headers != null) headers.validate();
            var context = new LinkedHashMap<String, String>(); context.put("owner", player.getUUID().toString());
            context.put("nativeRecipeContract", profile.recipeClass()); context.put("logicalOperations", "1");
            context.put("auxiliaryToolPolicy", "MATERIALS_ONLY_NO_TOOL_WEAR");
            if (profile.atlas()) context.put("atlasHeaderDigest", headers.digest());
            var reusable = new ArrayList<String>();
            for (int slot = 0; slot < supplied.size(); slot++) if (!supplied.get(slot).isEmpty() && AdvancedReflection.same(supplied.get(slot), remainders.get(slot), player.registryAccess())) reusable.add(Integer.toString(slot));
            if (!reusable.isEmpty()) context.put("reusableGridSlots", String.join(",", reusable));
            ItemStack fixedOutput = output;
            var callbackHeaderView = headers == null ? null : headers.freeze();
            return new AdvancedTransformation(holder, "advanced:building_dynamic-v1", supplied,
                    supplied.stream().map(stack -> stack.isEmpty() ? 0 : 1).toList(), List.of(output), remainders,
                    SamplingEvidence.deterministic(), context, AdvancedTransformation.references(holder),
                    List.of("item_crafted", "neo_crafting_event", "recipe_award"), actor -> {
                        try (var callbackHeaders = callbackHeaderView == null ? null : callbackHeaderView.begin(actor)) {
                            AdvancedCraftingAdapter.crafted(holder, supplied, fixedOutput, actor);
                            if (callbackHeaders != null) callbackHeaders.validate();
                        }
                    }, player.registryAccess());
        } finally { CommonHooks.setCraftingPlayer(previous); }
    }
    @SuppressWarnings("unchecked")
    private static AdvancedTransformation smithing(RecipeHolder<?> holder, List<ItemStack> supplied, ServerPlayer player) {
        var working = RecipeInputIsolation.copyAll(supplied, player.registryAccess());
        var input = new SmithingRecipeInput(working.get(0), working.get(1), working.get(2));
        var recipe = (Recipe<SmithingRecipeInput>) holder.value();
        if (!recipe.matches(input, player.serverLevel())) throw AdvancedReflection.failure("BUILDING_DYNAMIC_INPUT_MISMATCH");
        var output = AdvancedReflection.copy(recipe.assemble(input, player.registryAccess()), player.registryAccess());
        if (output.isEmpty()) throw AdvancedReflection.failure("BUILDING_DYNAMIC_RESULT_EMPTY");
        for (int slot = 0; slot < 3; slot++) if (!AdvancedReflection.same(supplied.get(slot), working.get(slot), player.registryAccess())) throw AdvancedReflection.failure("BUILDING_DYNAMIC_INPUT_MUTATED");
        // Actual SmithingMenu shrinks the three real inputs, without crafting-grid remainders.
        return new AdvancedTransformation(holder, "advanced:building_smithing_rune-v1", supplied,
                supplied.stream().map(stack -> stack.isEmpty() ? 0 : 1).toList(), List.of(output), AdvancedReflection.emptySlots(3),
                SamplingEvidence.deterministic(), Map.of("owner", player.getUUID().toString(), "logicalOperations", "1", "remainderPolicy", "NATIVE_SMITHING_CONSUMPTION"),
                AdvancedTransformation.references(holder), List.of("item_crafted", "recipe_award"), actor -> {
                    var result = AdvancedReflection.copy(output, actor.registryAccess()); result.onCraftedBy(actor.serverLevel(), actor, result.getCount());
                    if (!AdvancedReflection.same(result, output, actor.registryAccess())) throw AdvancedReflection.failure("PROCESSING_CALLBACK_OUTPUT_UNCERTAIN");
                    if (!holder.value().isSpecial()) { actor.awardRecipes(List.of(holder)); actor.triggerRecipeCrafted(holder, supplied); }
                }, player.registryAccess());
    }
    private static Map<String, Profile> profiles() {
        var result = new LinkedHashMap<String, Profile>();
        add(result, "quark", "4.1-482", QUARK + "base.recipe.ExclusionRecipe", "quark:exclusion", Kind.CRAFTING, false, false);
        add(result, "quark", "4.1-482", QUARK + "base.recipe.ExclusionRecipe$ShapedExclusionRecipe", "quark:exclusion", Kind.CRAFTING, false, false);
        add(result, "quark", "4.1-482", QUARK + "content.building.recipe.MixedExclusionRecipe", "quark:mixed_exclusion", Kind.CRAFTING, false, false);
        add(result, "quark", "4.1-482", QUARK + "content.tweaks.recipe.SlabToBlockRecipe", "quark:slab_to_block", Kind.CRAFTING, false, false);
        add(result, "quark", "4.1-482", QUARK + "content.tweaks.recipe.ElytraDuplicationRecipe", "quark:elytra_duplication", Kind.CRAFTING, false, false);
        add(result, "quark", "4.1-482", QUARK + "content.tools.recipe.SmithingRuneRecipe", "quark:smithing_rune", Kind.SMITHING, false, false);
        add(result, "quark", "4.1-482", "org.violetmoon.zeta.recipe.ZetaDyeRecipe", "quark:dye_item", Kind.CRAFTING, false, false);
        for (var pair : Map.ofEntries(
                Map.entry("BlackboardDuplicateRecipe", "blackboard_duplicate"), Map.entry("ConfettiDyeRecipe", "confetti_dye"),
                Map.entry("FlagFromBannerRecipe", "flag_from_banner"), Map.entry("PresentDyeRecipe", "present_dye"),
                Map.entry("SafeRecipe", "safe"), Map.entry("SoapClearRecipe", "soap_clearing"),
                Map.entry("TippedBambooSpikesRecipe", "bamboo_spikes_tipped"), Map.entry("ItemLoreRecipe", "item_lore"),
                Map.entry("TatteredBookRecipe", "antique_book"), Map.entry("AddChargeRecipe", "add_charges"),
                Map.entry("TrappedPresentRecipe", "trapped_present"), Map.entry("SusRecipe", "sus_crafting")).entrySet())
            add(result, "supplementaries", "1.21.1-3.8.3", SUPP + pair.getKey(), "supplementaries:" + pair.getValue(), Kind.CRAFTING, false, false);
        add(result, "map_atlases", "1.21-6.6.0", ATLAS + "MapAtlasCreateRecipe", "map_atlases:crafting_atlas", Kind.CRAFTING, false, true);
        add(result, "map_atlases", "1.21-6.6.0", ATLAS + "MapAtlasesAddRecipe", "map_atlases:adding_atlas", Kind.CRAFTING, false, true);
        add(result, "map_atlases", "1.21-6.6.0", ATLAS + "MapAtlasesCutExistingRecipe", "map_atlases:cutting_atlas", Kind.CRAFTING, true, true);
        add(result, "map_atlases", "1.21-6.6.0", ATLAS + "AntiqueAtlasRecipe", "map_atlases:antique_atlas", Kind.CRAFTING, false, true);
        return Collections.unmodifiableMap(result);
    }
    private static void add(Map<String, Profile> target, String mod, String version, String type, String serializer, Kind kind, boolean shears, boolean atlas) {
        target.put(type, new Profile(mod, version, type, serializer, kind, shears, atlas));
    }

    /** Native Quark recursion flags must be restored even when a competing recipe throws. */
    private static final class QuarkStateGuard implements AutoCloseable {
        private record State(Object recipe, VarHandle field, boolean value) { }
        private final List<State> states;
        private QuarkStateGuard(List<State> states) { this.states = states; }
        static QuarkStateGuard begin(RecipeHolder<?> holder, ServerPlayer player) {
            String selected = holder.value().getClass().getName();
            if (!selected.startsWith(QUARK + "base.recipe.ExclusionRecipe")
                    && !selected.equals(QUARK + "content.tweaks.recipe.SlabToBlockRecipe")
                    && !selected.equals(QUARK + "content.building.recipe.MixedExclusionRecipe"))
                return new QuarkStateGuard(List.of());
            validateExclusions(holder.value(), player, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
            var snapshots = new ArrayList<State>();
            var recipes = player.serverLevel().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING);
            if (recipes.size() > 16384) throw AdvancedReflection.failure("BUILDING_DYNAMIC_RECIPE_CATALOG_LIMIT");
            for (var candidate : recipes) {
                String type = candidate.value().getClass().getName();
                String field = type.equals(QUARK + "content.tweaks.recipe.SlabToBlockRecipe") ? "locked"
                        : type.equals(QUARK + "content.building.recipe.MixedExclusionRecipe") ? "ignoreMe" : "";
                if (!field.isEmpty()) try {
                    var handle = MethodHandles.privateLookupIn(candidate.value().getClass(), MethodHandles.lookup()).findVarHandle(candidate.value().getClass(), field, boolean.class);
                    boolean value = (boolean) handle.get(candidate.value()); handle.set(candidate.value(), value);
                    snapshots.add(new State(candidate.value(), handle, value));
                } catch (ReflectiveOperationException unavailable) { throw AdvancedReflection.failure("BUILDING_DYNAMIC_RECIPE_STATE_UNAVAILABLE"); }
            }
            return new QuarkStateGuard(snapshots);
        }
        private static void validateExclusions(Recipe<?> recipe, ServerPlayer player, Set<Recipe<?>> seen, int depth) {
            if (depth > 16 || !seen.add(recipe)) throw AdvancedReflection.failure("BUILDING_DYNAMIC_EXCLUSION_CYCLE");
            try {
                if (!recipe.getClass().getName().startsWith(QUARK + "base.recipe.ExclusionRecipe")) return;
                Class<?> base = AdvancedReflection.type(QUARK + "base.recipe.ExclusionRecipe");
                var lookup = MethodHandles.privateLookupIn(base, MethodHandles.lookup());
                var parent = (Recipe<?>) lookup.findGetter(base, "parent", CraftingRecipe.class).invoke(recipe);
                if (parent.getClass() != ShapedRecipe.class && parent.getClass() != ShapelessRecipe.class)
                    throw AdvancedReflection.failure("BUILDING_DYNAMIC_EXCLUSION_PARENT_UNSUPPORTED");
                var excluded = (List<?>) lookup.findGetter(base, "excluded", List.class).invoke(recipe);
                if (excluded.size() > 64) throw AdvancedReflection.failure("BUILDING_DYNAMIC_EXCLUSION_LIMIT");
                for (Object id : excluded) {
                    if (!(id instanceof net.minecraft.resources.ResourceLocation key)) throw AdvancedReflection.failure("BUILDING_DYNAMIC_EXCLUSION_INVALID");
                    var other = player.serverLevel().getRecipeManager().byKey(key).orElse(null);
                    if (other == null) continue;
                    var value = other.value();
                    if (value.getClass() != ShapedRecipe.class && value.getClass() != ShapelessRecipe.class
                            && !value.getClass().getName().startsWith(QUARK + "base.recipe.ExclusionRecipe"))
                        throw AdvancedReflection.failure("BUILDING_DYNAMIC_EXCLUSION_MATCHER_UNSUPPORTED");
                    validateExclusions(value, player, seen, depth + 1);
                }
            } catch (RuntimeException failure) { throw failure; }
            catch (Throwable unavailable) { throw AdvancedReflection.failure("BUILDING_DYNAMIC_RECIPE_STATE_UNAVAILABLE"); }
            finally { seen.remove(recipe); }
        }
        @Override public void close() {
            for (var state : states) state.field().set(state.recipe(), state.value());
        }
    }
}
