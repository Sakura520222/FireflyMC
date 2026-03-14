package firefly520.fireflymc;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import firefly520.fireflymc.client.ClientHandler;

@Mod(FireflyMCMod.MODID)
public class FireflyMCMod {
  public static final String MODID = "fireflymc";
  public static final String VERSION = "2.0.0";

  public FireflyMCMod(IEventBus modEventBus, ModContainer modContainer) {
    // 1. 注册客户端配置（官方标准写法）
    modContainer.registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);

    // 2. 注册配置界面（官方文档推荐写法，无编译歧义）
    modContainer.registerExtensionPoint(
            IConfigScreenFactory.class,
            ConfigurationScreen::new
    );

    // 3. 注册客户端事件
    modEventBus.addListener(ClientHandler::onClientSetup);

    System.out.println("Loading FireflyMC 2.0.0");
  }
}
