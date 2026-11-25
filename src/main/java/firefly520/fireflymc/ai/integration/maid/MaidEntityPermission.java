package firefly520.fireflymc.ai.integration.maid;

import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.integration.equipment.OwnedPlayerAccess;
import firefly520.fireflymc.ai.integration.equipment.ReadOnlyNativeAccess;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.ItemStack;

/** Exact OPAC entity interaction, independent of block-container permissions. True means deny. */
public final class MaidEntityPermission {
    private static final String API = "xaero.pac.common.server.api.OpenPACServerAPI";
    private static final String PROTECTION = "xaero.pac.common.server.claims.protection.api.IChunkProtectionAPI";
    private static final String DATA = "xaero.pac.common.server.player.data.api.ServerPlayerDataAPI";
    private final MinecraftServer server;
    private final OwnedPlayerAccess access;
    private final InstalledPermissionAdapters permissions;
    private final ReadOnlyNativeAccess nativeAccess = new ReadOnlyNativeAccess();

    public MaidEntityPermission(MinecraftServer server, Predicate<ServerPlayer> authenticated,
                                InstalledPermissionAdapters permissions) {
        this.server = server;
        this.access = new OwnedPlayerAccess(server, authenticated);
        this.permissions = permissions;
    }

    public void require(ServerPlayer player, UUID owner, Entity target) {
        require(player, owner, target, "fireflymc.ai.maid.inventory");
    }

    public void requireControl(ServerPlayer player, UUID owner, Entity target) {
        require(player, owner, target, "fireflymc.ai.maid.control");
    }

    private void require(ServerPlayer player, UUID owner, Entity target, String permission) {
        access.require(player, owner);
        if (!(target instanceof TamableAnimal pet) || !pet.isTame() || !owner.equals(pet.getOwnerUUID())
                || !pet.isOwnedBy(player)) throw new SecurityException("MAID_OWNER_MISMATCH");
        if (target.isRemoved() || !target.isAlive() || target.level() != player.serverLevel()
                || player.serverLevel().getEntity(target.getUUID()) != target
                || !player.serverLevel().hasChunkAt(target.blockPosition())
                || !player.serverLevel().getWorldBorder().isWithinBounds(target.blockPosition()))
            throw new SecurityException("MAID_WORLD_ACCESS_DENIED");
        if (!permissions.checkBooleanPermission(player, permission, true).allowed())
            throw new SecurityException(permission.endsWith(".inventory") ? "MAID_INVENTORY_PERMISSION_DENIED" : "MAID_CONTROL_PERMISSION_DENIED");
        var status = nativeAccess.version("openpartiesandclaims", "0.28.1", null,
                "ev_463c8ad10ec22ec784b7b71ab2f403856be5352b5bc5cedafebe6c0459102a6e");
        if (status.state() == ReadOnlyNativeAccess.State.ABSENT) return;
        if (status.state() != ReadOnlyNativeAccess.State.STATIC_API_MATCH)
            throw new SecurityException("MAID_PROTECTION_VERSION_UNSUPPORTED");
        try {
            Object data = nativeAccess.call(null, DATA, "from", nativeAccess.type(DATA),
                    new Class<?>[] { ServerPlayer.class }, player);
            boolean admin = (boolean) nativeAccess.query(data, DATA, "isClaimsAdminMode", boolean.class);
            boolean serverMode = (boolean) nativeAccess.query(data, DATA, "isClaimsServerMode", boolean.class);
            if (admin || serverMode) throw new SecurityException("CLAIMS_ADMIN_MODE_ACTIVE");
            Object api = nativeAccess.call(null, API, "get", nativeAccess.type(API),
                    new Class<?>[] { MinecraftServer.class }, server);
            Object checker = nativeAccess.query(api, API, "getChunkProtection", nativeAccess.type(PROTECTION));
            boolean denied = (boolean) nativeAccess.call(checker, PROTECTION, "onEntityInteraction", boolean.class,
                    new Class<?>[] { Entity.class, Entity.class, Entity.class, ItemStack.class,
                            InteractionHand.class, boolean.class, boolean.class },
                    player, player, target, ItemStack.EMPTY, InteractionHand.MAIN_HAND, false, false);
            if (denied) throw new SecurityException("MAID_CLAIM_DENIED");
        } catch (SecurityException denied) {
            throw denied;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            throw new SecurityException("MAID_PROTECTION_API_FAILED");
        }
    }
}
