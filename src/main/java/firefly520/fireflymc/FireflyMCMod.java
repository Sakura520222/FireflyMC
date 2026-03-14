/*    */ package top.firefly520.fireflymc;
/*    */ 
/*    */ import net.neoforged.bus.api.IEventBus;
/*    */ import net.neoforged.fml.common.Mod;
/*    */ import top.firefly520.fireflymc.client.ClientHandler;
/*    */ 
/*    */ @Mod("fireflymc")
/*    */ public class FireflyMCMod
/*    */ {
/*    */   public static final String MODID = "fireflymc";
/*    */   public static final String VERSION = "2.0.0";
/*    */   
/*    */   public FireflyMCMod(IEventBus modEventBus) {
/* 14 */     modEventBus.addListener(ClientHandler::onClientSetup);
/*    */ 
/*    */     
/* 17 */     System.out.println("Loading FireflyMC GUI 2.0.0");
/*    */   }
/*    */ }


/* Location:              D:\MC\.minecraft\versions\FireflyMC-1.21.1-NeoForge\mods\fireflymc-2.0.0.jar!\top\firefly520\fireflymc\FireflyMCMod.class
 * Java compiler version: 21 (65.0)
 * JD-Core Version:       1.1.3
 */