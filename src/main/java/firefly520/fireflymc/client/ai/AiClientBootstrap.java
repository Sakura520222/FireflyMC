package firefly520.fireflymc.client.ai;

import firefly520.fireflymc.client.ai.workspace.CompanionWorkspaceScreen;
import firefly520.fireflymc.client.ai.workspace.HudLayoutScreen;
import firefly520.fireflymc.client.ai.workspace.WorkspaceReviewScreen;
import firefly520.fireflymc.client.ai.hud.CompanionStatusHud;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.*;
import org.lwjgl.glfw.GLFW;

/** Loaded exclusively inside FireflyMC's existing physical-client initialization branch. */
public final class AiClientBootstrap {
    public static final KeyMapping OPEN = new KeyMapping("key.fireflymc.ai_workspace", GLFW.GLFW_KEY_K,
            "key.categories.fireflymc");
    private AiClientBootstrap() {}
    public static void register(IEventBus modBus) {
        modBus.addListener((RegisterKeyMappingsEvent event) -> event.register(OPEN));
        modBus.addListener((RegisterGuiLayersEvent event) -> event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath("fireflymc", "ai_status"), CompanionStatusHud::render));
        NeoForge.EVENT_BUS.addListener(AiClientBootstrap::tick);
        NeoForge.EVENT_BUS.addListener(AiClientBootstrap::key);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> AiClientNetwork.disconnected());
    }
    private static void tick(ClientTickEvent.Post event) {
        AiClientNetwork.tick();
        Minecraft mc = Minecraft.getInstance();
        while (OPEN.consumeClick()) {
            if (mc.screen == null) AiClientNetwork.open();
        }
    }
    /** KeyMapping clicks never accumulate while a screen is open, so GUI-covered presses go through the raw key event. */
    private static void key(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS) return;
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        if (screen == null || screen instanceof CompanionWorkspaceScreen
                || screen instanceof HudLayoutScreen || screen instanceof WorkspaceReviewScreen) return;
        if (screen.getFocused() instanceof EditBox box && box.canConsumeInput()) return;
        if (OPEN.matches(event.getKey(), event.getScanCode())) AiClientNetwork.open();
    }
}
