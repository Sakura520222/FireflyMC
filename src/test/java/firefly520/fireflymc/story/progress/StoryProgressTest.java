package firefly520.fireflymc.story.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import firefly520.fireflymc.story.condition.ConditionPersistence;
import firefly520.fireflymc.story.condition.StoryCondition;
import firefly520.fireflymc.story.definition.QuestDefinition;
import firefly520.fireflymc.story.definition.RequirementDefinition;
import firefly520.fireflymc.story.engine.InterestIndex;
import firefly520.fireflymc.story.engine.StoryRuntimeSnapshot;
import firefly520.fireflymc.story.loader.StoryGraph;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class StoryProgressTest {
    private static final ResourceLocation QUEST = ResourceLocation.parse("fireflymc:test/quest");
    private static final ResourceLocation INITIAL = ResourceLocation.parse("fireflymc:test/initial");
    private static final ResourceLocation OTHER = ResourceLocation.parse("fireflymc:test/other");

    @Test
    void defaultsAreEncapsulatedAcrossIndependentStoryProgressObjects() {
        StoryProgress first = new StoryProgress();
        StoryProgress second = new StoryProgress();

        assertEquals(QuestState.LOCKED, first.getQuestState(QUEST));
        assertFalse(first.getFlag("firefly.met"));
        assertEquals(0, first.getVar("firefly.affinity"));
        assertEquals(Set.of(), first.completedQuests());
        assertEquals(Set.of(), first.activeQuests());
        assertEquals(Map.of(), first.flags());
        assertEquals(Map.of(), first.vars());
        assertTrue(first.trackedQuest().isEmpty());

        assertTrue(first.setFlag("firefly.met", true));
        assertTrue(first.setVar("firefly.affinity", 3, 0, 10));
        first.activate(QUEST);
        first.setTrackedQuest(QUEST);

        assertEquals(QuestState.ACTIVE, first.getQuestState(QUEST));
        assertTrue(first.getFlag("firefly.met"));
        assertEquals(3, first.getVar("firefly.affinity"));
        assertEquals(QUEST, first.trackedQuest().orElseThrow());
        assertEquals(QuestState.LOCKED, second.getQuestState(QUEST));
        assertFalse(second.getFlag("firefly.met"));
        assertEquals(0, second.getVar("firefly.affinity"));
        assertTrue(second.trackedQuest().isEmpty());
    }

    @Test
    void accessIsConfinedToTheOwnerThread() throws Throwable {
        StoryProgress progress = createdOnAnotherThread(StoryProgress::new);

        assertThrows(IllegalThreadStateException.class, () -> progress.getQuestState(null));
        assertThrows(IllegalThreadStateException.class, () -> progress.activate(QUEST));
        assertThrows(IllegalThreadStateException.class, () -> progress.counter(null));
        assertThrows(IllegalThreadStateException.class, () -> progress.flags());
        assertThrows(IllegalThreadStateException.class, () -> progress.serializeNBT(null));
    }

    @Test
    void questStateOnlyFollowsTheLegalLockedActiveCompletedPath() {
        StoryProgress progress = new StoryProgress();

        assertThrows(IllegalStateException.class, () -> progress.complete(QUEST));
        progress.activate(QUEST);
        assertEquals(QuestState.ACTIVE, progress.getQuestState(QUEST));
        assertThrows(IllegalStateException.class, () -> progress.activate(QUEST));

        progress.complete(QUEST);
        assertEquals(QuestState.COMPLETED, progress.getQuestState(QUEST));
        assertEquals(Set.of(QUEST), progress.completedQuests());
        assertEquals(Set.of(), progress.activeQuests());
        assertTrue(progress.trackedQuest().isEmpty());
        assertThrows(IllegalStateException.class, () -> progress.activate(QUEST));
        assertThrows(IllegalStateException.class, () -> progress.complete(QUEST));

        progress.activate(OTHER);
        assertEquals(QuestState.ACTIVE, progress.getQuestState(OTHER));
    }

    @Test
    void countersAreKeyedIncludingStateVersionAndOverflowIsRejected() {
        StoryProgress progress = new StoryProgress();
        var versionOne = new RequirementProgressKey(QUEST, "gather", 1);
        var versionTwo = new RequirementProgressKey(QUEST, "gather", 2);

        assertEquals(0, progress.counter(versionOne));
        assertTrue(progress.incrementCounter(versionOne, 2));
        assertTrue(progress.incrementCounter(versionOne, 3));
        assertEquals(5, progress.counter(versionOne));
        assertEquals(0, progress.counter(versionTwo));

        var nearMaximum = new RequirementProgressKey(OTHER, "gather", 1);
        assertTrue(progress.incrementCounter(nearMaximum, Integer.MAX_VALUE - 1));
        assertTrue(progress.incrementCounter(nearMaximum, 1));
        assertEquals(Integer.MAX_VALUE, progress.counter(nearMaximum));
        assertThrows(ArithmeticException.class,
            () -> progress.incrementCounter(nearMaximum, 1));
        assertEquals(Integer.MAX_VALUE, progress.counter(nearMaximum));

        assertThrows(IllegalArgumentException.class,
            () -> progress.incrementCounter(versionOne, 0));
        assertThrows(IllegalArgumentException.class,
            () -> progress.incrementCounter(versionOne, -1));
    }

    @Test
    void flagAndVarMutationsClampAfterTheOperationAndRejectBadNames() {
        StoryProgress progress = new StoryProgress();

        assertFalse(progress.setFlag("firefly.met", false));
        assertTrue(progress.setFlag("firefly.met", true));
        assertFalse(progress.setFlag("firefly.met", true));

        assertTrue(progress.setVar("firefly.affinity", 2, 0, 10));
        assertTrue(progress.addVar("firefly.affinity", 20, 0, 10));
        assertEquals(10, progress.getVar("firefly.affinity"));
        assertTrue(progress.setVar("firefly.affinity", -3, 0, 10));
        assertEquals(0, progress.getVar("firefly.affinity"));
        assertFalse(progress.addVar("firefly.affinity", -2, 0, 10));
        assertEquals(0, progress.getVar("firefly.affinity"));
        assertFalse(progress.addVar("firefly.affinity", 0, 0, 10));
        assertThrows(IllegalArgumentException.class,
            () -> progress.setVar("Invalid", 1, 0, 10));
        assertThrows(IllegalArgumentException.class,
            () -> progress.addVar("Invalid", 1, 0, 10));
        assertThrows(IllegalArgumentException.class,
            () -> progress.setVar("firefly.affinity", 1, 10, 0));
    }

    @Test
    void immutableViewsAreCopySafeSnapshots() {
        StoryProgress progress = new StoryProgress();
        progress.activate(QUEST);
        progress.setFlag("firefly.met", true);
        progress.setVar("firefly.affinity", 2, 0, 10);
        var key = new RequirementProgressKey(QUEST, "gather", 1);
        progress.incrementCounter(key, 4);
        progress.markSatisfied(key);

        var quests = progress.activeQuests();
        var flags = progress.flags();
        var vars = progress.vars();
        var counters = progress.counters();
        var satisfied = progress.satisfied();

        assertThrows(UnsupportedOperationException.class, () -> quests.add(OTHER));
        assertThrows(UnsupportedOperationException.class, () -> flags.put("firefly.met", false));
        assertThrows(UnsupportedOperationException.class, () -> vars.put("firefly.affinity", 0));
        assertThrows(UnsupportedOperationException.class, () -> counters.put(key, 0));
        assertThrows(UnsupportedOperationException.class, () -> satisfied.add(key));

        progress.activate(OTHER);
        progress.setFlag("firefly.other", true);
        assertEquals(Set.of(QUEST), quests);
        assertEquals(Map.of("firefly.met", true), flags);
        assertEquals(Map.of("firefly.affinity", 2), vars);
        assertEquals(Map.of(key, 4), counters);
        assertEquals(Set.of(key), satisfied);
    }

    @Test
    void reconcileInvalidatesChangedStateVersionsWithoutDiscardingQuestState() {
        StoryProgress progress = new StoryProgress();
        progress.activate(QUEST);
        progress.incrementCounter(new RequirementProgressKey(QUEST, "gather", 1), 7);
        progress.markSatisfied(new RequirementProgressKey(QUEST, "gather", 1));

        progress.reconcile(snapshot(requirement("gather", 2, ConditionPersistence.COUNTER)));

        assertEquals(QuestState.ACTIVE, progress.getQuestState(QUEST));
        assertEquals(Map.of(), progress.counters());
        assertEquals(Set.of(), progress.satisfied());
    }

    @Test
    void deletedQuestLifecycleAndRuntimeValuesAreRetainedAndWarnedOnce() {
        List<ResourceLocation> warnings = new java.util.ArrayList<>();
        StoryProgress progress = new StoryProgress(warnings::add);
        progress.activate(QUEST);
        var key = new RequirementProgressKey(QUEST, "gather", 1);
        progress.incrementCounter(key, 7);
        var stickyKey = new RequirementProgressKey(QUEST, "entered", 1);
        progress.markSatisfied(stickyKey);
        CompoundTag beforeWarning = progress.serializeNBT(RegistryAccess.EMPTY);

        progress.reconcile(snapshotWithoutQuests());
        progress.reconcile(snapshotWithoutQuests());

        assertEquals(List.of(QUEST), warnings);
        assertEquals(QuestState.ACTIVE, progress.getQuestState(QUEST));
        assertEquals(Map.of(key, 7), progress.counters());
        assertEquals(Set.of(stickyKey), progress.satisfied());
        assertEquals(beforeWarning, progress.serializeNBT(RegistryAccess.EMPTY));
    }

    @Test
    void deletedRequirementRuntimeValuesAreInvalidatedWhileLifecycleIsRetained() {
        StoryProgress progress = new StoryProgress();
        progress.activate(QUEST);
        progress.incrementCounter(new RequirementProgressKey(QUEST, "gather", 1), 4);
        progress.markSatisfied(new RequirementProgressKey(QUEST, "entered", 1));

        progress.reconcile(snapshot(requirement("kept", 1, ConditionPersistence.COUNTER)));

        assertEquals(QuestState.ACTIVE, progress.getQuestState(QUEST));
        assertEquals(Map.of(), progress.counters());
        assertEquals(Set.of(), progress.satisfied());
    }

    @Test
    void stickyRuntimeSurvivesOnlyTheMatchingVersionAndKind() {
        StoryProgress progress = new StoryProgress();
        var key = new RequirementProgressKey(QUEST, "entered", 1);
        progress.markSatisfied(key);

        progress.reconcile(snapshot(requirement("entered", 1, ConditionPersistence.STICKY)));
        assertEquals(Set.of(key), progress.satisfied());
    }

    @Test
    void dynamicRequirementsHaveNoPersistedRuntimeValue() {
        StoryProgress progress = new StoryProgress();
        var key = new RequirementProgressKey(QUEST, "carrying", 1);
        progress.incrementCounter(key, 2);
        progress.markSatisfied(key);

        progress.reconcile(snapshot(requirement("carrying", 1, ConditionPersistence.DYNAMIC)));

        assertEquals(Map.of(), progress.counters());
        assertEquals(Set.of(), progress.satisfied());
    }

    @Test
    void counterToStickyMigrationInvalidatesTheOldRuntimeValue() {
        StoryProgress progress = new StoryProgress();
        var key = new RequirementProgressKey(QUEST, "changed", 1);
        progress.incrementCounter(key, 3);

        progress.reconcile(snapshot(requirement("changed", 1, ConditionPersistence.STICKY)));

        assertEquals(Map.of(), progress.counters());
        assertEquals(Set.of(), progress.satisfied());
    }

    @Test
    void stickyToCounterMigrationInvalidatesTheOldRuntimeValue() {
        StoryProgress progress = new StoryProgress();
        var key = new RequirementProgressKey(QUEST, "changed", 1);
        progress.markSatisfied(key);

        progress.reconcile(snapshot(requirement("changed", 1, ConditionPersistence.COUNTER)));

        assertEquals(Map.of(), progress.counters());
        assertEquals(Set.of(), progress.satisfied());
    }

    @Test
    void clearAndBootstrapResetsStateActivatesInitialQuestsAndMigratesVersions() {
        StoryProgress progress = new StoryProgress();
        progress.activate(QUEST);
        progress.complete(QUEST);
        progress.incrementCounter(new RequirementProgressKey(QUEST, "gather", 1), 7);
        progress.setFlag("firefly.met", true);
        progress.setVar("firefly.affinity", 5, 0, 10);
        progress.activate(OTHER);
        progress.setTrackedQuest(OTHER);

        progress.clearAndBootstrap(
            snapshot(requirement("gather", 2, ConditionPersistence.COUNTER)));

        assertEquals(QuestState.LOCKED, progress.getQuestState(QUEST));
        assertEquals(QuestState.ACTIVE, progress.getQuestState(INITIAL));
        assertFalse(progress.getFlag("firefly.met"));
        assertEquals(0, progress.getVar("firefly.affinity"));
        assertTrue(progress.trackedQuest().isEmpty());
        assertEquals(Map.of(), progress.counters());
        assertEquals(Set.of(), progress.satisfied());
    }

    @Test
    void structuredNbtRoundTripPreservesAllState() {
        StoryProgress progress = new StoryProgress();
        progress.activate(QUEST);
        progress.complete(QUEST);
        progress.activate(OTHER);
        progress.setTrackedQuest(OTHER);
        progress.setFlag("firefly.met", true);
        progress.setVar("firefly.affinity", 7, 0, 10);
        var counterKey = new RequirementProgressKey(QUEST, "gather", 2);
        progress.incrementCounter(counterKey, 4);
        progress.markSatisfied(counterKey);

        CompoundTag nbt = progress.serializeNBT(RegistryAccess.EMPTY);
        var requirements = nbt.getCompound("requirements");
        var quest = requirements.getCompound("fireflymc:test/quest");
        var requirement = quest.getCompound("gather");
        assertEquals(2, requirement.getInt("state_version"));
        assertEquals(4, requirement.getInt("counter"));
        assertEquals(1, requirement.getInt("satisfied"));

        StoryProgress restored = new StoryProgress();
        restored.deserializeNBT(RegistryAccess.EMPTY, nbt);

        assertEquals(progress.completedQuests(), restored.completedQuests());
        assertEquals(progress.activeQuests(), restored.activeQuests());
        assertEquals(progress.flags(), restored.flags());
        assertEquals(progress.vars(), restored.vars());
        assertEquals(progress.counters(), restored.counters());
        assertEquals(progress.satisfied(), restored.satisfied());
        assertEquals(progress.trackedQuest(), restored.trackedQuest());
        assertEquals(QuestState.COMPLETED, restored.getQuestState(QUEST));
        assertEquals(4, restored.counter(counterKey));
    }

    @Test
    void serializationRejectsMultipleVersionsForOneRequirementIdentity() {
        StoryProgress progress = new StoryProgress();
        progress.incrementCounter(new RequirementProgressKey(QUEST, "gather", 1), 1);
        progress.markSatisfied(new RequirementProgressKey(QUEST, "gather", 2));

        var exception = assertThrows(IllegalStateException.class,
            () -> progress.serializeNBT(RegistryAccess.EMPTY));

        assertTrue(exception.getMessage().contains("identity collision"));
        assertTrue(exception.getMessage().contains("fireflymc:test/quest"));
        assertTrue(exception.getMessage().contains("gather"));
    }

    @Test
    void missingOptionalContainersDefaultAndMalformedPlayerContainersAreRejected() {
        StoryProgress progress = new StoryProgress();
        progress.deserializeNBT(RegistryAccess.EMPTY, new CompoundTag());
        assertEquals(Map.of(), progress.flags());

        assertRejectedPlayerTag(playerTag("quests", IntTag.valueOf(2)));
        assertRejectedPlayerTag(playerTag("flags", IntTag.valueOf(2)));
        assertRejectedPlayerTag(playerTag("requirements", IntTag.valueOf(2)));

        CompoundTag quests = new CompoundTag();
        quests.put("completed", new CompoundTag());
        assertRejectedPlayerTag(playerTag("quests", quests));

        CompoundTag requirements = new CompoundTag();
        requirements.putString(QUEST.toString(), "not-a-compound");
        assertRejectedPlayerTag(playerTag("requirements", requirements));

        CompoundTag quest = new CompoundTag();
        quest.put("gather", IntTag.valueOf(1));
        CompoundTag questContainers = new CompoundTag();
        questContainers.put(QUEST.toString(), quest);
        assertRejectedPlayerTag(playerTag("requirements", questContainers));

        CompoundTag value = new CompoundTag();
        value.putInt("state_version", 1);
        value.putInt("satisfied", 2);
        quest.put("gather", value);
        CompoundTag invalidSatisfied = new CompoundTag();
        invalidSatisfied.put(QUEST.toString(), quest);
        assertRejectedPlayerTag(playerTag("requirements", invalidSatisfied));
    }

    private static void assertRejectedPlayerTag(CompoundTag tag) {
        StoryProgress progress = new StoryProgress();
        assertThrows(IllegalArgumentException.class,
            () -> progress.deserializeNBT(RegistryAccess.EMPTY, tag));
    }

    private static CompoundTag playerTag(String name, net.minecraft.nbt.Tag value) {
        CompoundTag root = new CompoundTag();
        root.put(name, value);
        return root;
    }

    private static StoryProgress createdOnAnotherThread(Supplier<StoryProgress> supplier)
        throws Throwable {
        AtomicReference<StoryProgress> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                result.set(supplier.get());
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        thread.start();
        thread.join();
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    private static StoryRuntimeSnapshot snapshotWithoutQuests() {
        return new StoryRuntimeSnapshot(
            Map.of(),
            Map.of(),
            InterestIndex.empty(),
            StoryGraph.fromQuests(Map.of()),
            "test-revision");
    }

    private static StoryRuntimeSnapshot snapshot(String requirementId, int stateVersion) {
        return snapshot(requirement(requirementId, stateVersion, ConditionPersistence.COUNTER));
    }

    private static StoryRuntimeSnapshot snapshot(RequirementDefinition... requirements) {
        QuestDefinition quest = new QuestDefinition(
            QUEST,
            ResourceLocation.parse("fireflymc:test/chapter"),
            Component.literal("Quest"),
            Component.literal(""),
            List.of(),
            null,
            false,
            List.of(requirements),
            List.of());
        QuestDefinition initial = new QuestDefinition(
            INITIAL,
            ResourceLocation.parse("fireflymc:test/chapter"),
            Component.literal("Initial"),
            Component.literal(""),
            List.of(),
            null,
            true,
            List.of(),
            List.of());
        Map<ResourceLocation, QuestDefinition> quests = Map.of(QUEST, quest, INITIAL, initial);
        return new StoryRuntimeSnapshot(
            Map.of(),
            quests,
            InterestIndex.empty(),
            StoryGraph.fromQuests(quests),
            "test-revision");
    }

    private static RequirementDefinition requirement(
        String id,
        int stateVersion,
        ConditionPersistence persistence
    ) {
        StoryCondition condition = new StoryCondition() {
            @Override
            public ConditionPersistence persistence() {
                return persistence;
            }
        };
        return new RequirementDefinition(id, stateVersion, condition);
    }
}
