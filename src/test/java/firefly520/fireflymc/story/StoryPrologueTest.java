package firefly520.fireflymc.story;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import firefly520.fireflymc.story.action.ActionTypes;
import firefly520.fireflymc.story.condition.ConditionTypes;
import firefly520.fireflymc.story.condition.StoryCondition;
import firefly520.fireflymc.story.loader.PreparedStoryData;
import firefly520.fireflymc.story.loader.StoryDefinitionLoader;
import firefly520.fireflymc.story.loader.StoryRevision;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StoryPrologueTest {
    private static final List<String> NODES = List.of(
        "first_light", "find_memory", "heard_voice", "quiet_night", "old_trail", "embers_end");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @Test
    void bundledPrologueHasOneInitialAndACompleteSixNodeChain() throws IOException {
        PreparedStoryData data = StoryDefinitionLoader.prepare(resources());
        assertEquals(1, data.chapters().size());
        assertEquals(6, data.quests().size());
        assertEquals(Set.of(quest("first_light")), data.graph().initialQuests());
        assertTrue(data.quests().get(quest("first_light")).requirements().isEmpty());
        for (int index = 0; index < NODES.size(); index++) {
            assertEquals(index + 1 < NODES.size()
                ? List.of(quest(NODES.get(index + 1))) : List.of(),
                data.graph().outgoing(quest(NODES.get(index))));
        }
        var memory = data.quests().get(quest("find_memory")).requirements().getFirst().condition();
        assertInstanceOf(ConditionTypes.PickupCondition.class, memory);
        assertEquals(ResourceLocation.parse("minecraft:echo_shard"),
            ((ConditionTypes.PickupCondition) memory).item());
    }

    @Test
    void trailAndFinaleCanFinishWithoutCoordinatesOrFutureNpcEvents() throws IOException {
        PreparedStoryData data = StoryDefinitionLoader.prepare(resources());
        var trail = data.quests().get(quest("old_trail"));
        assertTrue(trail.requirements().stream().anyMatch(requirement ->
            requirement.condition() instanceof ConditionTypes.InventoryCondition inventory
                && inventory.item().equals(ResourceLocation.parse("minecraft:torch"))));
        assertTrue(data.quests().values().stream().flatMap(q -> q.requirements().stream())
            .noneMatch(r -> r.condition() instanceof ConditionTypes.LocationCondition
                || r.condition() instanceof ConditionTypes.CustomEventCondition));
        assertTrue(data.quests().get(quest("embers_end")).onComplete().stream().anyMatch(action ->
            action instanceof ActionTypes.SetFlagAction flag
                && flag.scope() == StoryCondition.Scope.WORLD
                && flag.flag().equals("prologue.awaiting_firefly") && flag.value()));
    }

    @Test
    void everyStoryTranslationExistsInBothLanguages() throws IOException {
        Map<ResourceLocation, JsonElement> resources = resources();
        for (String language : List.of("zh_cn", "en_us")) {
            JsonObject translations = json("assets/fireflymc/lang/" + language + ".json");
            resources.values().forEach(value -> assertTranslations(value, translations));
        }
    }

    @Test
    void allEngineCapabilitiesHaveAnIsolatedDatapackFixture() throws IOException {
        Path root = Path.of("src/test/resources/story_fixture/coverage/data/fireflymc");
        Map<ResourceLocation, JsonElement> resources = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                resources.put(ResourceLocation.parse("fireflymc:" + root.relativize(file)
                    .toString().replace('\\', '/')), JsonParser.parseString(Files.readString(file)));
            }
        }
        var data = StoryDefinitionLoader.prepare(resources);
        var probes = data.quests().get(ResourceLocation.parse("fireflymc:coverage/probes"));
        assertEquals(10, probes.requirements().stream().map(r -> r.condition().getClass()).distinct().count());
        assertEquals(9, probes.onComplete().stream().map(Object::getClass).distinct().count());
        assertEquals(ResourceLocation.parse("minecraft:story/mine_stone"),
            ((ConditionTypes.AdvancementCondition) probes.requirements().stream()
                .filter(r -> r.id().equals("advancement")).findFirst().orElseThrow().condition()).advancement());
        assertTrue(StoryDefinitionLoader.prepare(resources()).quests().keySet().stream()
            .noneMatch(id -> id.getPath().startsWith("coverage/")));
    }

    @Test
    void datapackTitleOverrideChangesRevisionWithoutChangingBundledContent() throws IOException {
        Map<ResourceLocation, JsonElement> resources = resources();
        String original = StoryRevision.sha256(StoryDefinitionLoader.prepare(resources));
        ResourceLocation file = ResourceLocation.parse(
            "fireflymc:story/quests/prologue/first_light.json");
        JsonObject override = resources.get(file).deepCopy().getAsJsonObject();
        override.add("title", JsonParser.parseString("{\"text\":\"Datapack override\"}"));
        resources.put(file, override);
        assertNotEquals(original, StoryRevision.sha256(StoryDefinitionLoader.prepare(resources)));
        assertEquals(original, StoryRevision.sha256(StoryDefinitionLoader.prepare(resources())));
    }

    private static void assertTranslations(JsonElement value, JsonObject translations) {
        if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(child -> assertTranslations(child, translations));
        } else if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has("translate")) {
                String key = object.get("translate").getAsString();
                assertTrue(translations.has(key), () -> "Missing story translation: " + key);
                assertFalse(translations.get(key).getAsString().isBlank());
            }
            object.entrySet().forEach(entry -> assertTranslations(entry.getValue(), translations));
        }
    }

    private static Map<ResourceLocation, JsonElement> resources() throws IOException {
        Map<ResourceLocation, JsonElement> resources = new LinkedHashMap<>();
        String chapter = "story/chapters/prologue.json";
        resources.put(ResourceLocation.parse("fireflymc:" + chapter), json("data/fireflymc/" + chapter));
        for (String node : NODES) {
            String path = "story/quests/prologue/" + node + ".json";
            resources.put(ResourceLocation.parse("fireflymc:" + path), json("data/fireflymc/" + path));
        }
        return resources;
    }

    private static JsonObject json(String path) throws IOException {
        var stream = StoryPrologueTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(stream, () -> "Missing bundled resource: " + path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static ResourceLocation quest(String name) {
        return ResourceLocation.parse("fireflymc:prologue/" + name);
    }
}
