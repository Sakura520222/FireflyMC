package firefly520.fireflymc.ai.integration.maid;

import firefly520.fireflymc.ai.blueprint.NativeConstructionSave;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.integration.equipment.ReadOnlyNativeAccess;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.security.Actor;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.behavior.*;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.items.IItemHandler;

/** Audited native melee primitive. The caller owns fixed approval, leases, before-intent and durable outcome recording. */
final class MaidMeleeKernel {
    private static final String ROOT = "com.github.tartaricacid.touhoulittlemaid.";
    private static final Map<String, String> TARGETS = Map.of(
            "minecraft:zombie", "net.minecraft.world.entity.monster.Zombie", "minecraft:husk", "net.minecraft.world.entity.monster.Husk",
            "minecraft:drowned", "net.minecraft.world.entity.monster.Drowned", "minecraft:skeleton", "net.minecraft.world.entity.monster.Skeleton",
            "minecraft:stray", "net.minecraft.world.entity.monster.Stray", "minecraft:spider", "net.minecraft.world.entity.monster.Spider",
            "minecraft:cave_spider", "net.minecraft.world.entity.monster.CaveSpider");
    record Target(UUID id, String type, float health, float absorption, boolean alive, boolean removed, double x, double y, double z) {}
    record Snapshot(MaidControlPlan.NativeState maid, String backpackId, List<MaterialStack> inventory, MaterialStack hand,
                    MaterialStack offHand, List<MaterialStack> armour, List<MaterialStack> baubles, float maidHealth, float maidAbsorption, Target target) {
        Snapshot { inventory = List.copyOf(inventory); armour = List.copyOf(armour); baubles = List.copyOf(baubles); }
    }
    record State(MaidControlNative.Handle maid, LivingEntity target, MaidCarrierBridge.BackpackSource backpack,
                 BehaviorControl<LivingEntity> behavior, Snapshot before) {}
    record Outcome(boolean entered, boolean attempted, boolean verified, boolean killed, Snapshot before, Snapshot after, Target targetAfter,
                   List<MaidCombatScope.Drop> loot, List<String> experience, String reason, NativeSaveBarrier.Receipt save) {
        Outcome { loot = List.copyOf(loot); experience = List.copyOf(experience); }
    }
    private final MaterialInventoryService inventory;
    private final MaidControlNative control;
    private final MaidCombatPermission permissions;
    private final ReadOnlyNativeAccess api = new ReadOnlyNativeAccess();
    MaidMeleeKernel(net.minecraft.server.MinecraftServer server, MaterialInventoryService inventory, InstalledPermissionAdapters permissions, MaidControlNative control) {
        this.inventory = inventory; this.control = control; this.permissions = new MaidCombatPermission(server, permissions);
    }
    @SuppressWarnings("unchecked") State inspect(ServerPlayer player, Actor actor, UUID targetId) throws Exception {
        actor.requireAccess(player.getStringUUID());
        var maid = control.capture(player, player.getUUID());
        if (maid.entity().isNoAi() || !maid.state().brainMoving() || maid.state().seated() || maid.entity().getHealth() <= 4)
            throw new IllegalStateException("MAID_COMBAT_NATIVE_CONTROL_UNAVAILABLE");
        if (!(player.serverLevel().getEntity(targetId) instanceof LivingEntity target)) throw new IllegalStateException("MAID_COMBAT_TARGET_NOT_LOADED");
        if (!target.getClass().getName().equals(TARGETS.get(BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString())))
            throw new IllegalStateException("MAID_COMBAT_TARGET_EFFECT_ADAPTER_REQUIRED");
        permissions.require(player, maid.entity(), target);
        if (!nativeAttackAllowed(maid.entity(), target) || !maid.entity().canAttack(target) || maid.entity().isAlliedTo(target) || maid.entity().distanceToSqr(target) > 32 * 32)
            throw new IllegalStateException("MAID_COMBAT_NATIVE_TARGET_UNAVAILABLE");
        if (!(boolean) api.call(maid.entity(), ROOT + "entity.passive.EntityMaid", "isWithinRestriction", boolean.class,
                new Class<?>[]{net.minecraft.core.BlockPos.class}, target.blockPosition())) throw new IllegalStateException("MAID_COMBAT_OUTSIDE_NATIVE_RESTRICTION");
        var baubles = (IItemHandler) api.query(maid.entity(), ROOT + "entity.passive.EntityMaid", "getMaidBauble", api.type(ROOT + "inventory.handler.BaubleItemHandler"));
        for (int slot = 0; slot < baubles.getSlots(); slot++) if (!baubles.getStackInSlot(slot).isEmpty()) throw new IllegalStateException("MAID_COMBAT_BAUBLE_REQUIRES_ADAPTER");
        ItemStack hand = maid.entity().getMainHandItem();
        if (hand.isEmpty() || hand.getCount() != 1 || hand.getItem().getClass() != SwordItem.class || hand.isEnchanted()
                || !BuiltInRegistries.ITEM.getKey(hand.getItem()).getNamespace().equals("minecraft") || !maid.entity().getOffhandItem().isEmpty())
            throw new IllegalStateException("MAID_COMBAT_WEAPON_REQUIRES_ADAPTER");
        for (var entry : hand.getComponentsPatch().entrySet()) {
            var id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(entry.getKey());
            if (id == null || !id.getNamespace().equals("minecraft") || id.getPath().equals("custom_data")) throw new IllegalStateException("MAID_COMBAT_WEAPON_COMPONENT_REQUIRES_ADAPTER");
        }
        for (var stack : maid.entity().getArmorSlots()) if (!stack.isEmpty()) {
            if (stack.getItem().getClass() != net.minecraft.world.item.ArmorItem.class || stack.isEnchanted()
                    || !BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals("minecraft"))
                throw new IllegalStateException("MAID_COMBAT_ARMOUR_REQUIRES_ADAPTER");
            for (var entry : stack.getComponentsPatch().entrySet()) {
                var id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(entry.getKey());
                if (id == null || !id.getNamespace().equals("minecraft") || id.getPath().equals("custom_data")) throw new IllegalStateException("MAID_COMBAT_ARMOUR_COMPONENT_REQUIRES_ADAPTER");
            }
        }
        if (maid.entity().getAttributeValue(Attributes.SWEEPING_DAMAGE_RATIO) > 0) throw new IllegalStateException("MAID_COMBAT_SWEEP_FOOTPRINT_REQUIRED");
        if (!MaidCombatScope.LootHook.class.isAssignableFrom(CommonHooks.class)
                || !MaidCombatScope.ExperienceHook.class.isAssignableFrom(net.minecraft.world.entity.ExperienceOrb.class)
                || !MaidCombatScope.RemovalHook.class.isAssignableFrom(net.minecraft.world.entity.Entity.class)
                || !MaidCombatSaveWitness.Hook.class.isAssignableFrom(net.minecraft.world.entity.Entity.class)
                || !MaidCombatLootLifecycle.ItemHook.class.isAssignableFrom(ItemEntity.class)
                || !MaidCombatLootLifecycle.MaidHook.class.isAssignableFrom(api.type(ROOT + "entity.passive.EntityMaid")))
            throw new IllegalStateException("MAID_COMBAT_NATIVE_SAVE_HOOKS_UNAVAILABLE");
        var backpack = inventory.maids().backpackSource(player, player.getUUID(), maid.entity().getUUID());
        var nativeBehavior = (BehaviorControl<LivingEntity>) api.call(null, ROOT + "entity.ai.brain.task.MaidMeleeAttack", "create",
                OneShot.class, new Class<?>[]{int.class}, 20);
        return new State(maid, target, backpack, nativeBehavior, snapshot(player, maid, target, backpack));
    }
    boolean ready(ServerPlayer player, State state) throws Exception {
        validate(player, state);
        var brain = state.maid().entity().getBrain();
        if (brain.hasMemoryValue(MemoryModuleType.ATTACK_COOLING_DOWN)) return false;
        var visible = brain.getMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES);
        return visible.isPresent() && visible.get().contains(state.target()) && state.maid().entity().hasLineOfSight(state.target())
                && (boolean) api.call(state.maid().entity(), ROOT + "entity.passive.EntityMaid", "isWithinMeleeAttackRange", boolean.class,
                        new Class<?>[]{LivingEntity.class}, state.target());
    }
    void navigate(ServerPlayer player, State state) throws Exception {
        validate(player, state);
        if (!(boolean) api.call(state.maid().entity(), ROOT + "entity.passive.EntityMaid", "isWithinMeleeAttackRange", boolean.class,
                new Class<?>[]{LivingEntity.class}, state.target()))
            BehaviorUtils.setWalkAndLookTargetMemories(state.maid().entity(), state.target(), .6f, 0);
    }
    void stopNavigation(State state, UUID owner) {
        var maid = state.maid().entity();
        if (maid.isRemoved() || !owner.equals(maid.getOwnerUUID()) || MaidNativeRevision.revision(maid) != state.maid().revision()) return;
        var walk = maid.getBrain().getMemory(MemoryModuleType.WALK_TARGET);
        if (walk.isPresent() && walk.get().getTarget() instanceof EntityTracker tracker && tracker.getEntity() == state.target()) {
            maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET); maid.getNavigation().stop();
        }
    }
    void validate(ServerPlayer player, State state) throws Exception {
        permissions.require(player, state.maid().entity(), state.target());
        var current = control.capture(player, player.getUUID());
        MaidControlNative.requireSame(state.maid(), current, state.before().maid());
        var actual = snapshot(player, current, state.target(), state.backpack());
        for (var attribute : List.of(Attributes.ATTACK_DAMAGE, Attributes.ATTACK_SPEED, Attributes.SWEEPING_DAMAGE_RATIO))
            if (!Double.isFinite(current.entity().getAttributeValue(attribute))) throw new IllegalStateException("MAID_COMBAT_ATTRIBUTE_UNSUPPORTED");
        if (player.serverLevel().getEntity(state.target().getUUID()) != state.target() || !state.target().isAlive()
                || !actual.inventory().equals(state.before().inventory()) || !actual.hand().equals(state.before().hand())
                || !actual.offHand().equals(state.before().offHand()) || !actual.armour().equals(state.before().armour())
                || !actual.baubles().equals(state.before().baubles()) || !actual.backpackId().equals(state.before().backpackId())
                || current.entity().isNoAi() || current.entity().getHealth() <= 4
                || !current.entity().canAttack(state.target()) || current.entity().isAlliedTo(state.target())
                || !nativeAttackAllowed(current.entity(), state.target()) || current.entity().getAttributeValue(Attributes.SWEEPING_DAMAGE_RATIO) > 0
                || current.entity().distanceToSqr(state.target()) > 32 * 32
                || !(boolean) api.call(current.entity(), ROOT + "entity.passive.EntityMaid", "isWithinRestriction", boolean.class,
                        new Class<?>[]{net.minecraft.core.BlockPos.class}, state.target().blockPosition()))
            throw new IllegalStateException("MAID_COMBAT_NATIVE_CONTEXT_CHANGED");
    }
    Outcome hit(ServerPlayer player, State expected, UUID operation) {
        boolean entered = false; Snapshot before = null, after = null; NativeSaveBarrier.Receipt receipt = null;
        var loot = new ArrayList<MaidCombatScope.Drop>(); var xp = new ArrayList<String>();
        MaidCombatScope observedDamage = null;
        try {
            validate(player, expected);
            if (!ready(player, expected)) {
                before = snapshot(player, control.capture(player, player.getUUID()), expected.target(), expected.backpack());
                return new Outcome(false, false, true, false, before, before, target(expected.target()), List.of(), List.of(), "WAITING_NATIVE_MELEE", null);
            }
            before = snapshot(player, control.capture(player, player.getUUID()), expected.target(), expected.backpack());
            try (var nativeSave = NativeConstructionSave.begin(player); var damage = new MaidCombatScope(player, expected.maid().entity(), expected.target(), permissions)) {
                observedDamage = damage;
                nativeSave.allowRandomSequences(player.serverLevel());
                var maid = expected.maid().entity(); var brain = maid.getBrain();
                if (brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET)) throw new IllegalStateException("MAID_COMBAT_TARGET_MEMORY_BUSY");
                var look = brain.getMemory(MemoryModuleType.LOOK_TARGET); boolean started;
                brain.setMemory(MemoryModuleType.ATTACK_TARGET, expected.target()); entered = true;
                try { started = expected.behavior().tryStart(player.serverLevel(), maid, player.serverLevel().getGameTime()); }
                finally {
                    if (brain.getMemory(MemoryModuleType.ATTACK_TARGET).filter(value -> value == expected.target()).isPresent()) brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
                    var currentLook = brain.getMemory(MemoryModuleType.LOOK_TARGET);
                    if (currentLook.isPresent() && currentLook.get() instanceof EntityTracker tracker && tracker.getEntity() == expected.target()) {
                        if (look.isPresent()) brain.setMemory(MemoryModuleType.LOOK_TARGET, look.get()); else brain.eraseMemory(MemoryModuleType.LOOK_TARGET);
                    }
                }
                expected.behavior().tickOrStop(player.serverLevel(), maid, player.serverLevel().getGameTime());
                loot.addAll(damage.loot()); xp.addAll(damage.experienceObservations());
                if (!player.getUUID().equals(maid.getOwnerUUID())) throw new IllegalStateException("MAID_OWNER_MISMATCH");
                var actualMaid = control.capture(player, player.getUUID());
                MaidControlNative.requireSame(expected.maid(), actualMaid, expected.before().maid());
                after = snapshot(player, actualMaid, expected.target(), expected.backpack());
                var receipts = new ArrayList<CompletableFuture<NativeSaveBarrier.Receipt>>();
                receipts.add(CompletableFuture.completedFuture(nativeSave.finish()));
                receipts.add(CompletableFuture.completedFuture(inventory.nativeSaves().changed(List.of(NativeSaveObserver.entity(maid)))));
                receipts.add(CompletableFuture.completedFuture(MaidCombatEntityProof.observe(expected.target(), operation, inventory.nativeSaves())));
                for (var awarded : damage.experienceReceipts()) receipts.add(CompletableFuture.completedFuture(awarded));
                for (var ground : damage.lootReceipts(inventory.nativeSaves(), operation)) receipts.add(CompletableFuture.completedFuture(ground));
                receipt = NativeSaveBarrier.combine(receipts);
                boolean stable = nativeSave.changedPositions().isEmpty() && damage.failure().isEmpty() && after.inventory().equals(before.inventory())
                        && after.offHand().equals(before.offHand()) && legalArmourWear(before.armour(), after.armour(), player)
                        && after.baubles().equals(before.baubles()) && legalWear(before.hand(), after.hand(), player)
                        && after.maidHealth() + after.maidAbsorption() <= before.maidHealth() + before.maidAbsorption()
                        && (!expected.target().isAlive() || after.target().health() + after.target().absorption() <= before.target().health() + before.target().absorption());
                String reason = !damage.failure().isEmpty() ? damage.failure() : !stable ? "MAID_COMBAT_UNADAPTED_EFFECT"
                        : !started ? "MAID_COMBAT_NATIVE_NO_HIT" : !damage.attempted() ? "MAID_COMBAT_NATIVE_DAMAGE_CANCELLED_OR_NO_HIT" : "";
                return new Outcome(true, damage.attempted(), stable, !expected.target().isAlive(), before, after, after.target(), loot, xp, reason, receipt);
            }
        } catch (Exception | LinkageError failure) {
            if (observedDamage != null) { loot.clear(); loot.addAll(observedDamage.loot()); xp.clear(); xp.addAll(observedDamage.experienceObservations()); }
            String reason = failure.getMessage(); if (reason == null || !reason.matches("[A-Z0-9_]{1,128}")) reason = "MAID_COMBAT_NATIVE_EFFECT_UNCONFIRMED";
            Target knownTarget = null; try { knownTarget = target(expected.target()); } catch (RuntimeException unavailable) { /* No fabricated target observation. */ }
            if (after == null) try { after = snapshot(player, control.capture(player, player.getUUID()), expected.target(), expected.backpack()); }
            catch (Exception | LinkageError unavailable) { /* A changed owner/binding never gets a fabricated after snapshot. */ }
            return new Outcome(entered, observedDamage != null && observedDamage.attempted(), false, false, before, after, knownTarget, loot, xp, reason, receipt);
        }
    }
    private static boolean legalWear(MaterialStack before, MaterialStack after, ServerPlayer owner) {
        if (before.equals(after)) return true;
        var original = ItemSnapshotCodec.restore(before, owner.registryAccess());
        if (after.empty()) return original.isDamageableItem() && original.getDamageValue() + 1 >= original.getMaxDamage();
        var actual = ItemSnapshotCodec.restore(after, owner.registryAccess());
        if (!ItemStack.isSameItem(original, actual) || actual.getCount() != 1 || actual.getDamageValue() < original.getDamageValue()) return false;
        original.setDamageValue(actual.getDamageValue()); return ItemSnapshotCodec.capture(original, owner.registryAccess()).equals(after);
    }
    private static boolean legalArmourWear(List<MaterialStack> before, List<MaterialStack> after, ServerPlayer owner) {
        if (before.size() != after.size()) return false;
        for (int i = 0; i < before.size(); i++) if (!legalWear(before.get(i), after.get(i), owner)) return false;
        return true;
    }
    private Snapshot snapshot(ServerPlayer player, MaidControlNative.Handle maid, LivingEntity target, MaidCarrierBridge.BackpackSource backpack) throws ReflectiveOperationException {
        if (!player.getUUID().equals(maid.entity().getOwnerUUID())) throw new SecurityException("MAID_OWNER_MISMATCH");
        var armour = new ArrayList<MaterialStack>();
        for (var stack : maid.entity().getArmorSlots()) { if (armour.size() >= 4) throw new IllegalStateException("MAID_COMBAT_ARMOUR_LAYOUT_UNSUPPORTED"); armour.add(ItemSnapshotCodec.capture(stack, player.registryAccess())); }
        if (armour.size() != 4 || !Float.isFinite(maid.entity().getHealth()) || !Float.isFinite(maid.entity().getAbsorptionAmount()))
            throw new IllegalStateException("MAID_COMBAT_NATIVE_STATE_UNSUPPORTED");
        var baubles = (IItemHandler) api.query(maid.entity(), ROOT + "entity.passive.EntityMaid", "getMaidBauble", api.type(ROOT + "inventory.handler.BaubleItemHandler"));
        var nativeBackpack = api.query(maid.entity(), ROOT + "entity.passive.EntityMaid", "getMaidBackpackType", api.type(ROOT + "api.backpack.IMaidBackpack"));
        var backpackId = (net.minecraft.resources.ResourceLocation) api.query(nativeBackpack, ROOT + "api.backpack.IMaidBackpack", "getId", net.minecraft.resources.ResourceLocation.class);
        return new Snapshot(maid.state(), backpackId.toString(), MaidFarmNative.snapshot(backpack.inventory(), player), ItemSnapshotCodec.capture(maid.entity().getMainHandItem(), player.registryAccess()),
                ItemSnapshotCodec.capture(maid.entity().getOffhandItem(), player.registryAccess()), armour, MaidFarmNative.snapshot(baubles, player), maid.entity().getHealth(), maid.entity().getAbsorptionAmount(), target == null ? null : target(target));
    }
    private boolean nativeAttackAllowed(LivingEntity maid, LivingEntity target) throws ReflectiveOperationException {
        var found = (Optional<?>) api.call(null, ROOT + "entity.task.TaskManager", "findTask", Optional.class,
                new Class<?>[]{net.minecraft.resources.ResourceLocation.class}, net.minecraft.resources.ResourceLocation.parse("touhou_little_maid:attack"));
        var task = found.orElseThrow(() -> new IllegalStateException("MAID_COMBAT_NATIVE_TASK_NOT_REGISTERED"));
        if (!task.getClass().getName().equals(ROOT + "entity.task.TaskAttack")) throw new IllegalStateException("MAID_COMBAT_NATIVE_TASK_REPLACED");
        return (boolean) api.call(task, ROOT + "api.task.IAttackTask", "canAttack", boolean.class,
                new Class<?>[]{api.type(ROOT + "entity.passive.EntityMaid"), LivingEntity.class}, maid, target);
    }
    State advance(State previous, Snapshot after) { return new State(previous.maid(), previous.target(), previous.backpack(), previous.behavior(), after); }
    State refresh(ServerPlayer player, State previous) throws Exception {
        validate(player, previous);
        return advance(previous, snapshot(player, control.capture(player, player.getUUID()), previous.target(), previous.backpack()));
    }
    Snapshot recovery(ServerPlayer player, UUID maidId, UUID targetId) throws Exception {
        var maid = control.inspectRecovery(player, player.getUUID(), maidId);
        var backpack = inventory.maids().backpackSource(player, player.getUUID(), maidId);
        var entity = player.serverLevel().getEntity(targetId);
        return snapshot(player, maid, entity instanceof LivingEntity living ? living : null, backpack);
    }
    private static Target target(LivingEntity target) {
        Vec3 position = target.position();
        var view = new Target(target.getUUID(), BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString(), target.getHealth(), target.getAbsorptionAmount(),
                target.isAlive(), target.isRemoved(), position.x, position.y, position.z);
        if (!Float.isFinite(view.health()) || !Float.isFinite(view.absorption()) || !Double.isFinite(view.x()) || !Double.isFinite(view.y()) || !Double.isFinite(view.z()))
            throw new IllegalStateException("MAID_COMBAT_TARGET_STATE_UNSUPPORTED");
        return view;
    }
}
