package firefly520.fireflymc;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import firefly520.fireflymc.client.ClientHandler;
import firefly520.fireflymc.client.UpdateChecker;
import firefly520.fireflymc.client.TitleScreenDetector;
import firefly520.fireflymc.event.websocket.PlayerEventWebSocketClient;
import firefly520.fireflymc.network.ModNetwork;
import firefly520.fireflymc.util.ServerLanguageLoader;

@Mod(FireflyMCMod.MODID)
public class FireflyMCMod {
  public static final String MODID = "fireflymc";
  public static final String VERSION = "2.3.2";

  public FireflyMCMod(IEventBus modEventBus, ModContainer modContainer) {
    // 1. 注册客户端配置（官方标准写法）
    modContainer.registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);

    // 2. 注册网络包处理（MOD 总线）
    modEventBus.addListener(ModNetwork::registerPayloads);

    // 3. 客户端专用注册
    if (FMLEnvironment.dist == Dist.CLIENT) {
      modContainer.registerExtensionPoint(
              IConfigScreenFactory.class,
              ConfigurationScreen::new
      );
      // 直接注册渲染事件到 NeoForge 总线
      NeoForge.EVENT_BUS.addListener(ClientHandler::onRenderGui);
      // 注册主菜单更新通知检测器
      NeoForge.EVENT_BUS.addListener(TitleScreenDetector::onScreenRender);
    }

    // 4. 注册游戏事件处理（GAME 总线）
    NeoForge.EVENT_BUS.addListener(ModEventHandler::onPlayerLoggedIn);
    NeoForge.EVENT_BUS.addListener(ModEventHandler::onPlayerLoggedOut);

    // 4.5. 初始化WebSocket事件广播（仅服务端）
    if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
      PlayerEventWebSocketClient.init();
    }

    // 4.6. 注册服务器生命周期事件（加载中文语言文件）
    NeoForge.EVENT_BUS.addListener(this::onServerStarted);
    NeoForge.EVENT_BUS.addListener(this::onServerStopping);

    // 5. 检查Mod更新
    UpdateChecker.checkForUpdate();

    System.out.println("Loading FireflyMC 2.3.2");
  }

  // 服务端启动完成后加载中文语言文件
  private void onServerStarted(ServerStartedEvent event) {
    ServerLanguageLoader.loadZhCnLanguage();
    // 设置服务器实例，用于WebSocket接收消息后广播
    firefly520.fireflymc.event.websocket.PlayerEventWebSocketClient.setServer(event.getServer());
  }

  // 服务端关闭时清理资源
  private void onServerStopping(ServerStoppingEvent event) {
    ServerLanguageLoader.clear();
    // 清理WebSocket服务器实例引用
    firefly520.fireflymc.event.websocket.PlayerEventWebSocketClient.clearServer();
  }
}
