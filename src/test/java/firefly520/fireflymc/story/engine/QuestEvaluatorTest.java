package firefly520.fireflymc.story.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import firefly520.fireflymc.story.condition.ConditionPersistence;
import firefly520.fireflymc.story.condition.ConditionTypes;
import firefly520.fireflymc.story.condition.StoryCondition;
import firefly520.fireflymc.story.definition.QuestDefinition;
import firefly520.fireflymc.story.definition.RequirementDefinition;
import firefly520.fireflymc.story.loader.PreparedStoryData;
import firefly520.fireflymc.story.progress.RequirementProgressKey;
import firefly520.fireflymc.story.progress.StoryProgress;
import firefly520.fireflymc.story.progress.WorldStoryState;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class QuestEvaluatorTest {
    private static final ResourceLocation ITEM = ResourceLocation.parse("minecraft:echo_shard");
    private static final ResourceLocation ENTITY = ResourceLocation.parse("minecraft:zombie");
    private static final ResourceLocation SKELETON = ResourceLocation.parse("minecraft:skeleton");
    private static final ResourceLocation ZOMBIE_TAG = ResourceLocation.parse("story:zombies");
    private static final ResourceLocation SKELETON_TAG = ResourceLocation.parse("story:skeletons");
    private static final ResourceLocation CUSTOM = ResourceLocation.parse("story:custom");
    private static final ResourceLocation DIMENSION =
        ResourceLocation.parse("minecraft:overworld");
    private static final ResourceLocation BIOME = ResourceLocation.parse("minecraft:plains");
    private static final ResourceLocation STRUCTURE =
        ResourceLocation.parse("minecraft:village");
    private static final ResourceLocation ADVANCEMENT =
        ResourceLocation.parse("minecraft:story/mine_stone");
    private static final ResourceLocation FLAG = ResourceLocation.parse("story.ready");
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final QuestEvaluator evaluator = new QuestEvaluator();
    private final WorldStoryState world = new WorldStoryState();

    @Test
    void storyEventIsImmutableValidatedAndScopedForStateSignals() {
        StoryEvent event = new StoryEvent(
            StoryEventType.FLAG_CHANGED,
            FLAG,
            1,
            Map.of("scope", "world", "ignored", "value"));

        assertEquals(StoryEventType.FLAG_CHANGED, event.type());
        assertEquals(FLAG, event.key());
        assertEquals(1, event.amount());
        assertEquals("world", event.context().get("scope"));
        assertThrowsExactly(UnsupportedOperationException.class,
            () -> event.context().put("mutation", "forbidden"));
        assertEquals(Map.of("scope", "world", "ignored", "value"), event.context());
        assertEquals(StoryCondition.Scope.WORLD, event.scope().orElseThrow());

        assertThrowsExactly(NullPointerException.class,
            () -> new StoryEvent(null, FLAG, 1, Map.of()));
        assertThrowsExactly(NullPointerException.class,
            () -> new StoryEvent(StoryEventType.ITEM_PICKUP, null, 1, Map.of()));
        assertThrowsExactly(IllegalArgumentException.class,
            () -> new StoryEvent(StoryEventType.ITEM_PICKUP, ITEM, 0, Map.of()));
        assertThrowsExactly(IllegalArgumentException.class,
            () -> new StoryEvent(StoryEventType.ITEM_PICKUP, ITEM, 1, Map.of("scope", "player")));
        assertThrowsExactly(IllegalArgumentException.class,
            () -> new StoryEvent(StoryEventType.FLAG_CHANGED, FLAG, 1, Map.of()));
        assertThrowsExactly(IllegalArgumentException.class,
            () -> new StoryEvent(
                StoryEventType.VAR_CHANGED, FLAG, 1, Map.of("scope", "world")));
    }

    @Test
    void positiveEventAdvancesOnlyMatchingActiveCountersAndReturnsActiveCandidates() {
        ResourceLocation questId = ResourceLocation.parse("fireflymc:test");
        QuestDefinition quest = quest(
            questId,
            requirement("pickup", new ConditionTypes.PickupCondition(ITEM, 3)),
            requirement("kill", new ConditionTypes.KillCondition(ENTITY, null, 2)),
            requirement("custom", new ConditionTypes.CustomEventCondition(CUSTOM, 2)));
        StoryProgress progress = activeProgress(questId);
        RequirementProgressKey pickup = key(questId, "pickup");
        RequirementProgressKey kill = key(questId, "kill");
        RequirementProgressKey custom = key(questId, "custom");
        StoryRuntimeSnapshot snapshot = snapshot(quest);

        assertEquals(Set.of(questId), evaluator.onEvent(
            progress,
            world,
            snapshot,
            event(StoryEventType.ITEM_PICKUP, ITEM, 2),
            QuestEvaluator.CurrentState.empty()));
        assertEquals(2, progress.counter(pickup));
        assertEquals(0, progress.counter(kill));
        assertEquals(0, progress.counter(custom));

        assertEquals(Set.of(questId), evaluator.onEvent(
            progress,
            world,
            snapshot,
            event(StoryEventType.ENTITY_KILL, ENTITY, 1),
            QuestEvaluator.CurrentState.empty()));
        assertEquals(Set.of(questId), evaluator.onEvent(
            progress,
            world,
            snapshot,
            event(StoryEventType.CUSTOM_EVENT, CUSTOM, 1),
            QuestEvaluator.CurrentState.empty()));
        assertEquals(1, progress.counter(kill));
        assertEquals(1, progress.counter(custom));

        StoryProgress completed = activeProgress(questId);
        completed.complete(questId);
        assertEquals(Set.of(), evaluator.onEvent(
            completed,
            world,
            snapshot,
            event(StoryEventType.ITEM_PICKUP, ITEM, 1),
            QuestEvaluator.CurrentState.empty()));
        assertEquals(0, completed.counter(pickup));
    }

    @Test
    void disjointEntityTagKillsAdvanceOnlyTheMatchingRequirement() {
        ResourceLocation questId = ResourceLocation.parse("fireflymc:tagged-kills");
        QuestDefinition quest = quest(
            questId,
            requirement("zombie", new ConditionTypes.KillCondition(null, ZOMBIE_TAG, 1)),
            requirement("skeleton", new ConditionTypes.KillCondition(null, SKELETON_TAG, 1)));
        StoryProgress progress = activeProgress(questId);
        StoryRuntimeSnapshot snapshot = snapshotWithTargets(
            Map.of(
                declaration(questId, "requirements[0]",
                    PreparedStoryData.IndexDeclaration.Kind.ENTITY_TAG, ZOMBIE_TAG.toString()),
                Set.of(ENTITY),
                declaration(questId, "requirements[1]",
                    PreparedStoryData.IndexDeclaration.Kind.ENTITY_TAG, SKELETON_TAG.toString()),
                Set.of(SKELETON)),
            quest);

        assertEquals(Set.of(questId), evaluator.onEvent(
            progress,
            world,
            snapshot,
            event(StoryEventType.ENTITY_KILL, ENTITY, 1),
            QuestEvaluator.CurrentState.empty()));
        assertEquals(1, progress.counter(key(questId, "zombie")));
        assertEquals(0, progress.counter(key(questId, "skeleton")));
    }

    @Test
    void exactEntityConditionAndDisjointTagDoNotBothAdvance() {
        ResourceLocation questId = ResourceLocation.parse("fireflymc:exact-and-tag");
        QuestDefinition quest = quest(
            questId,
            requirement("exact", new ConditionTypes.KillCondition(ENTITY, null, 1)),
            requirement("tagged", new ConditionTypes.KillCondition(null, SKELETON_TAG, 1)));
        StoryProgress progress = activeProgress(questId);
        StoryRuntimeSnapshot snapshot = snapshotWithTargets(
            Map.of(
                declaration(questId, "requirements[0]",
                    PreparedStoryData.IndexDeclaration.Kind.ENTITY, ENTITY.toString()),
                Set.of(ENTITY),
                declaration(questId, "requirements[1]",
                    PreparedStoryData.IndexDeclaration.Kind.ENTITY_TAG, SKELETON_TAG.toString()),
                Set.of(SKELETON)),
            quest);

        assertEquals(Set.of(questId), evaluator.onEvent(
            progress,
            world,
            snapshot,
            event(StoryEventType.ENTITY_KILL, ENTITY, 1),
            QuestEvaluator.CurrentState.empty()));
        assertEquals(1, progress.counter(key(questId, "exact")));
        assertEquals(0, progress.counter(key(questId, "tagged")));
    }

    @Test
    void absentQuestCandidatesAreIgnored() {
        ResourceLocation absentQuestId = ResourceLocation.parse("fireflymc:deleted");
        QuestDefinition absentQuest = quest(
            absentQuestId,
            requirement("kill", new ConditionTypes.KillCondition(ENTITY, null, 1)));
        StoryProgress progress = activeProgress(absentQuestId);
        StoryRuntimeSnapshot staleSnapshot = snapshotWithTargets(
            Map.of(
                declaration(absentQuestId, "requirements[0]",
                    PreparedStoryData.IndexDeclaration.Kind.ENTITY, ENTITY.toString()),
                Set.of(ENTITY)));

        assertEquals(Set.of(), evaluator.onEvent(
            progress,
            world,
            staleSnapshot,
            event(StoryEventType.ENTITY_KILL, ENTITY, 1),
            QuestEvaluator.CurrentState.empty()));
        assertEquals(0, progress.counter(key(absentQuestId, "kill")));
        assertFalse(staleSnapshot.quests().containsKey(absentQuest.id()));
    }

    @Test
    void countersDoNotBackfillOnActivation() {
        ResourceLocation pickupQuest = ResourceLocation.parse("fireflymc:pickup");
        ResourceLocation killQuest = ResourceLocation.parse("fireflymc:kill");
        ResourceLocation customQuest = ResourceLocation.parse("fireflymc:custom");
        QuestDefinition pickup = quest(pickupQuest,
            requirement("memory", new ConditionTypes.PickupCondition(ITEM, 1)));
        QuestDefinition kill = quest(killQuest,
            requirement("memory", new ConditionTypes.KillCondition(ENTITY, null, 1)));
        QuestDefinition custom = quest(customQuest,
            requirement("memory", new ConditionTypes.CustomEventCondition(CUSTOM, 1)));

        StoryProgress progress = new StoryProgress();
        progress.activate(pickupQuest);
        progress.activate(killQuest);
        progress.activate(customQuest);
        QuestEvaluator.CurrentState present = QuestEvaluator.CurrentState.empty();

        assertFalse(evaluator.evaluate(pickup, progress, world, present));
        assertFalse(evaluator.evaluate(kill, progress, world, present));
        assertFalse(evaluator.evaluate(custom, progress, world, present));
        assertEquals(0, progress.counter(key(pickupQuest, "memory")));
        assertEquals(0, progress.counter(key(killQuest, "memory")));
        assertEquals(0, progress.counter(key(customQuest, "memory")));
    }

    @Test
    void stickyCurrentStateIsCheckedImmediatelyAndPersistedAtDefinitionVersion() {
        ResourceLocation questId = ResourceLocation.parse("fireflymc:visited");
        QuestDefinition quest = quest(
            questId,
            requirement("dimension", new ConditionTypes.EnterDimensionCondition(DIMENSION)),
            requirement("advancement", new ConditionTypes.AdvancementCondition(ADVANCEMENT)));
        StoryProgress progress = activeProgress(questId);

        assertFalse(evaluator.evaluate(
            quest, progress, world, QuestEvaluator.CurrentState.empty()));
        assertTrue(evaluator.evaluate(
            quest,
            progress,
            world,
            probe(true, true, false, false, false, false)));
        assertTrue(progress.isSatisfied(key(questId, "dimension")));
        assertTrue(progress.isSatisfied(key(questId, "advancement")));

        progress.reconcile(snapshot(quest));
        assertTrue(progress.isSatisfied(key(questId, "dimension")));
        assertTrue(progress.isSatisfied(key(questId, "advancement")));
    }

    @Test
    void inventoryIsAlwaysFreshDynamicStateAndIsNeverPersisted() {
        ResourceLocation questId = ResourceLocation.parse("fireflymc:collect");
        QuestDefinition quest = quest(
            questId,
            requirement("memory", new ConditionTypes.InventoryCondition(ITEM, 3)));
        StoryProgress progress = activeProgress(questId);
        RequirementProgressKey progressKey = key(questId, "memory");

        assertFalse(evaluator.evaluate(
            quest, progress, world, probeWithInventory(2)));
        assertTrue(evaluator.evaluate(
            quest, progress, world, probeWithInventory(3)));
        assertFalse(progress.isSatisfied(progressKey));
        assertFalse(evaluator.evaluate(
            quest, progress, world, probeWithInventory(0)));
        assertFalse(progress.isSatisfied(progressKey));
    }

    @Test
    void stickyConditionsPersistEvenWhenAnotherRequirementIsUnsatisfied() {
        ResourceLocation questId = ResourceLocation.parse("fireflymc:sticky-mixed");
        QuestDefinition quest = quest(
            questId,
            requirement("memory", new ConditionTypes.InventoryCondition(ITEM, 3)),
            requirement("advancement", new ConditionTypes.AdvancementCondition(ADVANCEMENT)));
        StoryProgress progress = activeProgress(questId);

        assertFalse(evaluator.evaluate(
            quest, progress, world, probe(false, true, false, false, false, false)));
        assertTrue(progress.isSatisfied(key(questId, "advancement")));
    }

    @Test
    void biomeStructureAndLocationCurrentStateAreEvaluatedAndPersisted() {
        ResourceLocation questId = ResourceLocation.parse("fireflymc:places");
        QuestDefinition quest = quest(
            questId,
            requirement("biome", new ConditionTypes.EnterBiomeCondition(BIOME, null)),
            requirement("structure", new ConditionTypes.EnterStructureCondition(STRUCTURE, null)),
            requirement("location", new ConditionTypes.LocationCondition(
                DIMENSION, 4.5D, 65.0D, -2.5D, 3.0F)));
        StoryProgress progress = activeProgress(questId);

        assertFalse(evaluator.evaluate(
            quest, progress, world, QuestEvaluator.CurrentState.empty()));
        assertTrue(evaluator.evaluate(
            quest, progress, world, probe(false, false, false, true, true, true)));
        assertTrue(progress.isSatisfied(key(questId, "biome")));
        assertTrue(progress.isSatisfied(key(questId, "structure")));
        assertTrue(progress.isSatisfied(key(questId, "location")));
    }

    @Test
    void biomeAndStructureSelectorsDoNotCollapseExactAndTagProbes() {
        ResourceLocation exactBiomeQuest = ResourceLocation.parse("fireflymc:exact-biome");
        ResourceLocation tagBiomeQuest = ResourceLocation.parse("fireflymc:tag-biome");
        ResourceLocation exactStructureQuest = ResourceLocation.parse("fireflymc:exact-structure");
        ResourceLocation tagStructureQuest = ResourceLocation.parse("fireflymc:tag-structure");
        ResourceLocation biomeTag = ResourceLocation.parse("minecraft:is_forest");
        ResourceLocation structureTag = ResourceLocation.parse("minecraft:village");
        StoryProgress progress = activeProgress(
            exactBiomeQuest,
            tagBiomeQuest,
            exactStructureQuest,
            tagStructureQuest);

        assertTrue(evaluator.evaluate(
            quest(exactBiomeQuest, requirement("biome",
                new ConditionTypes.EnterBiomeCondition(BIOME, null))),
            progress,
            world,
            new SelectorProbe(true, false, true, false)));
        assertTrue(evaluator.evaluate(
            quest(tagBiomeQuest, requirement("biome",
                new ConditionTypes.EnterBiomeCondition(null, biomeTag))),
            progress,
            world,
            new SelectorProbe(false, true, false, false)));
        assertTrue(evaluator.evaluate(
            quest(exactStructureQuest, requirement("structure",
                new ConditionTypes.EnterStructureCondition(STRUCTURE, null))),
            progress,
            world,
            new SelectorProbe(false, false, true, false)));
        assertTrue(evaluator.evaluate(
            quest(tagStructureQuest, requirement("structure",
                new ConditionTypes.EnterStructureCondition(null, structureTag))),
            progress,
            world,
            new SelectorProbe(false, false, false, true)));
        StoryProgress unsatisfiedProgress = activeProgress(exactBiomeQuest);
        assertFalse(evaluator.evaluate(
            quest(exactBiomeQuest, requirement("biome",
                new ConditionTypes.EnterBiomeCondition(BIOME, null))),
            unsatisfiedProgress,
            world,
            new SelectorProbe(false, true, false, false)));
    }

    @Test
    void dynamicFlagReadsTheExplicitPlayerOrWorldScope() {
        ResourceLocation playerQuestId = ResourceLocation.parse("fireflymc:player-flag");
        ResourceLocation worldQuestId = ResourceLocation.parse("fireflymc:world-flag");
        QuestDefinition playerQuest = quest(playerQuestId,
            requirement("ready", new ConditionTypes.FlagCondition(
                FLAG.getPath(), true, StoryCondition.Scope.PLAYER)));
        QuestDefinition worldQuest = quest(worldQuestId,
            requirement("ready", new ConditionTypes.FlagCondition(
                FLAG.getPath(), true, StoryCondition.Scope.WORLD)));
        StoryProgress progress = activeProgress(playerQuestId, worldQuestId);

        assertFalse(evaluator.evaluate(
            playerQuest, progress, world, QuestEvaluator.CurrentState.empty()));
        assertFalse(evaluator.evaluate(
            worldQuest, progress, world, QuestEvaluator.CurrentState.empty()));

        world.setFlag(FLAG.getPath(), true);
        assertTrue(evaluator.evaluate(
            worldQuest, progress, world, QuestEvaluator.CurrentState.empty()));
        assertFalse(evaluator.evaluate(
            playerQuest, progress, world, QuestEvaluator.CurrentState.empty()));

        progress.setFlag(FLAG.getPath(), true);
        assertTrue(evaluator.evaluate(
            playerQuest, progress, world, QuestEvaluator.CurrentState.empty()));
    }

    @Test
    void scopedStateSignalUsesTheExplicitInterestIndexOverload() {
        ResourceLocation playerQuestId = ResourceLocation.parse("fireflymc:player-flag");
        ResourceLocation worldQuestId = ResourceLocation.parse("fireflymc:world-flag");
        QuestDefinition playerQuest = quest(playerQuestId,
            requirement("ready", new ConditionTypes.FlagCondition(
                FLAG.getPath(), true, StoryCondition.Scope.PLAYER)));
        QuestDefinition worldQuest = quest(worldQuestId,
            requirement("ready", new ConditionTypes.FlagCondition(
                FLAG.getPath(), true, StoryCondition.Scope.WORLD)));
        StoryRuntimeSnapshot snapshot = snapshot(playerQuest, worldQuest);
        StoryProgress progress = activeProgress(playerQuestId, worldQuestId);

        assertEquals(
            Set.of(playerQuestId),
            evaluator.onEvent(
                progress,
                world,
                snapshot,
                new StoryEvent(StoryEventType.FLAG_CHANGED, FLAG, 1,
                    Map.of("scope", "player")),
                QuestEvaluator.CurrentState.empty()));
        assertEquals(
            Set.of(worldQuestId),
            evaluator.onEvent(
                progress,
                world,
                snapshot,
                new StoryEvent(StoryEventType.FLAG_CHANGED, FLAG, 1,
                    Map.of("scope", "world")),
                QuestEvaluator.CurrentState.empty()));
    }

    @Test
    void settingMissingFlagFalseDoesNotCompleteTrueCondition() {
        ResourceLocation questId = ResourceLocation.parse("fireflymc:flag");
        QuestDefinition quest = quest(questId,
            requirement("ready", new ConditionTypes.FlagCondition(
                FLAG.getPath(), true, StoryCondition.Scope.PLAYER)));
        StoryProgress progress = activeProgress(questId);

        assertFalse(progress.setFlag(FLAG.getPath(), false));
        assertFalse(evaluator.evaluate(
            quest, progress, world, QuestEvaluator.CurrentState.empty()));
        assertFalse(progress.isSatisfied(key(questId, "ready")));
    }

    @Test
    void watchSetTracksOnlyActiveContinuousQuestsAndSpreadsPlayerPhases() {
        ResourceLocation watchQuestId = ResourceLocation.parse("fireflymc:watch");
        ResourceLocation eventQuestId = ResourceLocation.parse("fireflymc:event");
        ResourceLocation inactiveQuestId = ResourceLocation.parse("fireflymc:inactive");
        QuestDefinition watchQuest = quest(watchQuestId,
            requirement("inventory", new ConditionTypes.InventoryCondition(ITEM, 1)));
        QuestDefinition eventQuest = quest(eventQuestId,
            requirement("pickup", new ConditionTypes.PickupCondition(ITEM, 1)));
        QuestDefinition inactiveQuest = quest(inactiveQuestId,
            requirement("inventory", new ConditionTypes.InventoryCondition(ITEM, 1)));
        StoryProgress progress = activeProgress(watchQuestId, eventQuestId);
        StoryRuntimeSnapshot snapshot = snapshot(watchQuest, eventQuest, inactiveQuest);
        ContinuousWatchSet watches = new ContinuousWatchSet();

        watches.rebuild(PLAYER_ID, snapshot, progress);
        assertEquals(Set.of(watchQuestId), watches.watched(PLAYER_ID));
        int phase = watches.phase(PLAYER_ID);
        long before = (20 - phase - 1 + 20) % 20;
        long due = (before + 1) % 20;

        assertEquals(Set.of(), watches.tickDue(PLAYER_ID, before));
        assertEquals(Set.of(watchQuestId), watches.tickDue(PLAYER_ID, due));
        assertEquals(Set.of(watchQuestId), watches.tickDue(PLAYER_ID, due + 20L));

        watches.deactivate(PLAYER_ID, watchQuestId);
        assertEquals(Set.of(), watches.watched(PLAYER_ID));
        assertEquals(Set.of(), watches.tickDue(PLAYER_ID, due));

        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000002");
        watches.activate(other, watchQuestId, snapshot, progress);
        assertEquals(Set.of(watchQuestId), watches.watched(other));
    }

    @Test
    void watchSetRebuildReplacesTheOlderWatchedSet() {
        ResourceLocation firstQuestId = ResourceLocation.parse("fireflymc:first-watch");
        ResourceLocation secondQuestId = ResourceLocation.parse("fireflymc:second-watch");
        QuestDefinition first = quest(firstQuestId,
            requirement("inventory", new ConditionTypes.InventoryCondition(ITEM, 1)));
        QuestDefinition second = quest(secondQuestId,
            requirement("inventory", new ConditionTypes.InventoryCondition(ITEM, 1)));
        StoryProgress progress = activeProgress(firstQuestId, secondQuestId);
        ContinuousWatchSet watches = new ContinuousWatchSet();

        watches.rebuild(PLAYER_ID, snapshot(first), progress);
        assertEquals(Set.of(firstQuestId), watches.watched(PLAYER_ID));
        watches.rebuild(PLAYER_ID, snapshot(second), progress);
        assertEquals(Set.of(secondQuestId), watches.watched(PLAYER_ID));
    }

    @Test
    void watchSetRejectsActivationThatIsNotAnActiveContinuousQuest() {
        ResourceLocation eventId = ResourceLocation.parse("fireflymc:event");
        ResourceLocation inactiveId = ResourceLocation.parse("fireflymc:inactive");
        QuestDefinition event = quest(eventId,
            requirement("pickup", new ConditionTypes.PickupCondition(ITEM, 1)));
        QuestDefinition inactive = quest(inactiveId,
            requirement("inventory", new ConditionTypes.InventoryCondition(ITEM, 1)));
        StoryRuntimeSnapshot snapshot = snapshot(event, inactive);
        ContinuousWatchSet watches = new ContinuousWatchSet();

        assertThrowsExactly(IllegalArgumentException.class,
            () -> watches.activate(PLAYER_ID, eventId, snapshot, new StoryProgress()));
        assertThrowsExactly(IllegalArgumentException.class,
            () -> watches.activate(PLAYER_ID, inactiveId, snapshot, new StoryProgress()));
    }

    @Test
    void storySignalRepresentsActionInvalidationsWithoutWorldAccess() {
        assertSame(StorySignal.Kind.FLAG_CHANGED, new StorySignal.FlagChanged(
            "story.ready",
            StoryCondition.Scope.PLAYER).kind());
        assertSame(StorySignal.Kind.VAR_CHANGED, new StorySignal.VarChanged(
            "story.count",
            StoryCondition.Scope.PLAYER).kind());
        assertSame(StorySignal.Kind.INVENTORY_CHANGED, new StorySignal.InventoryChanged().kind());
        assertSame(StorySignal.Kind.LOCATION_CHANGED, new StorySignal.LocationChanged().kind());
    }

    private StoryEvent event(StoryEventType type, ResourceLocation key, int amount) {
        return new StoryEvent(type, key, amount, Map.of());
    }

    private StoryProgress activeProgress(ResourceLocation... questIds) {
        StoryProgress progress = new StoryProgress();
        for (ResourceLocation questId : questIds) {
            progress.activate(questId);
        }
        return progress;
    }

    private QuestDefinition quest(ResourceLocation id, RequirementDefinition... requirements) {
        return new QuestDefinition(
            id,
            ResourceLocation.parse("fireflymc:test"),
            Component.literal("Test"),
            Component.literal("Test"),
            List.of(),
            "",
            false,
            List.of(requirements),
            List.of());
    }

    private RequirementDefinition requirement(String id, StoryCondition condition) {
        return new RequirementDefinition(id, 1, condition);
    }

    private RequirementProgressKey key(ResourceLocation questId, String requirementId) {
        return new RequirementProgressKey(questId, requirementId, 1);
    }

    private StoryRuntimeSnapshot snapshot(QuestDefinition... quests) {
        Map<ResourceLocation, QuestDefinition> questMap = new java.util.LinkedHashMap<>();
        Set<PreparedStoryData.IndexDeclaration> declarations =
            new java.util.LinkedHashSet<>();
        for (QuestDefinition quest : quests) {
            questMap.put(quest.id(), quest);
            for (int index = 0; index < quest.requirements().size(); index++) {
                addDeclarations(declarations, quest, index);
            }
        }
        return new StoryRuntimeSnapshot(
            Map.of(),
            questMap,
            InterestIndex.fromDeclarations(declarations.stream().collect(
                java.util.stream.Collectors.toMap(
                    declaration -> declaration,
                    declaration -> Set.of(ResourceLocation.parse(declaration.target()))))),
            firefly520.fireflymc.story.loader.StoryGraph.fromQuests(questMap),
            "test");
    }

    private StoryRuntimeSnapshot snapshotWithTargets(
        Map<PreparedStoryData.IndexDeclaration, Set<ResourceLocation>> declarations,
        QuestDefinition... quests
    ) {
        Map<ResourceLocation, QuestDefinition> questMap = new java.util.LinkedHashMap<>();
        for (QuestDefinition quest : quests) {
            questMap.put(quest.id(), quest);
        }
        return new StoryRuntimeSnapshot(
            Map.of(),
            questMap,
            InterestIndex.fromDeclarations(declarations),
            firefly520.fireflymc.story.loader.StoryGraph.fromQuests(questMap),
            "test");
    }

    private PreparedStoryData.IndexDeclaration declaration(
        ResourceLocation questId,
        String field,
        PreparedStoryData.IndexDeclaration.Kind kind,
        String target
    ) {
        return new PreparedStoryData.IndexDeclaration(questId, field, kind, target);
    }

    private void addDeclarations(
        Set<PreparedStoryData.IndexDeclaration> declarations,
        QuestDefinition quest,
        int index
    ) {
        StoryCondition condition = quest.requirements().get(index).condition();
        String field = "requirements[" + index + "]";
        if (condition.persistence() == ConditionPersistence.COUNTER
            && condition instanceof ConditionTypes.PickupCondition pickup) {
            declarations.add(new PreparedStoryData.IndexDeclaration(
                quest.id(), field, PreparedStoryData.IndexDeclaration.Kind.ITEM,
                pickup.item().toString()));
        } else if (condition.persistence() == ConditionPersistence.COUNTER
            && condition instanceof ConditionTypes.KillCondition kill) {
            declarations.add(new PreparedStoryData.IndexDeclaration(
                quest.id(), field, PreparedStoryData.IndexDeclaration.Kind.ENTITY,
                (kill.entity() == null ? kill.entityTag() : kill.entity()).toString()));
        } else if (condition.persistence() == ConditionPersistence.COUNTER
            && condition instanceof ConditionTypes.CustomEventCondition custom) {
            declarations.add(new PreparedStoryData.IndexDeclaration(
                quest.id(), field, PreparedStoryData.IndexDeclaration.Kind.CUSTOM_EVENT,
                custom.event().toString()));
        } else if (condition.persistence() == ConditionPersistence.STICKY
            && condition instanceof ConditionTypes.EnterDimensionCondition dimension) {
            declarations.add(new PreparedStoryData.IndexDeclaration(
                quest.id(), field, PreparedStoryData.IndexDeclaration.Kind.DIMENSION,
                dimension.dimension().toString()));
        } else if (condition.persistence() == ConditionPersistence.STICKY
            && condition instanceof ConditionTypes.AdvancementCondition advancement) {
            declarations.add(new PreparedStoryData.IndexDeclaration(
                quest.id(), field, PreparedStoryData.IndexDeclaration.Kind.ADVANCEMENT,
                advancement.advancement().toString()));
        } else if (condition.persistence() == ConditionPersistence.DYNAMIC
            && condition instanceof ConditionTypes.InventoryCondition inventory) {
            declarations.add(new PreparedStoryData.IndexDeclaration(
                quest.id(), field, PreparedStoryData.IndexDeclaration.Kind.ITEM,
                inventory.item().toString()));
        } else if (condition.persistence() == ConditionPersistence.DYNAMIC
            && condition instanceof ConditionTypes.FlagCondition flag) {
            declarations.add(new PreparedStoryData.IndexDeclaration(
                quest.id(), field, PreparedStoryData.IndexDeclaration.Kind.FLAG,
                ResourceLocation.parse(flag.flag()).toString(),
                flag.scope()));
        }
    }

    private QuestEvaluator.CurrentState probeWithInventory(int count) {
        return probe(false, false, false, false, false, false)
            .withInventory(ITEM, count);
    }

    private TestProbe probe(
        boolean dimension,
        boolean advancement,
        boolean inventory,
        boolean biome,
        boolean structure,
        boolean location
    ) {
        return new TestProbe(
            Map.of(),
            dimension,
            inventory ? 1 : 0,
            biome,
            structure,
            location,
            advancement);
    }

    private record TestProbe(
        Map<ResourceLocation, Integer> inventory,
        boolean dimension,
        int inventoryCount,
        boolean biome,
        boolean structure,
        boolean location,
        boolean advancement
    ) implements QuestEvaluator.CurrentState {
        TestProbe {
            inventory = Map.copyOf(inventory);
        }

        TestProbe withInventory(ResourceLocation item, int count) {
            Map<ResourceLocation, Integer> copied = new java.util.LinkedHashMap<>(inventory);
            copied.put(item, count);
            return new TestProbe(
                copied, dimension, inventoryCount, biome, structure, location, advancement);
        }

        @Override
        public int inventoryCount(ResourceLocation item) {
            return inventory.getOrDefault(item, 0);
        }

        @Override
        public boolean inDimension(ResourceLocation id) {
            return dimension;
        }

        @Override
        public boolean inBiome(ResourceLocation id) {
            return biome;
        }

        @Override
        public boolean inBiomeTag(ResourceLocation id) {
            return false;
        }

        @Override
        public boolean inStructure(ResourceLocation id) {
            return structure;
        }

        @Override
        public boolean inStructureTag(ResourceLocation id) {
            return false;
        }

        @Override
        public boolean atLocation(
            ResourceLocation dimensionId,
            double x,
            double y,
            double z,
            float radius
        ) {
            return location;
        }

        @Override
        public boolean advancementDone(ResourceLocation id) {
            return advancement;
        }
    }

    private record SelectorProbe(
        boolean exactBiome,
        boolean biomeTag,
        boolean exactStructure,
        boolean structureTag
    ) implements QuestEvaluator.CurrentState {
        @Override
        public int inventoryCount(ResourceLocation item) {
            return 0;
        }

        @Override
        public boolean inDimension(ResourceLocation id) {
            return false;
        }

        @Override
        public boolean inBiome(ResourceLocation id) {
            return exactBiome;
        }

        @Override
        public boolean inBiomeTag(ResourceLocation id) {
            return biomeTag;
        }

        @Override
        public boolean inStructure(ResourceLocation id) {
            return exactStructure;
        }

        @Override
        public boolean inStructureTag(ResourceLocation id) {
            return structureTag;
        }

        @Override
        public boolean atLocation(
            ResourceLocation dimension,
            double x,
            double y,
            double z,
            float radius
        ) {
            return false;
        }

        @Override
        public boolean advancementDone(ResourceLocation id) {
            return false;
        }
    }
}
