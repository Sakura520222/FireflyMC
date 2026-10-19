package firefly520.fireflymc.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import firefly520.fireflymc.story.progress.RequirementProgressKey;
import firefly520.fireflymc.story.sync.StorySyncService;
import firefly520.fireflymc.story.sync.StorySyncServiceTestAccess;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class StoryProgressSnapshotPayloadTest {
    private static final String EMPTY_REVISION =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final UUID PLAYER_A = UUID.fromString(
        "00000000-0000-0000-0000-000000000001");
    private static final UUID PLAYER_B = UUID.fromString(
        "00000000-0000-0000-0000-000000000002");
    private static final UUID PLAYER_C = UUID.fromString(
        "00000000-0000-0000-0000-000000000003");

    @Test
    void emptySnapshotRoundTripsWithoutTrailingBytes() {
        StoryProgressSnapshotPayload payload = emptyPayload();
        ByteBuf buffer = Unpooled.buffer();
        try {
            StoryProgressSnapshotPayload.STREAM_CODEC.encode(buffer, payload);
            StoryProgressSnapshotPayload decoded =
                StoryProgressSnapshotPayload.STREAM_CODEC.decode(buffer);

            assertEquals(payload, decoded);
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void mixedSnapshotRoundTripsAndIsImmutable() {
        ResourceLocation completed = ResourceLocation.parse("fireflymc:old_quest");
        ResourceLocation active = ResourceLocation.parse("fireflymc:current_quest");
        ResourceLocation tracked = ResourceLocation.parse("fireflymc:current_quest");
        RequirementProgressKey counterKey = new RequirementProgressKey(
            active,
            "collect_fireflies",
            2
        );
        RequirementProgressKey satisfiedKey = new RequirementProgressKey(
            active,
            "meet_sam",
            1
        );
        StoryProgressSnapshotPayload payload = new StoryProgressSnapshotPayload(
            Set.of(completed),
            Set.of(active),
            Map.of("firefly.met", true),
            Map.of("firefly.affinity", 7),
            Map.of("chapter01.memory_found", true),
            Map.of("chapter01.progress", 3),
            Map.of(counterKey, 4),
            Set.of(satisfiedKey),
            Optional.of(tracked),
            EMPTY_REVISION
        );
        ByteBuf buffer = Unpooled.buffer();
        try {
            StoryProgressSnapshotPayload.STREAM_CODEC.encode(buffer, payload);
            StoryProgressSnapshotPayload decoded =
                StoryProgressSnapshotPayload.STREAM_CODEC.decode(buffer);

            assertEquals(payload, decoded);
            assertEquals(List.of(Map.entry(counterKey, 4)), decoded.counters());
            assertEquals(Optional.of(tracked), decoded.trackedQuest());
            assertThrows(UnsupportedOperationException.class, () ->
                decoded.completedQuests().add(active));
            assertThrows(UnsupportedOperationException.class, () ->
                decoded.playerFlags().put("other", true));
            assertThrows(UnsupportedOperationException.class, () ->
                decoded.counters().add(Map.entry(counterKey, 9)));
            assertNotSame(payload.completedQuests(), decoded.completedQuests());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void duplicateQuestIdsAreRejected() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            ByteBufCodecs.VAR_INT.encode(buffer, 2);
            writeResourceLocation(buffer, "fireflymc:same_quest");
            writeResourceLocation(buffer, "fireflymc:same_quest");

            DecoderException exception = assertThrowsExactly(
                DecoderException.class,
                () -> StoryProgressSnapshotPayload.STREAM_CODEC.decode(buffer)
            );
            assertTrue(exception.getMessage().contains("duplicate quest id"));
        } finally {
            buffer.release();
        }
    }

    @Test
    void absurdCollectionLengthIsRejectedBeforeAllocation() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            ByteBufCodecs.VAR_INT.encode(
                buffer,
                StoryProgressSnapshotPayload.MAX_QUEST_IDS + 1
            );

            DecoderException exception = assertThrowsExactly(
                DecoderException.class,
                () -> StoryProgressSnapshotPayload.STREAM_CODEC.decode(buffer)
            );
            assertTrue(exception.getMessage().contains("exceeded maximum of "
                + StoryProgressSnapshotPayload.MAX_QUEST_IDS));
            assertEquals(0, buffer.refCnt() - 1);
        } finally {
            buffer.release();
        }
    }

    @Test
    void invalidResourceLocationIsRejected() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            ByteBufCodecs.VAR_INT.encode(buffer, 1);
            writeResourceLocation(buffer, "not a resource location");

            assertThrowsExactly(
                DecoderException.class,
                () -> StoryProgressSnapshotPayload.STREAM_CODEC.decode(buffer)
            );
        } finally {
            buffer.release();
        }
    }

    @Test
    void corruptedNestedCounterKeyIsRejected() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            ByteBufCodecs.VAR_INT.encode(buffer, 0); // completed quests
            ByteBufCodecs.VAR_INT.encode(buffer, 0); // active quests
            ByteBufCodecs.VAR_INT.encode(buffer, 0); // player flags
            ByteBufCodecs.VAR_INT.encode(buffer, 0); // player vars
            ByteBufCodecs.VAR_INT.encode(buffer, 0); // world flags
            ByteBufCodecs.VAR_INT.encode(buffer, 0); // world vars
            ByteBufCodecs.VAR_INT.encode(buffer, 1); // counters
            writeResourceLocation(buffer, "fireflymc:quest");
            writeStateString(buffer, "kill_hostiles");
            ByteBufCodecs.VAR_INT.encode(buffer, 0); // invalid state version
            ByteBufCodecs.VAR_INT.encode(buffer, 1); // counter value

            DecoderException exception = assertThrowsExactly(
                DecoderException.class,
                () -> StoryProgressSnapshotPayload.STREAM_CODEC.decode(buffer)
            );
            assertTrue(exception.getMessage().contains("stateVersion"));
        } finally {
            buffer.release();
        }
    }

    @Test
    void directConstructionRejectsDuplicateQuestIdsAndRequirementValues() {
        ResourceLocation quest = ResourceLocation.parse("fireflymc:duplicate");
        RequirementProgressKey key = new RequirementProgressKey(quest, "same", 1);

        IllegalArgumentException duplicateQuest = assertThrowsExactly(
            IllegalArgumentException.class,
            () -> new StoryProgressSnapshotPayload(
                List.of(quest, quest),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(),
                List.of(),
                Optional.empty(),
                EMPTY_REVISION
            )
        );
        assertTrue(duplicateQuest.getMessage().contains("duplicate quest id"));

        IllegalArgumentException duplicateCounter = assertThrowsExactly(
            IllegalArgumentException.class,
            () -> new StoryProgressSnapshotPayload(
                List.of(quest),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(Map.entry(key, 1), Map.entry(key, 2)),
                List.of(),
                Optional.empty(),
                EMPTY_REVISION
            )
        );
        assertTrue(duplicateCounter.getMessage().contains("duplicate requirement counter"));

        IllegalArgumentException negativeCounter = assertThrowsExactly(
            IllegalArgumentException.class,
            () -> new StoryProgressSnapshotPayload(
                List.of(quest),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(Map.entry(key, -1)),
                List.of(),
                Optional.empty(),
                EMPTY_REVISION
            )
        );
        assertTrue(negativeCounter.getMessage().contains("stored counter must be positive"));

        IllegalArgumentException duplicateSatisfied = assertThrowsExactly(
            IllegalArgumentException.class,
            () -> new StoryProgressSnapshotPayload(
                List.of(quest),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(),
                List.of(key, key),
                Optional.empty(),
                EMPTY_REVISION
            )
        );
        assertTrue(duplicateSatisfied.getMessage().contains("duplicate satisfied requirement"));
    }

    @Test
    void trackedQuestMustBeActiveWhenPresent() {
        ResourceLocation inactive = ResourceLocation.parse("fireflymc:inactive");

        IllegalArgumentException exception = assertThrowsExactly(
            IllegalArgumentException.class,
            () -> new StoryProgressSnapshotPayload(
                List.of(),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(),
                List.of(),
                Optional.of(inactive),
                EMPTY_REVISION
            )
        );
        assertTrue(exception.getMessage().contains("tracked quest must be ACTIVE"));
    }

    @Test
    void oversizedStringsAreRejectedByDeclaredBounds() {
        ByteBuf questId = Unpooled.buffer();
        ByteBuf revision = Unpooled.buffer();
        try {
            ByteBufCodecs.VAR_INT.encode(questId, 1);
            ByteBufCodecs.VAR_INT.encode(
                questId,
                StoryProgressSnapshotPayload.MAX_RESOURCE_LENGTH + 1
            );
            questId.writeCharSequence("x".repeat(
                StoryProgressSnapshotPayload.MAX_RESOURCE_LENGTH + 1
            ), java.nio.charset.StandardCharsets.UTF_8);
            assertThrowsExactly(
                DecoderException.class,
                () -> StoryProgressSnapshotPayload.STREAM_CODEC.decode(questId)
            );

            ByteBufCodecs.VAR_INT.encode(revision, 65);
            revision.writeCharSequence("x".repeat(65),
                java.nio.charset.StandardCharsets.UTF_8);
            assertThrowsExactly(
                DecoderException.class,
                () -> StoryProgressSnapshotPayload.STREAM_CODEC.decode(revision)
            );
        } finally {
            questId.release();
            revision.release();
        }
    }

    @Test
    void duplicateFlagAndVariableNamesAreRejectedDuringDecode() {
        List<Map.Entry<String, Boolean>> duplicateFlags = List.of(
            Map.entry("firefly.same", true),
            Map.entry("firefly.same", false)
        );
        DecoderException flagException = assertThrowsExactly(
            DecoderException.class,
            () -> StoryProgressSnapshotPayload.requireUniqueStateEntries(
                duplicateFlags,
                "flag name"
            )
        );
        assertTrue(flagException.getMessage().contains("duplicate flag name"));

        List<Map.Entry<String, Integer>> duplicateVars = List.of(
            Map.entry("firefly.same", 1),
            Map.entry("firefly.same", 2)
        );
        DecoderException varException = assertThrowsExactly(
            DecoderException.class,
            () -> StoryProgressSnapshotPayload.requireUniqueStateEntries(
                duplicateVars,
                "variable name"
            )
        );
        assertTrue(varException.getMessage().contains("duplicate variable name"));
    }

    @Test
    void duplicateSatisfiedRequirementKeysAreRejectedDuringDecode() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            writeEmptyQuestAndScopeCounts(buffer);
            ByteBufCodecs.VAR_INT.encode(buffer, 0); // counters
            ByteBufCodecs.VAR_INT.encode(buffer, 2); // satisfied
            writeRequirementKey(buffer, "fireflymc:quest", "same", 1);
            writeRequirementKey(buffer, "fireflymc:quest", "same", 1);

            DecoderException exception = assertThrowsExactly(
                DecoderException.class,
                () -> StoryProgressSnapshotPayload.STREAM_CODEC.decode(buffer)
            );
            assertTrue(exception.getMessage().contains("duplicate satisfied requirement"));
        } finally {
            buffer.release();
        }
    }

    @Test
    void invalidRevisionAndShortStringsAreRejectedDuringDecode() {
        ByteBuf revision = Unpooled.buffer();
        ByteBuf shortString = Unpooled.buffer();
        try {
            writeEmptyQuestAndScopeCounts(revision);
            ByteBufCodecs.VAR_INT.encode(revision, 0); // counters
            ByteBufCodecs.VAR_INT.encode(revision, 0); // satisfied
            ByteBufCodecs.BOOL.encode(revision, false);
            String invalidRevision = "g".repeat(64);
            ByteBufCodecs.VAR_INT.encode(revision, invalidRevision.length());
            revision.writeCharSequence(invalidRevision,
                java.nio.charset.StandardCharsets.UTF_8);
            assertThrowsExactly(
                IllegalArgumentException.class,
                () -> StoryProgressSnapshotPayload.STREAM_CODEC.decode(revision)
            );

            ByteBufCodecs.VAR_INT.encode(shortString, 1); // completed quests
            ByteBufCodecs.VAR_INT.encode(shortString, 5);
            shortString.writeCharSequence("ab",
                java.nio.charset.StandardCharsets.UTF_8);
            DecoderException exception = assertThrowsExactly(
                DecoderException.class,
                () -> StoryProgressSnapshotPayload.STREAM_CODEC.decode(shortString)
            );
            assertTrue(exception.getMessage().contains("short bounded UTF-8 string"));
        } finally {
            revision.release();
            shortString.release();
        }
    }

    @Test
    void threeMutationsInOneTransactionCoalesceToOneSend() {
        StorySyncService service = StorySyncServiceTestAccess.createService();
        List<UUID> sends = new ArrayList<>();

        for (int mutation = 0; mutation < 3; mutation++) {
            StorySyncServiceTestAccess.markPlayerDirty(service, PLAYER_A, true);
        }
        StorySyncServiceTestAccess.flush(
            service,
            Map.of(PLAYER_A, () -> sends.add(PLAYER_A)),
            playerId -> true,
            true
        );

        assertEquals(List.of(PLAYER_A), sends);
        assertTrue(StorySyncServiceTestAccess.dirtyTargets(service).isEmpty());
    }

    @Test
    void worldFlagChangeSendsOneSnapshotToEveryOnlinePlayer() {
        StorySyncService service = StorySyncServiceTestAccess.createService();
        List<UUID> sends = new ArrayList<>();

        StorySyncServiceTestAccess.markWorldDirty(
            service,
            List.of(PLAYER_A, PLAYER_B, PLAYER_C),
            true
        );
        StorySyncServiceTestAccess.flush(
            service,
            Map.of(
                PLAYER_A, () -> sends.add(PLAYER_A),
                PLAYER_B, () -> sends.add(PLAYER_B)
            ),
            playerId -> !PLAYER_C.equals(playerId),
            true
        );

        assertEquals(List.of(PLAYER_A, PLAYER_B), sends);
    }

    @Test
    void sendNowServesLoginOrRespawnWithoutWaitingForFlush() {
        StorySyncService service = StorySyncServiceTestAccess.createService();
        List<UUID> sends = new ArrayList<>();
        StorySyncServiceTestAccess.markPlayerDirty(service, PLAYER_A, true);

        StorySyncServiceTestAccess.sendNow(
            service,
            PLAYER_A,
            () -> sends.add(PLAYER_A),
            true
        );

        assertEquals(List.of(PLAYER_A), sends);
        assertTrue(StorySyncServiceTestAccess.dirtyTargets(service).isEmpty());
    }

    @Test
    void sendNowConstructionFailureEstablishesDirtyMarkerForLaterRetry() {
        StorySyncService service = StorySyncServiceTestAccess.createService();
        assertTrue(StorySyncServiceTestAccess.dirtyTargets(service).isEmpty());
        Supplier<Runnable> failingConstruction = () -> {
            throw new IllegalStateException("snapshot construction failed");
        };

        assertThrowsExactly(
            IllegalStateException.class,
            () -> StorySyncServiceTestAccess.sendNow(
                service,
                PLAYER_A,
                failingConstruction,
                true
            )
        );

        assertEquals(Set.of(PLAYER_A), StorySyncServiceTestAccess.dirtyTargets(service));
        List<UUID> sends = new ArrayList<>();
        StorySyncServiceTestAccess.flush(
            service,
            Map.of(PLAYER_A, () -> sends.add(PLAYER_A)),
            playerId -> true,
            true
        );
        assertEquals(List.of(PLAYER_A), sends);
        assertTrue(StorySyncServiceTestAccess.dirtyTargets(service).isEmpty());
    }

    @Test
    void firstFlushFailureRetriesWithoutBlockingLaterTargets() {
        StorySyncService service = StorySyncServiceTestAccess.createService();
        List<UUID> sends = new ArrayList<>();
        StorySyncServiceTestAccess.markPlayerDirty(service, PLAYER_A, true);
        StorySyncServiceTestAccess.markPlayerDirty(service, PLAYER_B, true);

        StorySyncServiceTestAccess.flush(
            service,
            Map.of(
                PLAYER_A,
                () -> {
                    throw new IllegalStateException("transport offline");
                },
                PLAYER_B,
                () -> sends.add(PLAYER_B)
            ),
            playerId -> true,
            true
        );

        assertEquals(List.of(PLAYER_B), sends);
        assertEquals(Set.of(PLAYER_A), StorySyncServiceTestAccess.dirtyTargets(service));

        StorySyncServiceTestAccess.flush(
            service,
            Map.of(PLAYER_A, () -> sends.add(PLAYER_A)),
            playerId -> true,
            true
        );
        assertEquals(List.of(PLAYER_B, PLAYER_A), sends);
        assertTrue(StorySyncServiceTestAccess.dirtyTargets(service).isEmpty());
    }

    @Test
    void sendNowFailurePreservesPendingDirtyMarker() {
        StorySyncService service = StorySyncServiceTestAccess.createService();
        StorySyncServiceTestAccess.markPlayerDirty(service, PLAYER_A, true);

        assertThrowsExactly(
            IllegalStateException.class,
            () -> StorySyncServiceTestAccess.sendNow(
                service,
                PLAYER_A,
                () -> {
                    throw new IllegalStateException("login transport failed");
                },
                true
            )
        );

        List<UUID> sends = new ArrayList<>();
        StorySyncServiceTestAccess.flush(
            service,
            Map.of(PLAYER_A, () -> sends.add(PLAYER_A)),
            playerId -> true,
            true
        );
        assertEquals(List.of(PLAYER_A), sends);
        assertTrue(StorySyncServiceTestAccess.dirtyTargets(service).isEmpty());
    }

    @Test
    void serviceMarkFlushAndSendNowAreServerThreadOnly() {
        StorySyncService service = StorySyncServiceTestAccess.createService();
        assertThrowsExactly(
            IllegalThreadStateException.class,
            () -> StorySyncServiceTestAccess.markPlayerDirty(service, PLAYER_A, false)
        );
        assertThrowsExactly(
            IllegalThreadStateException.class,
            () -> StorySyncServiceTestAccess.markWorldDirty(
                service,
                List.of(PLAYER_A),
                false
            )
        );
        assertThrowsExactly(
            IllegalThreadStateException.class,
            () -> StorySyncServiceTestAccess.flush(
                service,
                Map.of(),
                UUID -> true,
                false
            )
        );
        assertThrowsExactly(
            IllegalThreadStateException.class,
            () -> StorySyncServiceTestAccess.sendNow(
                service,
                PLAYER_A,
                () -> {},
                false
            )
        );
        assertTrue(StorySyncServiceTestAccess.dirtyTargets(service).isEmpty());
    }

    @Test
    void oneThousandTaskFixtureStaysWithinMeasuredFrameBound() {
        Set<ResourceLocation> completed = new LinkedHashSet<>();
        for (int index = 0; index < 1_000; index++) {
            completed.add(ResourceLocation.parse("fireflymc:quest_%04d".formatted(index)));
        }
        StoryProgressSnapshotPayload payload = new StoryProgressSnapshotPayload(
            completed,
            Set.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Set.of(),
            Optional.empty(),
            EMPTY_REVISION
        );
        ByteBuf buffer = Unpooled.buffer();
        try {
            StoryProgressSnapshotPayload.STREAM_CODEC.encode(buffer, payload);
            int frameSize = buffer.readableBytes();

            // One thousand fixed 20-byte ids cost 20,000 payload bytes plus 1,000 length bytes.
            // The remainder is collection counts, tracked-quest marker, and the 64-character
            // revision with its length byte. Pin the measured 1,000-task frame size.
            assertEquals(21_075, frameSize);
        } finally {
            buffer.release();
        }
    }

    private static StoryProgressSnapshotPayload emptyPayload() {
        return new StoryProgressSnapshotPayload(
            Set.of(),
            Set.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Set.of(),
            Optional.empty(),
            EMPTY_REVISION
        );
    }

    private static void writeResourceLocation(ByteBuf buffer, String value) {
        ByteBufCodecs.VAR_INT.encode(buffer, value.length());
        buffer.writeCharSequence(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void writeEmptyQuestAndScopeCounts(ByteBuf buffer) {
        ByteBufCodecs.VAR_INT.encode(buffer, 0); // completed quests
        ByteBufCodecs.VAR_INT.encode(buffer, 0); // active quests
        ByteBufCodecs.VAR_INT.encode(buffer, 0); // player flags
        ByteBufCodecs.VAR_INT.encode(buffer, 0); // player vars
        ByteBufCodecs.VAR_INT.encode(buffer, 0); // world flags
        ByteBufCodecs.VAR_INT.encode(buffer, 0); // world vars
    }

    private static void writeRequirementKey(
        ByteBuf buffer,
        String questId,
        String requirementId,
        int stateVersion
    ) {
        writeResourceLocation(buffer, questId);
        writeStateString(buffer, requirementId);
        ByteBufCodecs.VAR_INT.encode(buffer, stateVersion);
    }

    private static void writeStateString(ByteBuf buffer, String value) {
        ByteBufCodecs.VAR_INT.encode(buffer, value.length());
        buffer.writeBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
