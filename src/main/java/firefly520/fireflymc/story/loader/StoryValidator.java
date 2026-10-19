package firefly520.fireflymc.story.loader;

import static firefly520.fireflymc.story.action.ActionTypes.GiveItemAction;
import static firefly520.fireflymc.story.action.ActionTypes.PlaySoundAction;
import static firefly520.fireflymc.story.action.ActionTypes.SetFlagAction;
import static firefly520.fireflymc.story.action.ActionTypes.SetVarAction;
import static firefly520.fireflymc.story.action.ActionTypes.TeleportAction;
import static firefly520.fireflymc.story.action.ActionTypes.UnlockQuestAction;
import static firefly520.fireflymc.story.condition.ConditionTypes.AdvancementCondition;
import static firefly520.fireflymc.story.condition.ConditionTypes.CustomEventCondition;
import static firefly520.fireflymc.story.condition.ConditionTypes.EnterBiomeCondition;
import static firefly520.fireflymc.story.condition.ConditionTypes.EnterDimensionCondition;
import static firefly520.fireflymc.story.condition.ConditionTypes.EnterStructureCondition;
import static firefly520.fireflymc.story.condition.ConditionTypes.FlagCondition;
import static firefly520.fireflymc.story.condition.ConditionTypes.InventoryCondition;
import static firefly520.fireflymc.story.condition.ConditionTypes.KillCondition;
import static firefly520.fireflymc.story.condition.ConditionTypes.LocationCondition;
import static firefly520.fireflymc.story.condition.ConditionTypes.PickupCondition;

import firefly520.fireflymc.story.action.StoryAction;
import firefly520.fireflymc.story.condition.StoryCondition;
import firefly520.fireflymc.story.definition.ChapterDefinition;
import firefly520.fireflymc.story.definition.QuestDefinition;
import firefly520.fireflymc.story.definition.StoryCodecs.StoryDefinitionException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;

/** Structural validation and apply-time concrete reference resolution for prepared candidates. */
public final class StoryValidator {
    private StoryValidator() {}

    public static void validate(PreparedStoryData prepared) {
        Objects.requireNonNull(prepared, "prepared");
        List<String> errors = new ArrayList<>();
        validateDefinitionOwnership(prepared, errors);

        Map<ResourceLocation, ChapterDefinition> chapters = prepared.chapters();
        Map<ResourceLocation, QuestDefinition> quests = prepared.quests();
        boolean hasInitialQuest = false;
        Map<StoryCondition.Scope, Map<String, PreparedStoryData.IndexDeclaration.Kind>> stateKinds =
            new EnumMap<>(StoryCondition.Scope.class);

        for (QuestDefinition quest : sortedQuests(quests)) {
            if (!chapters.containsKey(quest.chapter())) {
                errors.add(questFile(quest) + ": chapter references missing chapter " + quest.chapter());
            }
            if (quest.initial()) {
                hasInitialQuest = true;
                if (!quest.requirements().isEmpty()) {
                    errors.add(questFile(quest) + ": initial quest requirements must be empty");
                }
            }
            collectStateUses(quest, stateKinds, errors);
            validateUnlockTargets(quest, prepared, errors);
        }
        if (!hasInitialQuest) {
            errors.add("at least one initial quest is required");
        }

        prepared.graph().findCycle()
            .ifPresent(cycle -> errors.add(cycleDiagnostic(cycle, quests)));

        if (!errors.isEmpty()) {
            throw new StoryDefinitionException(String.join("\n", errors));
        }
    }

    static ResolvedReferences validateResources(
        PreparedStoryData prepared,
        StoryReloadListener.ReferenceResolver resolver
    ) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(resolver, "resolver");

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<PreparedStoryData.IndexDeclaration, Set<ResourceLocation>> targets =
            new LinkedHashMap<>();
        for (PreparedStoryData.IndexDeclaration declaration : prepared.indexDeclarations()) {
            ResourceLocation target = ResourceLocation.tryParse(declaration.target());
            if (target == null) {
                errors.add(resourcePath(prepared, declaration)
                    + ": target is not a valid ResourceLocation: "
                    + declaration.target());
                targets.put(declaration, Set.of());
                continue;
            }

            switch (declaration.kind()) {
                case ITEM_TAG, ENTITY_TAG, BIOME_TAG, STRUCTURE_TAG -> {
                    String context = resourcePath(prepared, declaration);
                    boolean exists = resolve(() -> resolver.tagExists(declaration.kind(), target),
                        context + ": reference resolver failed checking tag " + target);
                    if (!exists) {
                        errors.add(context + ": missing tag " + target);
                        targets.put(declaration, Set.of());
                    } else {
                        Set<ResourceLocation> members = sorted(resolve(
                            () -> resolver.tagMembers(declaration.kind(), target),
                            context + ": reference resolver failed expanding tag " + target));
                        if (members.isEmpty()) {
                            warnings.add(context + ": tag " + target
                                + " is empty; its condition remains unmatchable");
                        }
                        targets.put(declaration, members);
                    }
                }
                case ITEM, ENTITY, DIMENSION, BIOME, STRUCTURE, SOUND -> {
                    String context = resourcePath(prepared, declaration);
                    boolean exists = resolve(() -> resolver.exists(declaration.kind(), target),
                        context + ": reference resolver failed checking "
                            + declaration.kind().name().toLowerCase() + " " + target);
                    if (!exists) {
                        errors.add(context + ": missing "
                            + declaration.kind().name().toLowerCase() + " " + target);
                    }
                    targets.put(declaration, Set.of(target));
                }
                case ADVANCEMENT -> {
                    String context = resourcePath(prepared, declaration);
                    boolean exists = resolve(() -> resolver.advancementExists(target),
                        context + ": reference resolver failed checking advancement " + target);
                    if (!exists) {
                        errors.add(context + ": missing advancement " + target);
                    }
                    targets.put(declaration, Set.of(target));
                }
                case CUSTOM_EVENT, FLAG, VAR -> targets.put(declaration, Set.of(target));
            }
        }

        if (!errors.isEmpty()) {
            throw new StoryDefinitionException(String.join("\n", errors));
        }
        return new ResolvedReferences(targets, warnings);
    }

    private static String resourcePath(
        PreparedStoryData prepared,
        PreparedStoryData.IndexDeclaration declaration
    ) {
        QuestDefinition quest = prepared.quests().get(declaration.owner());
        String file = quest == null ? declaration.owner().toString() : questFile(quest);
        return file + ": " + declaration.field();
    }

    private static <T> T resolve(Supplier<T> lookup, String context) {
        try {
            return lookup.get();
        } catch (RuntimeException exception) {
            throw new StoryDefinitionException(context, exception);
        }
    }

    private static Set<ResourceLocation> sorted(Set<ResourceLocation> values) {
        LinkedHashSet<ResourceLocation> result = new LinkedHashSet<>();
        values.stream()
            .sorted(Comparator.comparing(ResourceLocation::toString))
            .forEach(result::add);
        return Set.copyOf(result);
    }

    record ResolvedReferences(
        Map<PreparedStoryData.IndexDeclaration, Set<ResourceLocation>> targets,
        List<String> emptyTagWarnings
    ) {
        ResolvedReferences {
            targets = Collections.unmodifiableMap(new LinkedHashMap<>(targets));
            emptyTagWarnings = List.copyOf(emptyTagWarnings);
        }
    }

    static List<PreparedStoryData.IndexDeclaration> indexDeclarations(
        Map<ResourceLocation, QuestDefinition> quests
    ) {
        List<PreparedStoryData.IndexDeclaration> result = new ArrayList<>();
        for (QuestDefinition quest : sortedQuests(quests)) {
            for (int i = 0; i < quest.requirements().size(); i++) {
                var requirement = quest.requirements().get(i);
                addConditionDeclarations(
                    result, quest.id(), "requirements[" + i + "]", requirement.condition());
            }
            for (int i = 0; i < quest.onComplete().size(); i++) {
                addActionDeclarations(
                    result, quest.id(), "on_complete[" + i + "]", quest.onComplete().get(i));
            }
        }
        return result.stream()
            .sorted(Comparator.comparing(PreparedStoryData.IndexDeclaration::owner,
                Comparator.comparing(ResourceLocation::toString))
                .thenComparing(PreparedStoryData.IndexDeclaration::field)
                .thenComparing(PreparedStoryData.IndexDeclaration::kind)
                .thenComparing(declaration -> declaration.scope() == null
                    ? ""
                    : declaration.scope().serializedName())
                .thenComparing(PreparedStoryData.IndexDeclaration::target))
            .toList();
    }

    private static void validateDefinitionOwnership(PreparedStoryData prepared, List<String> errors) {
        prepared.chapters().forEach((id, chapter) -> {
            if (!id.equals(chapter.id())) {
                errors.add(chapterFile(id) + ": id does not match the loaded definition " + chapter.id());
            }
        });
        prepared.quests().forEach((id, quest) -> {
            if (!id.equals(quest.id())) {
                errors.add(questFile(id) + ": id does not match the loaded definition " + quest.id());
            }
        });
    }

    private static void validateUnlockTargets(
        QuestDefinition quest,
        PreparedStoryData prepared,
        List<String> errors
    ) {
        for (int i = 0; i < quest.onComplete().size(); i++) {
            StoryAction action = quest.onComplete().get(i);
            if (action instanceof UnlockQuestAction unlock
                && !prepared.quests().containsKey(unlock.quest())) {
                errors.add(questFile(quest) + ": on_complete[" + i + "].quest references missing quest "
                    + unlock.quest());
            }
        }
    }

    private static String cycleDiagnostic(
        List<ResourceLocation> cycle,
        Map<ResourceLocation, QuestDefinition> quests
    ) {
        List<String> edges = new ArrayList<>();
        for (int i = 0; i < cycle.size(); i++) {
            ResourceLocation source = cycle.get(i);
            ResourceLocation target = cycle.get((i + 1) % cycle.size());
            QuestDefinition quest = quests.get(source);
            if (quest == null) {
                throw new IllegalStateException("Cycle source is not a loaded quest: " + source);
            }
            edges.add(questFile(quest) + ": on_complete[" + unlockField(quest, target)
                + "].quest -> " + target);
        }
        return "unlock cycle: " + String.join("; ", edges);
    }

    private static int unlockField(QuestDefinition quest, ResourceLocation target) {
        for (int i = 0; i < quest.onComplete().size(); i++) {
            StoryAction action = quest.onComplete().get(i);
            if (action instanceof UnlockQuestAction unlock && unlock.quest().equals(target)) {
                return i;
            }
        }
        throw new IllegalStateException(
            "Cycle edge is not present in on_complete: " + quest.id() + " -> " + target);
    }

    private static void collectStateUses(
        QuestDefinition quest,
        Map<StoryCondition.Scope, Map<String, PreparedStoryData.IndexDeclaration.Kind>> stateKinds,
        List<String> errors
    ) {
        for (int i = 0; i < quest.requirements().size(); i++) {
            var condition = quest.requirements().get(i).condition();
            if (condition instanceof FlagCondition flag) {
                recordStateUse(
                    quest, "requirements[" + i + "].flag", flag.flag(),
                    PreparedStoryData.IndexDeclaration.Kind.FLAG, flag.scope(), stateKinds, errors);
            }
        }
        for (int i = 0; i < quest.onComplete().size(); i++) {
            StoryAction action = quest.onComplete().get(i);
            if (action instanceof SetFlagAction flag) {
                recordStateUse(
                    quest, "on_complete[" + i + "].flag", flag.flag(),
                    PreparedStoryData.IndexDeclaration.Kind.FLAG, flag.scope(), stateKinds, errors);
            } else if (action instanceof SetVarAction var) {
                recordStateUse(
                    quest, "on_complete[" + i + "].var", var.var(),
                    PreparedStoryData.IndexDeclaration.Kind.VAR, StoryCondition.Scope.PLAYER,
                    stateKinds, errors);
            }
        }
    }

    private static void recordStateUse(
        QuestDefinition quest,
        String field,
        String name,
        PreparedStoryData.IndexDeclaration.Kind kind,
        StoryCondition.Scope scope,
        Map<StoryCondition.Scope, Map<String, PreparedStoryData.IndexDeclaration.Kind>> stateKinds,
        List<String> errors
    ) {
        if (!StoryCondition.isValidStateName(name)) {
            errors.add(questFile(quest) + ": " + field + " is not a valid state name: " + name);
            return;
        }
        PreparedStoryData.IndexDeclaration.Kind previous = stateKinds
            .computeIfAbsent(scope, ignored -> new LinkedHashMap<>())
            .putIfAbsent(name, kind);
        if (previous != null && previous != kind) {
            errors.add(questFile(quest) + ": " + field + " conflicts with " + previous.name().toLowerCase()
                + " name " + name + " in scope " + scope.serializedName());
        }
    }

    private static void addConditionDeclarations(
        List<PreparedStoryData.IndexDeclaration> result,
        ResourceLocation owner,
        String field,
        StoryCondition condition
    ) {
        if (condition instanceof PickupCondition value) {
            add(result, owner, field, PreparedStoryData.IndexDeclaration.Kind.ITEM, value.item().toString());
        } else if (condition instanceof KillCondition value) {
            if (value.entity() != null) {
                add(result, owner, field, PreparedStoryData.IndexDeclaration.Kind.ENTITY,
                    value.entity().toString());
            } else {
                add(result, owner, field, PreparedStoryData.IndexDeclaration.Kind.ENTITY_TAG,
                    value.entityTag().toString());
            }
        } else if (condition instanceof CustomEventCondition value) {
            add(result, owner, field, PreparedStoryData.IndexDeclaration.Kind.CUSTOM_EVENT,
                value.event().toString());
        } else if (condition instanceof EnterDimensionCondition value) {
            add(result, owner, field, PreparedStoryData.IndexDeclaration.Kind.DIMENSION,
                value.dimension().toString());
        } else if (condition instanceof EnterBiomeCondition value) {
            if (value.biome() != null) {
                add(result, owner, field, PreparedStoryData.IndexDeclaration.Kind.BIOME,
                    value.biome().toString());
            } else {
                add(result, owner, field, PreparedStoryData.IndexDeclaration.Kind.BIOME_TAG,
                    value.biomeTag().toString());
            }
        } else if (condition instanceof EnterStructureCondition value) {
            if (value.structure() != null) {
                add(result, owner, field, PreparedStoryData.IndexDeclaration.Kind.STRUCTURE,
                    value.structure().toString());
            } else {
                add(result, owner, field, PreparedStoryData.IndexDeclaration.Kind.STRUCTURE_TAG,
                    value.structureTag().toString());
            }
        } else if (condition instanceof AdvancementCondition value) {
            add(result, owner, field, PreparedStoryData.IndexDeclaration.Kind.ADVANCEMENT,
                value.advancement().toString());
        } else if (condition instanceof LocationCondition value) {
            add(result, owner, field, PreparedStoryData.IndexDeclaration.Kind.DIMENSION,
                value.dimension().toString());
        } else if (condition instanceof InventoryCondition value) {
            add(result, owner, field, PreparedStoryData.IndexDeclaration.Kind.ITEM,
                value.item().toString());
        } else if (condition instanceof FlagCondition value) {
            add(result, owner, field, PreparedStoryData.IndexDeclaration.Kind.FLAG,
                value.flag(), value.scope());
        }
    }

    private static void addActionDeclarations(
        List<PreparedStoryData.IndexDeclaration> result,
        ResourceLocation owner,
        String field,
        StoryAction action
    ) {
        if (action instanceof SetFlagAction value) {
            add(result, owner, field, PreparedStoryData.IndexDeclaration.Kind.FLAG,
                value.flag(), value.scope());
        } else if (action instanceof SetVarAction value) {
            add(result, owner, field, PreparedStoryData.IndexDeclaration.Kind.VAR,
                value.var(), StoryCondition.Scope.PLAYER);
        } else if (action instanceof GiveItemAction value) {
            add(result, owner, field, PreparedStoryData.IndexDeclaration.Kind.ITEM, value.item().toString());
        } else if (action instanceof PlaySoundAction value) {
            add(result, owner, field, PreparedStoryData.IndexDeclaration.Kind.SOUND,
                value.sound().toString());
        } else if (action instanceof TeleportAction value) {
            add(result, owner, field, PreparedStoryData.IndexDeclaration.Kind.DIMENSION,
                value.dimension().toString());
        }
    }

    private static void add(
        List<PreparedStoryData.IndexDeclaration> result,
        ResourceLocation owner,
        String field,
        PreparedStoryData.IndexDeclaration.Kind kind,
        String target
    ) {
        add(result, owner, field, kind, target, null);
    }

    private static void add(
        List<PreparedStoryData.IndexDeclaration> result,
        ResourceLocation owner,
        String field,
        PreparedStoryData.IndexDeclaration.Kind kind,
        String target,
        StoryCondition.Scope scope
    ) {
        result.add(new PreparedStoryData.IndexDeclaration(owner, field, kind, target, scope));
    }

    private static List<QuestDefinition> sortedQuests(Map<ResourceLocation, QuestDefinition> quests) {
        return quests.values().stream()
            .sorted(Comparator.comparing(QuestDefinition::id, Comparator.comparing(ResourceLocation::toString)))
            .toList();
    }

    private static String chapterFile(ResourceLocation id) {
        return "data/" + id.getNamespace() + "/story/chapters/" + id.getPath() + ".json";
    }

    private static String questFile(ResourceLocation id) {
        return "data/" + id.getNamespace() + "/story/quests/" + id.getPath() + ".json";
    }

    private static String questFile(QuestDefinition quest) {
        return questFile(quest.id());
    }
}
