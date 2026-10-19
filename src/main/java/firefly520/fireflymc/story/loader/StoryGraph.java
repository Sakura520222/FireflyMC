package firefly520.fireflymc.story.loader;

import firefly520.fireflymc.story.definition.QuestDefinition;
import firefly520.fireflymc.story.action.ActionTypes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** The immutable static quest-unlock graph built during prepare. */
public record StoryGraph(
    Map<ResourceLocation, List<ResourceLocation>> unlockEdges,
    Set<ResourceLocation> initialQuests
) {
    public StoryGraph {
        unlockEdges = immutableEdges(unlockEdges);
        initialQuests = immutableQuests(initialQuests);
    }

    public static StoryGraph fromQuests(Map<ResourceLocation, QuestDefinition> quests) {
        Objects.requireNonNull(quests, "quests");
        Map<ResourceLocation, LinkedHashSet<ResourceLocation>> edges = new LinkedHashMap<>();
        Set<ResourceLocation> initial = new LinkedHashSet<>();

        quests.keySet().stream()
            .sorted(Comparator.comparing(ResourceLocation::toString))
            .forEach(id -> edges.put(id, new LinkedHashSet<>()));
        quests.values().stream()
            .sorted(Comparator.comparing(QuestDefinition::id, Comparator.comparing(ResourceLocation::toString)))
            .forEach(quest -> {
                if (quest.initial()) {
                    initial.add(quest.id());
                }
                for (var action : quest.onComplete()) {
                    if (action instanceof ActionTypes.UnlockQuestAction unlock) {
                        edges.get(quest.id()).add(unlock.quest());
                    }
                }
            });

        Map<ResourceLocation, List<ResourceLocation>> copiedEdges = new LinkedHashMap<>();
        edges.forEach((source, targets) -> {
            List<ResourceLocation> sortedTargets = targets.stream()
                .sorted(Comparator.comparing(ResourceLocation::toString))
                .toList();
            copiedEdges.put(source, sortedTargets);
        });
        return new StoryGraph(copiedEdges, initial);
    }

    public List<ResourceLocation> outgoing(ResourceLocation quest) {
        Objects.requireNonNull(quest, "quest");
        return unlockEdges.getOrDefault(quest, List.of());
    }

    public Optional<List<ResourceLocation>> findCycle() {
        Map<ResourceLocation, VisitState> states = new LinkedHashMap<>();
        Map<ResourceLocation, ResourceLocation> parents = new LinkedHashMap<>();
        Deque<ResourceLocation> stack = new ArrayDeque<>();

        for (ResourceLocation start : sortedKeys()) {
            if (states.containsKey(start)) {
                continue;
            }
            states.put(start, VisitState.VISITING);
            parents.put(start, null);
            stack.push(start);

            while (!stack.isEmpty()) {
                ResourceLocation current = stack.peek();
                boolean descended = false;
                for (ResourceLocation target : unlockEdges.getOrDefault(current, List.of())) {
                    VisitState state = states.get(target);
                    if (state == null) {
                        states.put(target, VisitState.VISITING);
                        parents.put(target, current);
                        stack.push(target);
                        descended = true;
                        break;
                    }
                    if (state == VisitState.VISITING) {
                        return Optional.of(cycleFrom(target, current, parents));
                    }
                }
                if (!descended) {
                    states.put(current, VisitState.VISITED);
                    stack.pop();
                }
            }
        }
        return Optional.empty();
    }

    private List<ResourceLocation> sortedKeys() {
        return unlockEdges.keySet().stream()
            .sorted(Comparator.comparing(ResourceLocation::toString))
            .toList();
    }

    private static List<ResourceLocation> cycleFrom(
        ResourceLocation entry,
        ResourceLocation current,
        Map<ResourceLocation, ResourceLocation> parents
    ) {
        List<ResourceLocation> result = new ArrayList<>();
        ResourceLocation node = current;
        while (!node.equals(entry)) {
            result.addFirst(node);
            node = parents.get(node);
        }
        result.addFirst(entry);
        return List.copyOf(result);
    }

    private static Map<ResourceLocation, List<ResourceLocation>> immutableEdges(
        Map<ResourceLocation, List<ResourceLocation>> values
    ) {
        LinkedHashMap<ResourceLocation, List<ResourceLocation>> result = new LinkedHashMap<>();
        values.entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().toString()))
            .forEach(entry -> result.put(entry.getKey(), List.copyOf(entry.getValue())));
        return Collections.unmodifiableMap(result);
    }

    private static Set<ResourceLocation> immutableQuests(Set<ResourceLocation> values) {
        LinkedHashSet<ResourceLocation> result = new LinkedHashSet<>();
        values.stream()
            .sorted(Comparator.comparing(ResourceLocation::toString))
            .forEach(result::add);
        return Collections.unmodifiableSet(result);
    }

    private enum VisitState {
        VISITING,
        VISITED
    }
}
