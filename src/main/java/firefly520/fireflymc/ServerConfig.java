package firefly520.fireflymc;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * 服务端配置
 */
public class ServerConfig {
    public static final ServerConfigImpl SERVER;
    public static final ModConfigSpec SERVER_SPEC;

    static {
        Pair<ServerConfigImpl, ModConfigSpec> serverPair = new ModConfigSpec.Builder()
                .configure(ServerConfigImpl::new);
        SERVER = serverPair.getLeft();
        SERVER_SPEC = serverPair.getRight();
    }

    public static class ServerConfigImpl {
        public final ModConfigSpec.BooleanValue enableRemoteShutdown;
        public final ModConfigSpec.ConfigValue<String> shutdownKey;

        public ServerConfigImpl(ModConfigSpec.Builder builder) {
            builder.push("server")
                    .translation("fireflymc.config.server");

            enableRemoteShutdown = builder
                    .comment("Enable remote server shutdown via WebSocket")
                    .translation("fireflymc.config.server.enable_remote_shutdown")
                    .define("enableRemoteShutdown", true);

            shutdownKey = builder
                    .comment("Secret key for remote shutdown verification")
                    .translation("fireflymc.config.server.shutdown_key")
                    .define("shutdownKey", "change-this-key-in-production");

            builder.pop();
        }
    }
}
