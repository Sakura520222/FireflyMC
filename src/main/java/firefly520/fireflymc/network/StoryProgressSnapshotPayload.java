package firefly520.fireflymc.network;

import firefly520.fireflymc.FireflyMCMod;
import firefly520.fireflymc.story.condition.StoryCondition;
import firefly520.fireflymc.story.engine.StoryRuntimeSnapshot;
import firefly520.fireflymc.story.progress.RequirementProgressKey;
import firefly520.fireflymc.story.progress.StoryAttachments;
import firefly520.fireflymc.story.progress.StoryProgress;
import firefly520.fireflymc.story.progress.WorldStoryState;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.TreeMap;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Immutable server-authoritative story progress sent to one client.
 *
 * <p>All collection counts are VAR_INT values checked against explicit maxima before any
 * collection is allocated. Strings use bounded UTF-8 codecs; requirement progress keeps its
 * quest/version structure instead of using an ambiguous concatenated key.</p>
 */
public record StoryProgressSnapshotPayload(
    Collection<ResourceLocation> completedQuests,
    Collection<ResourceLocation> activeQuests,
    Map<String, Boolean> playerFlags,
    Map<String, Integer> playerVars,
    Map<String, Boolean> worldFlags,
    Map<String, Integer> worldVars,
    Collection<Map.Entry<RequirementProgressKey, Integer>> counters,
    Collection<RequirementProgressKey> satisfied,
    Optional<ResourceLocation> trackedQuest,
    String storyRevision
) implements CustomPacketPayload {
    public static final int MAX_QUEST_IDS = 2_048;
    public static final int MAX_STATE_NAMES = 4_096;
    public static final int MAX_REQUIREMENT_ENTRIES = 8_192;
    public static final int MAX_RESOURCE_LENGTH = 321;
    public static final int MAX_STATE_NAME_LENGTH = 128;

    private static final StreamCodec<ByteBuf, ResourceLocation> RESOURCE_LOCATION =
        StreamCodec.of(
            (buffer, value) -> ByteBufCodecs.stringUtf8(MAX_RESOURCE_LENGTH)
                .encode(buffer, value.toString()),
            StoryProgressSnapshotPayload::decodeResourceLocation
        );
    private static final StreamCodec<ByteBuf, String> STATE_NAME =
        StreamCodec.of(
            (buffer, value) -> ByteBufCodecs.stringUtf8(MAX_STATE_NAME_LENGTH)
                .encode(buffer, value),
            StoryProgressSnapshotPayload::decodeStateName
        );
    private static final StreamCodec<ByteBuf, RequirementProgressKey> REQUIREMENT_KEY =
        StreamCodec.composite(
            RESOURCE_LOCATION,
            RequirementProgressKey::questId,
            STATE_NAME,
            RequirementProgressKey::requirementId,
            ByteBufCodecs.VAR_INT,
            RequirementProgressKey::stateVersion,
            RequirementProgressKey::new
        );

    public static final CustomPacketPayload.Type<StoryProgressSnapshotPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            FireflyMCMod.MODID,
            "story_progress_snapshot"
        ));

    public static final StreamCodec<ByteBuf, StoryProgressSnapshotPayload> STREAM_CODEC =
        StreamCodec.of(
            StoryProgressSnapshotPayload::encode,
            buffer -> decode(buffer)
        );

    public StoryProgressSnapshotPayload {
        completedQuests = immutableQuests(completedQuests, "completedQuests");
        activeQuests = immutableQuests(activeQuests, "activeQuests");
        playerFlags = immutableStateFlags(playerFlags, "playerFlags");
        playerVars = immutableStateVars(playerVars, "playerVars");
        worldFlags = immutableStateFlags(worldFlags, "worldFlags");
        worldVars = immutableStateVars(worldVars, "worldVars");
        counters = immutableCounters(counters);
        satisfied = immutableSatisfied(satisfied);
        trackedQuest = trackedQuest == null
            ? Optional.empty()
            : Optional.ofNullable(trackedQuest.orElse(null));
        storyRevision = validateRevision(storyRevision);
        Collection<ResourceLocation> activeQuestView = activeQuests;
        trackedQuest.ifPresent(quest -> {
            if (!activeQuestView.contains(quest)) {
                throw new IllegalArgumentException(
                    "tracked quest must be ACTIVE: " + quest);
            }
        });

        validateStateScopes(playerFlags, playerVars, "player");
        validateStateScopes(worldFlags, worldVars, "world");

        for (ResourceLocation questId : completedQuests) {
            if (activeQuests.contains(questId)) {
                throw new IllegalArgumentException(
                    "quest is both completed and active: " + questId);
            }
        }
    }

    /** Java-map/set convenience constructor used by in-process state and older tests. */
    public StoryProgressSnapshotPayload(
        Set<ResourceLocation> completedQuests,
        Set<ResourceLocation> activeQuests,
        Map<String, Boolean> playerFlags,
        Map<String, Integer> playerVars,
        Map<String, Boolean> worldFlags,
        Map<String, Integer> worldVars,
        Map<RequirementProgressKey, Integer> counters,
        Set<RequirementProgressKey> satisfied,
        Optional<ResourceLocation> trackedQuest,
        String storyRevision
    ) {
        this(
            completedQuests,
            activeQuests,
            playerFlags,
            playerVars,
            worldFlags,
            worldVars,
            counters.entrySet(),
            satisfied,
            trackedQuest,
            storyRevision
        );
    }

    public static StoryProgressSnapshotPayload from(
        ServerPlayer player,
        StoryRuntimeSnapshot snapshot
    ) {
        if (!player.server.isSameThread()) {
            throw new IllegalThreadStateException(
                "Story snapshots must be built on the server thread");
        }
        StoryProgress progress = player.getData(StoryAttachments.PROGRESS.get());
        WorldStoryState world = WorldStoryState.get(player.server);
        return new StoryProgressSnapshotPayload(
            progress.completedQuests(),
            progress.activeQuests(),
            progress.flags(),
            progress.vars(),
            world.flags(),
            world.vars(),
            progress.counters(),
            progress.satisfied(),
            progress.trackedQuest(),
            snapshot.revision()
        );
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(ByteBuf buffer, StoryProgressSnapshotPayload payload) {
        encodeQuests(buffer, payload.completedQuests());
        encodeQuests(buffer, payload.activeQuests());
        encodeFlags(buffer, payload.playerFlags());
        encodeVars(buffer, payload.playerVars());
        encodeFlags(buffer, payload.worldFlags());
        encodeVars(buffer, payload.worldVars());
        encodeCounters(buffer, payload.counters());
        encodeSatisfied(buffer, payload.satisfied());
        ByteBufCodecs.BOOL.encode(buffer, payload.trackedQuest().isPresent());
        payload.trackedQuest().ifPresent(quest -> RESOURCE_LOCATION.encode(buffer, quest));
        ByteBufCodecs.stringUtf8(64).encode(buffer, payload.storyRevision());
    }

    private static StoryProgressSnapshotPayload decode(ByteBuf buffer) {
        List<ResourceLocation> completedQuests = decodeQuests(buffer);
        List<ResourceLocation> activeQuests = decodeQuests(buffer);
        Map<String, Boolean> playerFlags = decodeFlags(buffer);
        Map<String, Integer> playerVars = decodeVars(buffer);
        Map<String, Boolean> worldFlags = decodeFlags(buffer);
        Map<String, Integer> worldVars = decodeVars(buffer);
        List<Map.Entry<RequirementProgressKey, Integer>> counters = decodeCounters(buffer);
        List<RequirementProgressKey> satisfied = decodeSatisfied(buffer);
        Optional<ResourceLocation> trackedQuest = ByteBufCodecs.BOOL.decode(buffer)
            ? Optional.of(RESOURCE_LOCATION.decode(buffer))
            : Optional.empty();
        String storyRevision = readBoundedString(buffer, 64);

        return new StoryProgressSnapshotPayload(
            completedQuests,
            activeQuests,
            playerFlags,
            playerVars,
            worldFlags,
            worldVars,
            counters,
            satisfied,
            trackedQuest,
            storyRevision
        );
    }

    private static void encodeQuests(ByteBuf buffer, Collection<ResourceLocation> quests) {
        requireCount(quests.size(), MAX_QUEST_IDS, "quest ids");
        ByteBufCodecs.VAR_INT.encode(buffer, quests.size());
        quests.forEach(quest -> RESOURCE_LOCATION.encode(buffer, quest));
    }

    private static List<ResourceLocation> decodeQuests(ByteBuf buffer) {
        int count = decodeCount(buffer, MAX_QUEST_IDS, "quest ids");
        Set<ResourceLocation> result = LinkedHashSet.newLinkedHashSet(count);
        for (int index = 0; index < count; index++) {
            ResourceLocation questId = RESOURCE_LOCATION.decode(buffer);
            if (!result.add(questId)) {
                throw new DecoderException("duplicate quest id: " + questId);
            }
        }
        return List.copyOf(result);
    }

    private static void encodeFlags(ByteBuf buffer, Map<String, Boolean> flags) {
        requireCount(flags.size(), MAX_STATE_NAMES, "flags");
        ByteBufCodecs.VAR_INT.encode(buffer, flags.size());
        flags.forEach((name, value) -> {
            STATE_NAME.encode(buffer, name);
            ByteBufCodecs.BOOL.encode(buffer, value);
        });
    }

    private static Map<String, Boolean> decodeFlags(ByteBuf buffer) {
        int count = decodeCount(buffer, MAX_STATE_NAMES, "flags");
        List<Map.Entry<String, Boolean>> entries = new ArrayList<>(capacity(count));
        for (int index = 0; index < count; index++) {
            String name = STATE_NAME.decode(buffer);
            Boolean value = ByteBufCodecs.BOOL.decode(buffer);
            entries.add(Map.entry(name, value));
        }
        return requireUniqueStateEntries(entries, "flag name");
    }

    private static void encodeVars(ByteBuf buffer, Map<String, Integer> vars) {
        requireCount(vars.size(), MAX_STATE_NAMES, "vars");
        ByteBufCodecs.VAR_INT.encode(buffer, vars.size());
        vars.forEach((name, value) -> {
            STATE_NAME.encode(buffer, name);
            ByteBufCodecs.VAR_INT.encode(buffer, value);
        });
    }

    private static Map<String, Integer> decodeVars(ByteBuf buffer) {
        int count = decodeCount(buffer, MAX_STATE_NAMES, "vars");
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(capacity(count));
        for (int index = 0; index < count; index++) {
            String name = STATE_NAME.decode(buffer);
            Integer value = ByteBufCodecs.VAR_INT.decode(buffer);
            entries.add(Map.entry(name, value));
        }
        return requireUniqueStateEntries(entries, "variable name");
    }

    private static void encodeCounters(
        ByteBuf buffer,
        Collection<Map.Entry<RequirementProgressKey, Integer>> counters
    ) {
        requireCount(counters.size(), MAX_REQUIREMENT_ENTRIES, "counters");
        ByteBufCodecs.VAR_INT.encode(buffer, counters.size());
        counters.forEach(entry -> {
            RequirementProgressKey key = entry.getKey();
            Integer value = entry.getValue();
            REQUIREMENT_KEY.encode(buffer, key);
            ByteBufCodecs.VAR_INT.encode(buffer, value);
        });
    }

    private static List<Map.Entry<RequirementProgressKey, Integer>> decodeCounters(
        ByteBuf buffer
    ) {
        int count = decodeCount(buffer, MAX_REQUIREMENT_ENTRIES, "counters");
        List<Map.Entry<RequirementProgressKey, Integer>> result = new ArrayList<>(capacity(count));
        Set<RequirementProgressKey> seenKeys = LinkedHashSet.newLinkedHashSet(count);
        for (int index = 0; index < count; index++) {
            RequirementProgressKey key = decodeRequirementKey(buffer);
            Integer value = ByteBufCodecs.VAR_INT.decode(buffer);
            if (!seenKeys.add(key)) {
                throw new DecoderException("duplicate requirement counter: " + key);
            }
            result.add(Map.entry(key, value));
        }
        return result;
    }

    private static void encodeSatisfied(
        ByteBuf buffer,
        Collection<RequirementProgressKey> satisfied
    ) {
        requireCount(satisfied.size(), MAX_REQUIREMENT_ENTRIES, "satisfied requirements");
        ByteBufCodecs.VAR_INT.encode(buffer, satisfied.size());
        satisfied.forEach(key -> REQUIREMENT_KEY.encode(buffer, key));
    }

    private static List<RequirementProgressKey> decodeSatisfied(ByteBuf buffer) {
        int count = decodeCount(buffer, MAX_REQUIREMENT_ENTRIES, "satisfied requirements");
        Set<RequirementProgressKey> result = LinkedHashSet.newLinkedHashSet(count);
        for (int index = 0; index < count; index++) {
            RequirementProgressKey key = decodeRequirementKey(buffer);
            if (!result.add(key)) {
                throw new DecoderException("duplicate satisfied requirement: " + key);
            }
        }
        return List.copyOf(result);
    }

    private static int decodeCount(ByteBuf buffer, int maximum, String label) {
        int count = ByteBufCodecs.VAR_INT.decode(buffer);
        if (count < 0 || count > maximum) {
            throw new DecoderException(
                count + " " + label + " exceeded maximum of " + maximum);
        }
        return count;
    }

    private static String readBoundedString(ByteBuf buffer, int maximum) {
        int length = ByteBufCodecs.VAR_INT.decode(buffer);
        if (length < 0 || length > maximum) {
            throw new DecoderException(
                "encoded string length " + length + " exceeded maximum of " + maximum);
        }
        if (buffer.readableBytes() < length) {
            throw new DecoderException("short bounded UTF-8 string: expected " + length
                + " bytes, got " + buffer.readableBytes());
        }
        return buffer.readCharSequence(length, StandardCharsets.UTF_8).toString();
    }

    private static void requireCount(int count, int maximum, String label) {
        if (count < 0 || count > maximum) {
            throw new IllegalArgumentException(
                count + " " + label + " exceeded maximum of " + maximum);
        }
    }

    private static ResourceLocation decodeResourceLocation(ByteBuf buffer) {
        String value = readBoundedString(buffer, MAX_RESOURCE_LENGTH);
        try {
            return ResourceLocation.parse(value);
        } catch (RuntimeException exception) {
            throw new DecoderException("invalid ResourceLocation: " + value, exception);
        }
    }

    static <V> Map<String, V> requireUniqueStateEntries(
        List<Map.Entry<String, V>> entries,
        String label
    ) {
        Map<String, V> result = new LinkedHashMap<>(capacity(entries.size()));
        for (Map.Entry<String, V> entry : entries) {
            String name = entry.getKey();
            if (result.put(name, entry.getValue()) != null) {
                throw new DecoderException("duplicate " + label + ": " + name);
            }
        }
        return result;
    }

    private static RequirementProgressKey decodeRequirementKey(ByteBuf buffer) {
        try {
            return REQUIREMENT_KEY.decode(buffer);
        } catch (DecoderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DecoderException(
                "invalid requirement progress key: " + exception.getMessage(),
                exception
            );
        }
    }

    private static String decodeStateName(ByteBuf buffer) {
        String value = readBoundedString(buffer, MAX_STATE_NAME_LENGTH);
        if (!StoryCondition.isValidStateName(value)) {
            throw new DecoderException("invalid story state name: " + value);
        }
        return value;
    }

    private static List<ResourceLocation> immutableQuests(
        Collection<ResourceLocation> values,
        String fieldName
    ) {
        if (values == null) {
            throw new NullPointerException(fieldName + " must not be null");
        }
        if (values.size() > MAX_QUEST_IDS) {
            throw new IllegalArgumentException(
                values.size() + " " + fieldName + " exceeded maximum of " + MAX_QUEST_IDS);
        }
        Set<ResourceLocation> unique = new LinkedHashSet<>();
        for (ResourceLocation value : values) {
            Objects.requireNonNull(value, fieldName + " entry must not be null");
            if (!unique.add(value)) {
                throw new IllegalArgumentException(
                    "duplicate quest id in " + fieldName + ": " + value);
            }
        }
        List<ResourceLocation> result = new ArrayList<>(unique.size());
        unique.stream()
            .sorted(Comparator.comparing(ResourceLocation::toString))
            .forEach(result::add);
        return Collections.unmodifiableList(result);
    }

    private static Map<String, Boolean> immutableStateFlags(
        Map<String, Boolean> values,
        String fieldName
    ) {
        if (values == null) {
            throw new NullPointerException(fieldName + " must not be null");
        }
        requireNameCount(values.size(), fieldName);
        Map<String, Boolean> result = new TreeMap<>();
        values.forEach((name, value) -> {
            requireStateName(name);
            if (value == null) {
                throw new NullPointerException(fieldName + "." + name + " must not be null");
            }
            result.put(name, value);
        });
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Integer> immutableStateVars(
        Map<String, Integer> values,
        String fieldName
    ) {
        if (values == null) {
            throw new NullPointerException(fieldName + " must not be null");
        }
        requireNameCount(values.size(), fieldName);
        Map<String, Integer> result = new TreeMap<>();
        values.forEach((name, value) -> {
            requireStateName(name);
            if (value == null) {
                throw new NullPointerException(fieldName + "." + name + " must not be null");
            }
            result.put(name, value);
        });
        return Collections.unmodifiableMap(result);
    }

    private static List<Map.Entry<RequirementProgressKey, Integer>> immutableCounters(
        Collection<Map.Entry<RequirementProgressKey, Integer>> values
    ) {
        if (values == null) {
            throw new NullPointerException("counters must not be null");
        }
        if (values.size() > MAX_REQUIREMENT_ENTRIES) {
            throw new IllegalArgumentException(
                values.size() + " counters exceeded maximum of " + MAX_REQUIREMENT_ENTRIES);
        }
        Map<RequirementProgressKey, Integer> ordered = new TreeMap<>(
            Comparator.comparing(RequirementProgressKey::questId,
                    Comparator.comparing(ResourceLocation::toString))
                .thenComparing(RequirementProgressKey::requirementId)
                .thenComparingInt(RequirementProgressKey::stateVersion)
        );
        for (Map.Entry<RequirementProgressKey, Integer> entry : values) {
            Objects.requireNonNull(entry, "counter entry must not be null");
            Objects.requireNonNull(entry.getKey(), "counter key must not be null");
            Objects.requireNonNull(entry.getValue(), "counter value must not be null");
            if (entry.getValue() <= 0) {
                throw new IllegalArgumentException(
                    "stored counter must be positive: " + entry.getKey());
            }
            if (ordered.put(entry.getKey(), entry.getValue()) != null) {
                throw new IllegalArgumentException(
                    "duplicate requirement counter: " + entry.getKey());
            }
        }
        List<Map.Entry<RequirementProgressKey, Integer>> result = new ArrayList<>(ordered.size());
        ordered.forEach((key, value) -> result.add(Map.entry(key, value)));
        return Collections.unmodifiableList(result);
    }

    private static List<RequirementProgressKey> immutableSatisfied(
        Collection<RequirementProgressKey> values
    ) {
        if (values == null) {
            throw new NullPointerException("satisfied must not be null");
        }
        if (values.size() > MAX_REQUIREMENT_ENTRIES) {
            throw new IllegalArgumentException(
                values.size() + " satisfied requirements exceeded maximum of "
                    + MAX_REQUIREMENT_ENTRIES);
        }
        Set<RequirementProgressKey> ordered = new TreeSet<>(
            Comparator.comparing(RequirementProgressKey::questId,
                    Comparator.comparing(ResourceLocation::toString))
                .thenComparing(RequirementProgressKey::requirementId)
                .thenComparingInt(RequirementProgressKey::stateVersion)
        );
        for (RequirementProgressKey value : values) {
            Objects.requireNonNull(value, "satisfied requirement must not be null");
            if (!ordered.add(value)) {
                throw new IllegalArgumentException(
                    "duplicate satisfied requirement: " + value);
            }
        }
        return List.copyOf(ordered);
    }

    private static void requireNameCount(int count, String fieldName) {
        if (count > MAX_STATE_NAMES) {
            throw new IllegalArgumentException(
                count + " " + fieldName + " exceeded maximum of " + MAX_STATE_NAMES);
        }
    }

    private static void requireStateName(String name) {
        if (name == null || name.length() > MAX_STATE_NAME_LENGTH
            || !StoryCondition.isValidStateName(name)) {
            throw new IllegalArgumentException("invalid story state name: " + name);
        }
    }

    private static void validateStateScopes(
        Map<String, Boolean> flags,
        Map<String, Integer> vars,
        String scope
    ) {
        for (String name : flags.keySet()) {
            if (vars.containsKey(name)) {
                throw new IllegalArgumentException(
                    scope + " flag and variable names collide: " + name);
            }
        }
    }

    private static String validateRevision(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("storyRevision must be lowercase SHA-256 hex");
        }
        return value;
    }

    private static int capacity(int count) {
        return Math.min(count, 1_024);
    }
}
