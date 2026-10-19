package firefly520.fireflymc.story.engine;

import firefly520.fireflymc.story.condition.ConditionPersistence;
import firefly520.fireflymc.story.condition.ConditionTypes;
import firefly520.fireflymc.story.condition.StoryCondition;
import firefly520.fireflymc.story.definition.QuestDefinition;
import firefly520.fireflymc.story.definition.RequirementDefinition;
import firefly520.fireflymc.story.progress.QuestState;
import firefly520.fireflymc.story.progress.RequirementProgressKey;
import firefly520.fireflymc.story.progress.StoryProgress;
import firefly520.fireflymc.story.progress.WorldStoryState;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.resources.ResourceLocation;

/**
 * Pure condition evaluation and runtime mutation. Callers own lifecycle transitions, actions,
 * queueing, and all world access; {@link CurrentState} is the only current-world input.
 */
public final class QuestEvaluator {
    /**
     * A narrow, server-side probe. Implementations must only be called on the thread that owns
     * the supplied progress/world state and must never retain the player or world handle.
     *
     * <p>Exact and tag selector methods are deliberately separate. An ID can exist as both a
     * registry entry and a tag, so implementations must not collapse the selector semantics.</p>
     */
    public interface CurrentState {
        int inventoryCount(ResourceLocation item);

        boolean inDimension(ResourceLocation id);

        /** Returns true only when the current biome has the exact registry ID. */
        boolean inBiome(ResourceLocation id);

        /** Returns true when the current biome is a member of the configured tag. */
        boolean inBiomeTag(ResourceLocation id);

        /** Returns true only when the current structure has the exact registry ID. */
        boolean inStructure(ResourceLocation id);

        /** Returns true when the current structure is a member of the configured tag. */
        boolean inStructureTag(ResourceLocation id);

        boolean atLocation(
            ResourceLocation dimension,
            double x,
            double y,
            double z,
            float radius
        );

        boolean advancementDone(ResourceLocation id);

        static CurrentState empty() {
            return EmptyState.INSTANCE;
        }
    }

    private record EmptyState() implements CurrentState {
        private static final EmptyState INSTANCE = new EmptyState();

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
            return false;
        }

        @Override
        public boolean inBiomeTag(ResourceLocation id) {
            return false;
        }

        @Override
        public boolean inStructure(ResourceLocation id) {
            return false;
        }

        @Override
        public boolean inStructureTag(ResourceLocation id) {
            return false;
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

    /**
     * Applies event-backed COUNTER and STICKY progress to active quests and returns the active
     * quests whose DYNAMIC requirements must be re-evaluated. Deleted and non-active quests are
     * filtered even when they remain in a stale candidate set.
     */
    public Set<ResourceLocation> onEvent(
        StoryProgress playerProgress,
        WorldStoryState worldState,
        StoryRuntimeSnapshot snapshot,
        StoryEvent event,
        CurrentState currentState
    ) {
        Objects.requireNonNull(playerProgress, "playerProgress");
        Objects.requireNonNull(worldState, "worldState");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(currentState, "currentState");

        Set<ResourceLocation> result = new TreeSet<>(Comparator.comparing(ResourceLocation::toString));
        for (ResourceLocation questId : candidates(snapshot, event)) {
            QuestDefinition quest = snapshot.quests().get(questId);
            if (quest == null || playerProgress.getQuestState(questId) != QuestState.ACTIVE) {
                continue;
            }
            result.add(questId);
            applyRequirements(
                quest,
                playerProgress,
                event,
                currentState,
                matchedFields(snapshot, event, questId));
        }
        return Set.copyOf(result);
    }

    /**
     * Evaluates all requirements at their current definitions. STICKY probes are persisted when
     * first observed; DYNAMIC state is never persisted, so completion callers can safely invoke
     * this as their final recheck.
     */
    public boolean evaluate(
        QuestDefinition quest,
        StoryProgress progress,
        WorldStoryState world,
        CurrentState currentState
    ) {
        Objects.requireNonNull(quest, "quest");
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(currentState, "currentState");

        boolean allSatisfied = true;
        for (RequirementDefinition requirement : quest.requirements()) {
            RequirementProgressKey key = new RequirementProgressKey(
                quest.id(), requirement.id(), requirement.stateVersion());
            StoryCondition condition = requirement.condition();
            boolean alreadySatisfied = condition.persistence() == ConditionPersistence.STICKY
                && progress.isSatisfied(key);
            boolean satisfied = switch (condition.persistence()) {
                case STICKY -> alreadySatisfied || stickySatisfied(condition, currentState);
                case COUNTER -> progress.counter(key) >= counterThreshold(condition);
                case DYNAMIC -> dynamicSatisfied(condition, progress, world, currentState);
            };
            if (condition.persistence() == ConditionPersistence.STICKY && !alreadySatisfied
                && satisfied) {
                progress.markSatisfied(key);
            }
            if (!satisfied) {
                allSatisfied = false;
            }
        }
        return allSatisfied;
    }

    private static void applyRequirements(
        QuestDefinition quest,
        StoryProgress progress,
        StoryEvent event,
        CurrentState currentState,
        Set<String> matchedFields
    ) {
        for (int index = 0; index < quest.requirements().size(); index++) {
            String field = "requirements[" + index + "]";
            if (!matchedFields.contains(field)) {
                continue;
            }
            RequirementDefinition requirement = quest.requirements().get(index);
            StoryCondition condition = requirement.condition();
            RequirementProgressKey key = new RequirementProgressKey(
                quest.id(), requirement.id(), requirement.stateVersion());
            if (condition.persistence() == ConditionPersistence.COUNTER
                && counterMatches(condition, event)) {
                progress.incrementCounter(key, event.amount());
            } else if (condition.persistence() == ConditionPersistence.STICKY
                && !progress.isSatisfied(key)
                && stickySatisfied(condition, currentState)) {
                progress.markSatisfied(key);
            }
        }
    }

    private static Set<String> matchedFields(
        StoryRuntimeSnapshot snapshot,
        StoryEvent event,
        ResourceLocation questId
    ) {
        Set<String> result = new LinkedHashSet<>();
        matchedDeclarations(snapshot, event).stream()
            .filter(match -> match.owner().equals(questId))
            .map(InterestIndex.DeclarationMatch::field)
            .forEach(result::add);
        return Set.copyOf(result);
    }

    private static Set<InterestIndex.DeclarationMatch> matchedDeclarations(
        StoryRuntimeSnapshot snapshot,
        StoryEvent event
    ) {
        if (event.type() == StoryEventType.FLAG_CHANGED
            || event.type() == StoryEventType.VAR_CHANGED) {
            Optional<StoryCondition.Scope> scope = event.scope();
            return snapshot.interestIndex().matches(
                event.type(), scope.orElseThrow(), event.key());
        }
        return snapshot.interestIndex().matches(event.type(), event.key());
    }

    private static Set<ResourceLocation> candidates(
        StoryRuntimeSnapshot snapshot,
        StoryEvent event
    ) {
        if (event.type() == StoryEventType.FLAG_CHANGED
            || event.type() == StoryEventType.VAR_CHANGED) {
            Optional<StoryCondition.Scope> scope = event.scope();
            return snapshot.interestIndex().candidates(
                event.type(), scope.orElseThrow(), event.key());
        }
        return snapshot.interestIndex().candidates(event.type(), event.key());
    }

    private static boolean counterMatches(StoryCondition condition, StoryEvent event) {
        if (condition instanceof ConditionTypes.PickupCondition pickup) {
            return event.type() == StoryEventType.ITEM_PICKUP
                && pickup.item().equals(event.key());
        }
        if (condition instanceof ConditionTypes.KillCondition kill) {
            return event.type() == StoryEventType.ENTITY_KILL
                && (kill.entity() != null
                    ? kill.entity().equals(event.key())
                    : kill.entityTag() != null);
        }
        if (condition instanceof ConditionTypes.CustomEventCondition custom) {
            return event.type() == StoryEventType.CUSTOM_EVENT
                && custom.event().equals(event.key());
        }
        return false;
    }

    private static int counterThreshold(StoryCondition condition) {
        if (condition instanceof ConditionTypes.PickupCondition pickup) {
            return pickup.count();
        }
        if (condition instanceof ConditionTypes.KillCondition kill) {
            return kill.count();
        }
        if (condition instanceof ConditionTypes.CustomEventCondition custom) {
            return custom.count();
        }
        return Integer.MAX_VALUE;
    }

    private static boolean stickySatisfied(StoryCondition condition, CurrentState state) {
        if (condition instanceof ConditionTypes.EnterDimensionCondition dimension) {
            return state.inDimension(dimension.dimension());
        }
            if (condition instanceof ConditionTypes.EnterBiomeCondition biome) {
                ResourceLocation id = biome.biome() == null ? biome.biomeTag() : biome.biome();
                return biome.biome() == null
                    ? state.inBiomeTag(id)
                    : state.inBiome(id);
            }
            if (condition instanceof ConditionTypes.EnterStructureCondition structure) {
                ResourceLocation id = structure.structure() == null
                    ? structure.structureTag()
                    : structure.structure();
                return structure.structure() == null
                    ? state.inStructureTag(id)
                    : state.inStructure(id);
            }
        if (condition instanceof ConditionTypes.AdvancementCondition advancement) {
            return state.advancementDone(advancement.advancement());
        }
        if (condition instanceof ConditionTypes.LocationCondition location) {
            return state.atLocation(
                location.dimension(),
                location.x(),
                location.y(),
                location.z(),
                location.radius());
        }
        return false;
    }

    private static boolean dynamicSatisfied(
        StoryCondition condition,
        StoryProgress progress,
        WorldStoryState world,
        CurrentState state
    ) {
        if (condition instanceof ConditionTypes.InventoryCondition inventory) {
            return state.inventoryCount(inventory.item()) >= inventory.count();
        }
        if (condition instanceof ConditionTypes.FlagCondition flag) {
            return flag.scope() == StoryCondition.Scope.PLAYER
                ? progress.getFlag(flag.flag()) == flag.value()
                : world.getFlag(flag.flag()) == flag.value();
        }
        return false;
    }
}
