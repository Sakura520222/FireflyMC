package firefly520.fireflymc.client;

import net.neoforged.neoforge.client.event.RenderGuiEvent;

public class ClientHandler {
  public static void onRenderGui(RenderGuiEvent.Post event) {
    HUDRenderer.render(event.getGuiGraphics());
  }
}
