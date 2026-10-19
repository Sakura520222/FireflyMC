package firefly520.fireflymc.story.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import firefly520.fireflymc.story.StoryEngine;
import firefly520.fireflymc.story.StoryEngineTestAccess;
import firefly520.fireflymc.story.condition.StoryCondition;
import firefly520.fireflymc.story.definition.StoryCodecs;
import firefly520.fireflymc.story.engine.InterestIndex;
import firefly520.fireflymc.story.engine.StoryEventType;
import firefly520.fireflymc.story.engine.StoryRuntimeSnapshot;
import firefly520.fireflymc.story.loader.StoryRevision;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.InactiveProfiler;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StoryReloadTest {
    private static final ResourceLocation CHAPTER_PATH =
        ResourceLocation.parse("fireflymc:story/chapters/prologue.json");
    private static final ResourceLocation INITIAL_PATH =
        ResourceLocation.parse("fireflymc:story/quests/prologue/intro.json");
    private static final ResourceLocation QUEST_PATH =
        ResourceLocation.parse("fireflymc:story/quests/prologue/hunt.json");
    private static final ResourceLocation QUEST_ID =
        ResourceLocation.parse("fireflymc:prologue/hunt");
    private static final String QUEST_FILE =
        "data/fireflymc/story/quests/prologue/hunt.json";

    @BeforeAll
    static void bootstrapMinecraftCodecs() {
        SharedConstants.tryDetectVersion();
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @Test
    void missingAdvancementRejectsCandidateWithContextAndPreservesOldSnapshot() {
        StoryEngine engine = StoryEngineTestAccess.instance();
        StoryEngineTestAccess.consumePendingReconcile(engine);
        StoryRuntimeSnapshot before = engine.snapshot();
        PreparedStoryData candidate = prepared("""
            {"id":"award","type":"fireflymc:advancement","id":"fireflymc:absent"}
            """);
        FakeResolver resolver = FakeResolver.advancement(Set.of(id("fireflymc:present")));
        List<String> warnings = new ArrayList<>();

        StoryCodecs.StoryDefinitionException exception = assertThrowsExactly(
            StoryCodecs.StoryDefinitionException.class,
            () -> StoryValidator.validateResources(candidate, resolver));
        assertTrue(exception.getMessage().contains(QUEST_FILE), exception.getMessage());
        assertTrue(exception.getMessage().contains("requirements[0]"), exception.getMessage());
        assertTrue(exception.getMessage().contains("missing advancement fireflymc:absent"),
            exception.getMessage());

        assertFalse(new StoryReloadListener(resolver, warnings::add).apply(candidate));
        assertSame(before, engine.snapshot());
        assertFalse(StoryEngineTestAccess.hasPendingReconcile(engine));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void successfulPurePrepareNeverConsultsTheReferenceResolver() {
        PreparedStoryData prepared = new StoryReloadListener(
            FakeResolver.throwing(), ignored -> {})
            .prepare(new InMemoryResourceManager(Map.of(
                CHAPTER_PATH, """
                    {"title":{"text":"Prologue"},"subtitle":{"text":"First"},"index":0}
                    """,
                INITIAL_PATH, """
                    {"chapter":"fireflymc:prologue","title":{"text":"Introduction"},
                     "initial":true,"requirements":[],"on_complete":[]}
                    """)),
                InactiveProfiler.INSTANCE);

        assertEquals(
            Set.of(ResourceLocation.parse("fireflymc:prologue/intro")),
            prepared.graph().initialQuests());
    }

    @Test
    void entityTagReloadReplacesZombieWithSkeletonAndKeepsRevisionConsistent() {
        StoryEngine engine = StoryEngineTestAccess.instance();
        StoryEngineTestAccess.consumePendingReconcile(engine);
        PreparedStoryData zombie = preparedTag("story:undead", "Zombie hunt");
        PreparedStoryData skeleton = preparedTag("story:undead", "Skeleton hunt");

        assertTrue(new StoryReloadListener(
            FakeResolver.tag("story:undead", Set.of(id("minecraft:zombie"))), ignored -> {})
            .apply(zombie));
        assertEquals(Set.of(QUEST_ID), engine.snapshot().interestIndex()
            .candidates(StoryEventType.ENTITY_KILL, id("minecraft:zombie")));

        assertTrue(new StoryReloadListener(
            FakeResolver.tag("story:undead", Set.of(id("minecraft:skeleton"))), ignored -> {})
            .apply(skeleton));

        assertEquals(Set.of(QUEST_ID), engine.snapshot().interestIndex()
            .candidates(StoryEventType.ENTITY_KILL, id("minecraft:skeleton")));
        assertEquals(Set.of(), engine.snapshot().interestIndex()
            .candidates(StoryEventType.ENTITY_KILL, id("minecraft:zombie")));
        assertEquals(StoryRevision.sha256(skeleton), engine.snapshot().revision());
        assertEquals("Skeleton hunt", engine.snapshot().quests().get(QUEST_ID).title().getString());
        assertEquals(skeleton.graph(), engine.snapshot().graph());
        assertEquals(simplifiedChapters(skeleton), simplifiedChaptersFromSnapshot(engine.snapshot()));
        assertTrue(StoryEngineTestAccess.consumePendingReconcile(engine));
        assertFalse(StoryEngineTestAccess.hasPendingReconcile(engine));
    }

    @Test
    void absentTagRejectsCandidateWithResourcePathAndField() {
        StoryEngine engine = StoryEngineTestAccess.instance();
        StoryEngineTestAccess.consumePendingReconcile(engine);
        StoryRuntimeSnapshot before = engine.snapshot();
        PreparedStoryData candidate = preparedTag("story:absent", "Absent tag");
        FakeResolver resolver = FakeResolver.tag("story:unrelated", Set.of());
        List<String> warnings = new ArrayList<>();

        StoryCodecs.StoryDefinitionException exception = assertThrowsExactly(
            StoryCodecs.StoryDefinitionException.class,
            () -> StoryValidator.validateResources(candidate, resolver));
        assertTrue(exception.getMessage().contains(QUEST_FILE), exception.getMessage());
        assertTrue(exception.getMessage().contains("requirements[0]"), exception.getMessage());
        assertTrue(exception.getMessage().contains("missing tag story:absent"),
            exception.getMessage());

        assertFalse(new StoryReloadListener(resolver, warnings::add).apply(candidate));
        assertSame(before, engine.snapshot());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void existingEmptyTagWarnsWithResourcePathAndPublishesNoCandidates() {
        StoryEngine engine = StoryEngineTestAccess.instance();
        StoryEngineTestAccess.consumePendingReconcile(engine);
        List<String> warnings = new ArrayList<>();

        boolean applied = new StoryReloadListener(
            FakeResolver.tag("story:empty", Set.of()), warnings::add)
            .apply(preparedTag("story:empty", "Empty tag"));

        assertTrue(applied);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains(QUEST_FILE), warnings.get(0));
        assertTrue(warnings.get(0).contains("requirements[0]"), warnings.get(0));
        assertTrue(warnings.get(0).contains("tag story:empty is empty"), warnings.get(0));
        assertEquals(Set.of(), engine.snapshot().interestIndex()
            .candidates(StoryEventType.ENTITY_KILL, id("minecraft:any")));
        assertTrue(StoryEngineTestAccess.consumePendingReconcile(engine));
    }

    @Test
    void successfulAdvancementIsPublishedInTheInterestIndex() {
        StoryEngine engine = StoryEngineTestAccess.instance();
        StoryEngineTestAccess.consumePendingReconcile(engine);
        ResourceLocation advancement = id("fireflymc:story/root");

        assertTrue(new StoryReloadListener(
            FakeResolver.advancement(Set.of(advancement)), ignored -> {})
            .apply(prepared("""
                {"id":"award","type":"fireflymc:advancement","id":"fireflymc:story/root"}
                """)));

        assertEquals(Set.of(QUEST_ID), engine.snapshot().interestIndex()
            .candidates(StoryEventType.ADVANCEMENT_EARNED, advancement));
        assertTrue(StoryEngineTestAccess.consumePendingReconcile(engine));
    }

    @Test
    void flagAndVarSignalsPreserveScopeAndClassifyRequirementsAndActions() {
        StoryEngine engine = StoryEngineTestAccess.instance();
        StoryEngineTestAccess.consumePendingReconcile(engine);
        PreparedStoryData prepared = preparedStateScopes();

        assertTrue(new StoryReloadListener(FakeResolver.empty(), ignored -> {}).apply(prepared));

        InterestIndex index = engine.snapshot().interestIndex();
        ResourceLocation sharedFlag = id("minecraft:shared.choice");
        ResourceLocation counter = id("minecraft:shared.counter");
        ResourceLocation initial = ResourceLocation.parse("fireflymc:prologue/intro");
        ResourceLocation player = ResourceLocation.parse("fireflymc:prologue/player");
        ResourceLocation world = ResourceLocation.parse("fireflymc:prologue/world");

        assertEquals(Set.of(player), index.candidates(
            StoryEventType.FLAG_CHANGED, StoryCondition.Scope.PLAYER, sharedFlag));
        assertEquals(Set.of(initial, world), index.candidates(
            StoryEventType.FLAG_CHANGED, StoryCondition.Scope.WORLD, sharedFlag));
        assertEquals(Set.of(player), index.candidates(
            StoryEventType.CUSTOM_EVENT, counter));
        assertEquals(Set.of(initial, player), index.candidates(
            StoryEventType.VAR_CHANGED, StoryCondition.Scope.PLAYER, counter));
        assertThrowsExactly(IllegalArgumentException.class,
            () -> index.candidates(StoryEventType.FLAG_CHANGED, sharedFlag));
        assertThrowsExactly(IllegalArgumentException.class,
            () -> index.candidates(StoryEventType.ENTITY_KILL, StoryCondition.Scope.PLAYER,
                id("minecraft:zombie")));
    }

    @Test
    void failedNewGenerationCannotMixItsTagMembersIntoTheOldSnapshot() {
        StoryEngine engine = StoryEngineTestAccess.instance();
        StoryEngineTestAccess.consumePendingReconcile(engine);
        PreparedStoryData oldGeneration = preparedTag("story:undead", "Zombie generation");
        PreparedStoryData newGeneration = preparedWithRequirement("""
            {"id":"hunt","type":"fireflymc:kill","entity_tag":"story:undead","count":1},
            {"id":"award","type":"fireflymc:advancement","id":"fireflymc:absent"}
            """);
        ResourceLocation zombie = id("minecraft:zombie");
        ResourceLocation skeleton = id("minecraft:skeleton");
        FakeResolver resolver = FakeResolver.tag("story:undead", Set.of(zombie));

        assertTrue(new StoryReloadListener(resolver, ignored -> {}).apply(oldGeneration));
        StoryRuntimeSnapshot oldSnapshot = engine.snapshot();
        assertTrue(StoryEngineTestAccess.consumePendingReconcile(engine));
        assertFalse(StoryEngineTestAccess.hasPendingReconcile(engine));
        resolver.tags
            .get(PreparedStoryData.IndexDeclaration.Kind.ENTITY_TAG)
            .put(id("story:undead"), Set.of(skeleton));

        assertFalse(new StoryReloadListener(resolver, ignored -> {}).apply(newGeneration));

        assertSame(oldSnapshot, engine.snapshot());
        assertEquals(StoryRevision.sha256(oldGeneration), oldSnapshot.revision());
        assertEquals(Set.of(QUEST_ID), oldSnapshot.interestIndex()
            .candidates(StoryEventType.ENTITY_KILL, zombie));
        assertEquals(Set.of(), oldSnapshot.interestIndex()
            .candidates(StoryEventType.ENTITY_KILL, skeleton));
        assertFalse(StoryEngineTestAccess.hasPendingReconcile(engine));
    }

    @Test
    void runtimeCollectionsAndIndexAreImmutable() {
        StoryEngine engine = StoryEngineTestAccess.instance();
        StoryEngineTestAccess.consumePendingReconcile(engine);
        PreparedStoryData prepared = preparedTag("story:undead", "Immutable");

        assertTrue(new StoryReloadListener(
            FakeResolver.tag("story:undead", Set.of(id("minecraft:zombie"))), ignored -> {})
            .apply(prepared));

        StoryRuntimeSnapshot snapshot = engine.snapshot();
        InterestIndex index = snapshot.interestIndex();
        assertThrows(() -> snapshot.chapters().put(id("fireflymc:other"), null));
        assertThrows(() -> snapshot.quests().put(id("fireflymc:other"), null));
        assertThrows(() -> snapshot.graph().unlockEdges().put(id("fireflymc:other"), List.of()));
        assertThrows(() -> index.keys(StoryEventType.ENTITY_KILL).add(id("minecraft:creeper")));
        assertEquals(Set.of(), index.candidates(StoryEventType.ENTITY_KILL, id("minecraft:creeper")));
    }

    @Test
    void resolverFailuresAreWrappedWithDeclarationContextAndOriginalCause() {
        IllegalStateException cause = new IllegalStateException("registry offline");
        FakeResolver resolver = new FakeResolver(Map.of(), Map.of(), Set.of()) {
            @Override
            public boolean exists(
                PreparedStoryData.IndexDeclaration.Kind kind, ResourceLocation target
            ) {
                throw cause;
            }
        };
        PreparedStoryData candidate = prepared("""
            {"id":"memory","type":"fireflymc:pickup","item":"minecraft:echo_shard","count":1}
            """);

        StoryCodecs.StoryDefinitionException exception = assertThrowsExactly(
            StoryCodecs.StoryDefinitionException.class,
            () -> StoryValidator.validateResources(candidate, resolver));

        assertTrue(exception.getMessage().contains(QUEST_FILE), exception.getMessage());
        assertTrue(exception.getMessage().contains("requirements[0]"), exception.getMessage());
        assertTrue(exception.getMessage().contains("reference resolver failed"),
            exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    void pendingReconcileIsConsumedExactlyOnce() {
        StoryEngine engine = StoryEngineTestAccess.instance();
        StoryEngineTestAccess.consumePendingReconcile(engine);

        StoryEngineTestAccess.markAllPlayersForReconcile(engine);
        StoryEngineTestAccess.markAllPlayersForReconcile(engine);

        assertTrue(StoryEngineTestAccess.hasPendingReconcile(engine));
        assertTrue(StoryEngineTestAccess.consumePendingReconcile(engine));
        assertFalse(StoryEngineTestAccess.hasPendingReconcile(engine));
        assertFalse(StoryEngineTestAccess.consumePendingReconcile(engine));
    }

    private static void assertThrows(Runnable mutation) {
        try {
            mutation.run();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("Expected an immutable collection");
    }

    private static PreparedStoryData prepared(String requirementJson) {
        return preparedWithRequirement(requirementJson);
    }

    private static List<String> simplifiedChapters(PreparedStoryData prepared) {
        return prepared.chapters().values().stream()
            .map(chapter -> chapter.id() + ":" + chapter.title().getString())
            .toList();
    }

    private static List<String> simplifiedChaptersFromSnapshot(StoryRuntimeSnapshot snapshot) {
        return snapshot.chapters().values().stream()
            .map(chapter -> chapter.id() + ":" + chapter.title().getString())
            .toList();
    }

    private static PreparedStoryData preparedTag(String tag, String title) {
        return preparedWithRequirement("""
            {"id":"hunt","type":"fireflymc:kill","entity_tag":"%s","count":1}
            """.formatted(tag), title);
    }

    private static PreparedStoryData preparedWithRequirement(String requirementJson) {
        return preparedWithRequirement(requirementJson, "Hunt");
    }

    private static PreparedStoryData preparedWithRequirement(String requirementJson, String title) {
        Map<ResourceLocation, JsonElement> resources = Map.of(
            CHAPTER_PATH, JsonParser.parseString("""
                {"title":{"text":"Prologue"},"subtitle":{"text":"First"},"index":0}
                """),
            INITIAL_PATH, JsonParser.parseString("""
                {"chapter":"fireflymc:prologue","title":{"text":"Introduction"},
                 "initial":true,"requirements":[],"on_complete":[]}
                """),
            QUEST_PATH, JsonParser.parseString("""
                {"chapter":"fireflymc:prologue","title":{"text":"%s"},
                 "requirements":[%s],"on_complete":[]}
                """.formatted(title, requirementJson)));
        return StoryDefinitionLoader.prepare(resources);
    }

    private static PreparedStoryData preparedStateScopes() {
        ResourceLocation playerPath =
            ResourceLocation.parse("fireflymc:story/quests/prologue/player.json");
        ResourceLocation worldPath =
            ResourceLocation.parse("fireflymc:story/quests/prologue/world.json");
        Map<ResourceLocation, JsonElement> resources = Map.of(
            CHAPTER_PATH, JsonParser.parseString("""
                {"title":{"text":"Prologue"},"subtitle":{"text":"First"},"index":0}
                """),
            INITIAL_PATH, JsonParser.parseString("""
                {"chapter":"fireflymc:prologue","title":{"text":"Introduction"},
                 "initial":true,"requirements":[],"on_complete":[
                   {"type":"fireflymc:set_flag","flag":"shared.choice","value":true,"scope":"world"},
                   {"type":"fireflymc:set_var","var":"shared.counter","op":"set","amount":1}
                 ]}
                """),
            playerPath, JsonParser.parseString("""
                {"chapter":"fireflymc:prologue","title":{"text":"Player"},
                 "requirements":[
                   {"id":"player_flag","type":"fireflymc:flag","flag":"shared.choice",
                    "value":true,"scope":"player"},
                   {"id":"counter","type":"fireflymc:custom_event",
                    "event":"shared.counter","count":1}
                 ],"on_complete":[
                   {"type":"fireflymc:set_var","var":"shared.counter","op":"add","amount":1}
                 ]}
                """),
            worldPath, JsonParser.parseString("""
                {"chapter":"fireflymc:prologue","title":{"text":"World"},
                 "requirements":[
                   {"id":"world_flag","type":"fireflymc:flag","flag":"shared.choice",
                    "value":true,"scope":"world"}
                 ],"on_complete":[]}
                """));
        return StoryDefinitionLoader.prepare(resources);
    }

    private static ResourceLocation id(String value) {
        return ResourceLocation.parse(value);
    }

    private record InMemoryResourceManager(Map<ResourceLocation, String> resources)
        implements ResourceManager {
        @Override
        public Set<String> getNamespaces() {
            return Set.of("fireflymc");
        }

        @Override
        public List<Resource> getResourceStack(ResourceLocation location) {
            return resources.containsKey(location) ? List.of(resource(location)) : List.of();
        }

        @Override
        public Map<ResourceLocation, Resource> listResources(
            String prefix,
            java.util.function.Predicate<ResourceLocation> filter
        ) {
            Map<ResourceLocation, Resource> result = new HashMap<>();
            resources.forEach((location, content) -> {
                if (location.getPath().startsWith(prefix + "/") && filter.test(location)) {
                    result.put(location, resource(location));
                }
            });
            return result;
        }

        @Override
        public Map<ResourceLocation, List<Resource>> listResourceStacks(
            String prefix,
            java.util.function.Predicate<ResourceLocation> filter
        ) {
            Map<ResourceLocation, List<Resource>> result = new HashMap<>();
            listResources(prefix, filter).forEach((location, resource) ->
                result.put(location, List.of(resource)));
            return result;
        }

        @Override
        public Stream<PackResources> listPacks() {
            return Stream.empty();
        }

        @Override
        public Optional<Resource> getResource(ResourceLocation location) {
            return Optional.ofNullable(resources.get(location)).map(ignored -> resource(location));
        }

        private Resource resource(ResourceLocation location) {
            return new Resource(null, () -> new ByteArrayInputStream(
                resources.get(location).getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static class FakeResolver implements StoryReloadListener.ReferenceResolver {
        private final Map<PreparedStoryData.IndexDeclaration.Kind, Set<ResourceLocation>> concrete;
        private final Map<PreparedStoryData.IndexDeclaration.Kind,
            Map<ResourceLocation, Set<ResourceLocation>>> tags;
        private final Set<ResourceLocation> advancements;

        private FakeResolver(
            Map<PreparedStoryData.IndexDeclaration.Kind, Set<ResourceLocation>> concrete,
            Map<PreparedStoryData.IndexDeclaration.Kind,
                Map<ResourceLocation, Set<ResourceLocation>>> tags,
            Set<ResourceLocation> advancements
        ) {
            this.concrete = concrete;
            this.tags = tags;
            this.advancements = advancements;
        }

        static FakeResolver empty() {
            return new FakeResolver(Map.of(), Map.of(), Set.of());
        }

        static FakeResolver advancement(Set<ResourceLocation> advancements) {
            return new FakeResolver(Map.of(), Map.of(), advancements);
        }

        static FakeResolver throwing() {
            return new FakeResolver(Map.of(), Map.of(), Set.of()) {
                @Override
                public boolean exists(
                    PreparedStoryData.IndexDeclaration.Kind kind, ResourceLocation target
                ) {
                    throw new AssertionError("prepare must not resolve live registries");
                }

                @Override
                public boolean tagExists(
                    PreparedStoryData.IndexDeclaration.Kind kind, ResourceLocation target
                ) {
                    throw new AssertionError("prepare must not inspect live tag existence");
                }

                @Override
                public Set<ResourceLocation> tagMembers(
                    PreparedStoryData.IndexDeclaration.Kind kind, ResourceLocation target
                ) {
                    throw new AssertionError("prepare must not expand live tags");
                }

                @Override
                public boolean advancementExists(ResourceLocation target) {
                    throw new AssertionError("prepare must not resolve advancements");
                }
            };
        }

        static FakeResolver tag(String tag, Set<ResourceLocation> members) {
            Map<ResourceLocation, Set<ResourceLocation>> entityTags = new HashMap<>();
            entityTags.put(ResourceLocation.parse(tag), members);
            return new FakeResolver(
                Map.of(),
                Map.of(PreparedStoryData.IndexDeclaration.Kind.ENTITY_TAG, entityTags),
                Set.of());
        }

        @Override
        public boolean exists(
            PreparedStoryData.IndexDeclaration.Kind kind, ResourceLocation target
        ) {
            return concrete.getOrDefault(kind, Set.of()).contains(target);
        }

        @Override
        public boolean tagExists(
            PreparedStoryData.IndexDeclaration.Kind kind, ResourceLocation target
        ) {
            return tags.getOrDefault(kind, Map.of()).containsKey(target);
        }

        @Override
        public Set<ResourceLocation> tagMembers(
            PreparedStoryData.IndexDeclaration.Kind kind, ResourceLocation target
        ) {
            return tags.getOrDefault(kind, Map.of()).getOrDefault(target, Set.of());
        }

        @Override
        public boolean advancementExists(ResourceLocation target) {
            return advancements.contains(target);
        }
    }
}
