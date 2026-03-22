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
        // 服务器配置
        public final ModConfigSpec.BooleanValue enableRemoteShutdown;
        public final ModConfigSpec.ConfigValue<String> shutdownKey;
        public final ModConfigSpec.BooleanValue enableMemberVerification;
        public final ModConfigSpec.IntValue memberVerificationTimeout;

        // AI配置
        public final ModConfigSpec.ConfigValue<String> aiApiUrl;
        public final ModConfigSpec.ConfigValue<String> aiApiKey;
        public final ModConfigSpec.ConfigValue<String> aiModel;
        public final ModConfigSpec.ConfigValue<String> aiName;
        public final ModConfigSpec.ConfigValue<String> aiNamePlain;
        public final ModConfigSpec.ConfigValue<String> aiUuid;
        public final ModConfigSpec.IntValue aiMaxHistorySize;
        public final ModConfigSpec.IntValue aiMaxResponseLength;
        public final ModConfigSpec.IntValue aiCooldownSeconds;
        public final ModConfigSpec.BooleanValue aiBroadcastToAll;
        public final ModConfigSpec.BooleanValue aiEnabled;

        // AI主动回复配置
        public final ModConfigSpec.BooleanValue aiProactiveEnabled;
        public final ModConfigSpec.IntValue aiProactiveInterval;
        public final ModConfigSpec.IntValue aiProactiveTimeout;

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

            enableMemberVerification = builder
                    .comment("Enable WebSocket member verification (kick players not in verified list)")
                    .translation("fireflymc.config.server.enable_member_verification")
                    .define("enableMemberVerification", false);

            memberVerificationTimeout = builder
                    .comment("Member verification timeout in seconds")
                    .translation("fireflymc.config.server.member_verification_timeout")
                    .defineInRange("memberVerificationTimeout", 10, 3, 60);

            builder.pop();

            // AI配置
            builder.push("ai")
                    .comment("AI聊天功能配置")
                    .translation("fireflymc.config.ai");

            aiApiUrl = builder
                    .comment("AI API地址")
                    .translation("fireflymc.config.ai.api_url")
                    .define("apiUrl", "https://api.xiaomimimo.com/v1");

            aiApiKey = builder
                    .comment("AI API密钥 (需要替换为你的实际密钥)")
                    .translation("fireflymc.config.ai.api_key")
                    .define("apiKey", "your-api-key-here");

            aiModel = builder
                    .comment("AI模型名称")
                    .translation("fireflymc.config.ai.model")
                    .define("model", "mimo-v2-flash");

            aiName = builder
                    .comment("AI显示名称 (支持颜色代码，如 §d 表示粉色)")
                    .translation("fireflymc.config.ai.name")
                    .define("name", "§d小樱§r");

            aiNamePlain = builder
                    .comment("AI纯文本名称 (不包含颜色代码)")
                    .translation("fireflymc.config.ai.name_plain")
                    .define("namePlain", "小樱");

            aiUuid = builder
                    .comment("AI UUID (用于标识)")
                    .translation("fireflymc.config.ai.uuid")
                    .define("uuid", "00000000-0000-4000-8000-000000000001");

            aiMaxHistorySize = builder
                    .comment("聊天历史记录最大条数")
                    .translation("fireflymc.config.ai.max_history_size")
                    .defineInRange("maxHistorySize", 30, 1, 100);

            aiMaxResponseLength = builder
                    .comment("AI回复最大长度")
                    .translation("fireflymc.config.ai.max_response_length")
                    .defineInRange("maxResponseLength", 200, 50, 1000);

            aiCooldownSeconds = builder
                    .comment("命令冷却时间（秒），0表示无冷却")
                    .translation("fireflymc.config.ai.cooldown_seconds")
                    .defineInRange("cooldownSeconds", 5, 0, 60);

            aiBroadcastToAll = builder
                    .comment("是否将AI回复广播给所有玩家 (false则仅发送给触发玩家)")
                    .translation("fireflymc.config.ai.broadcast_to_all")
                    .define("broadcastToAll", true);

            aiEnabled = builder
                    .comment("是否启用AI聊天功能")
                    .translation("fireflymc.config.ai.enabled")
                    .define("enabled", true);

            aiProactiveEnabled = builder
                    .comment("是否启用AI主动回复（智能判断是否参与对话）")
                    .translation("fireflymc.config.ai.proactive_enabled")
                    .define("proactiveEnabled", true);

            aiProactiveInterval = builder
                    .comment("主动回复触发间隔（玩家聊天消息条数）")
                    .translation("fireflymc.config.ai.proactive_interval")
                    .defineInRange("proactiveInterval", 50, 1, 100);

            aiProactiveTimeout = builder
                    .comment("主动回复判断API超时时间（秒）")
                    .translation("fireflymc.config.ai.proactive_timeout")
                    .defineInRange("proactiveTimeout", 8, 3, 30);

            builder.pop();
        }
    }
}
