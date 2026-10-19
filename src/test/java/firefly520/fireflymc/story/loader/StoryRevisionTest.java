package firefly520.fireflymc.story.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StoryRevisionTest {
    @BeforeAll
    static void bootstrapMinecraftCodecs() {
        SharedConstants.tryDetectVersion();
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @Test
    void jsonObjectKeyOrderAndWhitespaceDoNotChangeRevision() {
        ResourceLocation chapterPath = ResourceLocation.parse("fireflymc:story/chapters/prologue.json");
        ResourceLocation questPath = ResourceLocation.parse("fireflymc:story/quests/prologue/intro.json");
        JsonElement chapter = JsonParser.parseString("""
            {"title":{"text":"Prologue"},"subtitle":{"text":"First"},"index":0}
            """);
        JsonElement quest = JsonParser.parseString("""
            {
              "chapter": "fireflymc:prologue",
              "title": {"text":"Introduction"},
              "initial": true,
              "requirements": [],
              "on_complete": [
                {"type":"fireflymc:send_message","text":{"text":"first"}},
                {"type":"fireflymc:send_message","text":{"text":"second"}}
              ]
            }
            """);
        JsonElement reorderedChapter = JsonParser.parseString("""
            {"index":0,"subtitle":{"text":"First"},"title":{"text":"Prologue"}}
            """);
        JsonElement reorderedQuest = JsonParser.parseString("""

              {"on_complete":[{"text":{"text":"first"},"type":"fireflymc:send_message"},
                               {"text":{"text":"second"},"type":"fireflymc:send_message"}],
               "requirements":[],"initial":true,"title":{"text":"Introduction"},
               "chapter":"fireflymc:prologue"}

            """
        );

        String first = StoryRevision.sha256(StoryDefinitionLoader.prepare(Map.of(
            chapterPath, chapter, questPath, quest)));
        String second = StoryRevision.sha256(StoryDefinitionLoader.prepare(Map.of(
            chapterPath, reorderedChapter, questPath, reorderedQuest)));

        assertEquals(first, second);
    }

    @Test
    void arrayOrderChangesRevision() {
        ResourceLocation chapterPath = ResourceLocation.parse("fireflymc:story/chapters/prologue.json");
        ResourceLocation questPath = ResourceLocation.parse("fireflymc:story/quests/prologue/intro.json");
        JsonElement chapter = JsonParser.parseString(
            "{\"title\":{\"text\":\"Prologue\"},\"subtitle\":{\"text\":\"First\"},\"index\":0}");
        JsonElement quest = JsonParser.parseString("""
            {"chapter":"fireflymc:prologue","title":{"text":"Introduction"},"initial":true,"requirements":[],
             "on_complete":[
               {"type":"fireflymc:send_message","text":{"text":"first"}},
               {"type":"fireflymc:send_message","text":{"text":"second"}}
             ]}
            """);
        JsonObject reversed = quest.deepCopy().getAsJsonObject();
        var actions = reversed.getAsJsonArray("on_complete");
        var first = actions.remove(0);
        actions.add(first);

        String original = StoryRevision.sha256(StoryDefinitionLoader.prepare(Map.of(
            chapterPath, chapter, questPath, quest)));
        String changed = StoryRevision.sha256(StoryDefinitionLoader.prepare(Map.of(
            chapterPath, chapter, questPath, reversed)));

        assertNotEquals(original, changed);
    }

    @Test
    void repeatedPreparationAndResourceIterationOrderAreDeterministic() {
        ResourceLocation chapterPath = ResourceLocation.parse("fireflymc:story/chapters/prologue.json");
        ResourceLocation questPath = ResourceLocation.parse("fireflymc:story/quests/prologue/intro.json");
        JsonElement chapter = JsonParser.parseString(
            "{\"title\":{\"text\":\"Prologue\"},\"subtitle\":{\"text\":\"First\"},\"index\":0}");
        JsonElement quest = JsonParser.parseString("""
            {"chapter":"fireflymc:prologue","title":{"text":"Introduction"},"initial":true,"requirements":[],
             "on_complete":[{"type":"fireflymc:send_message","text":{"text":"first"}}]}
            """);

        Map<ResourceLocation, JsonElement> forward = new LinkedHashMap<>();
        forward.put(chapterPath, chapter);
        forward.put(questPath, quest);
        Map<ResourceLocation, JsonElement> reverse = new LinkedHashMap<>();
        reverse.put(questPath, quest);
        reverse.put(chapterPath, chapter);

        String first = StoryRevision.sha256(StoryDefinitionLoader.prepare(forward));
        String repeated = StoryRevision.sha256(StoryDefinitionLoader.prepare(forward));
        String reordered = StoryRevision.sha256(StoryDefinitionLoader.prepare(reverse));

        assertEquals(first, repeated);
        assertEquals(first, reordered);
        assertTrue(first.matches("[0-9a-f]{64}"));
    }

    @Test
    void canonicalDefinitionsAreSortedByDefinitionId() {
        ResourceLocation chapterPath = ResourceLocation.parse("fireflymc:story/chapters/z.json");
        ResourceLocation questPath = ResourceLocation.parse("fireflymc:story/quests/chapter/a.json");
        JsonElement chapter = JsonParser.parseString("""
            {"title":{"text":"Prologue"},"subtitle":{"text":"First"},"index":0}
            """);
        JsonElement quest = JsonParser.parseString("""
            {"chapter":"fireflymc:z","title":{"text":"Introduction"},
             "initial":true,"requirements":[],"on_complete":[]}
            """);

        PreparedStoryData prepared = StoryDefinitionLoader.prepare(Map.of(
            chapterPath, chapter, questPath, quest));

        assertEquals(
            List.of(
                ResourceLocation.parse("fireflymc:chapter/a"),
                ResourceLocation.parse("fireflymc:z")),
            prepared.canonicalDefinitions().stream()
                .map(PreparedStoryData.CanonicalDefinition::id)
                .toList());
    }

    @Test
    void equivalentNumberRepresentationsNormalizeToTheSameRevision() {
        ResourceLocation chapterPath = ResourceLocation.parse("fireflymc:story/chapters/prologue.json");
        ResourceLocation questPath = ResourceLocation.parse("fireflymc:story/quests/prologue/intro.json");
        JsonElement quest = JsonParser.parseString("""
            {"chapter":"fireflymc:prologue","title":{"text":"Introduction"},
             "initial":true,"requirements":[],"on_complete":[]}
            """);
        JsonElement integerChapter = JsonParser.parseString("""
            {"title":{"text":"Prologue"},"subtitle":{"text":"First"},"index":0}
            """);
        JsonElement decimalChapter = JsonParser.parseString("""
            {"title":{"text":"Prologue"},"subtitle":{"text":"First"},"index":0.0}
            """);

        String integerRevision = StoryRevision.sha256(StoryDefinitionLoader.prepare(Map.of(
            chapterPath, integerChapter, questPath, quest)));
        String decimalRevision = StoryRevision.sha256(StoryDefinitionLoader.prepare(Map.of(
            chapterPath, decimalChapter, questPath, quest)));

        assertEquals(integerRevision, decimalRevision);
    }

    @Test
    void nonAsciiLengthPrefixesUseUtf8ByteCounts() throws Exception {
        ResourceLocation chapterPath = ResourceLocation.parse("fireflymc:story/chapters/prologue.json");
        ResourceLocation questPath = ResourceLocation.parse("fireflymc:story/quests/prologue/intro.json");
        JsonElement chapter = JsonParser.parseString("""
            {"title":{"text":"萤"},"subtitle":{"text":"序章"},"index":0}
            """);
        JsonElement quest = JsonParser.parseString("""
            {"chapter":"fireflymc:prologue","title":{"text":"引言"},
             "initial":true,"requirements":[],"on_complete":[]}
            """);

        PreparedStoryData prepared = StoryDefinitionLoader.prepare(Map.of(
            chapterPath, chapter, questPath, quest));
        MessageDigest utf8Lengths = MessageDigest.getInstance("SHA-256");
        MessageDigest characterLengths = MessageDigest.getInstance("SHA-256");
        for (PreparedStoryData.CanonicalDefinition definition : prepared.canonicalDefinitions()) {
            updatePrefixed(utf8Lengths, definition.kind(), true);
            updatePrefixed(utf8Lengths, definition.id().toString(), true);
            updatePrefixed(utf8Lengths, definition.canonicalJson(), true);
            updatePrefixed(characterLengths, definition.kind(), false);
            updatePrefixed(characterLengths, definition.id().toString(), false);
            updatePrefixed(characterLengths, definition.canonicalJson(), false);
        }

        assertEquals(
            HexFormat.of().formatHex(utf8Lengths.digest()),
            StoryRevision.sha256(prepared));
        assertNotEquals(
            HexFormat.of().formatHex(characterLengths.digest()),
            StoryRevision.sha256(prepared));
    }

    private static void updatePrefixed(
        MessageDigest digest,
        String value,
        boolean useUtf8ByteLength
    ) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int length = useUtf8ByteLength ? bytes.length : value.length();
        digest.update(new byte[] {
            (byte) (length >>> 24),
            (byte) (length >>> 16),
            (byte) (length >>> 8),
            (byte) length});
        digest.update(bytes);
    }
}
