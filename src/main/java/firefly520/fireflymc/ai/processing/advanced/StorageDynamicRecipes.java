package firefly520.fireflymc.ai.processing.advanced;

import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import firefly520.fireflymc.ai.processing.VerifiedTransformation.SamplingEvidence;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.neoforged.neoforge.common.CommonHooks;

/** Exact native storage recipe classes. No JSON result is treated as a production result. */
public final class StorageDynamicRecipes {
    public enum InputKind { GRID, SMITHING }
    public record Profile(String mod, String version, String buildVersion, String className, String serializer, InputKind input,
                          boolean readsSharedStorage, boolean basicBackpack) {
        public String typeId() { return input == InputKind.SMITHING ? "minecraft:smithing" : "minecraft:crafting"; }
        public int inputSize() { return input == InputKind.SMITHING ? 3 : 9; }
    }
    private static final String CORE_VERSION = "1.21.1-1.4.77.2173";
    private static final String BP = "net.p3pp3rf1y.sophisticatedbackpacks.crafting.";
    private static final String SS = "net.p3pp3rf1y.sophisticatedstorage.crafting.";
    private static final String SC = "net.p3pp3rf1y.sophisticatedcore.crafting.";
    private static final String SD = "com.jaquadro.minecraft.storagedrawers.core.recipe.";
    private static final Map<String, Profile> PROFILES = profilesByClass();
    private static final ClassValue<String> BUILD_VERSION = new ClassValue<>() {
        @Override protected String computeValue(Class<?> type) {
            try (var stream = type.getModule().getResourceAsStream("META-INF/MANIFEST.MF")) {
                return stream == null ? "" : Objects.toString(new java.util.jar.Manifest(stream).getMainAttributes().getValue("Implementation-Version"), "");
            } catch (java.io.IOException unavailable) { return ""; }
        }
    };
    private StorageDynamicRecipes() {}

    private static Map<String, Profile> profilesByClass() {
        var result = new LinkedHashMap<String, Profile>();
        add(result, "storagedrawers", "13.11.4", SD, "AddUpgradeRecipe", "add_upgrade", false, false);
        add(result, "storagedrawers", "13.11.4", SD, "UpgradeDetachedDrawerRecipe", "add_detached_upgrade", false, false);
        add(result, "storagedrawers", "13.11.4", SD, "KeyringRecipe", "keyring", false, false);
        add(result, "storagedrawers", "13.11.4", SD, "PersonalKeyRecipe", "personal_key_cycle", false, false);
        add(result, "storagedrawers", "13.11.4", SD, "RemoteGroupUpgradeRecipe", "remote_group_upgrade", false, false);
        add(result, "sophisticatedbackpacks", "1.21.1-3.25.71.1997", BP, "BasicBackpackRecipe", "basic_backpack", false, true);
        add(result, "sophisticatedbackpacks", "1.21.1-3.25.71.1997", BP, "BackpackUpgradeRecipe", "backpack_upgrade", false, false);
        add(result, "sophisticatedbackpacks", "1.21.1-3.25.71.1997", BP, "BackpackDyeRecipe", "backpack_dye", false, false);
        var smithing = new Profile("sophisticatedbackpacks", "3.25.71", "1.21.1-3.25.71.1997", BP + "SmithingBackpackUpgradeRecipe",
                "sophisticatedbackpacks:smithing_backpack_upgrade", InputKind.SMITHING, false, false);
        result.put(smithing.className(), smithing);
        add(result, "sophisticatedcore", CORE_VERSION, SC, "UpgradeClearRecipe", "upgrade_clear", false, false);
        add(result, "sophisticatedcore", CORE_VERSION, SC, "UpgradeNextTierRecipe", "upgrade_next_tier", false, false);
        add(result, "sophisticatedstorage", "1.21.1-1.5.80.1999", SS, "StorageTierUpgradeRecipe", "storage_tier_upgrade", true, false);
        add(result, "sophisticatedstorage", "1.21.1-1.5.80.1999", SS, "StorageTierUpgradeShapelessRecipe", "storage_tier_upgrade_shapeless", true, false);
        add(result, "sophisticatedstorage", "1.21.1-1.5.80.1999", SS, "DoubleChestTierUpgradeRecipe", "double_chest_tier_upgrade", true, false);
        add(result, "sophisticatedstorage", "1.21.1-1.5.80.1999", SS, "DoubleChestTierUpgradeShapelessRecipe", "double_chest_tier_upgrade_shapeless", true, false);
        add(result, "sophisticatedstorage", "1.21.1-1.5.80.1999", SS, "StorageDyeRecipe", "storage_dye", false, false);
        add(result, "sophisticatedstorage", "1.21.1-1.5.80.1999", SS, "BarrelMaterialRecipe", "barrel_material", false, false);
        add(result, "sophisticatedstorage", "1.21.1-1.5.80.1999", SS, "FlatTopBarrelToggleRecipe", "flat_top_barrel_toggle", false, false);
        add(result, "sophisticatedstorage", "1.21.1-1.5.80.1999", SS, "GenericWoodStorageRecipe", "generic_wood_storage", false, false);
        add(result, "sophisticatedstorage", "1.21.1-1.5.80.1999", SS, "ShulkerBoxFromChestRecipe", "shulker_box_from_chest", false, false);
        add(result, "sophisticatedstorage", "1.21.1-1.5.80.1999", SS, "ShulkerBoxFromVanillaShapelessRecipe", "shulker_box_from_vanilla_shapeless", true, false);
        return Collections.unmodifiableMap(result);
    }
    private static void add(Map<String, Profile> result, String mod, String version, String prefix, String name,
                            String serializer, boolean shared, boolean basic) {
        var profile = new Profile(mod, loaderVersion(mod), version, prefix + name, mod + ":" + serializer, InputKind.GRID, shared, basic);
        result.put(profile.className(), profile);
    }
    public static List<Profile> profiles() { return List.copyOf(PROFILES.values()); }

    /** Pure matching metadata is also used by the static profile regression; loading a class is insufficient. */
    public static Optional<Profile> match(String className, String installedVersion, String buildVersion, String type, String serializer) {
        var profile = PROFILES.get(className);
        return profile != null && profile.version().equals(installedVersion) && profile.buildVersion().equals(buildVersion) && profile.typeId().equals(type)
                && profile.serializer().equals(serializer) ? Optional.of(profile) : Optional.empty();
    }
    private static String loaderVersion(String mod) {
        return switch (mod) {
            case "storagedrawers" -> "13.11.4";
            case "sophisticatedbackpacks" -> "3.25.71";
            case "sophisticatedstorage" -> "1.5.80";
            case "sophisticatedcore" -> "1.4.77";
            default -> throw new IllegalArgumentException("STORAGE_DYNAMIC_UNKNOWN_MOD");
        };
    }
    public static Profile profile(Recipe<?> recipe) {
        if (recipe == null) return null;
        var profile = PROFILES.get(recipe.getClass().getName());
        if (profile == null || !AdvancedReflection.version(profile.mod(), profile.version())
                || !profile.buildVersion().equals(BUILD_VERSION.get(recipe.getClass()))) return null;
        if (profile.mod().startsWith("sophisticated")) {
            if (!AdvancedReflection.version("sophisticatedcore", "1.4.77")) return null;
            if (!CORE_VERSION.equals(BUILD_VERSION.get(AdvancedReflection.type(SC + "IWrapperRecipe")))) return null;
        }
        return profile.typeId().equals(String.valueOf(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType())))
                && profile.serializer().equals(String.valueOf(BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer()))) ? profile : null;
    }
    public static boolean supports(Recipe<?> recipe) { return profile(recipe) != null; }
    public static int inputSize(Recipe<?> recipe) {
        var profile = profile(recipe); if (profile == null) throw AdvancedReflection.failure("UNSUPPORTED_PROCESSING_ADAPTER");
        return profile.inputSize();
    }

    public static List<AdvancedInput> inputRequirements(RecipeHolder<?> holder, List<ItemStack> selected, ServerPlayer player) {
        verify(holder, selected, player);
        var result = new ArrayList<AdvancedInput>();
        for (int slot = 0; slot < selected.size(); slot++) if (!selected.get(slot).isEmpty()) {
            ItemStack expected = RecipeInputIsolation.copy(selected.get(slot).copyWithCount(1), player.registryAccess());
            result.add(AdvancedInput.one(slot, "native_storage_component_input", 1, candidate ->
                    ItemStack.isSameItem(candidate, expected)
                            && AdvancedReflection.same(candidate.copyWithCount(1), expected, player.registryAccess())));
        }
        return List.copyOf(result);
    }

    public static AdvancedTransformation verify(RecipeHolder<?> holder, List<ItemStack> selected, ServerPlayer player) {
        AdvancedReflection.current(holder, player.serverLevel());
        var profile = profile(holder.value());
        if (profile == null) throw AdvancedReflection.failure("UNSUPPORTED_PROCESSING_ADAPTER");
        if (selected.size() != profile.inputSize() || selected.stream().anyMatch(stack -> stack == null || stack.getCount() > 1))
            throw AdvancedReflection.failure("STORAGE_DYNAMIC_UNIT_INPUT_REQUIRED");
        var before = RecipeInputIsolation.copyAll(selected, player.registryAccess());
        var working = RecipeInputIsolation.copyAll(before, player.registryAccess());
        if (profile.readsSharedStorage() && (profile.className().endsWith("ShulkerBoxFromVanillaShapelessRecipe")
                || working.stream().anyMatch(StorageDynamicRecipes::hasStorageUuid)
                || hasStorageUuid(holder.value().getResultItem(player.registryAccess()))))
            return StorageDynamicSharedRecipe.verify(holder, selected, player, profile);
        preflightPure(profile, holder, working, player);
        var previous = CommonHooks.getCraftingPlayer(); CommonHooks.setCraftingPlayer(player);
        try {
            var result = profile.input() == InputKind.GRID ? grid(holder, working, player) : smithing(holder, working, player);
            for (int slot = 0; slot < working.size(); slot++)
                if (!AdvancedReflection.same(working.get(slot), before.get(slot), player.registryAccess()))
                    throw AdvancedReflection.failure("STORAGE_DYNAMIC_INPUT_MUTATED");
            ItemStack output = RecipeInputIsolation.copy(result.output(), player.registryAccess());
            if (output.isEmpty() || output.getCount() > output.getMaxStackSize()) throw AdvancedReflection.failure("STORAGE_DYNAMIC_OUTPUT_INVALID");
            var context = new LinkedHashMap<String, String>();
            context.put("owner", player.getUUID().toString()); context.put("profile", profile.className());
            context.put("installedVersion", profile.version()); context.put("inputLayout", profile.input().name());
            context.put("nativeBuildVersion", profile.buildVersion());
            context.put("outputAuthority", "native-matches-assemble-remaining-items");
            context.put("sharedContents", "no-preview-write;existing-UUID-remains-native");
            if (profile.className().endsWith("UpgradeClearRecipe") || profile.className().endsWith("UpgradeDetachedDrawerRecipe"))
                context.put("componentChangeReview", "native recipe may clear settings or contained data; inspect the fixed before/after snapshots");
            var callbacks = List.of("item_crafted", "neo_crafting_event", "recipe_award");
            return new AdvancedTransformation(holder, "advanced:storage-dynamic-v1", before,
                    before.stream().map(stack -> stack.isEmpty() ? 0 : 1).toList(), List.of(output), result.remainders(),
                    SamplingEvidence.deterministic(), context, AdvancedTransformation.references(holder), callbacks,
                    actor -> AdvancedCraftingAdapter.crafted(holder, before, output, actor), player.registryAccess());
        } finally { CommonHooks.setCraftingPlayer(previous); }
    }

    private record NativeResult(ItemStack output, List<ItemStack> remainders) {}
    @SuppressWarnings("unchecked")
    private static NativeResult grid(RecipeHolder<?> holder, List<ItemStack> working, ServerPlayer player) {
        var positioned = CraftingInput.ofPositioned(3, 3, working); var input = positioned.input();
        var recipe = (CraftingRecipe) holder.value();
        if (!recipe.canCraftInDimensions(3, 3) || !recipe.matches(input, player.serverLevel()))
            throw AdvancedReflection.failure("STORAGE_DYNAMIC_INPUT_MISMATCH");
        ItemStack output = recipe.assemble(input, player.registryAccess());
        var remaining = recipe.getRemainingItems(input);
        if (remaining.size() != input.size()) throw AdvancedReflection.failure("STORAGE_DYNAMIC_REMAINDERS_INVALID");
        var mapped = AdvancedReflection.emptySlots(9);
        for (int y = 0; y < input.height(); y++) for (int x = 0; x < input.width(); x++) {
            int actual = y * input.width() + x, original = (y + positioned.top()) * 3 + x + positioned.left();
            mapped.set(original, RecipeInputIsolation.copy(remaining.get(actual), player.registryAccess()));
        }
        return new NativeResult(output, List.copyOf(mapped));
    }
    @SuppressWarnings("unchecked")
    private static NativeResult smithing(RecipeHolder<?> holder, List<ItemStack> working, ServerPlayer player) {
        if (Thread.currentThread().getThreadGroup() != net.neoforged.fml.util.thread.SidedThreadGroups.SERVER)
            throw AdvancedReflection.failure("STORAGE_DYNAMIC_SERVER_THREAD_REQUIRED");
        var input = new SmithingRecipeInput(working.get(0), working.get(1), working.get(2));
        var recipe = (Recipe<SmithingRecipeInput>) holder.value();
        if (!recipe.matches(input, player.serverLevel())) throw AdvancedReflection.failure("STORAGE_DYNAMIC_INPUT_MISMATCH");
        ItemStack output = recipe.assemble(input, player.registryAccess());
        var remaining = recipe.getRemainingItems(input);
        if (remaining.size() != 3) throw AdvancedReflection.failure("STORAGE_DYNAMIC_REMAINDERS_INVALID");
        // Native SmithingMenu shrinks each occupied role; it has no crafting-remainder transfer.
        return new NativeResult(output, List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY));
    }

    private static void preflightPure(Profile profile, RecipeHolder<?> holder, List<ItemStack> input, ServerPlayer player) {
        if (profile.basicBackpack()) {
            var template = holder.value().getResultItem(player.registryAccess());
            if (hasStorageUuid(template) || template.has(DataComponents.CUSTOM_DATA))
                throw AdvancedReflection.failure("STORAGE_DYNAMIC_RESULT_REQUIRES_SHARED_COMMIT");
        }
        if (profile.readsSharedStorage() && (input.stream().anyMatch(StorageDynamicRecipes::hasStorageUuid)
                || hasStorageUuid(holder.value().getResultItem(player.registryAccess()))))
            throw AdvancedReflection.failure("STORAGE_DYNAMIC_CONTENTS_REQUIRE_SHARED_COMMIT");
    }
    private static boolean hasStorageUuid(ItemStack stack) {
        if (stack.isEmpty()) return false;
        Object field = AdvancedReflection.field("net.p3pp3rf1y.sophisticatedcore.init.ModCoreDataComponents", "STORAGE_UUID");
        if (!(field instanceof Supplier<?> supplier) || !(supplier.get() instanceof DataComponentType<?> component))
            throw AdvancedReflection.failure("STORAGE_DYNAMIC_COMPONENT_API_MISMATCH");
        return stack.has(component);
    }
}
