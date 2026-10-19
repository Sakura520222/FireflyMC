package firefly520.fireflymc.story.engine;

import firefly520.fireflymc.story.definition.ChapterDefinition;
import firefly520.fireflymc.story.definition.QuestDefinition;
import firefly520.fireflymc.story.loader.StoryGraph;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** One immutable runtime generation; reload replaces this object with a single volatile write. */
public record StoryRuntimeSnapshot(
    Map<ResourceLocation, ChapterDefinition> chapters,
    Map<ResourceLocation, QuestDefinition> quests,
    InterestIndex interestIndex,
    StoryGraph graph,
    String revision
) {
    /**
     * SHA-256 of zero canonical definitions. The initial volatile snapshot therefore follows the
     * same revision rule as an installed reload generation.
     */
    private static final String EMPTY_REVISION =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    public StoryRuntimeSnapshot {
        chapters = immutableCopy(chapters);
        quests = immutableCopy(quests);
        interestIndex = Objects.requireNonNull(interestIndex, "interestIndex");
        graph = Objects.requireNonNull(graph, "graph");
        revision = Objects.requireNonNull(revision, "revision");
    }

    public static StoryRuntimeSnapshot empty() {
        return new StoryRuntimeSnapshot(
            Map.of(),
            Map.of(),
            InterestIndex.empty(),
            StoryGraph.fromQuests(Map.of()),
            EMPTY_REVISION);
    }

    private static <V> Map<ResourceLocation, V> immutableCopy(Map<ResourceLocation, V> values) {
        LinkedHashMap<ResourceLocation, V> result = new LinkedHashMap<>();
        values.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
            .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(result);
    }
}
