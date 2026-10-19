package firefly520.fireflymc.story.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.ComponentSerialization;
import java.util.stream.Stream;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import firefly520.fireflymc.story.action.StoryAction;
import firefly520.fireflymc.story.condition.ConditionTypes.PickupCondition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StoryCodecsTest {
    private static final ResourceLocation QUEST_ID = ResourceLocation.parse("fireflymc:prologue/intro");

    @BeforeAll
    static void bootstrapMinecraftCodecs() {
        SharedConstants.tryDetectVersion();
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @Test
    void parseChapter() {
        var chapter = StoryCodecs.parseChapter(
            ResourceLocation.parse("fireflymc:prologue"),
            json("""
                {"title":{"translate":"story.fireflymc.prologue"},"subtitle":{"text":"Chapter one"},"index":2}
                """));

        assertEquals(ResourceLocation.parse("fireflymc:prologue"), chapter.id());
        assertEquals("story.fireflymc.prologue", translationKey(chapter.title()));
        assertEquals("Chapter one", chapter.subtitle().getString());
        assertEquals(2, chapter.index());
    }

    @Test
    void parseInitialQuestWithoutRequirementsOrActions() {
        var quest = StoryCodecs.parseQuest(QUEST_ID, json("""
            {"chapter":"fireflymc:prologue","title":{"translate":"story.fireflymc.intro"},
             "requirements":[],"on_complete":[],"initial":true}
            """));

        assertEquals(QUEST_ID, quest.id());
        assertEquals(ResourceLocation.parse("fireflymc:prologue"), quest.chapter());
        assertEquals("story.fireflymc.intro", translationKey(quest.title()));
        assertTrue(quest.initial());
        assertTrue(quest.requirements().isEmpty());
        assertTrue(quest.onComplete().isEmpty());
    }

    @Test
    void nullApiArgumentsUseDefinitionDiagnostics() {
        var nullQuestJson = assertThrowsExactly(StoryCodecs.StoryDefinitionException.class,
            () -> StoryCodecs.parseQuest(QUEST_ID, null));
        assertTrue(nullQuestJson.getMessage().contains("data/fireflymc/story/quests/prologue/intro.json"),
            () -> "missing quest file path in: " + nullQuestJson.getMessage());
        assertTrue(nullQuestJson.getMessage().contains("root must be an object"),
            () -> "missing root field in: " + nullQuestJson.getMessage());

        var nullChapterJson = assertThrowsExactly(StoryCodecs.StoryDefinitionException.class,
            () -> StoryCodecs.parseChapter(ResourceLocation.parse("fireflymc:prologue"), null));
        assertTrue(nullChapterJson.getMessage().contains("data/fireflymc/story/chapters/prologue.json"),
            () -> "missing chapter file path in: " + nullChapterJson.getMessage());
        assertTrue(nullChapterJson.getMessage().contains("root must be an object"),
            () -> "missing root field in: " + nullChapterJson.getMessage());

        var nullQuestId = assertThrowsExactly(StoryCodecs.StoryDefinitionException.class,
            () -> StoryCodecs.parseQuest(null, json("{}")));
        assertTrue(nullQuestId.getMessage().contains("id is required"),
            () -> "missing id field in: " + nullQuestId.getMessage());

        var nullChapterId = assertThrowsExactly(StoryCodecs.StoryDefinitionException.class,
            () -> StoryCodecs.parseChapter(null, json("{}")));
        assertTrue(nullChapterId.getMessage().contains("id is required"),
            () -> "missing id field in: " + nullChapterId.getMessage());
    }

    @Test
    void absentListsAndDefaultableFieldsUseSpecifiedDefaults() {
        var quest = StoryCodecs.parseQuest(QUEST_ID, json("""
            {"chapter":"fireflymc:prologue","title":{"translate":"story.fireflymc.intro"},
             "requirements":[{"id":"memory","type":"fireflymc:pickup",
                              "item":"minecraft:apple","count":1}]}
            """));

        assertFalse(quest.initial());
        assertTrue(quest.description().isEmpty());
        assertTrue(quest.onComplete().isEmpty());
        assertNull(quest.icon());
        assertEquals(1, quest.requirements().get(0).stateVersion());
    }

    @Test
    void sameStateNameIsAcceptedAcrossPlayerAndWorldScopes() {
        var quest = StoryCodecs.parseQuest(QUEST_ID, json("""
            {"chapter":"fireflymc:prologue","title":{"text":"Intro"},"requirements":[],
             "on_complete":[
               {"type":"fireflymc:set_flag","flag":"story.value","value":true,"scope":"world"},
               {"type":"fireflymc:set_var","var":"story.value","op":"set","amount":1}
             ]}
            """));

        assertEquals(2, quest.onComplete().size());
    }

    @Test
    void questDefinitionDefensivelyCopiesMutableInputLists() {
        var oldDescription = Component.literal("old description");
        var condition = new PickupCondition(ResourceLocation.parse("minecraft:apple"), 1);
        var oldRequirement = new RequirementDefinition("memory", 1, condition);
        StoryAction oldAction = new StoryAction() {};
        var descriptionInput = new ArrayList<>(List.<Component>of(oldDescription));
        var requirementInput = new ArrayList<>(List.of(oldRequirement));
        var actionInput = new ArrayList<>(List.of(oldAction));

        var quest = new QuestDefinition(
            QUEST_ID,
            ResourceLocation.parse("fireflymc:prologue"),
            Component.literal("title"),
            Component.empty(),
            descriptionInput,
            null,
            false,
            requirementInput,
            actionInput);

        descriptionInput.set(0, Component.literal("mutated"));
        requirementInput.clear();
        actionInput.clear();

        assertEquals(List.of(oldDescription), quest.description());
        assertEquals(List.of(oldRequirement), quest.requirements());
        assertEquals(List.of(oldAction), quest.onComplete());
        assertThrowsExactly(UnsupportedOperationException.class,
            () -> quest.description().add(Component.literal("new")));
        assertThrowsExactly(UnsupportedOperationException.class,
            () -> quest.requirements().clear());
        assertThrowsExactly(UnsupportedOperationException.class,
            () -> quest.onComplete().clear());
    }

    @Test
    void translatedTitleSurvivesComponentCodecRoundTrip() {
        var quest = StoryCodecs.parseQuest(QUEST_ID, json("""
            {"chapter":"fireflymc:prologue","title":{"translate":"story.fireflymc.intro"},
             "requirements":[],"on_complete":[]}
            """));

        var roundTripped = ComponentSerialization.CODEC
            .encodeStart(JsonOps.INSTANCE, quest.title())
            .flatMap(encoded -> ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, encoded))
            .result()
            .orElseThrow();

        assertEquals("story.fireflymc.intro", translationKey(roundTripped));
    }

    @Test
    void duplicateRequirementIdsFailWithQuestPath() {
        var input = quest("""
            [{"id":"memory","type":"fireflymc:custom_event","event":"fireflymc:event","count":1},
             {"id":"memory","type":"fireflymc:custom_event","event":"fireflymc:other","count":1}]
            """, "[]");
        var exception = assertThrowsExactly(StoryCodecs.StoryDefinitionException.class,
            () -> StoryCodecs.parseQuest(QUEST_ID, json(input)));

        assertTrue(exception.getMessage().contains("data/fireflymc/story/quests/prologue/intro.json"),
            () -> "missing file path in: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("requirements[1].id"),
            () -> "missing field path in: " + exception.getMessage());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("invalidQuests")
    void invalidValuesNeverSilentlyDefault(String caseName, String input, String expectedField) {
        var exception = assertThrowsExactly(StoryCodecs.StoryDefinitionException.class,
            () -> StoryCodecs.parseQuest(QUEST_ID, json(input)));

        assertTrue(exception.getMessage().contains("data/fireflymc/story/quests/prologue/intro.json"),
            () -> "missing file path in: " + exception.getMessage());
        assertTrue(exception.getMessage().contains(expectedField),
            () -> "missing " + expectedField + " in: " + exception.getMessage());
    }

    private static Stream<Arguments> invalidQuests() {
        return Stream.of(
            Arguments.of("unknown condition type", quest("""
                [{"id":"memory","type":"fireflymc:missing","count":1}]
                """, "[]"), "requirements[0].type"),
            Arguments.of("selector both", quest("""
                [{"id":"memory","type":"fireflymc:kill","entity":"minecraft:creeper",
                  "entity_tag":"minecraft:creepers","count":1}]
                """, "[]"), "requirements[0].entity/entity_tag"),
            Arguments.of("selector neither", quest("""
                [{"id":"memory","type":"fireflymc:enter_biome","count":1}]
                """, "[]"), "requirements[0].biome/biome_tag"),
            Arguments.of("condition count zero", quest("""
                [{"id":"memory","type":"fireflymc:pickup","item":"minecraft:apple","count":0}]
                """, "[]"), "requirements[0].count"),
            Arguments.of("requirements null", """
                {"chapter":"fireflymc:prologue","title":{"text":"Intro"},"requirements":null}
                """, "requirements"),
            Arguments.of("on complete null", """
                {"chapter":"fireflymc:prologue","title":{"text":"Intro"},"on_complete":null}
                """, "on_complete"),
            Arguments.of("description null", """
                {"chapter":"fireflymc:prologue","title":{"text":"Intro"},"description":null}
                """, "description"),
            Arguments.of("initial null", """
                {"chapter":"fireflymc:prologue","title":{"text":"Intro"},"initial":null}
                """, "initial"),
            Arguments.of("icon null", """
                {"chapter":"fireflymc:prologue","title":{"text":"Intro"},"icon":null}
                """, "icon"),
            Arguments.of("count integer overflow high", quest("""
                [{"id":"memory","type":"fireflymc:pickup","item":"minecraft:apple","count":2147483648}]
                """, "[]"), "requirements[0].count"),
            Arguments.of("count integer overflow low", quest("""
                [{"id":"memory","type":"fireflymc:pickup","item":"minecraft:apple","count":-2147483649}]
                """, "[]"), "requirements[0].count"),
            Arguments.of("count is not exact integer", quest("""
                [{"id":"memory","type":"fireflymc:pickup","item":"minecraft:apple","count":1.5}]
                """, "[]"), "requirements[0].count"),
            Arguments.of("state version zero", quest("""
                [{"id":"memory","type":"fireflymc:pickup","item":"minecraft:apple","count":1,"state_version":0}]
                """, "[]"), "requirements[0].state_version"),
            Arguments.of("state version overflow high", quest("""
                [{"id":"memory","type":"fireflymc:pickup","item":"minecraft:apple","count":1,"state_version":2147483648}]
                """, "[]"), "requirements[0].state_version"),
            Arguments.of("state version overflow low", quest("""
                [{"id":"memory","type":"fireflymc:pickup","item":"minecraft:apple","count":1,"state_version":-2147483649}]
                """, "[]"), "requirements[0].state_version"),
            Arguments.of("state version is not exact integer", quest("""
                [{"id":"memory","type":"fireflymc:pickup","item":"minecraft:apple","count":1,"state_version":1.5}]
                """, "[]"), "requirements[0].state_version"),
            Arguments.of("custom event id invalid", quest("""
                [{"id":"memory","type":"fireflymc:custom_event","event":"Bad:Event","count":1}]
                """, "[]"), "requirements[0].event"),
            Arguments.of("flag name invalid", quest("""
                [{"id":"memory","type":"fireflymc:flag","flag":"Bad:Flag","value":true,"scope":"player"}]
                """, "[]"), "requirements[0].flag"),
            Arguments.of("radius negative", quest("""
                [{"id":"memory","type":"fireflymc:location","dimension":"minecraft:overworld",
                  "x":0.0,"y":64.0,"z":0.0,"radius":-1.0}]
                """, "[]"), "requirements[0].radius"),
            Arguments.of("nonfinite coordinate", quest("""
                [{"id":"memory","type":"fireflymc:location","dimension":"minecraft:overworld",
                  "x":NaN,"y":64.0,"z":0.0,"radius":1.0}]
                """, "[]"), "requirements[0].x"),
            Arguments.of("give item count negative", quest("[]", """
                [{"type":"fireflymc:give_item","item":"minecraft:apple","count":-1}]
                """), "on_complete[0].count"),
            Arguments.of("flag and var collide in same scope", quest("[]", """
                [{"type":"fireflymc:set_flag","flag":"story.value","value":true,"scope":"player"},
                 {"type":"fireflymc:set_var","var":"story.value","op":"set","amount":1}]
                """), "on_complete[1].var"),
            Arguments.of("set var bounds reversed", quest("[]", """
                [{"type":"fireflymc:set_var","var":"story.value","op":"set","amount":1,"min":10,"max":1}]
                """), "on_complete[0].min/max")
            ,
            Arguments.of("amount integer overflow high", quest("[]", """
                [{"type":"fireflymc:set_var","var":"story.value","op":"set","amount":2147483648}]
                """), "on_complete[0].amount"),
            Arguments.of("amount integer overflow low", quest("[]", """
                [{"type":"fireflymc:set_var","var":"story.value","op":"set","amount":-2147483649}]
                """), "on_complete[0].amount"),
            Arguments.of("amount is not exact integer", quest("[]", """
                [{"type":"fireflymc:set_var","var":"story.value","op":"set","amount":1.5}]
                """), "on_complete[0].amount"),
            Arguments.of("set var min null", quest("[]", """
                [{"type":"fireflymc:set_var","var":"story.value","op":"set","amount":1,"min":null}]
                """), "on_complete[0].min"),
            Arguments.of("set var max null", quest("[]", """
                [{"type":"fireflymc:set_var","var":"story.value","op":"set","amount":1,"max":null}]
                """), "on_complete[0].max"),
            Arguments.of("set var min overflow high", quest("[]", """
                [{"type":"fireflymc:set_var","var":"story.value","op":"set","amount":1,"min":2147483648}]
                """), "on_complete[0].min"),
            Arguments.of("set var min overflow low", quest("[]", """
                [{"type":"fireflymc:set_var","var":"story.value","op":"set","amount":1,"min":-2147483649}]
                """), "on_complete[0].min"),
            Arguments.of("set var max overflow high", quest("[]", """
                [{"type":"fireflymc:set_var","var":"story.value","op":"set","amount":1,"max":2147483648}]
                """), "on_complete[0].max"),
            Arguments.of("set var max overflow low", quest("[]", """
                [{"type":"fireflymc:set_var","var":"story.value","op":"set","amount":1,"max":-2147483649}]
                """), "on_complete[0].max"),
            Arguments.of("show title component null", quest("[]", """
                [{"type":"fireflymc:show_title","title":null,"subtitle":{"text":"Hello"},
                  "fade_in":5,"stay":20,"fade_out":5}]
                """), "on_complete[0].title"),
            Arguments.of("send message component null", quest("[]", """
                [{"type":"fireflymc:send_message","text":null}]
                """), "on_complete[0].text")
        );
    }

    private static String quest(String requirements, String actions) {
        return """
            {"chapter":"fireflymc:prologue","title":{"translate":"story.fireflymc.intro"},
             "requirements":%s,"on_complete":%s}
            """.formatted(requirements, actions);
    }

    private static JsonElement json(String value) {
        return JsonParser.parseString(value);
    }

    private static String translationKey(Component component) {
        return ((TranslatableContents) component.getContents()).getKey();
    }
}
