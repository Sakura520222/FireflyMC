package firefly520.fireflymc.story;

import com.mojang.authlib.GameProfile;
import firefly520.fireflymc.ai.material.ItemSnapshotCodec;
import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import firefly520.fireflymc.ai.processing.NativeProcessingCallback;
import firefly520.fireflymc.ai.processing.advanced.AdvancedTransformation;
import firefly520.fireflymc.ai.processing.advanced.DynamicRecipeOperationScope;
import firefly520.fireflymc.ai.processing.advanced.StorageDynamicRecipes;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/** Actual loaded recipes and components; registered only for the relevant installed native mod. */
@GameTestHolder("fireflymc")
@PrefixGameTestTemplate(false)
public final class StorageDynamicGameTests {
    @GameTestGenerator public static Collection<TestFunction> storageRecipes() {
        var tests = new ArrayList<TestFunction>();
        if (ModList.get().isLoaded("sophisticatedbackpacks")) {
            add(tests, "backpack_upgrade_preserves_real_contents_and_uuid", helper -> backpack(helper, false));
            add(tests, "backpack_dye_is_native_and_does_not_write_shared_contents", helper -> backpack(helper, true));
        }
        if (ModList.get().isLoaded("sophisticatedstorage")) {
            add(tests, "storage_flat_top_preserves_real_components", StorageDynamicGameTests::barrelToggle);
            add(tests, "storage_existing_uuid_preview_is_private_and_integrated_commit_is_rejected", StorageDynamicGameTests::sharedUpgrade);
            add(tests, "vanilla_shulker_fixed_uuid_preview_rejects_integrated_commit", StorageDynamicGameTests::shulkerMigration);
        }
        if (ModList.get().isLoaded("storagedrawers"))
            add(tests, "drawer_upgrade_preserves_native_component_payload", StorageDynamicGameTests::drawerUpgrade);
        if (ModList.get().isLoaded("sophisticatedcore") && (ModList.get().isLoaded("sophisticatedbackpacks") || ModList.get().isLoaded("sophisticatedstorage")))
            add(tests, "upgrade_clear_uses_native_settings_reset", StorageDynamicGameTests::upgradeClear);
        return tests;
    }
    private static void add(List<TestFunction> tests, String name, Consumer<GameTestHelper> body) {
        tests.add(new TestFunction("ai-storage-dynamic", "fireflymc.storage_dynamic_" + name, "fireflymc:story_empty", 160, 0, true, helper -> {
            try { body.accept(helper); }
            catch (RuntimeException | LinkageError failure) {
                // All inputs here are isolated fixtures. Keep the internal cause in native-test logs;
                // production tool results continue to expose only their fixed public error code.
                System.err.println("STORAGE_DYNAMIC_NATIVE_FIXTURE_FAILURE " + name);
                failure.printStackTrace(System.err); throw failure;
            }
        }));
    }
    private static ServerPlayer actor(GameTestHelper helper) {
        return new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), new GameProfile(UUID.randomUUID(), "StorageDynamic"), ClientInformation.createDefault());
    }
    private static ItemStack item(String id) {
        var value = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        if (value == Items.AIR) throw new IllegalStateException("ACTUAL_STORAGE_FIXTURE_ITEM_MISSING:" + id);
        return new ItemStack(value);
    }
    private static RecipeHolder<CraftingRecipe> recipe(GameTestHelper helper, String simple) {
        return recipe(helper, simple, "");
    }
    private static RecipeHolder<CraftingRecipe> recipe(GameTestHelper helper, String simple, String id) {
        return helper.getLevel().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING).stream()
                .filter(holder -> holder.value().getClass().getSimpleName().equals(simple)
                        && (id.isEmpty() || holder.id().equals(ResourceLocation.parse(id)))).findFirst()
                .orElseThrow(() -> new IllegalStateException("ACTUAL_STORAGE_RECIPE_MISSING:" + simple));
    }
    private static List<ItemStack> grid() { return new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY)); }
    private static List<ItemStack> shaped(RecipeHolder<CraftingRecipe> holder) {
        var recipe = (ShapedRecipe) holder.value(); var grid = grid();
        var ingredients = recipe.getIngredients();
        for (int y = 0; y < recipe.getHeight(); y++) for (int x = 0; x < recipe.getWidth(); x++) {
            var ingredient = ingredients.get(y * recipe.getWidth() + x);
            if (!ingredient.isEmpty()) grid.set(y * 3 + x, ingredient.getItems()[0].copyWithCount(1));
        }
        return grid;
    }
    @SuppressWarnings("unchecked") private static DataComponentType<UUID> uuidComponent() {
        try {
            return (DataComponentType<UUID>) ((Supplier<?>) Class.forName("net.p3pp3rf1y.sophisticatedcore.init.ModCoreDataComponents").getField("STORAGE_UUID").get(null)).get();
        } catch (ReflectiveOperationException unavailable) { throw new IllegalStateException("ACTUAL_STORAGE_UUID_COMPONENT_MISSING", unavailable); }
    }
    private static void backpack(GameTestHelper helper, boolean dye) {
        var holder = recipe(helper, dye ? "BackpackDyeRecipe" : "BackpackUpgradeRecipe");
        var inputs = dye ? grid() : shaped(holder);
        if (dye) { inputs.set(0, item("sophisticatedbackpacks:backpack")); inputs.set(1, new ItemStack(Items.RED_DYE)); }
        ItemStack source = inputs.stream().filter(stack -> !stack.isEmpty() && stack.getItem().getClass().getName().equals("net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem"))
                .findFirst().orElseThrow(() -> new IllegalStateException("NATIVE_BACKPACK_ROLE_NOT_FOUND"));
        UUID uuid = UUID.randomUUID(); source.set(uuidComponent(), uuid); source.set(DataComponents.CUSTOM_NAME, Component.literal("real contents stay owned"));
        try {
            var wrapperType = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper");
            Object wrapper = wrapperType.getMethod("fromStack", ItemStack.class).invoke(null, source);
            var inventory = (IItemHandler) wrapper.getClass().getMethod("getInventoryHandler").invoke(wrapper);
            helper.assertTrue(inventory.insertItem(0, new ItemStack(Items.DIAMOND, 7), false).isEmpty(), "native test inventory accepts real nested contents");
            var storeType = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage");
            Object store = storeType.getMethod("get").invoke(null);
            CompoundTag contents = (CompoundTag) storeType.getMethod("getOrCreateBackpackContents", UUID.class).invoke(store, uuid);
            CompoundTag before = contents.copy();
            var output = verifiedAgainstNative(helper, holder, inputs);
            helper.assertTrue(uuid.equals(output.get(uuidComponent())), "native storage UUID is transferred once rather than cloning contents");
            helper.assertTrue(output.getHoverName().getString().equals("real contents stay owned"), "native name survives component transfer");
            helper.assertTrue(contents.equals(before) && inventory.getStackInSlot(0).getCount() == 7, "preview does not edit actual global BackpackStorage or nested materials");
        } catch (ReflectiveOperationException unavailable) { throw new IllegalStateException("ACTUAL_BACKPACK_FIXTURE_API_MISMATCH", unavailable); }
        helper.succeed();
    }
    private static void barrelToggle(GameTestHelper helper) {
        var holder = recipe(helper, "FlatTopBarrelToggleRecipe"); var inputs = grid();
        var barrel = item("sophisticatedstorage:barrel"); barrel.set(DataComponents.CUSTOM_NAME, Component.literal("component-bearing barrel"));
        barrel.set(uuidComponent(), UUID.randomUUID()); inputs.set(4, barrel);
        var output = verifiedAgainstNative(helper, holder, inputs);
        helper.assertTrue(Objects.equals(barrel.get(uuidComponent()), output.get(uuidComponent())), "flat-top native toggle retains source UUID");
        helper.assertTrue(output.getHoverName().getString().equals("component-bearing barrel"), "native barrel name retained"); helper.succeed();
    }
    private static void drawerUpgrade(GameTestHelper helper) {
        var holder = recipe(helper, "AddUpgradeRecipe"); var inputs = grid(); var drawer = item("storagedrawers:oak_full_drawers_1");
        drawer.set(DataComponents.CUSTOM_NAME, Component.literal("named native drawer")); inputs.set(0, drawer); inputs.set(1, item("storagedrawers:obsidian_storage_upgrade"));
        var output = verifiedAgainstNative(helper, holder, inputs);
        helper.assertTrue(output.getHoverName().getString().equals("named native drawer"), "native drawer upgrade preserves item components"); helper.succeed();
    }
    private static void upgradeClear(GameTestHelper helper) {
        var holder = recipe(helper, "UpgradeClearRecipe"); var inputs = grid();
        var upgrade = item(ModList.get().isLoaded("sophisticatedbackpacks") ? "sophisticatedbackpacks:filter_upgrade" : "sophisticatedstorage:filter_upgrade");
        upgrade.set(DataComponents.CUSTOM_NAME, Component.literal("reset this actual setting")); inputs.set(8, upgrade);
        var output = verifiedAgainstNative(helper, holder, inputs);
        helper.assertTrue(!output.has(DataComponents.CUSTOM_NAME), "settings reset follows native assemble instead of copying every component blindly"); helper.succeed();
    }
    private static void sharedUpgrade(GameTestHelper helper) {
        var player = actor(helper); var holder = recipe(helper, "StorageTierUpgradeRecipe", "sophisticatedstorage:copper_shulker_box"); var inputs = shaped(holder);
        var nativeType = type("net.p3pp3rf1y.sophisticatedstorage.item.StorageBlockItem");
        var source = inputs.stream().filter(stack -> !stack.isEmpty() && nativeType.isInstance(stack.getItem())).findFirst().orElseThrow();
        source.set(DataComponents.CUSTOM_NAME, Component.literal("shared native inventory"));
        Object wrapper = callStatic("net.p3pp3rf1y.sophisticatedstorage.item.StackStorageWrapper", "fromStack",
                new Class<?>[]{net.minecraft.core.HolderLookup.Provider.class, ItemStack.class}, player.registryAccess(), source);
        call(wrapper, "ensureContentsUuid", new Class<?>[0]);
        var inventory = (IItemHandler) call(wrapper, "getInventoryHandler", new Class<?>[0]);
        helper.assertTrue(inventory.insertItem(0, new ItemStack(Items.DIAMOND, 7), false).isEmpty(), "actual storage source contains seven real nested diamonds");
        UUID id = source.get(uuidComponent()), other = UUID.randomUUID(), operation = UUID.randomUUID();
        Object store = callStatic("net.p3pp3rf1y.sophisticatedstorage.block.ItemContentsStorage", "get", new Class<?>[0]);
        CompoundTag entry = (CompoundTag) call(store, "getOrCreateStorageContents", new Class<?>[]{UUID.class}, id); var original = entry.copy();
        CompoundTag unrelated = (CompoundTag) call(store, "getOrCreateStorageContents", new Class<?>[]{UUID.class}, other); unrelated.putString("marker", "unrelated-first");
        AdvancedTransformation first, second;
        try (var scope = DynamicRecipeOperationScope.open(operation)) { first = StorageDynamicRecipes.verify(holder, inputs, player); }
        helper.assertTrue("true".equals(first.contextRequirements().get("requiresNativeAssemblyCommit")), "shared plan requires the central native-production intent protocol");
        helper.assertTrue(entry.equals(original), "existing shared source entry is unchanged by native preview");
        unrelated.putString("marker", "unrelated-new-state");
        try (var scope = DynamicRecipeOperationScope.open(operation)) { second = StorageDynamicRecipes.verify(holder, inputs, player); }
        helper.assertTrue(first.contextRequirements().equals(second.contextRequirements()), "another UUID cannot invalidate or enter this private plan fingerprint");
        helper.assertTrue(id.equals(first.outputs().getFirst().get(uuidComponent())), "shared upgrade preserves the exact existing storage identity");
        boolean[] dispatched = {false};
        var committed = NativeProcessingCallback.run(player, () -> { dispatched[0] = true; second.commitCallbacks(player); }, 0);
        helper.assertTrue(!committed.completed() && !committed.entered() && !dispatched[0]
                        && "NATIVE_BUILD_SAVE_SCOPE_INVALID".equals(committed.code()),
                "integrated GameTest environment cannot enter a dedicated-only shared commit: " + committed.code());
        helper.assertTrue(entry.equals(original) && inventory.getStackInSlot(0).is(Items.DIAMOND) && inventory.getStackInSlot(0).getCount() == 7,
                "rejected integrated commit leaves the actual source contents intact");
        helper.assertTrue(unrelated.getString("marker").equals("unrelated-new-state"), "rejected commit never overwrites another storage UUID"); helper.succeed();
    }
    private static void shulkerMigration(GameTestHelper helper) {
        var player = actor(helper); var holder = recipe(helper, "ShulkerBoxFromVanillaShapelessRecipe"); var inputs = grid();
        for (int slot = 0; slot < holder.value().getIngredients().size(); slot++) inputs.set(slot, holder.value().getIngredients().get(slot).getItems()[0].copyWithCount(1));
        var source = inputs.stream().filter(stack -> stack.getItem() instanceof BlockItem block && block.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock)
                .findFirst().orElseThrow();
        var diamond = new ItemStack(Items.DIAMOND, 7); diamond.set(DataComponents.CUSTOM_NAME, Component.literal("same seven diamonds"));
        source.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(diamond)));
        var original = ItemSnapshotCodec.capture(source, player.registryAccess()); var operation = UUID.randomUUID();
        Object store = callStatic("net.p3pp3rf1y.sophisticatedstorage.block.ItemContentsStorage", "get", new Class<?>[0]);
        AdvancedTransformation first, repeated;
        try (var scope = DynamicRecipeOperationScope.open(operation)) { first = StorageDynamicRecipes.verify(holder, inputs, player); }
        helper.assertTrue("true".equals(first.contextRequirements().get("requiresNativeAssemblyCommit")), "native container migration requires the central native-production intent protocol");
        UUID reserved = first.outputs().getFirst().get(uuidComponent());
        helper.assertTrue(!(boolean) call(store, "has", new Class<?>[]{UUID.class}, reserved), "preview must not create its reserved UUID in real SavedData");
        try (var scope = DynamicRecipeOperationScope.open(operation)) { repeated = StorageDynamicRecipes.verify(holder, inputs, player); }
        helper.assertTrue(ItemSnapshotCodec.capture(first.outputs().getFirst(), player.registryAccess()).equals(ItemSnapshotCodec.capture(repeated.outputs().getFirst(), player.registryAccess())),
                "same operation preview and revalidation use one fixed native output UUID");
        helper.assertTrue(original.equals(ItemSnapshotCodec.capture(source, player.registryAccess())), "vanilla container remains intact before physical commit");
        boolean[] dispatched = {false};
        var committed = NativeProcessingCallback.run(player, () -> { dispatched[0] = true; repeated.commitCallbacks(player); }, 0);
        helper.assertTrue(!committed.completed() && !committed.entered() && !dispatched[0]
                        && "NATIVE_BUILD_SAVE_SCOPE_INVALID".equals(committed.code()),
                "integrated GameTest environment cannot dispatch native shared migration: " + committed.code());
        helper.assertTrue(!(boolean) call(store, "has", new Class<?>[]{UUID.class}, reserved), "rejected integrated migration creates no real shared entry");
        helper.assertTrue(original.equals(ItemSnapshotCodec.capture(source, player.registryAccess())), "rejected migration leaves the original vanilla container intact");
        try (var scope = DynamicRecipeOperationScope.open(operation)) {
            var afterRejection = StorageDynamicRecipes.verify(holder, inputs, player);
            helper.assertTrue(ItemSnapshotCodec.capture(afterRejection.outputs().getFirst(), player.registryAccess())
                            .equals(ItemSnapshotCodec.capture(first.outputs().getFirst(), player.registryAccess())),
                    "a rejected commit leaves only the same private preview rather than a produced shared carrier");
        }
        helper.succeed();
    }
    private static Class<?> type(String name) {
        try { return Class.forName(name); } catch (ReflectiveOperationException unavailable) { throw new IllegalStateException("ACTUAL_STORAGE_CLASS_MISSING", unavailable); }
    }
    private static Object callStatic(String name, String method, Class<?>[] types, Object... args) {
        try { return type(name).getMethod(method, types).invoke(null, args); }
        catch (ReflectiveOperationException unavailable) { throw new IllegalStateException("ACTUAL_STORAGE_METHOD_MISMATCH:" + method, unavailable); }
    }
    private static Object call(Object target, String method, Class<?>[] types, Object... args) {
        try { return target.getClass().getMethod(method, types).invoke(target, args); }
        catch (ReflectiveOperationException unavailable) { throw new IllegalStateException("ACTUAL_STORAGE_METHOD_MISMATCH:" + method, unavailable); }
    }
    private static ItemStack verifiedAgainstNative(GameTestHelper helper, RecipeHolder<CraftingRecipe> holder, List<ItemStack> inputs) {
        var player = actor(helper); var original = inputs.stream().map(stack -> ItemSnapshotCodec.capture(stack, player.registryAccess())).toList();
        var transformation = StorageDynamicRecipes.verify(holder, inputs, player);
        var nativeInput = CraftingInput.ofPositioned(3, 3, RecipeInputIsolation.copyAll(inputs, player.registryAccess())).input();
        helper.assertTrue(holder.value().matches(nativeInput, helper.getLevel()), "real concrete native recipe matches fixture");
        var expected = holder.value().assemble(nativeInput, player.registryAccess()); var output = transformation.outputs().getFirst();
        helper.assertTrue(ItemSnapshotCodec.capture(expected, player.registryAccess()).equals(ItemSnapshotCodec.capture(output, player.registryAccess())), "exact native count and component output");
        helper.assertTrue(transformation.consumedBySlot().equals(inputs.stream().map(stack -> stack.isEmpty() ? 0 : 1).toList()), "one real input per occupied role");
        helper.assertTrue(original.equals(inputs.stream().map(stack -> ItemSnapshotCodec.capture(stack, player.registryAccess())).toList()), "native preview cannot mutate supplied physical input stacks");
        return output;
    }
}
