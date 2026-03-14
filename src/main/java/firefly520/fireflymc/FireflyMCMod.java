package firefly520.fireflymc;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import firefly520.fireflymc.client.ClientHandler;

@Mod("fireflymc")
public class FireflyMCMod {
  public static final String MODID = "fireflymc";
  public static final String VERSION = "2.0.0";

  public FireflyMCMod(IEventBus modEventBus, ModContainer modContainer) {
    // 注册配置
    modContainer.registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);

    // 注册配置界面
    modContainer.registerExtensionPoint(
            IConfigScreenFactory.class,
            (screen, container) -> new net.neoforged.neoforge.client.gui.ConfigurationScreen(screen, container)
    );

    modEventBus.addListener(ClientHandler::onClientSetup);

    System.out.println("Loading FireflyMC GUI 2.0.0");
  }
}
