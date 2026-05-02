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
    public final ModConfigSpec.BooleanValue SINGLEPLAYER_RELAY_ENABLED;
    public final ModConfigSpec.BooleanValue SINGLEPLAYER_RELAY_PROMPT_ON_JOIN;
    public final ModConfigSpec.BooleanValue SINGLEPLAYER_RELAY_ALLOW_COMMANDS;
    public final ModConfigSpec.IntValue SINGLEPLAYER_RELAY_MAX_PLAYERS;
    public final ModConfigSpec.ConfigValue<String> SINGLEPLAYER_RELAY_URL;

    public ClientConfig(ModConfigSpec.Builder builder) {
      // 给配置节指定翻译键，官方推荐格式：<modid>.config.<节名>
      builder.push("hud_settings")
              .translation("fireflymc.config.hud_settings");

      // 给配置项指定翻译键，官方推荐格式：<modid>.config.<节名>.<键名>
      HUD_SCALE = builder
              .comment("HUD interface scale value, range 0.5 to 1.0")
              .translation("fireflymc.config.hud_settings.hud_scale") // 官方强制指定翻译键
              .defineInRange("hud_scale", 0.5, 0.5, 1.0);

      // 退出配置节
      builder.pop();

      builder.push("singleplayer_relay")
        .translation("fireflymc.config.singleplayer_relay");

      SINGLEPLAYER_RELAY_ENABLED = builder
        .comment("Enable the singleplayer public lobby prompt and LAN bridge preparation")
        .translation("fireflymc.config.singleplayer_relay.enabled")
        .define("enabled", true);

      SINGLEPLAYER_RELAY_PROMPT_ON_JOIN = builder
        .comment("Show a prompt after entering a singleplayer world to ask whether to publish it")
        .translation("fireflymc.config.singleplayer_relay.prompt_on_join")
        .define("promptOnJoin", true);

      SINGLEPLAYER_RELAY_ALLOW_COMMANDS = builder
        .comment("Allow commands when opening the integrated server to LAN for relay hosting")
        .translation("fireflymc.config.singleplayer_relay.allow_commands")
        .define("allowCommands", false);

      SINGLEPLAYER_RELAY_MAX_PLAYERS = builder
        .comment("Maximum remote players allowed by the future relay lobby")
        .translation("fireflymc.config.singleplayer_relay.max_players")
        .defineInRange("maxPlayers", 8, 1, 10);

      SINGLEPLAYER_RELAY_URL = builder
        .comment("WebSocket URL for FireflyMC singleplayer relay lobby")
        .translation("fireflymc.config.singleplayer_relay.relay_url")
        .define("relayUrl", "wss://fk.firefly520.top/relay");

      builder.pop();
    }
  }
}
