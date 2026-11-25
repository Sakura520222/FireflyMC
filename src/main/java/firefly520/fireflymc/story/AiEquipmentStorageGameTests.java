package firefly520.fireflymc.story;

import firefly520.fireflymc.ai.integration.equipment.OwnedPlayerAccess;
import firefly520.fireflymc.ai.integration.equipment.ReadOnlyNativeAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

/** Dedicated service-chain tests live in src/validation EquipmentScenario, never a weakened gate. */
@GameTestHolder("fireflymc")
@PrefixGameTestTemplate(false)
public final class AiEquipmentStorageGameTests {
    @GameTest(template = "story_empty", batch = "ai-equipment", timeoutTicks = 40)
    public static void gameTestHostCannotBypassDedicatedEquipmentGate(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        helper.assertTrue(!server.isDedicatedServer(), "This GameTest host is not a dedicated player server");
        var access = new OwnedPlayerAccess(server, player -> true);
        helper.assertValueEqual(access.refusal(null, UUID.randomUUID()), "AI_DEDICATED_SERVER_REQUIRED",
                "A fixture authentication predicate cannot bypass the production server-type gate");
        helper.succeed();
    }
    @GameTest(template = "story_empty", batch = "ai-equipment", timeoutTicks = 40)
    public static void equipmentObservationKeepsComponentsBehindDigest(GameTestHelper helper) {
        var helmet = new ItemStack(Items.DIAMOND_HELMET);
        helmet.set(DataComponents.CUSTOM_NAME, Component.literal("private-equipment-note"));
        var observed = ReadOnlyNativeAccess.item(helmet);
        helper.assertValueEqual(observed.itemId(), "minecraft:diamond_helmet", "Native registry ID is retained");
        helper.assertTrue(observed.componentsDigest().matches("[0-9a-f]{64}"), "Only a component digest is displayed");
        helper.assertTrue(!observed.consumptionAuthorized(), "An equipment observation is not a material grant");
        String json = firefly520.fireflymc.ai.storage.AiJson.GSON.toJson(observed);
        helper.assertTrue(!json.contains("private-equipment-note") && !json.contains("snapshot"),
                "Even generic serialization cannot expose a native stack or private component");
        helper.succeed();
    }
}
