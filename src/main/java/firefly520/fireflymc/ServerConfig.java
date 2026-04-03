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
        public final ModConfigSpec.ConfigValue<String> wsAuthKey;
        public final ModConfigSpec.BooleanValue enableMemberVerification;
        public final ModConfigSpec.IntValue memberVerificationTimeout;
        public final ModConfigSpec.BooleanValue enableItemCleanup;
        public final ModConfigSpec.IntValue itemCleanupIntervalMinutes;

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

        // AI函数调用配置
        public final ModConfigSpec.BooleanValue aiFunctionsEnabled;
        public final ModConfigSpec.IntValue aiFunctionsRequireOpLevel;

        // 新手福利包配置
        public final ModConfigSpec.BooleanValue enableStarterKit;

        // 在线时长限制配置
        public final ModConfigSpec.BooleanValue enablePlaytimeLimiter;
        public final ModConfigSpec.IntValue playtimeDailyLimitMinutes;
        public final ModConfigSpec.IntValue playtimeContinuousLimitMinutes;
        public final ModConfigSpec.IntValue playtimeBypassOpLevel;
        public final ModConfigSpec.IntValue playtimeCheckIntervalSeconds;
        public final ModConfigSpec.ConfigValue<String> playtimeKickMessageDaily;
        public final ModConfigSpec.ConfigValue<String> playtimeKickMessageContinuous;

        public ServerConfigImpl(ModConfigSpec.Builder builder) {
            builder.push("server")
                    .translation("fireflymc.config.server");

            enableRemoteShutdown = builder
                    .comment("Enable remote server shutdown via WebSocket")
                    .translation("fireflymc.config.server.enable_remote_shutdown")
                    .define("enableRemoteShutdown", true);

            wsAuthKey = builder
                    .comment("Secret key for WebSocket authentication")
                    .translation("fireflymc.config.server.ws_auth_key")
                    .define("wsAuthKey", "change-this-key-in-production");

            enableMemberVerification = builder
                    .comment("Enable WebSocket member verification (kick players not in verified list)")
                    .translation("fireflymc.config.server.enable_member_verification")
                    .define("enableMemberVerification", false);

            memberVerificationTimeout = builder
                    .comment("Member verification timeout in seconds")
                    .translation("fireflymc.config.server.member_verification_timeout")
                    .defineInRange("memberVerificationTimeout", 10, 3, 60);

            enableItemCleanup = builder
                    .comment("Enable automatic item cleanup (remove dropped items periodically)")
                    .translation("fireflymc.config.server.enable_item_cleanup")
                    .define("enableItemCleanup", true);

            itemCleanupIntervalMinutes = builder
                    .comment("Item cleanup interval in minutes")
                    .translation("fireflymc.config.server.item_cleanup_interval_minutes")
                    .defineInRange("itemCleanupIntervalMinutes", 5, 1, 60);

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

            // AI函数调用配置
            aiFunctionsEnabled = builder
                    .comment("是否启用AI函数调用功能")
                    .translation("fireflymc.config.ai.functions_enabled")
                    .define("functionsEnabled", true);

            aiFunctionsRequireOpLevel = builder
                    .comment("AI函数调用所需的最低OP等级（0-4）")
                    .translation("fireflymc.config.ai.functions_require_op_level")
                    .defineInRange("functionsRequireOpLevel", 4, 0, 4);

            builder.pop();

            // 新手福利包配置
            builder.push("starterKit")
                    .comment("新手福利包配置")
                    .translation("fireflymc.config.starter_kit");

            enableStarterKit = builder
                    .comment("是否启用新手福利包（首次加入服务器时给予）")
                    .translation("fireflymc.config.starter_kit.enabled")
                    .define("enabled", true);

            builder.pop();

            // 在线时长限制配置
            builder.push("playtime")
                    .comment("玩家在线时长限制配置")
                    .translation("fireflymc.config.playtime");

            enablePlaytimeLimiter = builder
                    .comment("是否启用玩家在线时长限制")
                    .translation("fireflymc.config.playtime.enabled")
                    .define("enablePlaytimeLimiter", false);

            playtimeDailyLimitMinutes = builder
                    .comment("每日最大在线时长（分钟）")
                    .translation("fireflymc.config.playtime.daily_limit_minutes")
                    .defineInRange("dailyLimitMinutes", 480, 30, 1440);

            playtimeContinuousLimitMinutes = builder
                    .comment("连续在线最大时长（分钟）")
                    .translation("fireflymc.config.playtime.continuous_limit_minutes")
                    .defineInRange("continuousLimitMinutes", 120, 15, 720);

            playtimeBypassOpLevel = builder
                    .comment("跳过时长限制的最低OP等级（0=无人跳过，2=OP，4=最高OP）")
                    .translation("fireflymc.config.playtime.bypass_op_level")
                    .defineInRange("bypassOpLevel", 2, 0, 4);

            playtimeCheckIntervalSeconds = builder
                    .comment("时长检查间隔（秒）")
                    .translation("fireflymc.config.playtime.check_interval_seconds")
                    .defineInRange("checkIntervalSeconds", 30, 10, 300);

            playtimeKickMessageDaily = builder
                    .comment("达到每日时长限制时的踢出提示")
                    .translation("fireflymc.config.playtime.kick_message_daily")
                    .define("kickMessageDaily", "§c[FireflyMC] 你今日的在线时长已达上限，明天再来吧！");

            playtimeKickMessageContinuous = builder
                    .comment("达到连续在线时长限制时的踢出提示")
                    .translation("fireflymc.config.playtime.kick_message_continuous")
                    .define("kickMessageContinuous", "§c[FireflyMC] 你已连续在线过久，请休息一下再回来！");

            builder.pop();
        }
    }
}
