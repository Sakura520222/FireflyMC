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
import firefly520.fireflymc.client.ClientHandler;
import firefly520.fireflymc.network.ModNetwork;

@Mod(FireflyMCMod.MODID)
public class FireflyMCMod {
  public static final String MODID = "fireflymc";
  public static final String VERSION = "2.1.1";

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
    }

    // 4. 注册游戏事件处理（GAME 总线）
    NeoForge.EVENT_BUS.addListener(ModEventHandler::onPlayerLoggedIn);
    NeoForge.EVENT_BUS.addListener(ModEventHandler::onPlayerLoggedOut);

    System.out.println("Loading FireflyMC 2.1.1");
  }
}
