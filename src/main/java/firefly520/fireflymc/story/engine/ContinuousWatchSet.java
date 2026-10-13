package firefly520.fireflymc.story.engine;

import firefly520.fireflymc.story.condition.ConditionTypes;
import firefly520.fireflymc.story.definition.QuestDefinition;
import firefly520.fireflymc.story.progress.QuestState;
import firefly520.fireflymc.story.progress.StoryProgress;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/**
 * Per-player polling candidates for current-state conditions. The production caller owns the
 * main thread and world probes; this class never touches a level, player, or progress mutation.
 */
public final class ContinuousWatchSet {
    private static final int PHASE_COUNT = 20;

    private final Map<UUID, Integer> phases = new LinkedHashMap<>();
    private final Map<UUID, Set<ResourceLocation>> watches = new LinkedHashMap<>();

    /**
     * Adds one quest after its LOCKED -> ACTIVE transition if it needs periodic probing.
     * Non-continuous quests are valid graph successors and simply create no watch.
     */
    public void activate(
        UUID playerId,
        ResourceLocation questId,
        StoryRuntimeSnapshot snapshot,
        StoryProgress progress
    ) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(questId, "questId");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(progress, "progress");
        QuestDefinition quest = snapshot.quests().get(questId);
        if (quest == null || progress.getQuestState(questId) != QuestState.ACTIVE) {
            throw new IllegalArgumentException(
                "watch activation requires an active quest: " + questId);
        }
        if (!isContinuous(quest)) {
            return;
        }
        phases.putIfAbsent(playerId, phaseOf(playerId));
        watches.computeIfAbsent(playerId, ignored -> new LinkedHashSet<>())
            .add(questId);
    }

    /** Removes one completed or otherwise deactivated quest. */
    public void deactivate(UUID playerId, ResourceLocation questId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(questId, "questId");
        Set<ResourceLocation> quests = watches.get(playerId);
        if (quests != null) {
            quests.remove(questId);
            if (quests.isEmpty()) {
                watches.remove(playerId);
            }
        }
    }

    /** Atomically replaces a player's watch set from the active snapshot/progress pair. */
    public void rebuild(
        UUID playerId,
        StoryRuntimeSnapshot snapshot,
        StoryProgress progress
    ) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(progress, "progress");
        phases.putIfAbsent(playerId, phaseOf(playerId));
        Set<ResourceLocation> rebuilt = new LinkedHashSet<>();
        snapshot.quests().forEach((questId, quest) -> {
            if (isContinuous(quest) && progress.getQuestState(questId) == QuestState.ACTIVE) {
                rebuilt.add(questId);
            }
        });
        if (rebuilt.isEmpty()) {
            watches.remove(playerId);
        } else {
            watches.put(playerId, rebuilt);
        }
    }

    /** Returns due quests for this server tick, using the player's stable 0..19 phase. */
    public Set<ResourceLocation> tickDue(UUID playerId, long serverTick) {
        Objects.requireNonNull(playerId, "playerId");
        Set<ResourceLocation> quests = watches.get(playerId);
        if (quests == null || (phaseOf(playerId) + serverTick) % PHASE_COUNT != 0) {
            return Set.of();
        }
        Set<ResourceLocation> result = new TreeSet<>(Comparator.comparing(ResourceLocation::toString));
        result.addAll(quests);
        return Set.copyOf(result);
    }

    public Set<ResourceLocation> watched(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Set<ResourceLocation> quests = watches.get(playerId);
        return quests == null ? Set.of() : Set.copyOf(quests);
    }

    /** Stable polling phase used by callers for diagnostics and deterministic tests. */
    public int phase(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return phaseOf(playerId);
    }

    private static int phaseOf(UUID playerId) {
        byte[] bytes = new byte[16];
        writeLong(bytes, 0, playerId.getMostSignificantBits());
        writeLong(bytes, 8, playerId.getLeastSignificantBits());
        return Math.floorMod(Arrays.hashCode(bytes), PHASE_COUNT);
    }

    private static void writeLong(byte[] bytes, int offset, long value) {
        for (int index = 0; index < Long.BYTES; index++) {
            bytes[offset + index] = (byte) (value >>> ((Long.BYTES - 1 - index) * Byte.SIZE));
        }
    }

    private static boolean isContinuous(QuestDefinition quest) {
        return quest.requirements().stream().anyMatch(requirement -> {
            var condition = requirement.condition();
            return condition instanceof ConditionTypes.InventoryCondition
                || condition instanceof ConditionTypes.EnterBiomeCondition
                || condition instanceof ConditionTypes.EnterStructureCondition
                || condition instanceof ConditionTypes.LocationCondition;
        });
    }
}
