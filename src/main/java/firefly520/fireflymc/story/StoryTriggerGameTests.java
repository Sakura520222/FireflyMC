package firefly520.fireflymc.story;

import com.mojang.authlib.GameProfile;
import firefly520.fireflymc.network.ModPayloadHandler;
import firefly520.fireflymc.story.condition.ConditionTypes;
import firefly520.fireflymc.story.definition.QuestDefinition;
import firefly520.fireflymc.story.definition.RequirementDefinition;
import firefly520.fireflymc.story.engine.InterestIndex;
import firefly520.fireflymc.story.engine.StoryRuntimeSnapshot;
import firefly520.fireflymc.story.loader.PreparedStoryData;
import firefly520.fireflymc.story.loader.StoryGraph;
import firefly520.fireflymc.story.progress.QuestState;
import firefly520.fireflymc.story.progress.RequirementProgressKey;
import firefly520.fireflymc.story.progress.StoryAttachments;
import firefly520.fireflymc.story.progress.StoryProgress;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Server-side integration checks for the public custom-event trigger boundary. */
@GameTestHolder("fireflymc")
@PrefixGameTestTemplate(false)
public final class StoryTriggerGameTests {
    private static final UUID PLAYER_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000000808");
    private static final ResourceLocation CHAPTER_ID =
        ResourceLocation.parse("fireflymc:trigger_game_test_chapter");
    private static final ResourceLocation QUEST_ID =
        ResourceLocation.parse("fireflymc:trigger_game_test_quest");
    private static final ResourceLocation EVENT_ID =
        ResourceLocation.parse("fireflymc:trigger_game_test_event");
    private static final RequirementProgressKey COUNTER_KEY =
        new RequirementProgressKey(QUEST_ID, "trigger", 1);

    private StoryTriggerGameTests() {}

    @GameTest(template = "story_empty", batch = "story-trigger", timeoutTicks = 100)
    public static void offThreadCustomEventRejectsReplacementPlayer(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        StoryRuntimeSnapshot previousSnapshot = StoryEngine.instance().snapshot();
        boolean previousEnabled = StoryEngine.instance().storyEnabled();
        ServerPlayer original = null;
        ServerPlayer replacement = null;
        boolean sequenceScheduled = false;
        try {
            StoryEngine.instance().setStoryEnabled(true);
            original = placePlayer(helper.getLevel(), PLAYER_ID, "story-trigger-original");
            openSession(original);
            ServerPlayer initialPlayer = original;
            StoryProgress progress = new StoryProgress();
            progress.activate(QUEST_ID);
            original.setData(StoryAttachments.PROGRESS.get(), progress);
            StoryEngine.install(triggerSnapshot());

            // Same-thread positive control: the first of two required events must advance.
            StoryTriggers.fireCustomEvent(server, original, EVENT_ID, 1);
            helper.assertValueEqual(progress.counter(COUNTER_KEY), 1, "trigger counter");

            AtomicReference<RuntimeException> triggerFailure = new AtomicReference<>();
            Thread triggerThread = new Thread(() -> {
                try {
                    StoryTriggers.fireCustomEvent(server, initialPlayer, EVENT_ID, 1);
                } catch (RuntimeException exception) {
                    triggerFailure.set(exception);
                }
            }, "story-trigger-off-thread");
            triggerThread.start();
            triggerThread.join();
            if (triggerFailure.get() != null) {
                helper.fail("Off-thread trigger failed: " + triggerFailure.get());
                return;
            }

            // The server thread is blocked in this GameTest, so this call cannot dispatch yet.
            helper.assertValueEqual(progress.counter(COUNTER_KEY), 1, "queued trigger counter");

            AtomicBoolean queuedAfterTrigger = new AtomicBoolean();
            Thread markerThread = new Thread(() ->
                server.execute(() -> queuedAfterTrigger.set(true)), "story-trigger-marker");
            markerThread.start();
            markerThread.join();

            server.getPlayerList().remove(original);
            replacement = placePlayer(helper.getLevel(), PLAYER_ID, "story-trigger-replacement");
            openSession(replacement);
            StoryProgress replacementProgress = new StoryProgress();
            replacementProgress.activate(QUEST_ID);
            replacement.setData(StoryAttachments.PROGRESS.get(), replacementProgress);
            ServerPlayer replacementPlayer = replacement;
            helper.assertTrue(
                server.getPlayerList().getPlayer(PLAYER_ID) == replacement,
                "replacement player should own the UUID");

            helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                    queuedAfterTrigger.get(),
                    "server executor should drain both queued commands"))
                .thenExecute(() -> {
                    try {
                        helper.assertTrue(
                            server.getPlayerList().getPlayer(PLAYER_ID) == replacementPlayer,
                            "replacement identity should remain current");
                        helper.assertValueEqual(
                            progress.counter(COUNTER_KEY),
                            1,
                            "stale original trigger counter");
                        helper.assertValueEqual(replacementProgress.counter(COUNTER_KEY), 0,
                            "queued event must not reach the replacement connection");
                        StoryTriggers.fireCustomEvent(server, replacementPlayer, EVENT_ID, 1);
                        helper.assertValueEqual(replacementProgress.counter(COUNTER_KEY), 1,
                            "current replacement connection should accept new events");
                    } finally {
                        server.getPlayerList().remove(replacementPlayer);
                        closeSession(replacementPlayer);
                        StoryEngine.instance().setStoryEnabled(previousEnabled);
                        StoryEngine.install(previousSnapshot);
                    }
                })
                .thenSucceed();
            sequenceScheduled = true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            helper.fail("GameTest worker interrupted: " + exception);
        } catch (RuntimeException exception) {
            helper.fail("GameTest setup failed: " + exception);
        } finally {
            if (!sequenceScheduled) {
                if (replacement != null && replacement.connection != null) {
                    server.getPlayerList().remove(replacement);
                }
                if (original != null && original.connection != null) {
                    server.getPlayerList().remove(original);
                }
                closeSession(original);
                closeSession(replacement);
                StoryEngine.instance().setStoryEnabled(previousEnabled);
                StoryEngine.install(previousSnapshot);
            }
        }
    }

    private static StoryRuntimeSnapshot triggerSnapshot() {
        QuestDefinition quest = new QuestDefinition(
            QUEST_ID,
            CHAPTER_ID,
            Component.literal("Trigger GameTest"),
            Component.literal("Actual server.execute identity boundary"),
            List.of(),
            "",
            false,
            List.of(new RequirementDefinition(
                "trigger",
                1,
                new ConditionTypes.CustomEventCondition(EVENT_ID, 2))),
            List.of());
        return new StoryRuntimeSnapshot(
            Map.of(),
            Map.of(QUEST_ID, quest),
            InterestIndex.fromDeclarations(Map.of(
                new PreparedStoryData.IndexDeclaration(
                    QUEST_ID,
                    "requirements[0]",
                    PreparedStoryData.IndexDeclaration.Kind.CUSTOM_EVENT,
                    EVENT_ID.toString(),
                    null),
                Set.of(EVENT_ID))),
            StoryGraph.fromQuests(Map.of(QUEST_ID, quest)),
            "8".repeat(64));
    }

    private static ServerPlayer placePlayer(
        ServerLevel level,
        UUID playerId,
        String name
    ) {
        MinecraftServer server = level.getServer();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
            new GameProfile(playerId, name),
            false);
        ServerPlayer player = new ServerPlayer(
            server,
            level,
            cookie.gameProfile(),
            cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    private static void openSession(ServerPlayer player) {
        if (player.server.isDedicatedServer()) {
            ModPayloadHandler.VERIFIED_PLAYERS.put(player.getUUID(), true);
            ModPayloadHandler.CONFIRMED_PLAYERS.put(player.getUUID(), true);
            ModPayloadHandler.PASSWORD_VERIFIED_PLAYERS.put(player.getUUID(), true);
        }
    }

    private static void closeSession(ServerPlayer player) {
        if (player == null) {
            return;
        }
        ModPayloadHandler.VERIFIED_PLAYERS.remove(player.getUUID());
        ModPayloadHandler.CONFIRMED_PLAYERS.remove(player.getUUID());
        ModPayloadHandler.PASSWORD_VERIFIED_PLAYERS.remove(player.getUUID());
    }
}
