package firefly520.fireflymc.story.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.MapCodec;
import net.minecraft.SharedConstants;
import firefly520.fireflymc.story.action.ActionTypes;
import firefly520.fireflymc.story.action.ActionTypes.*;
import firefly520.fireflymc.story.action.StoryAction;
import firefly520.fireflymc.story.condition.ConditionPersistence;
import firefly520.fireflymc.story.condition.ConditionTypes;
import firefly520.fireflymc.story.condition.ConditionTypes.*;
import firefly520.fireflymc.story.condition.StoryCondition;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StorySchemaTest {
    @BeforeAll
    static void bootstrapMinecraftCodecs() {
        SharedConstants.tryDetectVersion();
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("conditions")
    void allBuiltinConditionSchemas(String typeName, String json, ConditionPersistence persistence, Object expected) {
        var condition = ConditionTypes.decode(type(typeName), object(json));

        assertEquals(persistence, condition.persistence());
        assertEquals(expected, condition);
    }

    private static Stream<Arguments> conditions() {
        return Stream.of(
            Arguments.of("fireflymc:pickup",
                "{\"item\":\"minecraft:apple\",\"count\":3}",
                ConditionPersistence.COUNTER,
                new PickupCondition(id("minecraft:apple"), 3)),
            Arguments.of("fireflymc:kill",
                "{\"entity\":\"minecraft:creeper\",\"count\":2}",
                ConditionPersistence.COUNTER,
                new KillCondition(id("minecraft:creeper"), null, 2)),
            Arguments.of("fireflymc:custom_event",
                "{\"event\":\"fireflymc:dialogue_done\",\"count\":4}",
                ConditionPersistence.COUNTER,
                new CustomEventCondition(id("fireflymc:dialogue_done"), 4)),
            Arguments.of("fireflymc:enter_dimension",
                "{\"dimension\":\"minecraft:the_nether\"}",
                ConditionPersistence.STICKY,
                new EnterDimensionCondition(id("minecraft:the_nether"))),
            Arguments.of("fireflymc:enter_biome",
                "{\"biome\":\"minecraft:plains\"}",
                ConditionPersistence.STICKY,
                new EnterBiomeCondition(id("minecraft:plains"), null)),
            Arguments.of("fireflymc:enter_structure",
                "{\"structure_tag\":\"minecraft:villages\"}",
                ConditionPersistence.STICKY,
                new EnterStructureCondition(null, id("minecraft:villages"))),
            Arguments.of("fireflymc:advancement",
                "{\"id\":\"minecraft:story/root\"}",
                ConditionPersistence.STICKY,
                new AdvancementCondition(id("minecraft:story/root"))),
            Arguments.of("fireflymc:location",
                "{\"dimension\":\"minecraft:overworld\",\"x\":1.5,\"y\":64.0,\"z\":-2.5,\"radius\":8.0}",
                ConditionPersistence.STICKY,
                new LocationCondition(id("minecraft:overworld"), 1.5D, 64.0D, -2.5D, 8.0F)),
            Arguments.of("fireflymc:inventory",
                "{\"item\":\"minecraft:apple\",\"count\":2}",
                ConditionPersistence.DYNAMIC,
                new InventoryCondition(id("minecraft:apple"), 2)),
            Arguments.of("fireflymc:flag",
                "{\"flag\":\"story.met_firefly\",\"value\":true,\"scope\":\"world\"}",
                ConditionPersistence.DYNAMIC,
                new FlagCondition("story.met_firefly", true, StoryCondition.Scope.WORLD))
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("actions")
    void allBuiltinActionSchemas(String typeName, String json, Object expected) {
        var action = ActionTypes.decode(type(typeName), object(json));

        assertEquals(expected, action);
    }

    private static Stream<Arguments> actions() {
        return Stream.of(
            Arguments.of("fireflymc:set_flag",
                "{\"flag\":\"story.met_firefly\",\"value\":true,\"scope\":\"player\"}",
                new SetFlagAction("story.met_firefly", true, StoryCondition.Scope.PLAYER)),
            Arguments.of("fireflymc:set_var",
                "{\"var\":\"story.affinity\",\"op\":\"add\",\"amount\":2,\"min\":0,\"max\":10}",
                new SetVarAction("story.affinity", SetVarAction.Operation.ADD, 2, 0, 10)),
            Arguments.of("fireflymc:unlock_quest",
                "{\"quest\":\"fireflymc:prologue/first_contact\"}",
                new UnlockQuestAction(id("fireflymc:prologue/first_contact"))),
            Arguments.of("fireflymc:give_item",
                "{\"item\":\"minecraft:apple\",\"count\":3}",
                new GiveItemAction(id("minecraft:apple"), 3)),
            Arguments.of("fireflymc:play_sound",
                "{\"sound\":\"minecraft:entity.player.levelup\",\"volume\":0.7,\"pitch\":1.2}",
                new PlaySoundAction(id("minecraft:entity.player.levelup"), 0.7F, 1.2F)),
            Arguments.of("fireflymc:show_title",
                "{\"title\":{\"translate\":\"story.title\"},\"subtitle\":{\"text\":\"Hello\"},\"fade_in\":5,\"stay\":20,\"fade_out\":5}",
                new ShowTitleAction(net.minecraft.network.chat.Component.translatable("story.title"),
                    net.minecraft.network.chat.Component.literal("Hello"), 5, 20, 5)),
            Arguments.of("fireflymc:send_message",
                "{\"text\":{\"translate\":\"story.message\"}}",
                new SendMessageAction(net.minecraft.network.chat.Component.translatable("story.message"))),
            Arguments.of("fireflymc:execute_command",
                "{\"command\":\"give @s minecraft:apple 1\"}",
                new ExecuteCommandAction("give @s minecraft:apple 1")),
            Arguments.of("fireflymc:teleport",
                "{\"dimension\":\"minecraft:overworld\",\"x\":1.0,\"y\":64.0,\"z\":-1.0}",
                new TeleportAction(id("minecraft:overworld"), 1.0D, 64.0D, -1.0D))
        );
    }

    @Test
    void unknownConditionTypeFails() {
        var exception = assertThrowsExactly(IllegalArgumentException.class,
            () -> ConditionTypes.decode(type("fireflymc:missing"), object("{}")));

        assertTrue(exception.getMessage().contains("fireflymc:missing"));
    }

    @Test
    void unknownActionTypeFails() {
        var exception = assertThrowsExactly(IllegalArgumentException.class,
            () -> ActionTypes.decode(type("fireflymc:missing"), object("{}")));

        assertTrue(exception.getMessage().contains("fireflymc:missing"));
    }

    @Test
    void extensionRegistrationWorksBeforeReloadAndBuiltinsCannotBeReplaced() {
        var conditionType = type("fireflymc:test_condition_extension");
        var actionType = type("fireflymc:test_action_extension");
        StoryCondition customCondition = new StoryCondition() {
            @Override
            public ConditionPersistence persistence() {
                return ConditionPersistence.DYNAMIC;
            }
        };
        StoryAction customAction = new StoryAction() {};

        ConditionTypes.register(conditionType, MapCodec.unit(customCondition));
        ActionTypes.register(actionType, MapCodec.unit(customAction));

        assertEquals(customCondition, ConditionTypes.decode(conditionType, object("{}")));
        assertEquals(customAction, ActionTypes.decode(actionType, object("{}")));
        assertThrowsExactly(IllegalStateException.class,
            () -> ConditionTypes.register(type("fireflymc:pickup"), MapCodec.unit(customCondition)));
        assertThrowsExactly(IllegalStateException.class,
            () -> ActionTypes.register(type("fireflymc:set_flag"), MapCodec.unit(customAction)));
    }

    @Test
    void requirementDefinitionIsTypedAndImmutable() {
        var condition = new FlagCondition("story.flag", false, StoryCondition.Scope.PLAYER);
        var requirement = new RequirementDefinition("memory", 3, condition);

        assertEquals("memory", requirement.id());
        assertEquals(3, requirement.stateVersion());
        assertEquals(condition, requirement.condition());
        assertEquals(ConditionPersistence.DYNAMIC, requirement.condition().persistence());
    }

    private static ResourceLocation type(String value) {
        return ResourceLocation.parse(value);
    }

    private static ResourceLocation id(String value) {
        return ResourceLocation.parse(value);
    }

    private static JsonObject object(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }
}
