package firefly520.fireflymc;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class Config {
  public static final ClientConfig CLIENT;
  public static final ModConfigSpec CLIENT_SPEC;

  static {
    Pair<ClientConfig, ModConfigSpec> clientPair = new ModConfigSpec.Builder()
            .configure(ClientConfig::new);
    CLIENT = clientPair.getLeft();
    CLIENT_SPEC = clientPair.getRight();
  }

  public static class ClientConfig {
    public final ModConfigSpec.DoubleValue HUD_SCALE;

    public ClientConfig(ModConfigSpec.Builder builder) {
      // 给配置节指定翻译键，官方推荐格式：<modid>.config.<节名>
      builder.push("hud_settings")
              .translation("fireflymc.config.hud_settings");

      // 给配置项指定翻译键，官方推荐格式：<modid>.config.<节名>.<键名>
      HUD_SCALE = builder
              .comment("HUD interface scale value, range 0.5 to 1.0")
              .translation("fireflymc.config.hud_settings.hud_scale") // 官方强制指定翻译键
              .defineInRange("hud_scale", 0.75, 0.5, 1.0);

      // 退出配置节
      builder.pop();
    }
  }
}
