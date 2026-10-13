package firefly520.fireflymc.story.engine;

import firefly520.fireflymc.story.loader.PreparedStoryData;
import firefly520.fireflymc.story.condition.StoryCondition;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * An immutable map from concrete event keys to interested quests and declarations. Tag conditions
 * must already have been expanded by reload apply; runtime events never carry tags.
 */
public final class InterestIndex {
    private final Map<StoryEventType, Map<ResourceLocation, Set<ResourceLocation>>> interests;
    private final Map<StoryEventType, Map<ScopedKey, Set<ResourceLocation>>> scopedInterests;
    private final Map<StoryEventType, Map<ResourceLocation, Set<DeclarationMatch>>> declarationMatches;
    private final Map<StoryEventType, Map<ScopedKey, Set<DeclarationMatch>>> scopedDeclarationMatches;

    private InterestIndex(
        Map<StoryEventType, Map<ResourceLocation, Set<ResourceLocation>>> interests,
        Map<StoryEventType, Map<ScopedKey, Set<ResourceLocation>>> scopedInterests,
        Map<StoryEventType, Map<ResourceLocation, Set<DeclarationMatch>>> declarationMatches,
        Map<StoryEventType, Map<ScopedKey, Set<DeclarationMatch>>> scopedDeclarationMatches
    ) {
        Map<StoryEventType, Map<ResourceLocation, Set<ResourceLocation>>> copied = new EnumMap<>(StoryEventType.class);
        interests.forEach((type, keys) -> copied.put(type, copyKeys(keys)));
        for (StoryEventType type : StoryEventType.values()) {
            copied.putIfAbsent(type, Map.of());
        }
        this.interests = Collections.unmodifiableMap(copied);

        Map<StoryEventType, Map<ScopedKey, Set<ResourceLocation>>> copiedScoped =
            new EnumMap<>(StoryEventType.class);
        scopedInterests.forEach((type, keys) -> copiedScoped.put(type, copyScopedKeys(keys)));
        for (StoryEventType type : StoryEventType.values()) {
            copiedScoped.putIfAbsent(type, Map.of());
        }
        this.scopedInterests = Collections.unmodifiableMap(copiedScoped);

        Map<StoryEventType, Map<ResourceLocation, Set<DeclarationMatch>>> copiedMatches =
            new EnumMap<>(StoryEventType.class);
        declarationMatches.forEach((type, keys) -> copiedMatches.put(type, copyMatches(keys)));
        for (StoryEventType type : StoryEventType.values()) {
            copiedMatches.putIfAbsent(type, Map.of());
        }
        this.declarationMatches = Collections.unmodifiableMap(copiedMatches);

        Map<StoryEventType, Map<ScopedKey, Set<DeclarationMatch>>> copiedScopedMatches =
            new EnumMap<>(StoryEventType.class);
        scopedDeclarationMatches.forEach(
            (type, keys) -> copiedScopedMatches.put(type, copyScopedMatches(keys)));
        for (StoryEventType type : StoryEventType.values()) {
            copiedScopedMatches.putIfAbsent(type, Map.of());
        }
        this.scopedDeclarationMatches = Collections.unmodifiableMap(copiedScopedMatches);
    }

    public static InterestIndex empty() {
        return new InterestIndex(Map.of(), Map.of(), Map.of(), Map.of());
    }

    public static InterestIndex fromDeclarations(
        Map<PreparedStoryData.IndexDeclaration, Set<ResourceLocation>> concreteTargets
    ) {
        Objects.requireNonNull(concreteTargets, "concreteTargets");
        Map<StoryEventType, Map<ResourceLocation, Set<ResourceLocation>>> values =
            new EnumMap<>(StoryEventType.class);
        Map<StoryEventType, Map<ScopedKey, Set<ResourceLocation>>> scopedValues =
            new EnumMap<>(StoryEventType.class);
        Map<StoryEventType, Map<ResourceLocation, Set<DeclarationMatch>>> declarationMatches =
            new EnumMap<>(StoryEventType.class);
        Map<StoryEventType, Map<ScopedKey, Set<DeclarationMatch>>> scopedDeclarationMatches =
            new EnumMap<>(StoryEventType.class);

        concreteTargets.forEach((declaration, targets) -> {
            Optional<StoryEventType> eventType = eventType(declaration);
            if (eventType.isEmpty()) {
                return;
            }
            for (ResourceLocation target : sorted(targets)) {
                if (isScoped(declaration.kind())) {
                    Map<ScopedKey, Set<ResourceLocation>> keys = scopedValues.computeIfAbsent(
                        eventType.get(), ignored -> new LinkedHashMap<>());
                    keys.computeIfAbsent(new ScopedKey(declaration.scope(), target),
                            ignored -> new LinkedHashSet<>())
                        .add(declaration.owner());
                    scopedDeclarationMatches.computeIfAbsent(
                            eventType.get(), ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(new ScopedKey(declaration.scope(), target),
                            ignored -> new LinkedHashSet<>())
                        .add(new DeclarationMatch(declaration.owner(), declaration.field()));
                } else {
                    Map<ResourceLocation, Set<ResourceLocation>> keys = values.computeIfAbsent(
                        eventType.get(), ignored -> new LinkedHashMap<>());
                    keys.computeIfAbsent(target, ignored -> new LinkedHashSet<>())
                        .add(declaration.owner());
                    declarationMatches.computeIfAbsent(
                            eventType.get(), ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(target, ignored -> new LinkedHashSet<>())
                        .add(new DeclarationMatch(declaration.owner(), declaration.field()));
                }
            }
        });

        return new InterestIndex(
            values, scopedValues, declarationMatches, scopedDeclarationMatches);
    }

    /**
     * Returns candidates for an unscoped concrete key. Flag and variable signals require a scope;
     * calling this overload for them is a programming error.
     */
    public Set<ResourceLocation> candidates(StoryEventType type, ResourceLocation key) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(key, "key");
        requireUnscoped(type);
        return interests.getOrDefault(type, Map.of())
            .getOrDefault(key, Set.of());
    }

    /** Returns candidates for player/world flag or player variable signals. */
    public Set<ResourceLocation> candidates(
        StoryEventType type,
        StoryCondition.Scope scope,
        ResourceLocation key
    ) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        requireScoped(type);
        if (type == StoryEventType.VAR_CHANGED && scope != StoryCondition.Scope.PLAYER) {
            throw new IllegalArgumentException("VAR_CHANGED only supports player scope");
        }
        return scopedInterests.getOrDefault(type, Map.of())
            .getOrDefault(new ScopedKey(scope, key), Set.of());
    }

    /**
     * Returns the declarations (owner quest plus requirement/action field) matched by an unscoped
     * concrete event key. This is intentionally additive to {@link #candidates(StoryEventType,
     * ResourceLocation)}: callers keep using candidates for queueing while evaluators use this
     * lookup to avoid advancing disjoint tag requirements that only share a candidate quest.
     */
    public Set<DeclarationMatch> matches(StoryEventType type, ResourceLocation key) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(key, "key");
        requireUnscoped(type);
        return declarationMatches.getOrDefault(type, Map.of()).getOrDefault(key, Set.of());
    }

    /**
     * Returns the declarations matched by a scoped flag or player-variable event key.
     */
    public Set<DeclarationMatch> matches(
        StoryEventType type,
        StoryCondition.Scope scope,
        ResourceLocation key
    ) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        requireScoped(type);
        if (type == StoryEventType.VAR_CHANGED && scope != StoryCondition.Scope.PLAYER) {
            throw new IllegalArgumentException("VAR_CHANGED only supports player scope");
        }
        return scopedDeclarationMatches.getOrDefault(type, Map.of())
            .getOrDefault(new ScopedKey(scope, key), Set.of());
    }

    public Set<ResourceLocation> keys(StoryEventType type) {
        Objects.requireNonNull(type, "type");
        if (isScoped(type)) {
            Set<ResourceLocation> result = scopedInterests.getOrDefault(type, Map.of()).keySet().stream()
                .map(ScopedKey::key)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            return Collections.unmodifiableSet(result);
        }
        return interests.getOrDefault(type, Map.of()).keySet();
    }

    private static Optional<StoryEventType> eventType(PreparedStoryData.IndexDeclaration declaration) {
        boolean requirement = declaration.field().startsWith("requirements[");
        boolean action = declaration.field().startsWith("on_complete[");
        if (!requirement && !action) {
            return Optional.empty();
        }

        if (action) {
            return declaration.kind() == PreparedStoryData.IndexDeclaration.Kind.FLAG
                || declaration.kind() == PreparedStoryData.IndexDeclaration.Kind.VAR
                    ? Optional.of(eventType(declaration.kind()))
                    : Optional.empty();
        }
        return Optional.ofNullable(eventTypeOrNull(declaration.kind()));
    }

    private static StoryEventType eventType(PreparedStoryData.IndexDeclaration.Kind kind) {
        StoryEventType result = eventTypeOrNull(kind);
        if (result == null) {
            throw new IllegalArgumentException("Kind has no action signal: " + kind);
        }
        return result;
    }

    private static StoryEventType eventTypeOrNull(PreparedStoryData.IndexDeclaration.Kind kind) {
        return switch (kind) {
            case ITEM, ITEM_TAG -> StoryEventType.ITEM_PICKUP;
            case ENTITY, ENTITY_TAG -> StoryEventType.ENTITY_KILL;
            case CUSTOM_EVENT -> StoryEventType.CUSTOM_EVENT;
            case DIMENSION -> StoryEventType.DIMENSION_ENTER;
            case BIOME, BIOME_TAG -> StoryEventType.BIOME_ENTER;
            case STRUCTURE, STRUCTURE_TAG -> StoryEventType.STRUCTURE_ENTER;
            case ADVANCEMENT -> StoryEventType.ADVANCEMENT_EARNED;
            case FLAG -> StoryEventType.FLAG_CHANGED;
            case VAR -> StoryEventType.VAR_CHANGED;
            case SOUND -> null;
        };
    }

    private static boolean isScoped(PreparedStoryData.IndexDeclaration.Kind kind) {
        return kind == PreparedStoryData.IndexDeclaration.Kind.FLAG
            || kind == PreparedStoryData.IndexDeclaration.Kind.VAR;
    }

    private static boolean isScoped(StoryEventType type) {
        return type == StoryEventType.FLAG_CHANGED || type == StoryEventType.VAR_CHANGED;
    }

    private static void requireUnscoped(StoryEventType type) {
        if (isScoped(type)) {
            throw new IllegalArgumentException(
                type + " requires an explicit player/world scope");
        }
    }

    private static void requireScoped(StoryEventType type) {
        if (!isScoped(type)) {
            throw new IllegalArgumentException(type + " does not carry a player/world scope");
        }
    }

    private static Map<ResourceLocation, Set<ResourceLocation>> copyKeys(
        Map<ResourceLocation, Set<ResourceLocation>> values
    ) {
        Map<ResourceLocation, Set<ResourceLocation>> result = new LinkedHashMap<>();
        values.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
            .forEach(entry -> result.put(entry.getKey(), sorted(entry.getValue())));
        return Collections.unmodifiableMap(result);
    }

    private static Set<ResourceLocation> sorted(Set<ResourceLocation> values) {
        LinkedHashSet<ResourceLocation> result = new LinkedHashSet<>();
        values.stream()
            .sorted(Comparator.comparing(ResourceLocation::toString))
            .forEach(result::add);
        return Collections.unmodifiableSet(result);
    }

    private static Map<ScopedKey, Set<ResourceLocation>> copyScopedKeys(
        Map<ScopedKey, Set<ResourceLocation>> values
    ) {
        Map<ScopedKey, Set<ResourceLocation>> result = new LinkedHashMap<>();
        values.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(ScopedKey::toString)))
            .forEach(entry -> result.put(entry.getKey(), sorted(entry.getValue())));
        return Collections.unmodifiableMap(result);
    }

    private static Map<ResourceLocation, Set<DeclarationMatch>> copyMatches(
        Map<ResourceLocation, Set<DeclarationMatch>> values
    ) {
        Map<ResourceLocation, Set<DeclarationMatch>> result = new LinkedHashMap<>();
        values.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
            .forEach(entry -> result.put(entry.getKey(), sortedMatches(entry.getValue())));
        return Collections.unmodifiableMap(result);
    }

    private static Map<ScopedKey, Set<DeclarationMatch>> copyScopedMatches(
        Map<ScopedKey, Set<DeclarationMatch>> values
    ) {
        Map<ScopedKey, Set<DeclarationMatch>> result = new LinkedHashMap<>();
        values.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(ScopedKey::toString)))
            .forEach(entry -> result.put(entry.getKey(), sortedMatches(entry.getValue())));
        return Collections.unmodifiableMap(result);
    }

    private static Set<DeclarationMatch> sortedMatches(Set<DeclarationMatch> values) {
        LinkedHashSet<DeclarationMatch> result = new LinkedHashSet<>();
        values.stream()
            .sorted(Comparator.comparing(
                    (DeclarationMatch match) -> match.owner().toString())
                .thenComparing(DeclarationMatch::field))
            .forEach(result::add);
        return Collections.unmodifiableSet(result);
    }

    private record ScopedKey(StoryCondition.Scope scope, ResourceLocation key) {
        ScopedKey {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(key, "key");
        }
    }

    /** Immutable identity of one declaration matched by an event. */
    public record DeclarationMatch(ResourceLocation owner, String field) {
        public DeclarationMatch {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(field, "field");
            if (field.isBlank()) {
                throw new IllegalArgumentException("field must not be blank");
            }
        }
    }
}
