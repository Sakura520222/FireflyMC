package firefly520.fireflymc.ai;

import firefly520.fireflymc.ServerConfig;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * AI 配置门面。
 * <p>
 * 所有 getter 直接委托 {@link ServerConfig} 的配置项动态读取，配置文件热重载后立即生效，
 * 不再有类加载期固化的快照常量。
 * <p>
 * 旧版的 11 个 {@code @Deprecated static final} 常量（API_URL / MODEL / AI_UUID / ENABLED 等）
 * 已移除——它们在类加载时一次性快照，无法跟随配置热重载，是潜在 bug 源；经全项目检索确认无引用。
 */
public class AIConfig {

    // ===== API 配置 =====
    public static String getApiUrl() {
        return ServerConfig.SERVER.aiApiUrl.get();
    }

    public static String getApiKey() {
        return ServerConfig.SERVER.aiApiKey.get();
    }

    public static String getModel() {
        return ServerConfig.SERVER.aiModel.get();
    }

    // ===== 显示配置 =====
    public static String getAiName() {
        return ServerConfig.SERVER.aiName.get();
    }

    public static String getAiNamePlain() {
        return ServerConfig.SERVER.aiNamePlain.get();
    }

    public static UUID getAiUuid() {
        try {
            return UUID.fromString(ServerConfig.SERVER.aiUuid.get());
        } catch (IllegalArgumentException e) {
            // 配置的 UUID 格式无效时使用默认值
            return UUID.fromString("00000000-0000-4000-8000-000000000001");
        }
    }

    // ===== 行为配置 =====
    public static int getMaxHistorySize() {
        return ServerConfig.SERVER.aiMaxHistorySize.get();
    }

    public static int getCooldownSeconds() {
        return ServerConfig.SERVER.aiCooldownSeconds.get();
    }

    public static boolean getBroadcastToAll() {
        return ServerConfig.SERVER.aiBroadcastToAll.get();
    }

    public static boolean getEnabled() {
        return ServerConfig.SERVER.aiEnabled.get();
    }

    // ===== 主动回复配置 =====
    public static boolean getProactiveEnabled() {
        return ServerConfig.SERVER.aiProactiveEnabled.get();
    }

    public static int getProactiveInterval() {
        return ServerConfig.SERVER.aiProactiveInterval.get();
    }

    public static int getProactiveTimeout() {
        return ServerConfig.SERVER.aiProactiveTimeout.get();
    }

    // ===== 函数调用配置 =====
    public static boolean getFunctionsEnabled() {
        return ServerConfig.SERVER.aiFunctionsEnabled.get();
    }

    public static int getFunctionsRequireOpLevel() {
        return ServerConfig.SERVER.aiFunctionsRequireOpLevel.get();
    }

    // ===== 多轮工具调用配置（新增） =====

    /** 多轮 Agentic 循环的最大轮次。 */
    public static int getMaxToolRounds() {
        return ServerConfig.SERVER.aiMaxToolRounds.get();
    }

    /** 单次对话累计工具调用上限。 */
    public static int getMaxToolCalls() {
        return ServerConfig.SERVER.aiMaxToolCalls.get();
    }

    /** 是否启用并行工具调用（本地模型兼容性开关）。 */
    public static boolean getParallelToolCalls() {
        return ServerConfig.SERVER.aiParallelToolCalls.get();
    }

    /**
     * 被禁用的工具名称列表（已 trim、去空），默认空表示全部启用。
     */
    public static List<String> getDisabledTools() {
        try {
            List<?> raw = ServerConfig.SERVER.aiDisabledTools.get();
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            return raw.stream()
                    .filter(Objects::nonNull)
                    .map(o -> String.valueOf(o).trim())
                    .filter(s -> !s.isEmpty())
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private AIConfig() {
        // 防止实例化
    }
}
