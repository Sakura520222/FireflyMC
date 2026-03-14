package firefly520.fireflymc.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;






@EventBusSubscriber(value = {Dist.CLIENT}, bus = EventBusSubscriber.Bus.MOD, modid = "fireflymc")
public class ClientHandler
{
  public static void onClientSetup(FMLClientSetupEvent event) {
    NeoForge.EVENT_BUS.addListener(ClientHandler::onRenderGui);
  }





  
  @SubscribeEvent
  public static void onRenderGui(RenderGuiEvent.Post event) {
    HUDRenderer.render(event.getGuiGraphics());
  }
}