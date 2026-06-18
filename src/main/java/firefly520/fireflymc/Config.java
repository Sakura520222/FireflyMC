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
    public final ModConfigSpec.ConfigValue<Double> HUD_SCALE;
    public final ModConfigSpec.BooleanValue SINGLEPLAYER_RELAY_ENABLED;
    public final ModConfigSpec.BooleanValue SINGLEPLAYER_RELAY_PROMPT_ON_JOIN;
    public final ModConfigSpec.BooleanValue SINGLEPLAYER_RELAY_ALLOW_COMMANDS;
    public final ModConfigSpec.ConfigValue<Integer> SINGLEPLAYER_RELAY_MAX_PLAYERS;
    public final ModConfigSpec.ConfigValue<String> SINGLEPLAYER_RELAY_URL;
    public final ModConfigSpec.BooleanValue SINGLEPLAYER_RELAY_P2P_ENABLED;
    public final ModConfigSpec.ConfigValue<Integer> SINGLEPLAYER_RELAY_P2P_CONNECT_TIMEOUT_SECONDS;
    public final ModConfigSpec.ConfigValue<Integer> SINGLEPLAYER_RELAY_P2P_UDP_PORT_MIN;
    public final ModConfigSpec.ConfigValue<Integer> SINGLEPLAYER_RELAY_P2P_UDP_PORT_MAX;
    public final ModConfigSpec.ConfigValue<Integer> SINGLEPLAYER_RELAY_P2P_RETRANSMIT_MILLIS;
    public final ModConfigSpec.ConfigValue<Integer> SINGLEPLAYER_RELAY_P2P_WINDOW_SIZE;
    public final ModConfigSpec.BooleanValue SINGLEPLAYER_RELAY_P2P_IPV6_ENABLED;
    public final ModConfigSpec.BooleanValue EVENT_NOTIFICATION_ENABLED;
    public final ModConfigSpec.ConfigValue<String> EVENT_NOTIFICATION_URL;
    public final ModConfigSpec.BooleanValue EVENT_NOTIFICATION_AUTO_RECONNECT;
    public final ModConfigSpec.ConfigValue<Integer> EVENT_NOTIFICATION_RECONNECT_INTERVAL_MILLIS;
    public final ModConfigSpec.ConfigValue<Integer> EVENT_NOTIFICATION_HEARTBEAT_INTERVAL_MILLIS;
    public final ModConfigSpec.ConfigValue<Integer> EVENT_NOTIFICATION_SEND_TIMEOUT_MILLIS;
    public final ModConfigSpec.ConfigValue<Integer> EVENT_NOTIFICATION_QUEUE_CAPACITY;
    public final ModConfigSpec.BooleanValue CROSS_CHAT_ENABLED;

    public ClientConfig(ModConfigSpec.Builder builder) {
      builder.push("hud_settings")
              .translation("fireflymc.configuration.hud_settings");

      HUD_SCALE = builder
          .comment("HUD interface scale value")
          .translation("fireflymc.config.hud_settings.hud_scale")
          .define("hud_scale", 0.5D);

      builder.pop();

      builder.push("singleplayer_relay")
        .translation("fireflymc.configuration.singleplayer_relay");

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
        .comment("Maximum remote players allowed by the relay lobby")
        .translation("fireflymc.config.singleplayer_relay.max_players")
        .define("maxPlayers", 8);

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
        .define("p2pConnectTimeoutSeconds", 10);

      SINGLEPLAYER_RELAY_P2P_UDP_PORT_MIN = builder
        .comment("Minimum local UDP port for P2P endpoints, 0 means random port")
        .translation("fireflymc.config.singleplayer_relay.p2p_udp_port_min")
        .define("p2pUdpPortMin", 0);

      SINGLEPLAYER_RELAY_P2P_UDP_PORT_MAX = builder
        .comment("Maximum local UDP port for P2P endpoints, 0 means random port")
        .translation("fireflymc.config.singleplayer_relay.p2p_udp_port_max")
        .define("p2pUdpPortMax", 0);

      SINGLEPLAYER_RELAY_P2P_RETRANSMIT_MILLIS = builder
        .comment("Reliable UDP retransmission interval in milliseconds")
        .translation("fireflymc.config.singleplayer_relay.p2p_retransmit_millis")
        .define("p2pRetransmitMillis", 120);

      SINGLEPLAYER_RELAY_P2P_WINDOW_SIZE = builder
        .comment("Reliable UDP sliding window size in packets")
        .translation("fireflymc.config.singleplayer_relay.p2p_window_size")
        .define("p2pWindowSize", 64);

      SINGLEPLAYER_RELAY_P2P_IPV6_ENABLED = builder
        .comment("Try direct IPv6 connection first when both peers have public IPv6 addresses")
        .translation("fireflymc.config.singleplayer_relay.p2p_ipv6_enabled")
        .define("p2pIpv6Enabled", true);

      builder.pop();

      builder.push("event_notification")
        .translation("fireflymc.configuration.event_notification");

      EVENT_NOTIFICATION_ENABLED = builder
        .comment("Enable client-side player event notifications over WebSocket")
        .translation("fireflymc.config.event_notification.enabled")
        .define("enabled", true);

      EVENT_NOTIFICATION_URL = builder
        .comment("WebSocket URL for client-side player event notifications")
        .translation("fireflymc.config.event_notification.web_socket_url")
        .define("webSocketUrl", "wss://fk.firefly520.top/events");

      EVENT_NOTIFICATION_AUTO_RECONNECT = builder
        .comment("Reconnect the event notification WebSocket after the connection closes")
        .translation("fireflymc.config.event_notification.auto_reconnect")
        .define("autoReconnect", true);

      EVENT_NOTIFICATION_RECONNECT_INTERVAL_MILLIS = builder
        .comment("Delay before reconnecting the event notification WebSocket in milliseconds")
        .translation("fireflymc.config.event_notification.reconnect_interval_millis")
        .define("reconnectIntervalMillis", 5000);

      EVENT_NOTIFICATION_HEARTBEAT_INTERVAL_MILLIS = builder
        .comment("Heartbeat interval for the event notification WebSocket in milliseconds")
        .translation("fireflymc.config.event_notification.heartbeat_interval_millis")
        .define("heartbeatIntervalMillis", 30000);

      EVENT_NOTIFICATION_SEND_TIMEOUT_MILLIS = builder
        .comment("Timeout for sending event notification WebSocket messages in milliseconds")
        .translation("fireflymc.config.event_notification.send_timeout_millis")
        .define("sendTimeoutMillis", 5000);

      EVENT_NOTIFICATION_QUEUE_CAPACITY = builder
        .comment("Maximum queued event notification messages while the WebSocket is reconnecting")
        .translation("fireflymc.config.event_notification.queue_capacity")
        .define("queueCapacity", 128);

      CROSS_CHAT_ENABLED = builder
        .comment("跨级聊天：开启后，游戏内聊天将与 QQ 群双向互通（需云端同步启用 cross_chat_enabled）")
        .translation("fireflymc.config.event_notification.cross_chat_enabled")
        .define("crossChatEnabled", true);

      builder.pop();
    }
  }
}
