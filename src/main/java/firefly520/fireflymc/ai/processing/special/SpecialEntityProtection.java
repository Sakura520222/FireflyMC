package firefly520.fireflymc.ai.processing.special;

import firefly520.fireflymc.ai.integration.equipment.ReadOnlyNativeAccess;
import firefly520.fireflymc.ai.material.ItemSnapshotCodec;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;

/** OPAC item-use and actual mob-spawn rules are additional to the approved area's block permission. */
final class SpecialEntityProtection {
    private static final String API = "xaero.pac.common.server.api.OpenPACServerAPI";
    private static final String PROTECTION = "xaero.pac.common.server.claims.protection.api.IChunkProtectionAPI";
    private static final String DATA = "xaero.pac.common.server.IServerData";
    private static final String CORE = "xaero.pac.common.server.claims.protection.ChunkProtection";
    private final ReadOnlyNativeAccess access = new ReadOnlyNativeAccess();

    void itemUse(ServerPlayer player, EntityProcessingAdapter.Plan plan) {
        if (!ready()) return;
        try {
            Object api = access.call(null, API, "get", access.type(API), new Class<?>[]{MinecraftServer.class}, player.server);
            Object checker = access.query(api, API, "getChunkProtection", access.type(PROTECTION));
            // The nine-argument native overload's final flag controls a physical block-state lookup.
            // Virtual processing has no station block. Item-use checks still run with real materials.
            for (var material : plan.consumed()) if (!material.empty()) {
                ItemStack input = ItemSnapshotCodec.restore(material, player.registryAccess());
                boolean denied = (boolean) access.call(checker, PROTECTION, "onBlockInteraction", boolean.class,
                        new Class<?>[]{Entity.class, InteractionHand.class, ItemStack.class, ServerLevel.class,
                                net.minecraft.core.BlockPos.class, Direction.class, boolean.class, boolean.class, boolean.class},
                        player, InteractionHand.MAIN_HAND, input, player.serverLevel(), plan.target(), Direction.UP, false, false, false);
                if (denied) throw new SecurityException("ENTITY_RAW_ITEM_USE_CLAIM_DENIED");
            }
        } catch (SecurityException denied) { throw denied; }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { throw new SecurityException("ENTITY_ITEM_PROTECTION_API_FAILED"); }
    }

    void mobSpawn(ServerPlayer player, EntityProcessingAdapter.Plan plan, Entity root) {
        for (Entity entity : root.getSelfAndPassengers().toList()) {
            if (entity instanceof Mob mob && mob.isSpawnCancelled()) throw new SecurityException("ENTITY_NATIVE_SPAWN_CANCELLED");
        }
        if (!ready()) return;
        try {
            Object data = access.call(null, "xaero.pac.common.server.ServerData", "from", access.type(DATA), new Class<?>[]{MinecraftServer.class}, player.server);
            Object checker = access.query(data, DATA, "getChunkProtection", access.type(CORE));
            MobSpawnType reason = plan.kind() == EntityProcessingAdapter.Kind.AETHER_INCUBATION ? MobSpawnType.TRIGGERED
                    : plan.entityType().toString().equals("touhou_little_maid:box") || plan.entityType().toString().equals("touhou_little_maid:maid")
                    ? MobSpawnType.SPAWN_EGG : MobSpawnType.EVENT;
            // OPAC CommonEvents.onMobSpawn explicitly skips SPAWN_EGG/BREEDING/BUCKET. Do not
            // reinterpret those player actions as natural spawns; item-use permission handles them.
            if (reason == MobSpawnType.SPAWN_EGG) return;
            for (Entity entity : root.getSelfAndPassengers().toList()) if (entity instanceof Mob) {
                boolean denied = (boolean) access.call(checker, CORE, "onMobSpawn", boolean.class,
                        new Class<?>[]{access.type(DATA), Entity.class, double.class, double.class, double.class, MobSpawnType.class},
                        data, entity, entity.getX(), entity.getY(), entity.getZ(), reason);
                if (denied) throw new SecurityException("ENTITY_MOB_SPAWN_CLAIM_DENIED");
            }
        } catch (SecurityException denied) { throw denied; }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { throw new SecurityException("ENTITY_SPAWN_PROTECTION_API_FAILED"); }
    }

    private boolean ready() {
        var status = access.version("openpartiesandclaims", "0.28.1", null,
                "ev_463c8ad10ec22ec784b7b71ab2f403856be5352b5bc5cedafebe6c0459102a6e");
        if (status.state() == ReadOnlyNativeAccess.State.ABSENT) return false;
        if (status.state() != ReadOnlyNativeAccess.State.STATIC_API_MATCH) throw new SecurityException("ENTITY_PROTECTION_VERSION_UNSUPPORTED");
        return true;
    }
}
