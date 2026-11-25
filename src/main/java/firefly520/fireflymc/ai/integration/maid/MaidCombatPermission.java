package firefly520.fireflymc.ai.integration.maid;

import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.integration.equipment.ReadOnlyNativeAccess;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;

/** Owner access and real entity-attack semantics, independent of block-break/container permissions. */
final class MaidCombatPermission {
    private static final String API = "xaero.pac.common.server.api.OpenPACServerAPI";
    private static final String PROTECTION = "xaero.pac.common.server.claims.protection.api.IChunkProtectionAPI";
    private final MinecraftServer server;
    private final InstalledPermissionAdapters permissions;
    private final MaidEntityPermission maidAccess;
    private final ReadOnlyNativeAccess nativeAccess = new ReadOnlyNativeAccess();
    MaidCombatPermission(MinecraftServer server, InstalledPermissionAdapters permissions) {
        this.server = server; this.permissions = permissions;
        maidAccess = new MaidEntityPermission(server, firefly520.fireflymc.ai.AiServerService::authenticated, permissions);
    }
    void require(ServerPlayer player, LivingEntity maid, LivingEntity target) {
        maidAccess.requireControl(player, player.getUUID(), maid);
        if (!permissions.checkBooleanPermission(player, "fireflymc.ai.maid.combat", true).allowed()) throw new SecurityException("MAID_COMBAT_PERMISSION_DENIED");
        if (!(target instanceof Monster) || target instanceof net.minecraft.world.entity.player.Player || target.isRemoved() || !target.isAlive()
                || target.level() != player.serverLevel() || player.serverLevel().getEntity(target.getUUID()) != target
                || !player.serverLevel().hasChunkAt(target.blockPosition()) || !player.serverLevel().getWorldBorder().isWithinBounds(target.blockPosition()))
            throw new SecurityException("MAID_COMBAT_HOSTILE_TARGET_REQUIRED");
        var status = nativeAccess.version("openpartiesandclaims", "0.28.1", null, "ev_463c8ad10ec22ec784b7b71ab2f403856be5352b5bc5cedafebe6c0459102a6e");
        if (status.state() == ReadOnlyNativeAccess.State.ABSENT) return;
        if (status.state() != ReadOnlyNativeAccess.State.STATIC_API_MATCH) throw new SecurityException("MAID_COMBAT_PROTECTION_VERSION_UNSUPPORTED");
        try {
            Object api = nativeAccess.call(null, API, "get", nativeAccess.type(API), new Class<?>[] {MinecraftServer.class}, server);
            Object protection = nativeAccess.query(api, API, "getChunkProtection", nativeAccess.type(PROTECTION));
            // Same attack path/flags as CommonEvents.onLivingHurt in the inspected 0.28.1 JAR;
            // the first actor is explicitly the authenticated owner, never an OP full pass.
            boolean denied = (boolean) nativeAccess.call(protection, PROTECTION, "onEntityInteraction", boolean.class,
                    new Class<?>[] {Entity.class, Entity.class, Entity.class, ItemStack.class, InteractionHand.class, boolean.class, boolean.class, boolean.class},
                    player, maid, target, maid.getMainHandItem(), InteractionHand.MAIN_HAND, true, false, true);
            if (denied) throw new SecurityException("MAID_COMBAT_CLAIM_DENIED");
        } catch (SecurityException denied) { throw denied; }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unready) { throw new SecurityException("MAID_COMBAT_PROTECTION_UNAVAILABLE"); }
    }
}
