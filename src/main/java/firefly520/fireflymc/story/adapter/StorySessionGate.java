package firefly520.fireflymc.story.adapter;

import firefly520.fireflymc.ServerConfig;
import firefly520.fireflymc.network.ModPayloadHandler;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Fail-closed authentication/session boundary for dedicated-server story progression. */
public final class StorySessionGate {
    private StorySessionGate() {}

    public static boolean isOpen(ServerPlayer player) {
        MinecraftServer server = player.server;
        if (!server.isDedicatedServer()) {
            // Singleplayer, integrated, and LAN servers do not use this mod's dedicated join gate.
            return true;
        }

        UUID playerId = player.getUUID();
        boolean verified = ModPayloadHandler.VERIFIED_PLAYERS
            .getOrDefault(playerId, false);
        boolean rulesConfirmed = ModPayloadHandler.CONFIRMED_PLAYERS
            .getOrDefault(playerId, false);
        boolean passwordRequired = ServerConfig.SERVER.playerAuthEnabled.get();
        boolean passwordVerified = !passwordRequired
            || ModPayloadHandler.PASSWORD_VERIFIED_PLAYERS.getOrDefault(playerId, false);
        return verified && rulesConfirmed && passwordVerified;
    }
}
