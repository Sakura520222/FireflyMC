package firefly520.fireflymc.music;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** 音乐模块服务端事件挂钩（登录同步/登出清理/权威计时 tick） */
public class MusicServerEvents {
    public static void onServerStarted(ServerStartedEvent event) {
        MusicServerBridge.onServerStarted(event.getServer());
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        MusicServerBridge.onServerStopping();
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            MusicServerBridge.onPlayerLoggedIn(sp);
        }
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            MusicServerBridge.onPlayerLoggedOut(sp);
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MusicServerBridge.tick();
    }
}
