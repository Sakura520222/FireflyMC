package firefly520.fireflymc.client.relay;

import firefly520.fireflymc.Config;
import firefly520.fireflymc.client.ClientState;
import firefly520.fireflymc.client.screen.RelayLobbyScreen;
import firefly520.fireflymc.client.screen.SingleplayerSharePromptScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.server.IntegratedServer;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.minecraft.network.chat.Component;

/**
 * 单人世界公开联机客户端事件。
 */
public final class SingleplayerRelayClientEvents {
    private static boolean promptPending = false;

    private SingleplayerRelayClientEvents() {
    }

    public static void onClientLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
        Minecraft mc = Minecraft.getInstance();
        if (!Config.CLIENT.SINGLEPLAYER_RELAY_ENABLED.get() || !Config.CLIENT.SINGLEPLAYER_RELAY_PROMPT_ON_JOIN.get()) {
            return;
        }

        if (mc.getSingleplayerServer() != null) {
            ClientState.hasHandledSingleplayerRelayPrompt = false;
            promptPending = true;
        }
    }

    public static void onClientLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        promptPending = false;
        ClientState.hasHandledSingleplayerRelayPrompt = false;
        RelayGuestJoiner.stopActiveRelay("client_logged_out");
        SingleplayerRelayManager.getInstance().stopHosting();
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        if (!promptPending || ClientState.hasHandledSingleplayerRelayPrompt) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null || mc.level == null || mc.screen != null) {
            return;
        }

        promptPending = false;
        mc.setScreen(new SingleplayerSharePromptScreen(null, resolveWorldName(server)));
    }

    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof JoinMultiplayerScreen screen)) {
            return;
        }

        int buttonWidth = 120;
        int buttonHeight = 20;
        int x = screen.width - buttonWidth - 8;
        int y = 8;
        event.addListener(Button.builder(
                Component.literal("FireflyMC 联机大厅"),
                button -> Minecraft.getInstance().setScreen(new RelayLobbyScreen(screen))
        ).bounds(x, y, buttonWidth, buttonHeight).build());
    }

    private static String resolveWorldName(IntegratedServer server) {
        try {
            String name = server.getWorldData().getLevelName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        } catch (Exception ignored) {
            // 使用降级名称
        }
        return "我的单人世界";
    }
}
