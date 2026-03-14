/*     */ package top.firefly520.fireflymc.client;
/*     */ 
/*     */ import java.util.Objects;
/*     */ import net.minecraft.client.Minecraft;
/*     */ import net.minecraft.client.gui.Font;
/*     */ import net.minecraft.client.gui.GuiGraphics;
/*     */ import net.minecraft.client.multiplayer.ClientPacketListener;
/*     */ import net.minecraft.client.player.LocalPlayer;
/*     */ import net.minecraft.network.chat.Component;
/*     */ import net.minecraft.network.chat.FormattedText;
/*     */ import net.minecraft.network.chat.MutableComponent;
/*     */ 
/*     */ 
/*     */ public class HUDRenderer
/*     */ {
/*  16 */   private static final Component SERVER_NAME = (Component)Component.literal("FireflyMC 2.0.0");
/*  17 */   private static final Component WEBSITE_URL = (Component)Component.literal("https://mc.firefly520.top");
/*  18 */   private static final Component PLAYER_COUNT_PREFIX = (Component)Component.literal("在线人数: ");
/*     */ 
/*     */   
/*     */   private static final int TEXT_COLOR = 16777215;
/*     */   
/*     */   private static final int BACKGROUND_COLOR = -2147483648;
/*     */ 
/*     */   
/*     */   public static void render(GuiGraphics guiGraphics) {
/*  27 */     Minecraft mc = Minecraft.getInstance();
/*     */ 
/*     */     
/*  30 */     if (mc.screen != null) {
/*     */       return;
/*     */     }
/*     */ 
/*     */     
/*  35 */     LocalPlayer player = mc.player;
/*  36 */     if (player == null) {
/*     */       return;
/*     */     }
/*     */ 
/*     */     
/*  41 */     int playerCount = getPlayerCount(player);
/*     */ 
/*     */     
/*  44 */     Font font = mc.font;
/*  45 */     int screenWidth = mc.getWindow().getGuiScaledWidth();
/*  46 */     int screenHeight = mc.getWindow().getGuiScaledHeight();
/*     */ 
/*     */     
/*  49 */     Objects.requireNonNull(font); int lineHeight = 9 + 2;
/*  50 */     int totalHeight = lineHeight * 3 + 4;
/*  51 */     int startY = (screenHeight - totalHeight) / 2;
/*  52 */     int x = 5;
/*     */ 
/*     */     
/*  55 */     int serverNameWidth = font.width((FormattedText)SERVER_NAME);
/*  56 */     int playerCountWidth = font.width((FormattedText)PLAYER_COUNT_PREFIX) + font.width((FormattedText)Component.literal(String.valueOf(playerCount)));
/*  57 */     int websiteUrlWidth = font.width((FormattedText)WEBSITE_URL);
/*  58 */     int maxWidth = Math.max(Math.max(serverNameWidth, playerCountWidth), websiteUrlWidth) + 10;
/*     */ 
/*     */     
/*  61 */     guiGraphics.fill(x, startY - 2, x + maxWidth, startY + totalHeight, -2147483648);
/*     */ 
/*     */     
/*  64 */     int y = startY;
/*     */ 
/*     */     
/*  67 */     guiGraphics.drawString(font, SERVER_NAME, x + 5, y, 16777215);
/*  68 */     y += lineHeight;
/*     */ 
/*     */ 
/*     */ 
/*     */     
/*  73 */     MutableComponent mutableComponent = Component.literal("").append(PLAYER_COUNT_PREFIX).append((Component)Component.literal(String.valueOf(playerCount)));
/*  74 */     guiGraphics.drawString(font, (Component)mutableComponent, x + 5, y, 16777215);
/*  75 */     y += lineHeight;
/*     */ 
/*     */     
/*  78 */     guiGraphics.drawString(font, WEBSITE_URL, x + 5, y, 16777215);
/*     */   }
/*     */ 
/*     */ 
/*     */ 
/*     */ 
/*     */ 
/*     */   
/*     */   private static int getPlayerCount(LocalPlayer player) {
/*  87 */     ClientPacketListener connection = player.connection;
/*  88 */     if (connection != null) {
/*     */       
/*     */       try {
/*  91 */         if (connection.getOnlinePlayers() != null) {
/*  92 */           return connection.getOnlinePlayers().size();
/*     */         }
/*  94 */       } catch (Exception e) {
/*     */         
/*  96 */         return 1;
/*     */       } 
/*     */     }
/*     */ 
/*     */     
/* 101 */     return 1;
/*     */   }
/*     */ }


/* Location:              D:\MC\.minecraft\versions\FireflyMC-1.21.1-NeoForge\mods\fireflymc-2.0.0.jar!\top\firefly520\fireflymc\client\HUDRenderer.class
 * Java compiler version: 21 (65.0)
 * JD-Core Version:       1.1.3
 */