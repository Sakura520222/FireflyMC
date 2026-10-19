package firefly520.fireflymc.story.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import firefly520.fireflymc.story.action.ActionTypes.SendMessageAction;
import firefly520.fireflymc.story.action.ActionTypes.UnlockQuestAction;
import firefly520.fireflymc.story.condition.ConditionTypes.FlagCondition;
import firefly520.fireflymc.story.condition.StoryCondition;
import firefly520.fireflymc.story.definition.ChapterDefinition;
import firefly520.fireflymc.story.definition.QuestDefinition;
import firefly520.fireflymc.story.definition.RequirementDefinition;
import firefly520.fireflymc.story.definition.StoryCodecs.StoryDefinitionException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StoryDefinitionLoaderTest {
    private static final Path FIXTURE_ROOT = Path.of("src/test/resources/story_fixture");

    @BeforeAll
    static void bootstrapMinecraftCodecs() {
        SharedConstants.tryDetectVersion();
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @Test
    void validFixtureLoadsDefinitionsGraphAndIndexDeclarations() {
        PreparedStoryData prepared = StoryDefinitionLoader.prepare(fixture("valid"));

        ResourceLocation chapterId = ResourceLocation.parse("fireflymc:prologue");
        ResourceLocation introId = ResourceLocation.parse("fireflymc:prologue/intro");
        ResourceLocation followupId = ResourceLocation.parse("fireflymc:prologue/followup");
        ChapterDefinition expectedChapter = new ChapterDefinition(
            chapterId,
            Component.literal("Prologue"),
            Component.literal("The first light"),
            0);
        QuestDefinition expectedIntro = new QuestDefinition(
            introId,
            chapterId,
            Component.literal("Introduction"),
            Component.literal("Follow the light"),
            List.of(),
            null,
            true,
            List.of(),
            List.of(
                new SendMessageAction(Component.literal("The forest remembers.")),
                new UnlockQuestAction(followupId)));
        QuestDefinition expectedFollowup = new QuestDefinition(
            followupId,
            chapterId,
            Component.literal("Memory"),
            Component.literal("Listen again"),
            List.of(),
            null,
            false,
            List.of(new RequirementDefinition(
                "heard_voice",
                1,
                new FlagCondition(
                    "prologue.heard_voice",
                    true,
                    StoryCondition.Scope.PLAYER))),
            List.of());

        assertEquals(
            Map.of(chapterId, expectedChapter),
            prepared.chapters());
        assertEquals(
            Map.of(
                introId, expectedIntro,
                followupId, expectedFollowup),
            prepared.quests());
        assertEquals(
            List.of(followupId),
            prepared.graph().outgoing(introId));
        assertEquals(
            Set.of(introId),
            prepared.graph().initialQuests());
        assertTrue(prepared.indexDeclarations().contains(
            new PreparedStoryData.IndexDeclaration(
                followupId,
                "requirements[0]",
                PreparedStoryData.IndexDeclaration.Kind.FLAG,
                "prologue.heard_voice",
                StoryCondition.Scope.PLAYER)));
    }

    @Test
    void emptyRequirementsAreValidAndInitialQuestIsRetained() {
        PreparedStoryData prepared = StoryDefinitionLoader.prepare(fixture("empty"));

        assertEquals(1, prepared.quests().size());
        assertEquals(
            Set.of(ResourceLocation.parse("fireflymc:prologue/intro")),
            prepared.graph().initialQuests());
        assertTrue(prepared.quests().values().iterator().next().requirements().isEmpty());
    }

    @Test
    void missingChapterReferenceRejectsWholeCandidate() {
        Map<ResourceLocation, JsonElement> resources = fixture("valid");
        JsonObject quest = resources
            .get(ResourceLocation.parse("fireflymc:story/quests/prologue/intro.json"))
            .getAsJsonObject();
        quest.addProperty("chapter", "fireflymc:missing");

        StoryDefinitionException exception = assertThrowsExactly(
            StoryDefinitionException.class, () -> StoryDefinitionLoader.prepare(resources));

        assertTrue(exception.getMessage().contains(
                "data/fireflymc/story/quests/prologue/intro.json: chapter"),
            () -> "missing chapter path in: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("fireflymc:missing"),
            () -> "missing chapter target in: " + exception.getMessage());
    }

    @Test
    void missingUnlockQuestTargetRejectsWholeCandidate() {
        Map<ResourceLocation, JsonElement> resources = fixture("valid");
        JsonObject quest = resources
            .get(ResourceLocation.parse("fireflymc:story/quests/prologue/intro.json"))
            .getAsJsonObject();
        quest.getAsJsonArray("on_complete")
            .get(1).getAsJsonObject()
            .addProperty("quest", "fireflymc:missing");

        StoryDefinitionException exception = assertThrowsExactly(
            StoryDefinitionException.class, () -> StoryDefinitionLoader.prepare(resources));

        assertTrue(exception.getMessage().contains(
                "data/fireflymc/story/quests/prologue/intro.json: on_complete[1].quest"),
            () -> "missing action field path in: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("fireflymc:missing"),
            () -> "missing quest target in: " + exception.getMessage());
    }

    @Test
    void staticUnlockCycleIsRejected() {
        StoryDefinitionException exception = assertThrowsExactly(
            StoryDefinitionException.class, () -> StoryDefinitionLoader.prepare(fixture("cycle")));

        assertTrue(exception.getMessage().contains("unlock cycle"),
            () -> "missing cycle diagnostic in: " + exception.getMessage());
        assertTrue(exception.getMessage().contains(
                "data/fireflymc/story/quests/prologue/a.json: on_complete[0].quest -> fireflymc:prologue/b"),
            () -> "missing first cycle edge in: " + exception.getMessage());
        assertTrue(exception.getMessage().contains(
                "data/fireflymc/story/quests/prologue/b.json: on_complete[0].quest -> fireflymc:prologue/a"),
            () -> "missing second cycle edge in: " + exception.getMessage());
    }

    @Test
    void duplicateRequirementIdsWithinOneQuestAreRejected() {
        StoryDefinitionException exception = assertThrowsExactly(
            StoryDefinitionException.class, () -> StoryDefinitionLoader.prepare(fixture("duplicate")));

        assertTrue(exception.getMessage().contains(
                "data/fireflymc/story/quests/prologue/intro.json: requirements[1].id"),
            () -> "missing duplicate field path in: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("memory"),
            () -> "missing duplicate requirement id in: " + exception.getMessage());
    }

    @Test
    void malformedJsonRejectsWholeCandidateWithPath() {
        StoryDefinitionException exception = assertThrowsExactly(
            StoryDefinitionException.class, () -> StoryDefinitionLoader.prepare(fixture("broken")));

        assertTrue(exception.getMessage().contains(
                "data/fireflymc/story/quests/prologue/intro.json"),
            () -> "missing broken file path in: " + exception.getMessage());
    }

    @Test
    void definitionsFromNonFireflymcNamespacesAreLoaded() {
        ResourceLocation chapterPath = ResourceLocation.parse("custom:story/chapters/prologue.json");
        ResourceLocation questPath = ResourceLocation.parse("custom:story/quests/prologue/intro.json");
        Map<ResourceLocation, JsonElement> resources = Map.of(
            chapterPath,
            JsonParser.parseString("""
                {"title":{"text":"Prologue"},"subtitle":{"text":"First"},"index":0}
                """),
            questPath,
            JsonParser.parseString("""
                {"chapter":"custom:prologue","title":{"text":"Introduction"},
                 "initial":true,"requirements":[],"on_complete":[]}
                """));

        PreparedStoryData prepared = StoryDefinitionLoader.prepare(resources);

        assertEquals(
            Set.of(ResourceLocation.parse("custom:prologue")),
            prepared.chapters().keySet());
        assertEquals(
            Set.of(ResourceLocation.parse("custom:prologue/intro")),
            prepared.quests().keySet());
    }

    @Test
    void resourcesOutsideStoryPathsAreIgnored() {
        Map<ResourceLocation, JsonElement> resources = fixture("valid");
        resources.put(ResourceLocation.parse("fireflymc:not_a_story_resource.json"),
            com.google.gson.JsonNull.INSTANCE);

        PreparedStoryData prepared = StoryDefinitionLoader.prepare(resources);

        assertEquals(2, prepared.quests().size());
    }

    @Test
    void duplicateDerivedChapterAndQuestIdsAreRejected() {
        IdentityHashMap<ResourceLocation, JsonElement> resources = new IdentityHashMap<>(fixture("valid"));
        resources.put(
            ResourceLocation.parse("fireflymc:story/chapters/prologue.json"),
            JsonParser.parseString("""
                {"title":{"text":"Duplicate"},"subtitle":{"text":"Chapter"},"index":1}
                """));
        resources.put(
            ResourceLocation.parse("fireflymc:story/quests/prologue/intro.json"),
            JsonParser.parseString("""
                {"chapter":"fireflymc:prologue","title":{"text":"Duplicate quest"},
                 "initial":true,"requirements":[],"on_complete":[]}
                """));

        StoryDefinitionException exception = assertThrowsExactly(
            StoryDefinitionException.class, () -> StoryDefinitionLoader.prepare(resources));

        assertTrue(exception.getMessage().contains(
                "data/fireflymc/story/chapters/prologue.json: duplicate chapter id fireflymc:prologue"),
            () -> "missing duplicate chapter diagnostic in: " + exception.getMessage());
        assertTrue(exception.getMessage().contains(
                "data/fireflymc/story/quests/prologue/intro.json: duplicate quest id fireflymc:prologue/intro"),
            () -> "missing duplicate quest diagnostic in: " + exception.getMessage());
    }

    @Test
    void directAndDeeplyNestedQuestPathsAreRejected() {
        ResourceLocation chapterPath = ResourceLocation.parse("fireflymc:story/chapters/prologue.json");
        JsonElement chapter = JsonParser.parseString("""
            {"title":{"text":"Prologue"},"subtitle":{"text":"First"},"index":0}
            """);
        JsonElement quest = JsonParser.parseString("""
            {"chapter":"fireflymc:prologue","title":{"text":"Introduction"},
             "initial":true,"requirements":[],"on_complete":[]}
            """);

        for (ResourceLocation questPath : List.of(
            ResourceLocation.parse("fireflymc:story/quests/intro.json"),
            ResourceLocation.parse("fireflymc:story/quests/prologue/intro/deep.json")
        )) {
            Map<ResourceLocation, JsonElement> resources = Map.of(
                chapterPath, chapter,
                questPath, quest);

            StoryDefinitionException exception = assertThrowsExactly(
                StoryDefinitionException.class, () -> StoryDefinitionLoader.prepare(resources));

            assertTrue(exception.getMessage().contains(
                    "data/fireflymc/" + questPath.getPath()
                        + ": path must match story/quests/<chapter>/<id>.json"),
                () -> "missing quest path shape for " + questPath + " in: " + exception.getMessage());
        }
    }

    @Test
    void invalidStateNameAtLoaderBoundaryIsRejectedWithPath() {
        ResourceLocation chapterPath = ResourceLocation.parse("fireflymc:story/chapters/prologue.json");
        ResourceLocation questPath = ResourceLocation.parse("fireflymc:story/quests/prologue/...json");
        Map<ResourceLocation, JsonElement> resources = Map.of(
            chapterPath,
            JsonParser.parseString("""
                {"title":{"text":"Prologue"},"subtitle":{"text":"First"},"index":0}
                """),
            questPath,
            JsonParser.parseString("""
                {"chapter":"fireflymc:prologue","title":{"text":"Introduction"},
                 "initial":true,"requirements":[],"on_complete":[]}
                """));

        StoryDefinitionException exception = assertThrowsExactly(
            StoryDefinitionException.class, () -> StoryDefinitionLoader.prepare(resources));

        assertTrue(exception.getMessage().contains(
                "data/fireflymc/story/quests/prologue/...json: definition path"),
            () -> "missing invalid-state resource path in: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("must match [a-z0-9_./-]+"),
            () -> "missing invalid-state rule in: " + exception.getMessage());
    }

    @Test
    void crossQuestSameScopeFlagAndVarCollisionIsRejected() {
        ResourceLocation chapterPath = ResourceLocation.parse("fireflymc:story/chapters/prologue.json");
        ResourceLocation initialPath = ResourceLocation.parse("fireflymc:story/quests/prologue/intro.json");
        ResourceLocation followupPath = ResourceLocation.parse("fireflymc:story/quests/prologue/followup.json");
        Map<ResourceLocation, JsonElement> resources = Map.of(
            chapterPath,
            JsonParser.parseString("""
                {"title":{"text":"Prologue"},"subtitle":{"text":"First"},"index":0}
                """),
            initialPath,
            JsonParser.parseString("""
                {"chapter":"fireflymc:prologue","title":{"text":"Introduction"},
                 "initial":true,"requirements":[],
                 "on_complete":[
                   {"type":"fireflymc:set_var","var":"shared.state","op":"set","amount":1},
                   {"type":"fireflymc:unlock_quest","quest":"fireflymc:prologue/followup"}
                 ]}
                """),
            followupPath,
            JsonParser.parseString("""
                {"chapter":"fireflymc:prologue","title":{"text":"Memory"},
                 "requirements":[
                   {"id":"gate","type":"fireflymc:flag","flag":"shared.state",
                    "value":true,"scope":"player"}
                 ],"on_complete":[]}
                """));

        StoryDefinitionException exception = assertThrowsExactly(
            StoryDefinitionException.class, () -> StoryDefinitionLoader.prepare(resources));

        assertTrue(exception.getMessage().contains("conflicts with"),
            () -> "missing cross-quest collision diagnostic in: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("shared.state in scope player"),
            () -> "missing cross-quest collision target in: " + exception.getMessage());
    }

    @Test
    void indexDeclarationsPreserveFlagAndVarScope() {
        Map<ResourceLocation, JsonElement> resources = fixture("valid");
        resources.put(ResourceLocation.parse("fireflymc:story/quests/prologue/world.json"),
            JsonParser.parseString("""
                {"chapter":"fireflymc:prologue","title":{"text":"World"},
                 "requirements":[
                   {"id":"world_gate","type":"fireflymc:flag","flag":"prologue.heard_voice",
                    "value":true,"scope":"world"}
                 ],"on_complete":[]}
                """));
        resources.get(ResourceLocation.parse("fireflymc:story/quests/prologue/intro.json"))
            .getAsJsonObject()
            .getAsJsonArray("on_complete")
            .add(JsonParser.parseString("""
                {"type":"fireflymc:set_var","var":"prologue.counter","op":"set","amount":1}
                """));

        PreparedStoryData prepared = StoryDefinitionLoader.prepare(resources);

        assertTrue(prepared.indexDeclarations().containsAll(Set.of(
            new PreparedStoryData.IndexDeclaration(
                ResourceLocation.parse("fireflymc:prologue/followup"),
                "requirements[0]",
                PreparedStoryData.IndexDeclaration.Kind.FLAG,
                "prologue.heard_voice",
                StoryCondition.Scope.PLAYER),
            new PreparedStoryData.IndexDeclaration(
                ResourceLocation.parse("fireflymc:prologue/world"),
                "requirements[0]",
                PreparedStoryData.IndexDeclaration.Kind.FLAG,
                "prologue.heard_voice",
                StoryCondition.Scope.WORLD),
            new PreparedStoryData.IndexDeclaration(
                ResourceLocation.parse("fireflymc:prologue/intro"),
                "on_complete[2]",
                PreparedStoryData.IndexDeclaration.Kind.VAR,
                "prologue.counter",
                StoryCondition.Scope.PLAYER))));
    }

    @Test
    void missingInitialQuestIsRejected() {
        Map<ResourceLocation, JsonElement> resources = fixture("valid");
        resources.get(ResourceLocation.parse("fireflymc:story/quests/prologue/intro.json"))
            .getAsJsonObject()
            .addProperty("initial", false);

        StoryDefinitionException exception = assertThrowsExactly(
            StoryDefinitionException.class, () -> StoryDefinitionLoader.prepare(resources));

        assertTrue(exception.getMessage().contains("at least one initial quest is required"),
            () -> "missing-initial diagnostic in: " + exception.getMessage());
    }

    @Test
    void nonemptyInitialQuestRequirementsAreRejected() {
        Map<ResourceLocation, JsonElement> resources = fixture("valid");
        resources.get(ResourceLocation.parse("fireflymc:story/quests/prologue/intro.json"))
            .getAsJsonObject()
            .getAsJsonArray("requirements")
            .add(JsonParser.parseString("""
                {"id":"gate","type":"fireflymc:flag","flag":"prologue.gate",
                 "value":true,"scope":"player"}
                """));

        StoryDefinitionException exception = assertThrowsExactly(
            StoryDefinitionException.class, () -> StoryDefinitionLoader.prepare(resources));

        assertTrue(exception.getMessage().contains(
                "data/fireflymc/story/quests/prologue/intro.json: initial quest requirements must be empty"),
            () -> "missing nonempty-initial diagnostic in: " + exception.getMessage());
    }

    @Test
    void exposedCollectionsAndGraphAreImmutable() {
        PreparedStoryData prepared = StoryDefinitionLoader.prepare(fixture("valid"));
        ResourceLocation quest = ResourceLocation.parse("fireflymc:prologue/intro");
        ResourceLocation target = ResourceLocation.parse("fireflymc:prologue/followup");

        assertThrowsExactly(UnsupportedOperationException.class,
            () -> prepared.chapters().put(quest, null));
        assertThrowsExactly(UnsupportedOperationException.class,
            () -> prepared.quests().put(quest, null));
        assertThrowsExactly(UnsupportedOperationException.class,
            () -> prepared.indexDeclarations().add(null));
        assertThrowsExactly(UnsupportedOperationException.class,
            () -> prepared.canonicalDefinitions().add(null));
        assertThrowsExactly(UnsupportedOperationException.class,
            () -> prepared.graph().unlockEdges().put(quest, List.of()));
        assertThrowsExactly(UnsupportedOperationException.class,
            () -> prepared.graph().initialQuests().add(quest));
        assertThrowsExactly(UnsupportedOperationException.class,
            () -> prepared.graph().outgoing(quest).add(target));
    }

    @Test
    void canonicalizationFailureRetainsSourceResourcePath() {
        ResourceLocation chapterPath = ResourceLocation.parse("fireflymc:story/chapters/prologue.json");
        ResourceLocation questPath = ResourceLocation.parse("fireflymc:story/quests/prologue/intro.json");
        JsonObject chapter = JsonParser.parseString("""
            {"title":{"text":"Prologue"},"subtitle":{"text":"First"},"index":0}
            """).getAsJsonObject();
        chapter.add("unused", new com.google.gson.JsonPrimitive(Double.NaN));
        Map<ResourceLocation, JsonElement> resources = Map.of(
            chapterPath, chapter,
            questPath,
            JsonParser.parseString("""
                {"chapter":"fireflymc:prologue","title":{"text":"Introduction"},
                 "initial":true,"requirements":[],"on_complete":[]}
                """));

        StoryDefinitionException exception = assertThrowsExactly(
            StoryDefinitionException.class, () -> StoryDefinitionLoader.prepare(resources));

        assertTrue(exception.getMessage().contains(
                "data/fireflymc/story/chapters/prologue.json: canonicalization failed"),
            () -> "missing canonicalization resource path in: " + exception.getMessage());
    }

    private static Map<ResourceLocation, JsonElement> fixture(String name) {
        Path root = FIXTURE_ROOT.resolve(name);
        try (Stream<Path> paths = Files.walk(root)) {
            Map<ResourceLocation, JsonElement> result = new HashMap<>();
            paths.filter(Files::isRegularFile).forEach(path -> {
                String relative = root.relativize(path).toString().replace('\\', '/');
                String[] segments = relative.split("/");
                if (segments.length < 4 || !"data".equals(segments[0])) {
                    throw new IllegalArgumentException("Unexpected fixture resource: " + relative);
                }
                String namespace = segments[1];
                String resourcePath = relative.substring(("data/" + namespace + "/").length());
                JsonElement json;
                try {
                    json = JsonParser.parseString(Files.readString(path));
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
                result.put(
                    ResourceLocation.parse(namespace + ":" + resourcePath),
                    json);
            });
            return result;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (UncheckedIOException exception) {
            throw exception;
        }
    }
}
