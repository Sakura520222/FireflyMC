package firefly520.fireflymc.ai.processing.special;

import firefly520.fireflymc.ai.material.ItemSnapshotCodec;
import firefly520.fireflymc.ai.material.MaterialStack;
import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import firefly520.fireflymc.ai.storage.AiJson;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.phys.AABB;

/** Entity recipes never produce placeholder ItemStacks. Native initialization is separate from spawning. */
public final class EntityProcessingAdapter {
    private static final String INCUBATION = "com.aetherteam.aether.recipe.recipes.item.IncubationRecipe";
    private static final String ALTAR = "com.github.tartaricacid.touhoulittlemaid.crafting.AltarRecipe";
    public enum Kind { AETHER_INCUBATION, MAID_ALTAR }
    public record Plan(UUID operationId, UUID owner, String dimension, ResourceLocation recipeId,
                       ResourceLocation entityType, BlockPos target, Kind kind, String recipeContextDigest, List<MaterialStack> consumed) {
        public Plan { target = target.immutable(); consumed = List.copyOf(consumed); }
    }
    public record Member(UUID uuid, ResourceLocation type) {}
    /** Complete native NBT is private server escrow data, never a model/UI projection. */
    public record Prepared(Plan plan, List<Member> members, String nativeNbt, String digest) {
        public Prepared {
            members = List.copyOf(members);
            if (members.isEmpty() || members.size() > 2 || nativeNbt.getBytes(StandardCharsets.UTF_8).length > 1_048_576
                    || !AiJson.digest(nativeNbt).equals(digest)) throw new IllegalArgumentException("ENTITY_OUTPUT_SNAPSHOT_INVALID");
        }
    }
    /** Supplied only by the server executor after fixed area/operation approval and per-position protection checks. */
    public interface SpawnGuard {
        void validate(ServerPlayer actor, Plan plan, List<AABB> occupiedBounds);
    }
    private EntityProcessingAdapter() {}

    public static Kind kind(Recipe<?> recipe) {
        if (recipe == null) return null;
        if (recipe.getClass().getName().equals(INCUBATION) && SpecialNative.version("aether", "1.5.10")
                && SpecialNative.id("aether:incubation").equals(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()))
                && SpecialNative.id("aether:incubation").equals(BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer()))) return Kind.AETHER_INCUBATION;
        if (recipe.getClass().getName().equals(ALTAR) && SpecialNative.version("touhou_little_maid", "1.5.3-neoforge+mc1.21.1")
                && SpecialNative.id("touhou_little_maid:altar_recipe").equals(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()))
                && SpecialNative.id("touhou_little_maid:altar_recipe_serializers").equals(BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer()))
                && !(boolean) SpecialNative.get(recipe, "isItemCraft", boolean.class)) return Kind.MAID_ALTAR;
        return null;
    }

    public static Plan plan(RecipeHolder<?> holder, List<ItemStack> supplied, ServerPlayer player,
                            BlockPos approvedTarget, UUID operationId, SpawnGuard guard) {
        ServerLevel level = player.serverLevel();
        SpecialNative.current(holder, level);
        Kind kind = kind(holder.value());
        if (kind == null || operationId == null || guard == null) throw new IllegalArgumentException("UNSUPPORTED_ENTITY_PROCESSING_ADAPTER");
        List<ItemStack> inputs = SpecialNative.units(supplied, kind == Kind.AETHER_INCUBATION ? 1 : 9, level);
        if (inputs.stream().filter(s -> !s.isEmpty()).count() > 6) throw new IllegalArgumentException("ALTAR_MATERIAL_LIMIT");
        RecipeInput input = kind == Kind.AETHER_INCUBATION ? new SingleRecipeInput(inputs.getFirst()) : CraftingInput.of(3, 3, inputs);
        @SuppressWarnings("unchecked") Recipe<RecipeInput> recipe = (Recipe<RecipeInput>) holder.value();
        if (!recipe.matches(input, level)) throw new IllegalArgumentException("ENTITY_RECIPE_MATERIAL_MISMATCH");
        ResourceLocation entityType = kind == Kind.AETHER_INCUBATION
                ? BuiltInRegistries.ENTITY_TYPE.getKey((EntityType<?>) SpecialNative.get(recipe, "getEntity", EntityType.class))
                : (ResourceLocation) SpecialNative.get(recipe, "getEntityType", ResourceLocation.class);
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(entityType) || BuiltInRegistries.ENTITY_TYPE.get(entityType) == EntityType.ITEM
                || BuiltInRegistries.ENTITY_TYPE.get(entityType) == EntityType.PLAYER)
            throw new IllegalArgumentException("ENTITY_RECIPE_TYPE_INVALID");
        if (kind == Kind.MAID_ALTAR && entityType.equals(SpecialNative.id("touhou_little_maid:maid"))) validateFilmOwner(inputs, player);
        String nativeContext = "maid_altar:" + entityType;
        if (kind == Kind.AETHER_INCUBATION) {
            Optional<?> tag = (Optional<?>) SpecialNative.get(recipe, "getTag", Optional.class);
            if (tag.isPresent() && !(tag.get() instanceof CompoundTag)) throw new IllegalStateException("INCUBATION_CONTEXT_INVALID");
            nativeContext = tag.map(t -> ((CompoundTag) t).copy().toString()).orElse("{}");
            if (nativeContext.getBytes(StandardCharsets.UTF_8).length > 262_144) throw new IllegalArgumentException("INCUBATION_CONTEXT_LIMIT");
        }
        Plan plan = new Plan(operationId, player.getUUID(), level.dimension().location().toString(), holder.id(), entityType,
                approvedTarget, kind, AiJson.digest(nativeContext), inputs.stream().map(s -> ItemSnapshotCodec.capture(s, level.registryAccess())).toList());
        context(plan, player, guard, List.of(new AABB(approvedTarget)));
        return plan;
    }

    /** Invoke only after the material intent/escrow journal exists. Persist the result before addFreshEntity. */
    public static Prepared prepareAfterEscrow(RecipeHolder<?> holder, Plan plan, ServerPlayer player, SpawnGuard guard) {
        context(plan, player, guard, List.of(new AABB(plan.target())));
        SpecialNative.current(holder, player.serverLevel());
        if (!holder.id().equals(plan.recipeId()) || kind(holder.value()) != plan.kind()) throw new IllegalArgumentException("ENTITY_RECIPE_STALE");
        List<ItemStack> inputs = plan.consumed().stream().map(s -> ItemSnapshotCodec.restore(s, player.registryAccess())).toList();
        Plan repeated = plan(holder, inputs, player, plan.target(), plan.operationId(), guard);
        if (!repeated.equals(plan)) throw new IllegalStateException("ENTITY_PLAN_CHANGED");
        Entity root;
        ServerLevel level = player.serverLevel();
        if (plan.kind() == Kind.AETHER_INCUBATION) {
            @SuppressWarnings("unchecked") Optional<CompoundTag> nativeTag = (Optional<CompoundTag>) SpecialNative.get(holder.value(), "getTag", Optional.class);
            CompoundTag tag = nativeTag.map(CompoundTag::copy).orElse(null);
            Consumer<Entity> customization = entity -> {
                if (tag != null && entity instanceof LivingEntity living) living.readAdditionalSaveData(tag.copy());
            };
            customization = EntityType.appendDefaultStackConfig(customization, level, inputs.getFirst(), player);
            root = type(plan.entityType()).create(level, customization, plan.target(), MobSpawnType.TRIGGERED, true, false);
            if (root != null) root.setCustomName(inputs.getFirst().get(DataComponents.CUSTOM_NAME));
        } else if (plan.entityType().equals(SpecialNative.id("touhou_little_maid:box"))) {
            root = type(plan.entityType()).create(level);
            Entity maid = type(SpecialNative.id("touhou_little_maid:maid")).create(level);
            if (root == null || !(maid instanceof Mob mob)) throw new IllegalStateException("MAID_ENTITY_FACTORY_FAILED");
            root.setPos(plan.target().getX(), plan.target().getY(), plan.target().getZ());
            mob.setPos(root.position());
            net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(plan.target()), MobSpawnType.SPAWN_EGG, null);
            if (mob.isSpawnCancelled()) throw new IllegalStateException("ENTITY_NATIVE_SPAWN_CANCELLED");
            if (!maid.startRiding(root, true)) throw new IllegalStateException("MAID_BOX_PASSENGER_FAILED");
        } else if (plan.entityType().equals(SpecialNative.id("touhou_little_maid:maid"))) {
            validateFilmOwner(inputs, player);
            root = type(plan.entityType()).create(level);
            if (!(root instanceof Mob mob)) throw new IllegalStateException("MAID_ENTITY_FACTORY_FAILED");
            CustomData info = film(inputs);
            if (info != null) SpecialNative.call(root, "readAdditionalSaveData", void.class, new Class<?>[]{CompoundTag.class}, info.copyTag());
            else net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(plan.target()), MobSpawnType.SPAWN_EGG, null);
            if (mob.isSpawnCancelled()) throw new IllegalStateException("ENTITY_NATIVE_SPAWN_CANCELLED");
            root.setPos(plan.target().getX(), plan.target().getY(), plan.target().getZ());
        } else root = type(plan.entityType()).create(level, null, plan.target(), MobSpawnType.EVENT, false, false);
        if (root == null) throw new IllegalStateException("ENTITY_NATIVE_CREATE_REJECTED");
        List<Entity> entities = root.getSelfAndPassengers().toList();
        if (entities.size() > 2) throw new IllegalStateException("ENTITY_UNREVIEWED_PASSENGERS");
        if (entities.stream().anyMatch(e -> e instanceof Mob mob && mob.isSpawnCancelled())) throw new IllegalStateException("ENTITY_NATIVE_SPAWN_CANCELLED");
        var members = new ArrayList<Member>();
        for (int i = 0; i < entities.size(); i++) {
            Entity entity = entities.get(i);
            UUID uuid = UUID.nameUUIDFromBytes(("fireflymc/" + plan.owner() + "/" + plan.operationId() + "/" + i).getBytes(StandardCharsets.UTF_8));
            entity.setUUID(uuid);
            entity.getPersistentData().putUUID("fireflymc_ai_operation", plan.operationId());
            entity.getPersistentData().putUUID("fireflymc_ai_owner", plan.owner());
            members.add(new Member(uuid, BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())));
        }
        context(plan, player, guard, entities.stream().map(Entity::getBoundingBox).toList());
        CompoundTag nativeNbt = new CompoundTag();
        if (!root.save(nativeNbt)) throw new IllegalStateException("ENTITY_NATIVE_SNAPSHOT_FAILED");
        String encoded = nativeNbt.toString();
        return new Prepared(plan, members, encoded, AiJson.digest(encoded));
    }

    /** Reconstructs the recorded native initialization without rerolling mob/maid initialization. No world insertion. */
    public static Entity restorePrepared(Prepared prepared, ServerPlayer player, SpawnGuard guard) {
        context(prepared.plan(), player, guard, List.of(new AABB(prepared.plan().target())));
        try {
            Entity root = EntityType.loadEntityRecursive(TagParser.parseTag(prepared.nativeNbt()), player.serverLevel(), entity -> entity);
            if (root == null) throw new IllegalStateException("ENTITY_NATIVE_RESTORE_FAILED");
            List<Entity> entities = root.getSelfAndPassengers().toList();
            if (entities.size() != prepared.members().size()) throw new IllegalStateException("ENTITY_PASSENGER_SNAPSHOT_MISMATCH");
            for (int i = 0; i < entities.size(); i++) if (!matches(entities.get(i), prepared.members().get(i), prepared.plan()))
                throw new IllegalStateException("ENTITY_NATIVE_IDENTITY_MISMATCH");
            context(prepared.plan(), player, guard, entities.stream().map(Entity::getBoundingBox).toList());
            return root;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
            throw new IllegalStateException("ENTITY_NATIVE_NBT_INVALID", failure);
        }
    }

    /** Positive evidence only. Missing/unloaded UUIDs are UNKNOWN and never authorize replay. */
    public static boolean verifyPresent(Prepared prepared, ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        if (!prepared.plan().dimension().equals(level.dimension().location().toString())) return false;
        for (Member member : prepared.members()) if (!matches(level.getEntity(member.uuid()), member, prepared.plan())) return false;
        return prepared.members().size() == 1 || level.getEntity(prepared.members().getFirst().uuid()).hasPassenger(level.getEntity(prepared.members().get(1).uuid()));
    }

    public static void callback(RecipeHolder<?> holder, Prepared prepared, ServerPlayer player) {
        SpecialNative.current(holder, player.serverLevel());
        if (!holder.id().equals(prepared.plan().recipeId()) || !prepared.plan().owner().equals(player.getUUID()) || !verifyPresent(prepared, player.serverLevel()))
            throw new IllegalStateException("ENTITY_CALLBACK_REQUIRES_VERIFIED_OUTPUT");
        if (prepared.plan().kind() == Kind.AETHER_INCUBATION) {
            Object trigger = ((java.util.function.Supplier<?>) SpecialNative.field("com.aetherteam.aether.advancement.AetherAdvancementTriggers", "INCUBATION_TRIGGER")).get();
            SpecialNative.call(trigger, "trigger", void.class, new Class<?>[]{ServerPlayer.class, ItemStack.class}, player,
                    ItemSnapshotCodec.restore(prepared.plan().consumed().getFirst(), player.registryAccess()));
        } else {
            Object trigger = ((java.util.function.Supplier<?>) SpecialNative.field("com.github.tartaricacid.touhoulittlemaid.init.InitTrigger", "ALTAR_CRAFT")).get();
            SpecialNative.call(trigger, "trigger", void.class, new Class<?>[]{ServerPlayer.class, ResourceLocation.class}, player, holder.id());
        }
    }

    private static boolean matches(Entity entity, Member member, Plan plan) {
        return entity != null && !entity.isRemoved() && member.uuid().equals(entity.getUUID())
                && member.type().equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()))
                && entity.getPersistentData().hasUUID("fireflymc_ai_operation")
                && plan.operationId().equals(entity.getPersistentData().getUUID("fireflymc_ai_operation"))
                && entity.getPersistentData().hasUUID("fireflymc_ai_owner")
                && plan.owner().equals(entity.getPersistentData().getUUID("fireflymc_ai_owner"));
    }

    private static void context(Plan plan, ServerPlayer player, SpawnGuard guard, List<AABB> bounds) {
        ServerLevel level = player.serverLevel();
        if (!level.getServer().isSameThread() || !plan.owner().equals(player.getUUID()) || !plan.dimension().equals(level.dimension().location().toString())
                || !player.isAlive() || !level.hasChunkAt(plan.target()) || !level.getWorldBorder().isWithinBounds(plan.target()))
            throw new IllegalStateException("ENTITY_PROCESSING_CONTEXT_CHANGED");
        if (guard == null) throw new IllegalArgumentException("ENTITY_PROTECTION_GUARD_REQUIRED");
        guard.validate(player, plan, bounds);
    }

    private static void validateFilmOwner(List<ItemStack> inputs, ServerPlayer player) {
        CustomData info = film(inputs);
        if (info == null) return;
        CompoundTag tag = info.copyTag();
        if (!tag.hasUUID("Owner") || !tag.getUUID("Owner").equals(player.getUUID()))
            throw new IllegalArgumentException("MAID_FILM_OWNER_NOT_AUTHORIZED");
    }

    private static CustomData film(List<ItemStack> inputs) {
        @SuppressWarnings("unchecked") DataComponentType<CustomData> data = (DataComponentType<CustomData>) ((java.util.function.Supplier<?>)
                SpecialNative.field("com.github.tartaricacid.touhoulittlemaid.init.InitDataComponent", "MAID_INFO")).get();
        for (ItemStack input : inputs) if (SpecialNative.instance("com.github.tartaricacid.touhoulittlemaid.item.ItemFilm", input.getItem())) return input.get(data);
        return null;
    }

    @SuppressWarnings("unchecked") private static EntityType<Entity> type(ResourceLocation id) { return (EntityType<Entity>) BuiltInRegistries.ENTITY_TYPE.get(id); }
}
