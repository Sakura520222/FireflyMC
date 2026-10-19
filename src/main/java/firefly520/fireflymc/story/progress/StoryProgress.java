package firefly520.fireflymc.story.progress;

import com.mojang.logging.LogUtils;
import firefly520.fireflymc.story.condition.ConditionPersistence;
import firefly520.fireflymc.story.condition.StoryCondition;
import firefly520.fireflymc.story.definition.QuestDefinition;
import firefly520.fireflymc.story.definition.RequirementDefinition;
import firefly520.fireflymc.story.engine.StoryRuntimeSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.util.INBTSerializable;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/** Server-authoritative per-player story state; all access is confined to its owner thread. */
public final class StoryProgress implements INBTSerializable<CompoundTag> {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Set<ResourceLocation> completedQuests = new LinkedHashSet<>();
    private final Set<ResourceLocation> activeQuests = new LinkedHashSet<>();
    private final Map<String, Boolean> flags = new LinkedHashMap<>();
    private final Map<String, Integer> vars = new LinkedHashMap<>();
    private final Map<RequirementProgressKey, Integer> counters = new LinkedHashMap<>();
    private final Set<RequirementProgressKey> satisfied = new LinkedHashSet<>();
    private final Set<ResourceLocation> warnedDeletedQuests = new LinkedHashSet<>();
    private final Thread owner;
    private final Consumer<ResourceLocation> deletedQuestWarningSink;
    @Nullable
    private ResourceLocation trackedQuest;

    public StoryProgress() {
        this(questId -> LOGGER.warn(
            "Story quest {} is deleted; its progress is retained, it will not be evaluated, and this warning will not repeat this session",
            questId));
    }

    StoryProgress(Consumer<ResourceLocation> deletedQuestWarningSink) {
        this.deletedQuestWarningSink = Objects.requireNonNull(
            deletedQuestWarningSink, "deletedQuestWarningSink");
        owner = Thread.currentThread();
    }

    public QuestState getQuestState(ResourceLocation questId) {
        requireOwnerThread();
        Objects.requireNonNull(questId, "questId");
        if (completedQuests.contains(questId)) {
            return QuestState.COMPLETED;
        }
        return activeQuests.contains(questId) ? QuestState.ACTIVE : QuestState.LOCKED;
    }

    public void activate(ResourceLocation questId) {
        requireOwnerThread();
        Objects.requireNonNull(questId, "questId");
        if (getQuestState(questId) != QuestState.LOCKED) {
            throw new IllegalStateException("Quest is not LOCKED: " + questId);
        }
        activeQuests.add(questId);
    }

    public void complete(ResourceLocation questId) {
        requireOwnerThread();
        Objects.requireNonNull(questId, "questId");
        if (getQuestState(questId) != QuestState.ACTIVE) {
            throw new IllegalStateException("Quest is not ACTIVE: " + questId);
        }
        activeQuests.remove(questId);
        completedQuests.add(questId);
        if (questId.equals(trackedQuest)) {
            trackedQuest = null;
        }
    }

    public int counter(RequirementProgressKey key) {
        requireOwnerThread();
        Objects.requireNonNull(key, "key");
        return counters.getOrDefault(key, 0);
    }

    public boolean incrementCounter(RequirementProgressKey key, int amount) {
        requireOwnerThread();
        Objects.requireNonNull(key, "key");
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be strictly positive");
        }
        int result = Math.addExact(counter(key), amount);
        counters.put(key, result);
        return true;
    }

    public boolean setCounter(RequirementProgressKey key, int value) {
        requireOwnerThread();
        Objects.requireNonNull(key, "key");
        int clamped = Math.max(0, value);
        int previous = counter(key);
        if (previous == clamped) {
            return false;
        }
        counters.put(key, clamped);
        return true;
    }

    public boolean addCounter(RequirementProgressKey key, int amount) {
        requireOwnerThread();
        Objects.requireNonNull(key, "key");
        long requested = (long) counter(key) + (long) amount;
        return setCounter(key, (int) Math.clamp(requested, 0, Integer.MAX_VALUE));
    }

    public boolean markSatisfied(RequirementProgressKey key) {
        requireOwnerThread();
        Objects.requireNonNull(key, "key");
        return satisfied.add(key);
    }

    public boolean isSatisfied(RequirementProgressKey key) {
        requireOwnerThread();
        Objects.requireNonNull(key, "key");
        return satisfied.contains(key);
    }

    public boolean getFlag(String name) {
        requireOwnerThread();
        requireStateName(name);
        return flags.getOrDefault(name, false);
    }

    public boolean setFlag(String name, boolean value) {
        requireOwnerThread();
        requireStateName(name);
        if (vars.containsKey(name)) {
            throw new IllegalArgumentException("name is already used by a variable: " + name);
        }
        Boolean previous = flags.get(name);
        if (previous == null) {
            if (!value) {
                return false;
            }
            flags.put(name, value);
            return true;
        }
        if (previous.booleanValue() == value) {
            return false;
        }
        flags.put(name, value);
        return true;
    }

    public int getVar(String name) {
        requireOwnerThread();
        requireStateName(name);
        return vars.getOrDefault(name, 0);
    }

    public boolean setVar(String name, int value, int min, int max) {
        requireOwnerThread();
        requireStateName(name);
        if (min > max) {
            throw new IllegalArgumentException("min must not exceed max");
        }
        if (flags.containsKey(name)) {
            throw new IllegalArgumentException("name is already used by a flag: " + name);
        }
        int clamped = clamp(value, min, max);
        if (clamped == getVar(name)) {
            return false;
        }
        vars.put(name, clamped);
        return true;
    }

    public boolean addVar(String name, int amount, int min, int max) {
        requireOwnerThread();
        requireStateName(name);
        if (min > max) {
            throw new IllegalArgumentException("min must not exceed max");
        }
        if (flags.containsKey(name)) {
            throw new IllegalArgumentException("name is already used by a flag: " + name);
        }
        int clamped = clamp((long) getVar(name) + (long) amount, min, max);
        int previous = getVar(name);
        if (clamped == previous) {
            return false;
        }
        vars.put(name, clamped);
        return true;
    }

    public void setTrackedQuest(@Nullable ResourceLocation questId) {
        requireOwnerThread();
        if (questId != null && getQuestState(questId) != QuestState.ACTIVE) {
            throw new IllegalArgumentException("tracked quest must be ACTIVE: " + questId);
        }
        trackedQuest = questId;
    }

    public Optional<ResourceLocation> trackedQuest() {
        requireOwnerThread();
        return Optional.ofNullable(trackedQuest);
    }

    public Set<ResourceLocation> completedQuests() {
        requireOwnerThread();
        return immutableQuests(completedQuests);
    }

    public Set<ResourceLocation> activeQuests() {
        requireOwnerThread();
        return immutableQuests(activeQuests);
    }

    public Map<String, Boolean> flags() {
        requireOwnerThread();
        return immutableFlags();
    }

    public Map<String, Integer> vars() {
        requireOwnerThread();
        return immutableVars();
    }

    public Map<RequirementProgressKey, Integer> counters() {
        requireOwnerThread();
        return immutableCounters();
    }

    public Set<RequirementProgressKey> satisfied() {
        requireOwnerThread();
        return immutableSatisfied();
    }

    public void reconcile(StoryRuntimeSnapshot snapshot) {
        requireOwnerThread();
        Objects.requireNonNull(snapshot, "snapshot");
        warnNewDeletedQuests(snapshot);

        Map<RequirementProgressKey, Integer> migratedCounters = new LinkedHashMap<>();
        Set<RequirementProgressKey> migratedSatisfied = new LinkedHashSet<>();

        counters.forEach((key, value) -> {
            if (!snapshot.quests().containsKey(key.questId())) {
                migratedCounters.put(key, value);
                return;
            }
            RequirementDefinition requirement = requirement(
                snapshot, key.questId(), key.requirementId());
            if (requirement != null
                && requirement.stateVersion() == key.stateVersion()
                && requirement.condition().persistence() == ConditionPersistence.COUNTER) {
                migratedCounters.put(key, value);
            }
        });
        satisfied.forEach(key -> {
            if (!snapshot.quests().containsKey(key.questId())) {
                migratedSatisfied.add(key);
                return;
            }
            RequirementDefinition requirement = requirement(
                snapshot, key.questId(), key.requirementId());
            if (requirement != null
                && requirement.stateVersion() == key.stateVersion()
                && requirement.condition().persistence() == ConditionPersistence.STICKY) {
                migratedSatisfied.add(key);
            }
        });

        counters.clear();
        counters.putAll(migratedCounters);
        satisfied.clear();
        satisfied.addAll(migratedSatisfied);
    }

    public void clearAndBootstrap(StoryRuntimeSnapshot snapshot) {
        requireOwnerThread();
        Objects.requireNonNull(snapshot, "snapshot");
        completedQuests.clear();
        activeQuests.clear();
        flags.clear();
        vars.clear();
        counters.clear();
        satisfied.clear();
        trackedQuest = null;
        reconcile(snapshot);
        snapshot.graph().initialQuests().forEach(initial -> {
            if (snapshot.quests().containsKey(initial)
                && getQuestState(initial) == QuestState.LOCKED) {
                activeQuests.add(initial);
            }
        });
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        requireOwnerThread();
        Objects.requireNonNull(provider, "provider");
        CompoundTag root = new CompoundTag();

        CompoundTag quests = new CompoundTag();
        quests.put("completed", questList(completedQuests));
        quests.put("active", questList(activeQuests));
        if (trackedQuest != null) {
            quests.putString("tracked", trackedQuest.toString());
        }
        root.put("quests", quests);

        CompoundTag flagTag = new CompoundTag();
        immutableFlags().forEach((name, value) -> flagTag.putInt(name, value ? 1 : 0));
        root.put("flags", flagTag);

        CompoundTag varTag = new CompoundTag();
        immutableVars().forEach(varTag::putInt);
        root.put("vars", varTag);

        CompoundTag requirementsTag = new CompoundTag();
        Map<String, CompoundTag> byQuest = new TreeMap<>();
        Map<String, Set<Integer>> versionsById = new HashMap<>();
        Set<RequirementProgressKey> keys = new LinkedHashSet<>();
        keys.addAll(counters.keySet());
        keys.addAll(satisfied);
        keys.stream()
            .sorted((left, right) -> requirementOrder(left).compareTo(requirementOrder(right)))
            .forEach(key -> {
                String identity = key.questId() + " -> " + key.requirementId();
                if (!versionsById.computeIfAbsent(identity, ignored -> new LinkedHashSet<>())
                    .add(key.stateVersion())) {
                    throw new IllegalStateException(
                        "duplicate requirement progress key: " + identity);
                }
                CompoundTag requirement = byQuest.computeIfAbsent(
                    key.questId().toString(), ignored -> new CompoundTag());
                if (requirement.contains(key.requirementId())) {
                    List<Integer> versions = new ArrayList<>(
                        versionsById.get(identity));
                    Collections.sort(versions);
                    throw new IllegalStateException(
                        "NBT requirement identity collision for " + identity
                            + " at versions " + versions);
                }

                CompoundTag value = new CompoundTag();
                value.putInt("state_version", key.stateVersion());
                if (counters.containsKey(key)) {
                    value.putInt("counter", counters.get(key));
                }
                if (satisfied.contains(key)) {
                    value.putInt("satisfied", 1);
                }
                requirement.put(key.requirementId(), value);
            });
        byQuest.forEach(requirementsTag::put);
        root.put("requirements", requirementsTag);

        return root;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        requireOwnerThread();
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(tag, "tag");

        CompoundTag quests = optionalCompound(tag, "quests", "quests");
        Set<ResourceLocation> parsedCompleted = readQuests(quests, "completed");
        Set<ResourceLocation> parsedActive = readQuests(quests, "active");
        ResourceLocation parsedTracked = readTracked(quests);

        CompoundTag flagTag = optionalCompound(tag, "flags", "flags");
        Map<String, Boolean> parsedFlags = new LinkedHashMap<>();
        for (String name : sortedNames(flagTag)) {
            requireStateName(name);
            if (flagTag.getTagType(name) != Tag.TAG_INT) {
                throw invalidType("flags." + name, "an int");
            }
            parsedFlags.put(name, flagTag.getInt(name) != 0);
        }

        CompoundTag varTag = optionalCompound(tag, "vars", "vars");
        Map<String, Integer> parsedVars = new LinkedHashMap<>();
        for (String name : sortedNames(varTag)) {
            requireStateName(name);
            if (varTag.getTagType(name) != Tag.TAG_INT) {
                throw invalidType("vars." + name, "an int");
            }
            if (parsedFlags.containsKey(name)) {
                throw new IllegalArgumentException("flag and variable names collide: " + name);
            }
            parsedVars.put(name, varTag.getInt(name));
        }

        Map<RequirementProgressKey, Integer> parsedCounters = new LinkedHashMap<>();
        Set<RequirementProgressKey> parsedSatisfied = new LinkedHashSet<>();
        CompoundTag requirementsTag = optionalCompound(
            tag, "requirements", "requirements");
        for (String questName : sortedNames(requirementsTag)) {
            CompoundTag questTag = optionalCompound(
                requirementsTag, questName, "requirements." + questName);
            ResourceLocation questId = parseResource(questName, "requirements");
            for (String requirementName : sortedNames(questTag)) {
                CompoundTag value = optionalCompound(
                    questTag,
                    requirementName,
                    "requirements." + questName + "." + requirementName
                );
                String path = "requirements." + questName + "." + requirementName;
                if (!value.contains("state_version", Tag.TAG_INT)
                    || value.getInt("state_version") < 1) {
                    throw new IllegalArgumentException(
                        path + ".state_version must be a positive int");
                }
                var key = new RequirementProgressKey(
                    questId, requirementName, value.getInt("state_version"));
                if (value.contains("counter")
                    && value.getTagType("counter") != Tag.TAG_INT) {
                    throw invalidType(path + ".counter", "an int");
                }
                if (value.contains("counter")) {
                    int count = value.getInt("counter");
                    if (count < 0) {
                        throw new IllegalArgumentException(
                            path + ".counter must not be negative");
                    }
                    parsedCounters.put(key, count);
                }
                if (value.contains("satisfied")
                    && value.getTagType("satisfied") != Tag.TAG_INT) {
                    throw invalidType(path + ".satisfied", "an int");
                }
                if (value.contains("satisfied")) {
                    int persisted = value.getInt("satisfied");
                    if (persisted != 0 && persisted != 1) {
                        throw new IllegalArgumentException(
                            path + ".satisfied must be 0 or 1");
                    }
                    if (persisted == 1) {
                        parsedSatisfied.add(key);
                    }
                }
            }
        }

        completedQuests.clear();
        completedQuests.addAll(parsedCompleted);
        activeQuests.clear();
        activeQuests.addAll(parsedActive);
        trackedQuest = parsedTracked;
        flags.clear();
        flags.putAll(parsedFlags);
        vars.clear();
        vars.putAll(parsedVars);
        counters.clear();
        counters.putAll(parsedCounters);
        satisfied.clear();
        satisfied.addAll(parsedSatisfied);
        warnedDeletedQuests.clear();
    }

    private void warnNewDeletedQuests(StoryRuntimeSnapshot snapshot) {
        Set<ResourceLocation> referenced = new TreeSet<>(
            (left, right) -> left.toString().compareTo(right.toString()));
        referenced.addAll(completedQuests);
        referenced.addAll(activeQuests);
        if (trackedQuest != null) {
            referenced.add(trackedQuest);
        }
        counters.keySet().forEach(key -> referenced.add(key.questId()));
        satisfied.forEach(key -> referenced.add(key.questId()));

        referenced.removeIf(snapshot.quests()::containsKey);
        for (ResourceLocation questId : referenced) {
            if (warnedDeletedQuests.add(questId)) {
                deletedQuestWarningSink.accept(questId);
            }
        }
    }

    private void requireOwnerThread() {
        Thread current = Thread.currentThread();
        if (owner != current) {
            throw new IllegalThreadStateException(
                "StoryProgress is confined to its attachment/construction thread");
        }
    }

    @Nullable
    private static RequirementDefinition requirement(
        StoryRuntimeSnapshot snapshot,
        ResourceLocation questId,
        String requirementId
    ) {
        QuestDefinition quest = snapshot.quests().get(questId);
        if (quest == null) {
            return null;
        }
        return quest.requirements().stream()
            .filter(candidate -> candidate.id().equals(requirementId))
            .findFirst()
            .orElse(null);
    }

    private static int clamp(long value, int min, int max) {
        if (min > max) {
            throw new IllegalArgumentException("min must not exceed max");
        }
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return (int) value;
    }

    private static void requireStateName(String name) {
        if (!StoryCondition.isValidStateName(name)) {
            throw new IllegalArgumentException("invalid story state name: " + name);
        }
    }

    private static Set<ResourceLocation> immutableQuests(Set<ResourceLocation> values) {
        Set<ResourceLocation> result = new LinkedHashSet<>();
        values.stream()
            .sorted((left, right) -> left.toString().compareTo(right.toString()))
            .forEach(result::add);
        return Collections.unmodifiableSet(result);
    }

    private Map<String, Boolean> immutableFlags() {
        Map<String, Boolean> result = new TreeMap<>();
        result.putAll(flags);
        return Collections.unmodifiableMap(result);
    }

    private Map<String, Integer> immutableVars() {
        Map<String, Integer> result = new TreeMap<>();
        result.putAll(vars);
        return Collections.unmodifiableMap(result);
    }

    private Map<RequirementProgressKey, Integer> immutableCounters() {
        Map<RequirementProgressKey, Integer> result = new LinkedHashMap<>();
        counters.keySet().stream()
            .sorted((left, right) -> requirementOrder(left).compareTo(requirementOrder(right)))
            .forEach(key -> result.put(key, counters.get(key)));
        return Collections.unmodifiableMap(result);
    }

    private Set<RequirementProgressKey> immutableSatisfied() {
        Set<RequirementProgressKey> result = new LinkedHashSet<>();
        satisfied.stream()
            .sorted((left, right) -> requirementOrder(left).compareTo(requirementOrder(right)))
            .forEach(result::add);
        return Collections.unmodifiableSet(result);
    }

    private static String requirementOrder(RequirementProgressKey key) {
        return key.toString();
    }

    private static ListTag questList(Set<ResourceLocation> quests) {
        ListTag result = new ListTag();
        immutableQuests(quests).forEach(quest -> result.add(StringTag.valueOf(quest.toString())));
        return result;
    }

    private static Set<ResourceLocation> readQuests(CompoundTag quests, String field) {
        ListTag list = optionalStringList(quests, field, "quests." + field);
        Set<ResourceLocation> result = new LinkedHashSet<>();
        for (int index = 0; index < list.size(); index++) {
            ResourceLocation quest = ResourceLocation.tryParse(list.getString(index));
            if (quest == null) {
                throw new IllegalArgumentException("quests." + field + "[" + index
                    + "] is not a ResourceLocation");
            }
            result.add(quest);
        }
        return result;
    }

    @Nullable
    private static ResourceLocation readTracked(CompoundTag quests) {
        if (!quests.contains("tracked")) {
            return null;
        }
        if (quests.getTagType("tracked") != Tag.TAG_STRING) {
            throw invalidType("quests.tracked", "a string");
        }
        ResourceLocation tracked = ResourceLocation.tryParse(quests.getString("tracked"));
        if (tracked == null) {
            throw new IllegalArgumentException("quests.tracked is not a ResourceLocation");
        }
        return tracked;
    }

    private static List<String> sortedNames(CompoundTag tag) {
        List<String> names = new ArrayList<>(tag.getAllKeys());
        Collections.sort(names);
        return names;
    }

    private static CompoundTag optionalCompound(
        CompoundTag parent,
        String name,
        String path
    ) {
        Tag value = parent.get(name);
        if (value == null) {
            return new CompoundTag();
        }
        if (!(value instanceof CompoundTag compound)) {
            throw invalidType(path, "a compound tag");
        }
        return compound;
    }

    private static ListTag optionalStringList(
        CompoundTag parent,
        String name,
        String path
    ) {
        Tag value = parent.get(name);
        if (value == null) {
            return new ListTag();
        }
        if (!(value instanceof ListTag list)) {
            throw invalidType(path, "a list tag");
        }
        for (int index = 0; index < list.size(); index++) {
            if (list.get(index).getId() != Tag.TAG_STRING) {
                throw invalidType(path + "[" + index + "]", "a string tag");
            }
        }
        return list;
    }

    private static ResourceLocation parseResource(String value, String field) {
        ResourceLocation result = ResourceLocation.tryParse(value);
        if (result == null) {
            throw invalidType(field, "a ResourceLocation");
        }
        return result;
    }

    private static IllegalArgumentException invalidType(String field, String expected) {
        return new IllegalArgumentException(field + " must be " + expected);
    }
}
