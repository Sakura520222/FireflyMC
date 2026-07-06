package firefly520.fireflymc.client.relay;

import firefly520.fireflymc.Config;
import firefly520.fireflymc.client.ClientState;
import firefly520.fireflymc.client.relay.ipv6.Ipv6ConnectivityChecker;
import firefly520.fireflymc.client.screen.RelayLobbyScreen;
import firefly520.fireflymc.client.screen.SingleplayerRelayControlScreen;
import firefly520.fireflymc.client.screen.SingleplayerSharePromptScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * 单人世界公开联机客户端事件。
 */
public final class SingleplayerRelayClientEvents {
    private static final int INJECTED_BUTTON_WIDTH = 120;
    private static final int INJECTED_BUTTON_HEIGHT = 20;
    private static final int INJECTED_BUTTON_MARGIN = 8;

    private static boolean promptPending = false;

    private SingleplayerRelayClientEvents() {
    }

    public static void onClientLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
        Minecraft mc = Minecraft.getInstance();
        if (!RelayConfig.RELAY.SINGLEPLAYER_RELAY_ENABLED.get() || !RelayConfig.RELAY.SINGLEPLAYER_RELAY_PROMPT_ON_JOIN.get()) {
            return;
        }

        if (mc.getSingleplayerServer() != null) {
            ClientState.hasHandledSingleplayerRelayPrompt = false;
            promptPending = true;

            // IPv6 出站检测:enabled && autoCheck 双检查
            if (Config.CLIENT.IPV6_PROBE_ENABLED.get() && Config.CLIENT.IPV6_PROBE_AUTO_ON_SP_JOIN.get()) {
                Ipv6ConnectivityChecker.getInstance().checkAsync(false);
            }
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
        if (event.getScreen() instanceof PauseScreen screen) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || !mc.hasSingleplayerServer()) {
                return;
            }
            int x = Math.max(INJECTED_BUTTON_MARGIN, screen.width - INJECTED_BUTTON_WIDTH - INJECTED_BUTTON_MARGIN);
            int y = INJECTED_BUTTON_MARGIN;
            event.addListener(Button.builder(
                    Component.translatable("gui.fireflymc.singleplayer_relay.entry"),
                    button -> mc.setScreen(new SingleplayerRelayControlScreen(screen))
            ).bounds(x, y, INJECTED_BUTTON_WIDTH, INJECTED_BUTTON_HEIGHT).build());
            return;
        }

        if (!(event.getScreen() instanceof JoinMultiplayerScreen screen)) {
            return;
        }

        int x = screen.width - INJECTED_BUTTON_WIDTH - INJECTED_BUTTON_MARGIN;
        int y = INJECTED_BUTTON_MARGIN;
        event.addListener(Button.builder(
                Component.literal("FireflyMC 联机大厅"),
                button -> Minecraft.getInstance().setScreen(new RelayLobbyScreen(screen))
        ).bounds(x, y, INJECTED_BUTTON_WIDTH, INJECTED_BUTTON_HEIGHT).build());
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
