package firefly520.fireflymc.story;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import firefly520.fireflymc.network.ModPayloadHandler;
import firefly520.fireflymc.network.ShowRulesPayload;
import firefly520.fireflymc.network.StoryProgressSnapshotPayload;
import firefly520.fireflymc.story.adapter.StoryGameEvents;
import firefly520.fireflymc.story.adapter.StorySessionGate;
import firefly520.fireflymc.story.condition.ConditionTypes;
import firefly520.fireflymc.story.definition.QuestDefinition;
import firefly520.fireflymc.story.definition.RequirementDefinition;
import firefly520.fireflymc.story.engine.InterestIndex;
import firefly520.fireflymc.story.engine.StoryEventType;
import firefly520.fireflymc.story.engine.StoryRuntimeSnapshot;
import firefly520.fireflymc.story.loader.StoryGraph;
import firefly520.fireflymc.story.progress.QuestState;
import firefly520.fireflymc.story.progress.StoryAttachments;
import firefly520.fireflymc.story.progress.StoryProgress;
import firefly520.fireflymc.story.progress.WorldStoryState;
import firefly520.fireflymc.story.sync.StorySyncService;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.SwampHutPiece;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Real server integration; separate batches isolate the singleton runtime during reloads. */
@GameTestHolder("fireflymc")
@PrefixGameTestTemplate(false)
public final class StoryGameTests {
    private StoryGameTests() {}

    @GameTest(template = "story_empty", batch = "story-prologue", timeoutTicks = 200)
    public static void prologueSurvivesDeathAndRelogin(GameTestHelper helper) throws Exception {
        MinecraftServer server = helper.getLevel().getServer();
        WorldStoryState world = WorldStoryState.get(server);
        CompoundTag worldBefore = world.serializeNBT(server.registryAccess());
        try (PlayerSession session = new PlayerSession(helper, UUID.randomUUID(), "story-prologue")) {
            ServerPlayer player = session.player;
            StoryProgress progress = player.getData(StoryAttachments.PROGRESS.get());
            assertState(helper, progress, "first_light", QuestState.COMPLETED);
            assertState(helper, progress, "find_memory", QuestState.ACTIVE);
            List<StoryProgressSnapshotPayload> login = session.snapshots();
            helper.assertTrue(!login.isEmpty(), "login must send the story snapshot");
            helper.assertTrue(login.getLast().activeQuests().contains(quest("find_memory")),
                "login snapshot must include the bootstrapped quest");

            ItemEntity shard = new ItemEntity(player.level(), player.getX(), player.getY(), player.getZ(),
                new ItemStack(Items.ECHO_SHARD));
            shard.setNoPickUpDelay();
            try {
                shard.playerTouch(player);
            } finally {
                shard.discard();
            }
            assertState(helper, progress, "find_memory", QuestState.COMPLETED);
            assertState(helper, progress, "heard_voice", QuestState.COMPLETED);
            assertState(helper, progress, "quiet_night", QuestState.ACTIVE);
            helper.assertValueEqual(progress.getVar("prologue.memory_resonance"), 1, "bridge variable");
            flushStory(server);
            helper.assertValueEqual(session.snapshots().size(), 1, "one snapshot for the completion chain");

            CompoundTag beforeDeath = progress.serializeNBT(server.registryAccess());
            session.player = server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
            player = session.player;
            StoryProgress copied = player.getData(StoryAttachments.PROGRESS.get());
            helper.assertTrue(copied != progress, "death must copy rather than share mutable progress");
            helper.assertValueEqual(copied.serializeNBT(server.registryAccess()), beforeDeath,
                "attachment must survive the real respawn/clone path");
            helper.assertValueEqual(session.snapshots().size(), 1, "respawn must resend a snapshot");

            for (int count = 0; count < 3; count++) {
                Zombie zombie = new Zombie(helper.getLevel());
                zombie.setPos(player.getX(), player.getY(), player.getZ());
                helper.getLevel().addFreshEntity(zombie);
                try {
                    zombie.hurt(player.damageSources().playerAttack(player), Float.MAX_VALUE);
                } finally {
                    zombie.discard();
                }
            }
            assertState(helper, copied, "quiet_night", QuestState.COMPLETED);
            assertState(helper, copied, "old_trail", QuestState.ACTIVE);
            player.getInventory().add(new ItemStack(Items.TORCH, 8));
            StoryEngine.instance().reconcile(player);
            assertState(helper, copied, "old_trail", QuestState.COMPLETED);
            assertState(helper, copied, "embers_end", QuestState.COMPLETED);
            helper.assertValueEqual(copied.completedQuests().size(), 6, "all six prologue quests");
            helper.assertTrue(world.getFlag("prologue.awaiting_firefly"), "finale must set the shared flag");
            assertWorldDiskRoundTrip(helper, world);

            UUID playerId = player.getUUID();
            CompoundTag beforeLogout = copied.serializeNBT(server.registryAccess());
            session.close();
            try (PlayerSession rejoined = new PlayerSession(helper, playerId, "story-prologue")) {
                helper.assertValueEqual(rejoined.player.getData(StoryAttachments.PROGRESS.get())
                    .serializeNBT(server.registryAccess()), beforeLogout, "player disk persistence on relogin");
                helper.assertTrue(!rejoined.snapshots().isEmpty(), "relogin must send a fresh snapshot");
            }
        } finally {
            world.deserializeNBT(server.registryAccess(), worldBefore);
            world.setDirty();
        }
        helper.succeed();
    }

    @GameTest(template = "story_empty", batch = "story-thrown-pickup", timeoutTicks = 100)
    public static void playerThrownShardAdvancesAfterPickupDelay(GameTestHelper helper) {
        try (PlayerSession session = new PlayerSession(helper, UUID.randomUUID(), "story-toss")) {
            ServerPlayer player = session.player;
            player.setNoGravity(true);
            StoryProgress progress = player.getData(StoryAttachments.PROGRESS.get());
            assertState(helper, progress, "find_memory", QuestState.ACTIVE);
            player.getInventory().setItem(player.getInventory().selected, new ItemStack(Items.ECHO_SHARD));
            helper.assertTrue(player.drop(false), "the real player drop action must succeed");
            ItemEntity dropped = player.serverLevel().getEntitiesOfClass(ItemEntity.class,
                player.getBoundingBox().inflate(5), item -> item.getItem().is(Items.ECHO_SHARD))
                .stream().findFirst().orElseThrow();
            dropped.setNoGravity(true);
            for (int tick = 0; tick < 45 && dropped.isAlive(); tick++) {
                dropped.setPos(player.getX(), player.getY(), player.getZ());
                dropped.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                dropped.tick();
                player.doTick();
            }
            helper.assertValueEqual(player.getInventory().countItem(Items.ECHO_SHARD), 1,
                "the thrown shard must actually return to the inventory");
            assertState(helper, progress, "find_memory", QuestState.COMPLETED);
            assertState(helper, progress, "quiet_night", QuestState.ACTIVE);
            dropped.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "story_empty", batch = "story-rules-gate", timeoutTicks = 100)
    public static void restoredProgressWaitsForRulesAndRecoveryDoesNotGrantConsent(GameTestHelper helper) throws Exception {
        StoryEngine engine = StoryEngine.instance();
        StoryRuntimeSnapshot definitions = engine.snapshot();
        boolean enabled = engine.storyEnabled();
        try (PlayerSession session = new PlayerSession(helper, UUID.randomUUID(), "story-rules")) {
            ServerPlayer player = session.player;
            var persisted = player.getData(StoryAttachments.PROGRESS.get()).serializeNBT(player.registryAccess());
            // Preserve the player's attachment while recreating the runtime, as on restart.
            engine.onServerStopping(() -> true);
            StoryEngine.install(definitions);
            engine.onServerStarted(true);
            StoryProgress restored = new StoryProgress();
            restored.deserializeNBT(player.registryAccess(), persisted);
            player.setData(StoryAttachments.PROGRESS.get(), restored);
            ModPayloadHandler.CONFIRMED_PLAYERS.remove(player.getUUID());
            // GameTestServer is not a DedicatedServer; exercise its dedicated join boundary explicitly.
            engine.setSessionGate(target ->
                ModPayloadHandler.VERIFIED_PLAYERS.getOrDefault(target.getUUID(), false)
                    && ModPayloadHandler.PASSWORD_VERIFIED_PLAYERS.getOrDefault(target.getUUID(), false)
                    && ModPayloadHandler.CONFIRMED_PLAYERS.getOrDefault(target.getUUID(), false));
            pickupShard(player);
            helper.assertValueEqual(player.getInventory().countItem(Items.ECHO_SHARD), 1,
                "password-only sessions can physically collect an item");
            assertState(helper, restored, "find_memory", QuestState.ACTIVE);
            helper.assertTrue(restored.counters().isEmpty(), "missing rules must block story counters");
            session.snapshots();
            helper.assertValueEqual(player.server.getCommands().getDispatcher().execute(
                "fireflymc rules", player.createCommandSourceStack().withPermission(0)), 1,
                "ordinary players must be able to reopen the required dialog");
            boolean promptSent = false;
            Object packet;
            while ((packet = session.channel.readOutbound()) != null) {
                if (packet instanceof ClientboundCustomPayloadPacket custom
                    && custom.payload() instanceof ShowRulesPayload rules) {
                    promptSent |= rules.isFirstJoin();
                }
            }
            helper.assertTrue(promptSent, "recovery must resend the explicit confirmation dialog");
            helper.assertTrue(!ModPayloadHandler.CONFIRMED_PLAYERS.getOrDefault(player.getUUID(), false),
                "opening a dialog must never grant consent");
            ModPayloadHandler.CONFIRMED_PLAYERS.put(player.getUUID(), true);
            StoryGameEvents.onJoinCompleted(player);
            player.getInventory().removeItemNoUpdate(player.getInventory().selected);
            pickupShard(player);
            assertState(helper, restored, "find_memory", QuestState.COMPLETED);
            assertState(helper, restored, "quiet_night", QuestState.ACTIVE);
        } finally {
            engine.setSessionGate(StorySessionGate::isOpen);
            engine.setStoryEnabled(enabled);
            StoryEngine.install(definitions);
            engine.onServerStarted(true);
        }
        helper.succeed();
    }

    private static void pickupShard(ServerPlayer player) {
        ItemEntity shard = new ItemEntity(player.level(), player.getX(), player.getY(), player.getZ(),
            new ItemStack(Items.ECHO_SHARD));
        try {
            shard.setNoPickUpDelay();
            shard.playerTouch(player);
        } finally {
            shard.discard();
        }
    }

    @GameTest(template = "story_empty", batch = "story-commands", timeoutTicks = 100)
    public static void registeredCommandsAndScopedResetWork(GameTestHelper helper) throws Exception {
        MinecraftServer server = helper.getLevel().getServer();
        WorldStoryState world = WorldStoryState.get(server);
        CompoundTag worldBefore = world.serializeNBT(server.registryAccess());
        RecordingSource output = new RecordingSource();
        var source = server.createCommandSourceStack().withSource(output).withPermission(4);
        try (PlayerSession session = new PlayerSession(helper, UUID.randomUUID(), "story-commands")) {
            var dispatcher = server.getCommands().getDispatcher();
            StoryProgress progress = session.player.getData(StoryAttachments.PROGRESS.get());
            helper.assertValueEqual(dispatcher.execute("fireflymc story status story-commands", source), 1,
                "registered status command");
            helper.assertValueEqual(dispatcher.execute(
                "fireflymc story counter fireflymc:prologue/find_memory memory set 1 story-commands", source),
                1, "registered counter command");
            assertState(helper, progress, "heard_voice", QuestState.COMPLETED);
            dispatcher.execute("fireflymc story setflag world gametest.preserved true", source);
            output.lines.clear();
            dispatcher.execute("fireflymc story reset player story-commands", source);
            assertState(helper, progress, "heard_voice", QuestState.COMPLETED);
            String confirmation = output.lines.stream()
                .filter(line -> line.contains("fireflymc story reset confirm "))
                .findFirst().orElseThrow();
            String token = confirmation.substring(confirmation.indexOf("confirm ") + 8).trim();
            helper.assertValueEqual(dispatcher.execute("fireflymc story reset confirm " + token, source), 1,
                "single-use reset confirmation");
            assertState(helper, progress, "first_light", QuestState.COMPLETED);
            assertState(helper, progress, "find_memory", QuestState.ACTIVE);
            helper.assertTrue(world.getFlag("gametest.preserved"), "player reset must preserve world scope");
            helper.assertValueEqual(dispatcher.execute("fireflymc story reset confirm " + token, source), 0,
                "used reset token must be rejected");

            helper.assertValueEqual(dispatcher.execute(
                "fireflymc story setvar player story-commands gametest.steps add 2", source), 1,
                "player variable command");
            helper.assertValueEqual(progress.getVar("gametest.steps"), 2, "player variable value");
            helper.assertValueEqual(dispatcher.execute(
                "fireflymc story setvar world gametest.steps set 4", source), 1, "world set result");
            helper.assertValueEqual(dispatcher.execute(
                "fireflymc story setvar world gametest.steps add 3", source), 1, "world add result");
            helper.assertValueEqual(world.getVar("gametest.steps"), 7, "world set/add commands");
            helper.assertValueEqual(dispatcher.execute(
                "fireflymc story unlock fireflymc:prologue/old_trail story-commands", source), 1,
                "unlock command with a namespaced quest");
            helper.assertValueEqual(dispatcher.execute(
                "fireflymc story complete fireflymc:prologue/old_trail story-commands", source), 1,
                "forced completion must run normal actions");
            assertState(helper, progress, "embers_end", QuestState.COMPLETED);
            helper.assertValueEqual(dispatcher.execute(
                "fireflymc story complete fireflymc:missing story-commands", source), 0,
                "unknown quests must produce an operator error");
            output.lines.clear();
            dispatcher.execute("fireflymc story reset world", source);
            String worldConfirmation = output.lines.stream()
                .filter(line -> line.contains("fireflymc story reset confirm "))
                .findFirst().orElseThrow();
            String worldToken = worldConfirmation.substring(worldConfirmation.indexOf("confirm ") + 8).trim();
            dispatcher.execute("fireflymc story reset confirm " + worldToken, source);
            helper.assertTrue(world.flags().isEmpty() && world.vars().isEmpty(), "world reset scope");
            helper.assertValueEqual(progress.getVar("gametest.steps"), 2, "world reset preserves player state");
            assertState(helper, progress, "embers_end", QuestState.COMPLETED);
        } finally {
            world.deserializeNBT(server.registryAccess(), worldBefore);
            world.setDirty();
        }
        helper.succeed();
    }

    @GameTest(template = "story_empty", batch = "story-probes", timeoutTicks = 100)
    public static void currentStateProbesUseThePlayersLoadedWorld(GameTestHelper helper) {
        StoryEngine engine = StoryEngine.instance();
        StoryRuntimeSnapshot previous = engine.snapshot();
        try (PlayerSession session = new PlayerSession(helper, UUID.randomUUID(), "story-probes")) {
            ServerPlayer player = session.player;
            var state = engine.commandContext(player).currentState();
            var level = player.serverLevel();
            ResourceLocation dimension = level.dimension().location();
            ResourceLocation biome = level.getBiome(player.blockPosition()).unwrapKey().orElseThrow().location();
            helper.assertTrue(state.inDimension(dimension), "current dimension probe");
            helper.assertTrue(state.inBiome(biome), "current biome probe");
            helper.assertTrue(state.atLocation(dimension, player.getX(), player.getY(), player.getZ(), 0),
                "exact location probe");
            helper.assertTrue(!state.atLocation(dimension, player.getX() + 10, player.getY(), player.getZ(), 1),
                "location radius must be respected");

            var structureId = ResourceLocation.parse("minecraft:swamp_hut");
            var structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(structureId);
            BlockPos pos = player.blockPosition();
            var chunk = level.getChunkAt(pos);
            Map<net.minecraft.world.level.levelgen.structure.Structure, StructureStart> oldStarts =
                Map.copyOf(chunk.getAllStarts());
            var oldReferences = new java.util.HashMap<>(chunk.getAllReferences());
            var piece = new SwampHutPiece(level.random, pos.getX(), pos.getZ());
            var box = piece.getBoundingBox();
            piece.move(pos.getX() - box.minX(), pos.getY() - box.minY(), pos.getZ() - box.minZ());
            try {
                chunk.setStartForStructure(structure,
                    new StructureStart(structure, chunk.getPos(), 0, new PiecesContainer(List.of(piece))));
                chunk.addReferenceForStructure(structure, chunk.getPos().toLong());
                helper.assertTrue(state.inStructure(structureId), "probe must see the loaded structure piece");
                var structureTag = ResourceLocation.parse("minecraft:cats_spawn_in");
                helper.assertTrue(state.inStructureTag(structureTag), "structure tag probe");
            } finally {
                chunk.setAllStarts(oldStarts);
                chunk.setAllReferences(oldReferences);
            }
            var probe = ResourceLocation.parse("fireflymc:gametest/probes");
            QuestDefinition quest = new QuestDefinition(probe, quest("chapter"), Component.empty(),
                Component.empty(), List.of(), null, true, List.of(
                    new RequirementDefinition("dimension", 1, new ConditionTypes.EnterDimensionCondition(dimension)),
                    new RequirementDefinition("biome", 1, new ConditionTypes.EnterBiomeCondition(biome, null)),
                    new RequirementDefinition("location", 1, new ConditionTypes.LocationCondition(
                        dimension, player.getX(), player.getY(), player.getZ(), 0))), List.of());
            Map<ResourceLocation, QuestDefinition> quests = Map.of(probe, quest);
            StoryEngine.install(new StoryRuntimeSnapshot(Map.of(), quests, InterestIndex.empty(),
                StoryGraph.fromQuests(quests), "b".repeat(64)));
            engine.reconcile(player);
            helper.assertValueEqual(player.getData(StoryAttachments.PROGRESS.get()).getQuestState(probe),
                QuestState.COMPLETED, "activation must evaluate the real current-state probes");
        } finally {
            StoryEngine.install(previous);
        }
        helper.succeed();
    }

    @GameTest(template = "story_empty", batch = "story-reload", timeoutTicks = 600)
    public static void realDatapackReloadIsAtomicAndRebuildsTheIndex(GameTestHelper helper) throws Exception {
        MinecraftServer server = helper.getLevel().getServer();
        StoryRuntimeSnapshot original = StoryEngine.instance().snapshot();
        List<String> selected = List.copyOf(server.getPackRepository().getSelectedIds());
        Path pack = Files.createTempDirectory(server.getWorldPath(LevelResource.DATAPACK_DIR), "story-gametest-");
        String packId = "file/" + pack.getFileName();
        Path override = pack.resolve("data/fireflymc/story/quests/prologue/find_memory.json");
        try {
            Files.writeString(pack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":48,"description":"Temporary story reload integration test"}}
                """);
            Files.createDirectories(override.getParent());
            Files.writeString(override, "{ broken JSON");
            server.getPackRepository().reload();
            List<String> withTestPack = new ArrayList<>(selected);
            withTestPack.add(packId);
            var badReload = server.reloadResources(withTestPack);
            helper.assertTrue(badReload.isCompletedExceptionally(),
                "malformed datapack must reject the reload");
            helper.assertTrue(badReload.handle((unused, failure) -> failure.toString()).join()
                .contains("data/fireflymc/story/quests/prologue/find_memory.json"),
                "parse failure must identify the exact datapack file");
            helper.assertTrue(StoryEngine.instance().snapshot() == original,
                "failed reload must keep the exact previous runtime snapshot");
            helper.assertTrue(original.interestIndex().candidates(StoryEventType.ITEM_PICKUP,
                ResourceLocation.parse("minecraft:echo_shard")).contains(quest("find_memory")),
                "old behavior must remain available after failed reload");

            JsonObject replacement;
            try (var reader = server.getResourceManager().getResource(ResourceLocation.parse(
                    "fireflymc:story/quests/prologue/find_memory.json")).orElseThrow().openAsReader()) {
                replacement = JsonParser.parseReader(reader).getAsJsonObject();
            }
            replacement.add("title", JsonParser.parseString("{\"text\":\"Reloaded memory\"}"));
            replacement.getAsJsonArray("requirements").get(0).getAsJsonObject()
                .addProperty("item", "minecraft:diamond");
            Files.writeString(override, replacement.toString());
            server.reloadResources(withTestPack).join();
            StoryRuntimeSnapshot changed = StoryEngine.instance().snapshot();
            helper.assertTrue(!changed.revision().equals(original.revision()), "override must change storyRevision");
            helper.assertTrue(changed.interestIndex().candidates(StoryEventType.ITEM_PICKUP,
                ResourceLocation.parse("minecraft:diamond")).contains(quest("find_memory")),
                "clean reload must publish the new pickup index");
            helper.assertTrue(changed.interestIndex().candidates(StoryEventType.ITEM_PICKUP,
                ResourceLocation.parse("minecraft:echo_shard")).isEmpty(), "old index entries must be removed");
        } finally {
            server.reloadResources(selected).join();
            try (var paths = Files.walk(pack)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
            server.getPackRepository().reload();
        }
        helper.assertValueEqual(StoryEngine.instance().snapshot().revision(), original.revision(),
            "removing the override must restore the bundled revision");
        helper.succeed();
    }

    private static void assertWorldDiskRoundTrip(GameTestHelper helper, WorldStoryState world) throws Exception {
        MinecraftServer server = helper.getLevel().getServer();
        server.overworld().getDataStorage().save();
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(WorldStoryState.DATA_NAME + ".dat");
        CompoundTag disk = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        WorldStoryState loaded = new WorldStoryState();
        loaded.deserializeNBT(server.registryAccess(), disk.getCompound("data"));
        helper.assertValueEqual(loaded.flags(), world.flags(), "world flags from actual SavedData disk file");
        helper.assertValueEqual(loaded.vars(), world.vars(), "world variables from actual SavedData disk file");
    }

    private static void assertState(GameTestHelper helper, StoryProgress progress, String name, QuestState expected) {
        helper.assertValueEqual(progress.getQuestState(quest(name)), expected, name);
    }

    private static ResourceLocation quest(String name) {
        return ResourceLocation.parse("fireflymc:prologue/" + name);
    }

    private static void flushStory(MinecraftServer server) {
        StoryEngine.instance().onServerTick(server);
        StorySyncService.instance().flush(server);
    }

    private static final class RecordingSource implements CommandSource {
        final List<String> lines = new ArrayList<>();
        @Override public void sendSystemMessage(Component message) { lines.add(message.getString()); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
    }

    private static final class PlayerSession implements AutoCloseable {
        ServerPlayer player;
        final EmbeddedChannel channel;
        boolean closed;

        PlayerSession(GameTestHelper helper, UUID id, String name) {
            MinecraftServer server = helper.getLevel().getServer();
            var cookie = new CommonListenerCookie(new GameProfile(id, name), 0,
                ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
            player = new ServerPlayer(server, helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            channel = new EmbeddedChannel(connection);
            NetworkRegistry.configureMockConnection(connection);
            server.getPlayerList().placeNewPlayer(connection, player, cookie);
            player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            ModPayloadHandler.VERIFIED_PLAYERS.put(id, true);
            ModPayloadHandler.CONFIRMED_PLAYERS.put(id, true);
            ModPayloadHandler.PASSWORD_VERIFIED_PLAYERS.put(id, true);
            StoryGameEvents.onJoinCompleted(player);
        }

        List<StoryProgressSnapshotPayload> snapshots() {
            List<StoryProgressSnapshotPayload> result = new ArrayList<>();
            Object packet;
            while ((packet = channel.readOutbound()) != null) {
                if (packet instanceof ClientboundCustomPayloadPacket custom
                    && custom.payload() instanceof StoryProgressSnapshotPayload snapshot) {
                    result.add(snapshot);
                }
            }
            return result;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            player.server.getPlayerList().remove(player);
            net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
            StoryEngine.instance().cleanupPlayer(player);
            UUID id = player.getUUID();
            ModPayloadHandler.VERIFIED_PLAYERS.remove(id);
            ModPayloadHandler.CONFIRMED_PLAYERS.remove(id);
            ModPayloadHandler.PASSWORD_VERIFIED_PLAYERS.remove(id);
            channel.finishAndReleaseAll();
        }
    }
}
