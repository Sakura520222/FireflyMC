package firefly520.fireflymc.validation;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.processing.*;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.*;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Real dedicated native award hooks and generic processing; all forced saves belong to this isolated fixture. */
public final class ProcessingCallbackScenario implements AutoCloseable {
    private final MinecraftServer server;
    private final ServerPlayer owner;
    private final EmbeddedChannel channel;
    private final Actor actor;
    private final WorkspaceManager manager;
    private final WorkspaceManager.Workspace workspace;
    private final MaterialLedger ledger;
    private final MaterialInventoryService inventory;
    private final VirtualProcessingService processing;
    private final ToolRegistry tools = new ToolRegistry();
    private final List<String> passed = new ArrayList<>();
    private final AtomicReference<TaskControl> cancelDuringCallback = new AtomicReference<>();
    private final Consumer<PlayerEvent.ItemSmeltedEvent> cancelled;
    private final CompletableFuture<JsonObject> result;

    public ProcessingCallbackScenario(MinecraftServer server) throws Exception {
        if (!server.isDedicatedServer()) throw new IllegalStateException("DEDICATED_TEST_REQUIRED");
        this.server = server;
        UUID id = UUID.randomUUID();
        var cookie = new CommonListenerCookie(new GameProfile(id, "cb-" + id.toString().substring(0, 8)), 0,
                ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
        owner = new ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND); channel = new EmbeddedChannel(connection);
        NetworkRegistry.configureMockConnection(connection); server.getPlayerList().placeNewPlayer(connection, owner, cookie);
        ValidationHandshake.reply(owner); owner.setGameMode(GameType.SURVIVAL);
        actor = Actor.player(id, true, true, 0);
        Path root = Files.createTempDirectory(Path.of(".").toAbsolutePath(), "processing-callback-case-");
        manager = new WorkspaceManager(root); workspace = manager.getWorkspace(actor); ledger = new MaterialLedger(root);
        inventory = new MaterialInventoryService(server, player -> player == owner, new InstalledPermissionAdapters(server));
        inventory.bind(actor.owner(), new MaterialSourceGrants(workspace, System::currentTimeMillis));
        processing = new VirtualProcessingService(server, inventory, ledger, () -> 1); processing.register(tools);
        cancelled = event -> {
            var control = cancelDuringCallback.get();
            if (event.getEntity() == owner && control != null) control.stop("CANCELLED");
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, cancelled);
        result = CompletableFuture.supplyAsync(() -> {
            long started = System.nanoTime(); var report = new JsonObject();
            try { run(); report.addProperty("status", "PASSED"); }
            catch (Throwable failure) { failure.printStackTrace(); report.addProperty("status", "FAILED"); report.addProperty("error", failure.toString()); }
            report.add("passedChecks", AiJson.GSON.toJsonTree(passed)); report.addProperty("durationMillis", (System.nanoTime() - started) / 1_000_000);
            report.addProperty("scope", "Actual generic processing tool, real input ESCROW/save wait, native smelting callback and XP new/merged entities, real canonical saves; isolated dedicated world and mock player, no provider or graphical client.");
            return report;
        }, action -> Thread.ofPlatform().daemon(true).name("processing-callback-validation").start(action));
    }
    private void run() throws Exception {
        nativeAwards();
        var first = process("first thermal callback", false); saveAndAwait(first);
        require(main(() -> owner.getInventory().countItem(Items.GOLD_INGOT)) == 1, "generic-real-thermal-output-and-input-conservation");
        var afterFirst = ledger.get(actor, first);
        require(afterFirst.nativeSaves().stream().filter(save -> save.kind().equals("callback-part")).count() == 2,
                "generic-native-callback-and-xp-each-have-own-save-obligation");
        var second = process("same already unlocked thermal recipe", false); saveAndAwait(second);
        require(ledger.get(actor, second).saved(), "same-recipe-unchanged-recipebook-and-advancements-have-real-file-receipts");
        var interrupted = process("cancel during accepted native callback", true);
        require(ledger.get(actor, interrupted).uncertain(), "cancelled-incomplete-callback-prefix-retains-recovery");
        main(() -> { saveNative(); return true; });
        inventory.nativeSaves().awaitJournalled(interrupted).toCompletableFuture().get(30, TimeUnit.SECONDS);
        var retained = ledger.get(actor, interrupted);
        require(retained.nativeSaves().stream().anyMatch(save -> save.kind().equals("callback-part") && !save.receipt().isEmpty()),
                "accepted-callback-observation-and-native-save-survive-cancelled-waiter");
        require(retained.uncertain() && retained.products().stream().anyMatch(stack -> stack.itemJson().contains("gold_ingot")),
                "cancelled-prefix-keeps-real-products-without-replaying-or-delivering");
    }
    private void nativeAwards() throws Exception {
        NativeProcessingCallback.Outcome fresh = main(() -> {
            Vec3 pos = owner.position().add(5, 12, 5);
            return NativeProcessingCallback.run(owner, () -> ExperienceOrb.award(owner.serverLevel(), pos, 3), 3);
        });
        require(fresh.completed() && fresh.observations().contains("\"spawned\":true"), "native-award-observes-exact-new-orb");
        NativeProcessingCallback.Outcome merged = main(() -> {
            var level = owner.serverLevel(); Vec3 pos = owner.position().add(-5, 12, -5);
            level.getRandom().setSeed(8748231L); int residue = level.getRandom().nextInt(40);
            var orb = new ExperienceOrb(level, pos.x, pos.y, pos.z, 3); orb.setId(2_000_000 + residue);
            if (!level.addFreshEntity(orb)) throw new IllegalStateException("MERGE_FIXTURE_ORB_REJECTED");
            level.getRandom().setSeed(8748231L);
            var outcome = NativeProcessingCallback.run(owner, () -> ExperienceOrb.award(level, pos, 3), 3);
            if (!outcome.observations().contains(orb.getUUID().toString()) || !outcome.observations().contains("\"beforeCount\":1")
                    || !outcome.observations().contains("\"afterCount\":2") || !outcome.observations().contains("\"spawned\":false"))
                throw new IllegalStateException("PREEXISTING_ORB_MERGE_NOT_OBSERVED");
            return outcome;
        });
        require(merged.completed(), "native-award-observes-actual-preexisting-orb-count-increment");
        require(fresh.save() != null && merged.save() != null && !fresh.save().saved().isDone() && !merged.save().saved().isDone(),
                "native-xp-callback-is-not-a-native-disk-save");
        main(() -> { saveNative(); return true; });
        awaitSave(fresh.save(), "new-orb"); awaitSave(merged.save(), "preexisting-award-merge");
        passed.add("new-and-preexisting-merged-orb-each-require-actual-native-entity-region-write");
        var picked = main(() -> {
            int before = owner.totalExperience; owner.takeXpDelay = 0;
            var outcome = NativeProcessingCallback.run(owner, () -> ExperienceOrb.award(owner.serverLevel(), owner.position(), 3), 3);
            var orb = observedOrb(outcome); orb.playerTouch(owner);
            if (!orb.isRemoved() || owner.totalExperience != before + 3) throw new IllegalStateException("NATIVE_PICKUP_NOT_EXECUTED");
            return outcome;
        });
        require(picked.completed() && !picked.save().saved().isDone(), "normal-pickup-before-first-orb-save-is-not-a-disk-proof");
        main(() -> { saveNative(); return true; }); awaitSave(picked.save(), "native-pickup");
        passed.add("normal-native-pickup-hands-award-proof-to-recipient-player-save-and-deletion-region");
        var mended = main(() -> {
            var sword = new ItemStack(Items.IRON_SWORD); sword.setDamageValue(30);
            sword.enchant(owner.serverLevel().holderOrThrow(Enchantments.MENDING), 1); owner.getInventory().setItem(0, sword);
            owner.getInventory().selected = 0; owner.takeXpDelay = 0;
            var outcome = NativeProcessingCallback.run(owner, () -> ExperienceOrb.award(owner.serverLevel(), owner.position(), 3), 3);
            var orb = observedOrb(outcome); orb.playerTouch(owner);
            if (!orb.isRemoved() || sword.getDamageValue() >= 30) throw new IllegalStateException("NATIVE_MENDING_NOT_EXECUTED");
            return outcome;
        });
        main(() -> { saveNative(); return true; }); awaitSave(mended.save(), "native-mending");
        passed.add("native-mending-keeps-real-repair-rule-and-requires-recipient-canonical-player-save");
        var naturallyMerged = main(() -> {
            var level = owner.serverLevel(); Vec3 pos = owner.position().add(10, 12, 10);
            var outcome = NativeProcessingCallback.run(owner, () -> ExperienceOrb.award(level, pos, 3), 3);
            var donor = observedOrb(outcome);
            var survivor = new ExperienceOrb(level, pos.x, pos.y, pos.z, 3); survivor.setId(3_000_000 + Math.floorMod(donor.getId(), 40));
            if (!level.addFreshEntity(survivor)) throw new IllegalStateException("NATURAL_MERGE_SURVIVOR_REJECTED");
            level.tickNonPassenger(survivor);
            if (!donor.isRemoved()) throw new IllegalStateException("NORMAL_TICK_MERGE_NOT_EXECUTED");
            return outcome;
        });
        require(naturallyMerged.completed() && !naturallyMerged.save().saved().isDone(), "normal-tick-merge-before-first-save-keeps-pending-proof");
        main(() -> { saveNative(); return true; }); awaitSave(naturallyMerged.save(), "normal-tick-merge");
        passed.add("normal-native-orb-merge-hands-proof-to-survivor-entity-save-and-donor-deletion-region");
        var chain = main(() -> {
            var level = owner.serverLevel(); Vec3 pos = owner.position().add(14, 12, -14);
            var donorAward = NativeProcessingCallback.run(owner, () -> ExperienceOrb.award(level, pos, 3), 3);
            var donor = observedOrb(donorAward);
            Consumer<EntityJoinLevelEvent> matchGroup = event -> {
                if (event.getEntity() instanceof ExperienceOrb orb && NativeExperienceAward.isExpectedCandidate(orb))
                    orb.setId(4_000_000 + Math.floorMod(donor.getId(), 40));
            };
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, matchGroup);
            NativeProcessingCallback.Outcome survivorAward;
            try { survivorAward = NativeProcessingCallback.run(owner, () -> ExperienceOrb.award(level, pos.add(2, 0, 0), 3), 3); }
            finally { NeoForge.EVENT_BUS.unregister(matchGroup); }
            var survivor = observedOrb(survivorAward); survivor.setPos(pos); level.tickNonPassenger(survivor);
            if (!donor.isRemoved()) throw new IllegalStateException("OWNED_CHAIN_MERGE_NOT_EXECUTED");
            owner.takeXpDelay = 0; survivor.playerTouch(owner);
            owner.takeXpDelay = 0; survivor.playerTouch(owner);
            if (!survivor.isRemoved()) throw new IllegalStateException("OWNED_CHAIN_PICKUP_NOT_EXECUTED");
            return List.of(donorAward, survivorAward);
        });
        main(() -> { saveNative(); return true; });
        for (int index = 0; index < chain.size(); index++) awaitSave(chain.get(index).save(), "owned-merge-pickup-chain-" + index);
        passed.add("two-owned-orb-receipts-merge-and-pickup-transitively-without-a-cycle-or-count-recovery");
    }
    private ExperienceOrb observedOrb(NativeProcessingCallback.Outcome outcome) {
        if (!outcome.completed()) throw new IllegalStateException("NATIVE_AWARD_FAILED:" + outcome.code());
        var values = JsonParser.parseString(outcome.observations()).getAsJsonArray();
        UUID id = UUID.fromString(values.get(0).getAsJsonObject().get("entity").getAsString());
        var entity = owner.serverLevel().getEntity(id);
        if (!(entity instanceof ExperienceOrb orb)) throw new IllegalStateException("EXACT_OBSERVED_ORB_MISSING");
        return orb;
    }
    private void awaitSave(NativeSaveBarrier.Receipt receipt, String phase) throws Exception {
        if (receipt == null) throw new IllegalStateException("MISSING_NATIVE_SAVE_RECEIPT:" + phase);
        try { receipt.saved().get(30, TimeUnit.SECONDS); }
        catch (TimeoutException timeout) { throw new IllegalStateException("NATIVE_SAVE_STILL_PENDING:" + phase, timeout); }
    }
    private UUID process(String prompt, boolean cancel) throws Exception {
        main(() -> {
            var recipe = server.getRecipeManager().byKey(ResourceLocation.parse("minecraft:gold_ingot_from_smelting_gold_ore")).orElseThrow();
            if (!(recipe.value() instanceof net.minecraft.world.item.crafting.AbstractCookingRecipe thermal) || thermal.getExperience() != 1.0F)
                throw new IllegalStateException("ONE_NATIVE_XP_THERMAL_FIXTURE_REQUIRED");
            owner.getInventory().clearContent(); owner.getInventory().setItem(0, new ItemStack(Items.GOLD_ORE)); return true;
        });
        var task = AgentTask.create(actor.owner(), UUID.randomUUID(), prompt); workspace.conversation().append(List.of(Draft.checkpoint(task)));
        var ctx = new AgentTool.Context(workspace, actor, new TaskControl(120000), task.id());
        var args = new JsonObject(); args.addProperty("recipeId", "minecraft:gold_ingot_from_smelting_gold_ore");
        var planned = tools.get("processing_plan").execute(ctx, args);
        if (!planned.success()) throw new IllegalStateException("GENERIC_PLAN_FAILED:" + planned.errorCode());
        UUID id = UUID.fromString(planned.observations().get("planId").getAsString());
        var execute = new JsonObject(); execute.addProperty("planId", id.toString()); execute.addProperty("planHash", planned.observations().get("planHash").getAsString());
        AgentTool.Deferred deferred;
        try { tools.get("processing_execute").execute(ctx, execute); throw new IllegalStateException("GENERIC_DID_NOT_RELEASE_INPUT_SAVE_WAIT"); }
        catch (AgentTool.Deferred waiting) { deferred = waiting; }
        var held = ledger.get(actor, id);
        require(held.state() == MaterialLedger.State.ESCROWED && held.products().isEmpty() && held.samplingEvidence().isEmpty()
                && !held.inputsSaved() && held.claims().stream().allMatch(claim -> claim.remaining() == 0), "generic-escrow-holds-inputs-with-no-products-or-rng-before-native-save");
        ctx.control().stop("TASK_SLICE_FINISHED");
        main(() -> { saveNative(); return true; }); deferred.readiness().toCompletableFuture().get(30, TimeUnit.SECONDS);
        var fresh = new AgentTool.Context(workspace, actor, new TaskControl(120000), task.id());
        if (cancel) cancelDuringCallback.set(fresh.control());
        try {
            var completed = deferred.continuation().resume(fresh);
            if (!cancel) require(completed.success(), "generic-same-approved-call-finishes-with-fresh-context");
        } catch (TaskControl.Stopped stopped) { if (!cancel) throw stopped; }
        finally { cancelDuringCallback.set(null); }
        if (!cancel) {
            var before = ledger.get(actor, id);
            tools.get("processing_execute").execute(new AgentTool.Context(workspace, actor, new TaskControl(120000), task.id()), execute);
            require(before.equals(ledger.get(actor, id)), "generic-historical-operation-does-not-retake-reroll-or-repeat-callback");
        }
        return id;
    }
    private void saveAndAwait(UUID id) throws Exception {
        main(() -> { saveNative(); return true; });
        inventory.nativeSaves().awaitJournalled(id).toCompletableFuture().get(30, TimeUnit.SECONDS);
        require(ledger.get(actor, id).saved(), "generic-input-output-callback-and-xp-have-positive-native-save-proofs");
    }
    private void saveNative() {
        server.getPlayerList().saveAll(); owner.getStats().save(); owner.getAdvancements().save(); owner.serverLevel().save(null, true, false);
    }
    private <T> T main(Callable<T> action) throws Exception {
        var future = new CompletableFuture<T>(); server.execute(() -> {
            try { future.complete(action.call()); } catch (Throwable failure) { future.completeExceptionally(failure); }
        }); return future.get(30, TimeUnit.SECONDS);
    }
    private void require(boolean condition, String check) { if (!condition) throw new IllegalStateException(check); passed.add(check); }
    public JsonObject tick() { inventory.tick(); processing.tick(); return result.isDone() ? result.join() : null; }
    @Override public void close() throws Exception {
        NeoForge.EVENT_BUS.unregister(cancelled); processing.close(); inventory.close(); processing.awaitEvidenceStopped(10000);
        manager.close(); server.getPlayerList().remove(owner); net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete(); channel.finishAndReleaseAll();
    }
}
