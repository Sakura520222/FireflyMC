package firefly520.fireflymc.story;

import firefly520.fireflymc.ai.blueprint.BlueprintBlockStateCodec;
import firefly520.fireflymc.ai.blueprint.BlueprintDocument;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;

/** Exercises the strict shared registry converter; never loads optional client classes or writes blocks. */
@GameTestHolder("fireflymc")
@PrefixGameTestTemplate(false)
public final class AiBlueprintSourceGameTests {
    private AiBlueprintSourceGameTests() {}

    @GameTest(template = "story_empty", batch = "ai-blueprint-source", timeoutTicks = 20)
    public static void unknownPaletteIdsAndPropertiesFailInsteadOfDefaulting(GameTestHelper helper) {
        reject(helper, new BlueprintDocument.State("fireflymc:__missing_blueprint_test_block__", Map.of()), "BLUEPRINT_UNKNOWN_BLOCK");
        reject(helper, new BlueprintDocument.State("minecraft:stone", Map.of("made_up", "true")), "BLUEPRINT_UNKNOWN_PROPERTY");
        reject(helper, new BlueprintDocument.State("minecraft:oak_log", Map.of("axis", "diagonal")), "BLUEPRINT_PROPERTY_VALUE_INVALID");
        helper.succeed();
    }

    @GameTest(template = "story_empty", batch = "ai-blueprint-source", timeoutTicks = 20)
    public static void exactStairPalettePropertiesRoundTripWithoutNbt(GameTestHelper helper) {
        var spec = new BlueprintDocument.State("minecraft:oak_stairs",
                Map.of("facing", "east", "shape", "outer_left", "half", "top", "waterlogged", "true"));
        helper.assertTrue(BlueprintBlockStateCodec.describe(BlueprintBlockStateCodec.resolve(spec)).equals(spec),
                "All stair properties survive strict palette conversion");
        helper.succeed();
    }

    private static void reject(GameTestHelper helper, BlueprintDocument.State spec, String code) {
        try {
            BlueprintBlockStateCodec.resolve(spec);
            helper.fail("Invalid palette entry was accepted: " + spec.blockId());
        } catch (IllegalArgumentException rejected) {
            helper.assertTrue(code.equals(rejected.getMessage()), "Expected explicit reason " + code);
        }
    }
}
