/*    */ package firefly520.fireflymc.client;
/*    */ 
/*    */ import net.neoforged.api.distmarker.Dist;
/*    */ import net.neoforged.bus.api.SubscribeEvent;
/*    */ import net.neoforged.fml.common.EventBusSubscriber;
/*    */ import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
/*    */ import net.neoforged.neoforge.client.event.RenderGuiEvent;
/*    */ import net.neoforged.neoforge.common.NeoForge;
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ @EventBusSubscriber(value = {Dist.CLIENT}, bus = EventBusSubscriber.Bus.MOD, modid = "fireflymc")
/*    */ public class ClientHandler
/*    */ {
/*    */   public static void onClientSetup(FMLClientSetupEvent event) {
/* 19 */     NeoForge.EVENT_BUS.addListener(ClientHandler::onRenderGui);
/*    */   }
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */   
/*    */   @SubscribeEvent
/*    */   public static void onRenderGui(RenderGuiEvent.Post event) {
/* 29 */     HUDRenderer.render(event.getGuiGraphics());
/*    */   }
/*    */ }


/* Location:              D:\MC\.minecraft\versions\FireflyMC-1.21.1-NeoForge\mods\fireflymc-2.0.0.jar!\top\firefly520\fireflymc\client\ClientHandler.class
 * Java compiler version: 21 (65.0)
 * JD-Core Version:       1.1.3
 */