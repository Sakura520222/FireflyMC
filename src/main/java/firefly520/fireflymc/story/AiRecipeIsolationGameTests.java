package firefly520.fireflymc.story;

import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real registry/component isolation checks without inventory or world mutation. */
@GameTestHolder("fireflymc")
@PrefixGameTestTemplate(false)
public final class AiRecipeIsolationGameTests {
    @net.minecraft.gametest.framework.GameTest(template = "story_empty", batch = "ai-recipe-isolation", timeoutTicks = 40)
    public static void translatedNameLiteralArgumentsKeepTheirCodecMeaning(GameTestHelper helper) {
        var source = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD);
        source.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.translatable(
                "tooltip.cookingforblockheads.toasted", net.minecraft.network.chat.Component.literal("named bread")));
        var detached = RecipeInputIsolation.copy(source, helper.getLevel().registryAccess());
        var before = firefly520.fireflymc.ai.material.ItemSnapshotCodec.capture(source, helper.getLevel().registryAccess());
        var after = firefly520.fireflymc.ai.material.ItemSnapshotCodec.capture(detached, helper.getLevel().registryAccess());
        helper.assertValueEqual(before, after, "Canonical component content survives vanilla argument normalization");
        helper.assertTrue(source.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME)
                != detached.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME), "The mutable name is still detached");
        helper.succeed();
    }
    private AiRecipeIsolationGameTests() {}

    @GameTest(template = "story_empty", batch = "ai-recipe-isolation", timeoutTicks = 20)
    public static void mutableNamesAndRemovedDefaultsRemainIndependent(GameTestHelper helper) {
        ItemStack original = new ItemStack(Items.APPLE, 17);
        MutableComponent originalName = Component.literal("recipe input");
        original.set(DataComponents.CUSTOM_NAME, originalName);
        original.remove(DataComponents.FOOD);

        ItemStack isolated = RecipeInputIsolation.copy(original, helper.getLevel().registryAccess());
        helper.assertTrue(ItemStack.isSameItemSameComponents(original, isolated),
                "Persistent components, including removed defaults, survive isolation");
        helper.assertValueEqual(isolated.getCount(), 17, "Input quantity survives isolation");
        helper.assertTrue(!isolated.has(DataComponents.FOOD), "Removed FOOD is not restored");
        MutableComponent isolatedName = (MutableComponent) isolated.get(DataComponents.CUSTOM_NAME);
        helper.assertTrue(isolatedName != originalName, "Mutable name is a distinct object");
        isolatedName.append(" changed by recipe");
        isolated.setCount(3);
        helper.assertValueEqual(originalName.getString(), "recipe input", "Recipe does not mutate source name");
        helper.assertValueEqual(original.getCount(), 17, "Recipe does not mutate source quantity");
        originalName.append(" changed at source");
        helper.assertValueEqual(isolatedName.getString(), "recipe input changed by recipe",
                "Source changes do not mutate the detached name");
        helper.succeed();
    }

    @GameTest(template = "story_empty", batch = "ai-recipe-isolation", timeoutTicks = 20)
    public static void nestedContainersAndRegistryEnchantmentsRoundTrip(GameTestHelper helper) {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.setDamageValue(23);
        sword.enchant(helper.getLevel().holderOrThrow(Enchantments.UNBREAKING), 2);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("nested sword"));
        ItemStack inner = new ItemStack(Items.SHULKER_BOX);
        inner.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(sword)));
        ItemStack outer = new ItemStack(Items.CHEST);
        outer.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(inner)));

        ItemStack isolated = RecipeInputIsolation.copy(outer, helper.getLevel().registryAccess());
        helper.assertTrue(ItemStack.isSameItemSameComponents(outer, isolated),
                "Nested contents, durability and registry-backed enchantments survive");
        ItemStack sourceSword = nestedSword(outer);
        ItemStack isolatedSword = nestedSword(isolated);
        MutableComponent sourceName = (MutableComponent) sourceSword.get(DataComponents.CUSTOM_NAME);
        MutableComponent isolatedName = (MutableComponent) isolatedSword.get(DataComponents.CUSTOM_NAME);
        helper.assertTrue(sourceName != isolatedName, "Nested mutable components are independently decoded");
        isolatedName.append(" changed");
        helper.assertValueEqual(sourceName.getString(), "nested sword", "Nested recipe changes stay isolated");
        helper.assertValueEqual(sourceSword.getDamageValue(), 23, "Source nested durability is unchanged");
        helper.succeed();
    }

    @GameTest(template = "story_empty", batch = "ai-recipe-isolation", timeoutTicks = 20)
    public static void repeatedBatchReferencesDoNotShareMutableComponents(GameTestHelper helper) {
        ItemStack original = new ItemStack(Items.STICK, 4);
        original.set(DataComponents.CUSTOM_NAME, Component.literal("shared input"));
        List<ItemStack> isolated = RecipeInputIsolation.copyAll(
                List.of(original, ItemStack.EMPTY, original), helper.getLevel().registryAccess());
        ((MutableComponent) isolated.getFirst().get(DataComponents.CUSTOM_NAME)).append(" first");
        helper.assertValueEqual(isolated.get(2).get(DataComponents.CUSTOM_NAME).getString(), "shared input",
                "Two occurrences are not aliases of one detached object");
        helper.assertValueEqual(original.get(DataComponents.CUSTOM_NAME).getString(), "shared input",
                "Batch recipe cannot mutate source");
        helper.assertTrue(isolated.get(1).isEmpty(), "Empty slot position is retained");
        helper.succeed();
    }

    @GameTest(template = "story_empty", batch = "ai-recipe-isolation", timeoutTicks = 20)
    public static void oversizedAndDeepInputsFailWithOnlySafeCodes(GameTestHelper helper) {
        CompoundTag oversized = new CompoundTag();
        oversized.putByteArray("payload", new byte[RecipeInputIsolation.MAX_ENCODED_BYTES + 1]);
        ItemStack large = new ItemStack(Items.STONE);
        large.set(DataComponents.CUSTOM_DATA, CustomData.of(oversized));
        expectFailure(helper, "RECIPE_INPUT_TOO_LARGE",
                () -> RecipeInputIsolation.copy(large, helper.getLevel().registryAccess()));

        CompoundTag deep = new CompoundTag();
        CompoundTag cursor = deep;
        for (int i = 0; i < RecipeInputIsolation.MAX_NBT_DEPTH + 1; i++) {
            CompoundTag child = new CompoundTag();
            cursor.put("nested", child);
            cursor = child;
        }
        ItemStack nested = new ItemStack(Items.STONE);
        nested.set(DataComponents.CUSTOM_DATA, CustomData.of(deep));
        expectFailure(helper, "RECIPE_INPUT_TOO_DEEP",
                () -> RecipeInputIsolation.copy(nested, helper.getLevel().registryAccess()));
        expectFailure(helper, "RECIPE_INPUT_TOO_MANY",
                () -> RecipeInputIsolation.copyAll(
                        Collections.nCopies(RecipeInputIsolation.MAX_INPUTS + 1, ItemStack.EMPTY),
                        helper.getLevel().registryAccess()));
        helper.succeed();
    }

    @GameTest(template = "story_empty", batch = "ai-recipe-isolation", timeoutTicks = 20)
    public static void transientComponentsCannotSilentlyDisappear(GameTestHelper helper) {
        DataComponentType<String> transientType = DataComponentType.<String>builder()
                .networkSynchronized(ByteBufCodecs.STRING_UTF8).build();
        ItemStack original = new ItemStack(Items.STONE);
        original.set(transientType, "private transient value");
        expectFailure(helper, "RECIPE_INPUT_NON_PERSISTENT_COMPONENT",
                () -> RecipeInputIsolation.copy(original, helper.getLevel().registryAccess()));
        helper.assertValueEqual(original.get(transientType), "private transient value",
                "Rejected input remains unchanged");
        helper.succeed();
    }

    private static ItemStack nestedSword(ItemStack outer) {
        ItemStack inner = outer.get(DataComponents.CONTAINER).getStackInSlot(0);
        return inner.get(DataComponents.CONTAINER).getStackInSlot(0);
    }

    private static void expectFailure(GameTestHelper helper, String code, Runnable operation) {
        try {
            operation.run();
            helper.fail("Expected " + code);
        } catch (RecipeInputIsolation.IsolationException failure) {
            helper.assertValueEqual(failure.code(), code, "Only the stable isolation code is exposed");
            helper.assertTrue(failure.getCause() == null, "Raw codec diagnostics must not be retained");
        }
    }
}
