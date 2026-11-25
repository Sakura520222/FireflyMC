package firefly520.fireflymc.ai.integration.equipment;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/** Rechecks the actual online player and the existing FireflyMC authentication gate on every call. */
public final class OwnedPlayerAccess {
    private final MinecraftServer server;
    private final Predicate<ServerPlayer> authenticated;

    public OwnedPlayerAccess(MinecraftServer server, Predicate<ServerPlayer> authenticated) {
        this.server = Objects.requireNonNull(server);
        this.authenticated = Objects.requireNonNull(authenticated);
    }

    public String refusal(ServerPlayer player, UUID workspaceOwner) {
        if (!server.isSameThread()) return "SERVER_THREAD_REQUIRED";
        if (!server.isDedicatedServer()) return "AI_DEDICATED_SERVER_REQUIRED";
        if (player == null || player.server != server || player.isRemoved() || !player.isAlive()
                || server.getPlayerList().getPlayer(player.getUUID()) != player) return "PLAYER_NOT_ACTIVE";
        if (!Objects.equals(player.getUUID(), workspaceOwner)) return "WORKSPACE_OWNER_MISMATCH";
        if (player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL) return "SURVIVAL_REQUIRED";
        try {
            return authenticated.test(player) ? null : "AUTHENTICATION_REQUIRED";
        } catch (RuntimeException unavailable) {
            return "AUTHENTICATION_UNAVAILABLE";
        }
    }

    public void require(ServerPlayer player, UUID workspaceOwner) {
        String reason = refusal(player, workspaceOwner);
        if (reason != null) throw new IllegalStateException(reason);
    }

    public void requireServerThread() {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        if (!server.isDedicatedServer()) throw new IllegalStateException("AI_DEDICATED_SERVER_REQUIRED");
    }
}
