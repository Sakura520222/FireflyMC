package firefly520.fireflymc.story.loader;

import firefly520.fireflymc.story.condition.StoryCondition;
import firefly520.fireflymc.story.definition.ChapterDefinition;
import firefly520.fireflymc.story.definition.QuestDefinition;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * The complete, immutable result of the pure prepare phase. It deliberately does not resolve
 * registries or tags and is not a runtime snapshot.
 */
public final class PreparedStoryData {
    private final Map<ResourceLocation, ChapterDefinition> chapters;
    private final Map<ResourceLocation, QuestDefinition> quests;
    private final StoryGraph graph;
    private final List<IndexDeclaration> indexDeclarations;
    private final List<CanonicalDefinition> canonicalDefinitions;

    PreparedStoryData(
        Map<ResourceLocation, ChapterDefinition> chapters,
        Map<ResourceLocation, QuestDefinition> quests,
        StoryGraph graph,
        List<IndexDeclaration> indexDeclarations,
        List<CanonicalDefinition> canonicalDefinitions
    ) {
        this.chapters = immutableCopy(chapters);
        this.quests = immutableCopy(quests);
        this.graph = Objects.requireNonNull(graph, "graph");
        this.indexDeclarations = immutableCopy(indexDeclarations);
        this.canonicalDefinitions = immutableCopy(canonicalDefinitions);
    }

    public Map<ResourceLocation, ChapterDefinition> chapters() {
        return chapters;
    }

    public Map<ResourceLocation, QuestDefinition> quests() {
        return quests;
    }

    public StoryGraph graph() {
        return graph;
    }

    public List<IndexDeclaration> indexDeclarations() {
        return indexDeclarations;
    }

    List<CanonicalDefinition> canonicalDefinitions() {
        return canonicalDefinitions;
    }

    private static <K, V> Map<K, V> immutableCopy(Map<K, V> values) {
        LinkedHashMap<K, V> result = new LinkedHashMap<>();
        values.entrySet().stream()
            .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
            .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(result);
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return List.copyOf(values);
    }

    /**
     * A future InterestIndex input; apply resolves registry/tag members in a later task. Scope is
     * required for FLAG/VAR declarations and null for all other unresolved targets.
     */
    public record IndexDeclaration(
        ResourceLocation owner,
        String field,
        Kind kind,
        String target,
        StoryCondition.Scope scope
    ) {
        public IndexDeclaration {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(target, "target");
            boolean isStateKind = kind == Kind.FLAG || kind == Kind.VAR;
            if (isStateKind && scope == null) {
                throw new IllegalArgumentException("scope is required for " + kind);
            }
            if (!isStateKind && scope != null) {
                throw new IllegalArgumentException("scope is not valid for " + kind);
            }
        }

        public IndexDeclaration(
            ResourceLocation owner,
            String field,
            Kind kind,
            String target
        ) {
            this(owner, field, kind, target, null);
        }

        public enum Kind {
            ITEM,
            ITEM_TAG,
            ENTITY,
            ENTITY_TAG,
            CUSTOM_EVENT,
            DIMENSION,
            BIOME,
            BIOME_TAG,
            STRUCTURE,
            STRUCTURE_TAG,
            ADVANCEMENT,
            SOUND,
            FLAG,
            VAR
        }
    }

    record CanonicalDefinition(String kind, ResourceLocation id, String canonicalJson) {
        CanonicalDefinition {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(canonicalJson, "canonicalJson");
        }
    }
}
