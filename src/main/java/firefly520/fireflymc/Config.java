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
    public final ModConfigSpec.BooleanValue SINGLEPLAYER_RELAY_P2P_ENABLED;
    public final ModConfigSpec.IntValue SINGLEPLAYER_RELAY_P2P_CONNECT_TIMEOUT_SECONDS;
    public final ModConfigSpec.IntValue SINGLEPLAYER_RELAY_P2P_UDP_PORT_MIN;
    public final ModConfigSpec.IntValue SINGLEPLAYER_RELAY_P2P_UDP_PORT_MAX;
    public final ModConfigSpec.IntValue SINGLEPLAYER_RELAY_P2P_RETRANSMIT_MILLIS;
    public final ModConfigSpec.IntValue SINGLEPLAYER_RELAY_P2P_WINDOW_SIZE;

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

      SINGLEPLAYER_RELAY_P2P_ENABLED = builder
        .comment("Try P2P UDP tunnel before falling back to the WebSocket relay")
        .translation("fireflymc.config.singleplayer_relay.p2p_enabled")
        .define("p2pEnabled", true);

      SINGLEPLAYER_RELAY_P2P_CONNECT_TIMEOUT_SECONDS = builder
        .comment("P2P connection timeout before falling back to relay")
        .translation("fireflymc.config.singleplayer_relay.p2p_connect_timeout_seconds")
        .defineInRange("p2pConnectTimeoutSeconds", 10, 3, 30);

      SINGLEPLAYER_RELAY_P2P_UDP_PORT_MIN = builder
        .comment("Minimum local UDP port for P2P endpoints, 0 means random port")
        .translation("fireflymc.config.singleplayer_relay.p2p_udp_port_min")
        .defineInRange("p2pUdpPortMin", 0, 0, 65535);

      SINGLEPLAYER_RELAY_P2P_UDP_PORT_MAX = builder
        .comment("Maximum local UDP port for P2P endpoints, 0 means random port")
        .translation("fireflymc.config.singleplayer_relay.p2p_udp_port_max")
        .defineInRange("p2pUdpPortMax", 0, 0, 65535);

      SINGLEPLAYER_RELAY_P2P_RETRANSMIT_MILLIS = builder
        .comment("Reliable UDP retransmission interval in milliseconds")
        .translation("fireflymc.config.singleplayer_relay.p2p_retransmit_millis")
        .defineInRange("p2pRetransmitMillis", 120, 50, 1000);

      SINGLEPLAYER_RELAY_P2P_WINDOW_SIZE = builder
        .comment("Reliable UDP sliding window size in packets")
        .translation("fireflymc.config.singleplayer_relay.p2p_window_size")
        .defineInRange("p2pWindowSize", 64, 8, 512);

      builder.pop();
    }
  }
}
