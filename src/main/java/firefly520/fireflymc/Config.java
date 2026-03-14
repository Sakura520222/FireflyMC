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
      builder.push("界面设置");

      HUD_SCALE = builder
              .comment("HUD 缩放值，范围 0.5 到 2.0")
              .defineInRange("缩放大小", 0.75, 0.5, 2.0);

      builder.pop();
    }
  }
}
