package firefly520.fireflymc;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

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
        public final ModConfigSpec.BooleanValue enableItemCleanup;
        public final ModConfigSpec.IntValue itemCleanupIntervalMinutes;
        public final ModConfigSpec.IntValue itemCleanupWarningSeconds;
        public final ModConfigSpec.IntValue itemCleanupCountdownSeconds;

        // 玩家密码验证配置
        public final ModConfigSpec.BooleanValue playerAuthEnabled;
        public final ModConfigSpec.ConfigValue<Integer> playerAuthTimeoutSeconds;
        public final ModConfigSpec.ConfigValue<Integer> playerAuthMaxAttempts;
        public final ModConfigSpec.ConfigValue<String> playerAuthKickMessageTimeout;
        public final ModConfigSpec.ConfigValue<String> playerAuthKickMessageFailed;
        public final ModConfigSpec.IntValue playerAuthLockoutMinutes;

        // AI配置
        public final ModConfigSpec.ConfigValue<String> aiApiUrl;
        public final ModConfigSpec.ConfigValue<String> aiApiKey;
        public final ModConfigSpec.ConfigValue<String> aiModel;
        public final ModConfigSpec.ConfigValue<String> aiName;
        public final ModConfigSpec.ConfigValue<String> aiNamePlain;
        public final ModConfigSpec.ConfigValue<String> aiUuid;
        public final ModConfigSpec.IntValue aiMaxHistorySize;
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
        // 多轮工具调用配置
        public final ModConfigSpec.IntValue aiMaxToolRounds;
        public final ModConfigSpec.IntValue aiMaxToolCalls;
        public final ModConfigSpec.BooleanValue aiParallelToolCalls;
        public final ModConfigSpec.ConfigValue<List<?>> aiDisabledTools;

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
                    .comment("服务器配置")
                    .translation("fireflymc.config.server");

            enableItemCleanup = builder
                    .comment("是否启用掉落物自动清理（定期移除地面掉落物）")
                    .translation("fireflymc.config.server.enable_item_cleanup")
                    .define("enableItemCleanup", true);

            itemCleanupIntervalMinutes = builder
                    .comment("掉落物清理间隔（分钟）")
                    .translation("fireflymc.config.server.item_cleanup_interval_minutes")
                    .defineInRange("itemCleanupIntervalMinutes", 5, 1, 60);

            itemCleanupWarningSeconds = builder
                    .comment("清理前警告时间（秒），0表示不警告")
                    .translation("fireflymc.config.server.item_cleanup_warning_seconds")
                    .defineInRange("itemCleanupWarningSeconds", 60, 0, 300);

            itemCleanupCountdownSeconds = builder
                    .comment("倒计时提醒时长（秒），在清理前最后N秒通过ActionBar逐秒倒计时，0表示不倒计时")
                    .translation("fireflymc.config.server.item_cleanup_countdown_seconds")
                    .defineInRange("itemCleanupCountdownSeconds", 10, 0, 60);

            builder.pop();

            // 玩家密码验证配置
            builder.push("playerAuth")
                    .comment("玩家密码验证配置（离线模式防顶号）")
                    .translation("fireflymc.config.player_auth");

            playerAuthEnabled = builder
                    .comment("是否启用玩家密码验证（首次加入设置密码，后续每次验证）")
                    .translation("fireflymc.config.player_auth.enabled")
                    .define("enabled", true);

            playerAuthTimeoutSeconds = builder
                    .comment("密码验证超时时间（秒）")
                    .translation("fireflymc.config.player_auth.timeout_seconds")
                    .define("timeoutSeconds", 60);

            playerAuthMaxAttempts = builder
                    .comment("密码最大尝试次数")
                    .translation("fireflymc.config.player_auth.max_attempts")
                    .define("maxAttempts", 3);

            playerAuthKickMessageTimeout = builder
                    .comment("验证超时踢出提示")
                    .translation("fireflymc.config.player_auth.kick_message_timeout")
                    .define("kickMessageTimeout", "§c[FireflyMC] 密码验证超时，请重新加入服务器");

            playerAuthKickMessageFailed = builder
                    .comment("密码错误次数耗尽踢出提示")
                    .translation("fireflymc.config.player_auth.kick_message_failed")
                    .define("kickMessageFailed", "§c[FireflyMC] 密码错误次数过多，请稍后再试");

            playerAuthLockoutMinutes = builder
                    .comment("密码错误次数耗尽后的限流时长（分钟），0 表示不限流")
                    .translation("fireflymc.config.player_auth.lockout_minutes")
                    .defineInRange("lockoutMinutes", 30, 0, 1440);

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

            aiMaxToolRounds = builder
                    .comment("AI多轮工具调用最大轮次（防止失控）")
                    .translation("fireflymc.config.ai.max_tool_rounds")
                    .defineInRange("maxToolRounds", 5, 1, 20);

            aiMaxToolCalls = builder
                    .comment("单次对话累计工具调用上限（防止失控）")
                    .translation("fireflymc.config.ai.max_tool_calls")
                    .defineInRange("maxToolCalls", 10, 1, 50);

            aiParallelToolCalls = builder
                    .comment("是否启用并行工具调用（部分本地模型/LM Studio不支持，默认关闭）")
                    .translation("fireflymc.config.ai.parallel_tool_calls")
                    .define("parallelToolCalls", false);

            aiDisabledTools = builder
                    .comment("禁用的工具名称列表（如 [\"spawn_entities\"]），为空表示全部启用")
                    .translation("fireflymc.config.ai.disabled_tools")
                    .defineListAllowEmpty("disabledTools", () -> List.<String>of(), () -> "", o -> o instanceof String);

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
