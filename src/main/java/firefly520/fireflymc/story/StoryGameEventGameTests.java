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
import firefly520.fireflymc.story.progress.RequirementProgressKey;
import firefly520.fireflymc.story.progress.StoryAttachments;
import firefly520.fireflymc.story.progress.StoryProgress;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real NeoForge bus checks for Task 10 adapter extraction and dispatch signatures. */
@GameTestHolder("fireflymc")
@PrefixGameTestTemplate(false)
public final class StoryGameEventGameTests {
    private static final ResourceLocation CHAPTER =
        ResourceLocation.parse("fireflymc:game_event_chapter");
    private static final ResourceLocation ECHO_SHARD =
        ResourceLocation.parse("minecraft:echo_shard");
    private static final ResourceLocation ZOMBIE =
        ResourceLocation.parse("minecraft:zombie");

    private StoryGameEventGameTests() {}

    @GameTest(timeoutTicks = 100)
    public static void pickupPostActuallyDispatchesMovedCount(GameTestHelper helper) {
        runBusTest(
            helper,
            Signal.PICKUP,
            UUID.fromString("00000000-0000-0000-0000-000000000902"),
            "game-event-pickup");
    }

    @GameTest(timeoutTicks = 100)
    public static void changedDimensionActuallyDispatchesDestination(
        GameTestHelper helper
    ) {
        runBusTest(
            helper,
            Signal.DIMENSION,
            UUID.fromString("00000000-0000-0000-0000-000000000903"),
            "game-event-dimension");
    }

    @GameTest(timeoutTicks = 100)
    public static void livingDeathActuallyDispatchesServerPlayerKill(
        GameTestHelper helper
    ) {
        runBusTest(
            helper,
            Signal.KILL,
            UUID.fromString("00000000-0000-0000-0000-000000000905"),
            "game-event-kill");
    }

    @GameTest(timeoutTicks = 100)
    public static void advancementEarnActuallyDispatchesResourceLocation(
        GameTestHelper helper
    ) {
        runBusTest(
            helper,
            Signal.ADVANCEMENT,
            UUID.fromString("00000000-0000-0000-0000-000000000906"),
            "game-event-advancement");
    }

    private static void runBusTest(
        GameTestHelper helper,
        Signal signal,
        UUID playerId,
        String playerName
    ) {
        StoryRuntimeSnapshot previousSnapshot = StoryEngine.instance().snapshot();
        boolean previousEnabled = StoryEngine.instance().storyEnabled();
        ServerPlayer player = null;
        Zombie killedZombie = null;
        try {
            StoryEngine.instance().setStoryEnabled(true);
            player = placePlayer(helper.getLevel(), playerId, playerName);
            openSession(player);

            ResourceLocation eventKey = eventKey(helper, signal);
            AdvancementHolder advancement = signal == Signal.ADVANCEMENT
                ? firstAdvancement(helper)
                : null;
            if (advancement != null) {
                eventKey = advancement.id();
            }
            StoryProgress progress = activateProgress(player, signal);
            StoryEngine.install(snapshot(signal, eventKey));

            switch (signal) {
                case PICKUP -> postPickup(helper, player);
                case DIMENSION -> postDimension(helper, player);
                case KILL -> killedZombie = postKill(helper, player);
                case ADVANCEMENT -> postAdvancement(helper, player, advancement);
            }
            assertSignal(helper, signal, progress);
            helper.succeed();
        } catch (RuntimeException exception) {
            helper.fail("GameTest failed: " + exception);
        } finally {
            if (killedZombie != null) {
                killedZombie.discard();
            }
            closeSession(player);
            removePlayer(player);
            StoryEngine.instance().setStoryEnabled(previousEnabled);
            StoryEngine.install(previousSnapshot);
        }
    }

    private static void postPickup(GameTestHelper helper, ServerPlayer player) {
        BlockPos position = helper.absolutePos(BlockPos.ZERO);
        ItemEntity itemEntity = new ItemEntity(
            helper.getLevel(),
            position.getX(),
            position.getY(),
            position.getZ(),
            new ItemStack(Items.ECHO_SHARD, 6));
        NeoForge.EVENT_BUS.post(new ItemEntityPickupEvent.Post(
            player,
            itemEntity,
            new ItemStack(Items.ECHO_SHARD, 10)));
    }

    private static void postDimension(GameTestHelper helper, ServerPlayer player) {
        ResourceKey<Level> origin = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.parse("fireflymc:test_origin"));
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerChangedDimensionEvent(
            player,
            origin,
            helper.getLevel().dimension()));
    }

    private static Zombie postKill(GameTestHelper helper, ServerPlayer player) {
        BlockPos position = helper.absolutePos(BlockPos.ZERO);
        Zombie zombie = new Zombie(helper.getLevel());
        zombie.setPos(position.getX(), position.getY(), position.getZ());
        helper.getLevel().addFreshEntity(zombie);
        DamageSource playerAttack = player.damageSources().playerAttack(player);
        NeoForge.EVENT_BUS.post(new LivingDeathEvent(zombie, playerAttack));
        return zombie;
    }

    private static void postAdvancement(
        GameTestHelper helper,
        ServerPlayer player,
        AdvancementHolder advancement
    ) {
        AdvancementProgress progress = player.getAdvancements()
            .getOrStartProgress(advancement);
        for (String criterion : progress.getRemainingCriteria()) {
            player.getAdvancements().award(advancement, criterion);
        }
        helper.assertTrue(
            player.getAdvancements().getOrStartProgress(advancement).isDone(),
            "advancement should be complete before EarnEvent dispatch");
        NeoForge.EVENT_BUS.post(new AdvancementEvent.AdvancementEarnEvent(
            player,
            advancement));
    }

    private static void assertSignal(
        GameTestHelper helper,
        Signal signal,
        StoryProgress progress
    ) {
        switch (signal) {
            case PICKUP -> helper.assertValueEqual(
                progress.counter(PickupKeys.KEY), 4, "moved pickup count");
            case DIMENSION -> helper.assertTrue(
                progress.satisfied().contains(DimensionKeys.KEY),
                "destination dimension should satisfy the sticky requirement");
            case KILL -> helper.assertValueEqual(
                progress.counter(KillKeys.KEY), 1, "server-player kill count");
            case ADVANCEMENT -> helper.assertTrue(
                progress.satisfied().contains(AdvancementKeys.KEY),
                "earned advancement should satisfy the sticky requirement");
        }
    }

    private static ResourceLocation eventKey(GameTestHelper helper, Signal signal) {
        return switch (signal) {
            case PICKUP -> ECHO_SHARD;
            case DIMENSION -> helper.getLevel().dimension().location();
            case KILL -> ZOMBIE;
            case ADVANCEMENT -> ResourceLocation.parse("fireflymc:unused-advancement");
        };
    }

    private static AdvancementHolder firstAdvancement(GameTestHelper helper) {
        return helper.getLevel().getServer()
            .getAdvancements()
            .getAllAdvancements()
            .stream()
            .min(Comparator.comparing(holder -> holder.id().toString()))
            .orElseThrow(() -> new IllegalStateException("No advancement is available"));
    }

    private static StoryProgress activateProgress(ServerPlayer player, Signal signal) {
        StoryProgress progress = player.getData(StoryAttachments.PROGRESS.get());
        progress.activate(signal.questId());
        player.setData(StoryAttachments.PROGRESS.get(), progress);
        return progress;
    }

    private static StoryRuntimeSnapshot snapshot(Signal signal, ResourceLocation eventKey) {
        RequirementDefinition requirement = switch (signal) {
            case PICKUP -> new RequirementDefinition(
                "pickup",
                1,
                new ConditionTypes.PickupCondition(eventKey, 4));
            case DIMENSION -> new RequirementDefinition(
                "dimension",
                1,
                new ConditionTypes.EnterDimensionCondition(eventKey));
            case KILL -> new RequirementDefinition(
                "kill",
                1,
                new ConditionTypes.KillCondition(eventKey, null, 1));
            case ADVANCEMENT -> new RequirementDefinition(
                "advancement",
                1,
                new ConditionTypes.AdvancementCondition(eventKey));
        };
        PreparedStoryData.IndexDeclaration.Kind indexKind = switch (signal) {
            case PICKUP -> PreparedStoryData.IndexDeclaration.Kind.ITEM;
            case DIMENSION -> PreparedStoryData.IndexDeclaration.Kind.DIMENSION;
            case KILL -> PreparedStoryData.IndexDeclaration.Kind.ENTITY;
            case ADVANCEMENT -> PreparedStoryData.IndexDeclaration.Kind.ADVANCEMENT;
        };
        QuestDefinition quest = new QuestDefinition(
            signal.questId(),
            CHAPTER,
            net.minecraft.network.chat.Component.empty(),
            net.minecraft.network.chat.Component.empty(),
            List.of(),
            null,
            false,
            List.of(requirement),
            List.of());
        Map<ResourceLocation, QuestDefinition> quests = Map.of(signal.questId(), quest);
        return new StoryRuntimeSnapshot(
            Map.of(),
            quests,
            InterestIndex.fromDeclarations(Map.of(
                new PreparedStoryData.IndexDeclaration(
                    signal.questId(),
                    "requirements[0]",
                    indexKind,
                    eventKey.toString()),
                Set.of(eventKey))),
            StoryGraph.fromQuests(quests),
            "task10-game-event-test");
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
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    private static void removePlayer(ServerPlayer player) {
        if (player != null && player.connection != null) {
            player.server.getPlayerList().remove(player);
        }
    }

    private enum Signal {
        PICKUP,
        DIMENSION,
        KILL,
        ADVANCEMENT;

        private ResourceLocation questId() {
            return switch (this) {
                case PICKUP -> ResourceLocation.parse("fireflymc:game_event_pickup");
                case DIMENSION -> ResourceLocation.parse("fireflymc:game_event_dimension");
                case KILL -> ResourceLocation.parse("fireflymc:game_event_kill");
                case ADVANCEMENT -> ResourceLocation.parse(
                    "fireflymc:game_event_advancement");
            };
        }
    }

    private static final class PickupKeys {
        private static final RequirementProgressKey KEY =
            new RequirementProgressKey(Signal.PICKUP.questId(), "pickup", 1);
    }

    private static final class DimensionKeys {
        private static final RequirementProgressKey KEY =
            new RequirementProgressKey(Signal.DIMENSION.questId(), "dimension", 1);
    }

    private static final class KillKeys {
        private static final RequirementProgressKey KEY =
            new RequirementProgressKey(Signal.KILL.questId(), "kill", 1);
    }

    private static final class AdvancementKeys {
        private static final RequirementProgressKey KEY =
            new RequirementProgressKey(Signal.ADVANCEMENT.questId(), "advancement", 1);
    }
}
