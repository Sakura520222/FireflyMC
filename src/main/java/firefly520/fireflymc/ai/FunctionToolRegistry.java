package firefly520.fireflymc.ai;

import firefly520.fireflymc.ai.function.*;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 函数工具注册中心。
 * <p>
 * 管理所有可被 AI 助手调用的函数工具，并提供基于配置的启用开关。
 */
public class FunctionToolRegistry {
    private static final Map<String, AIFunctionTool> TOOLS = new ConcurrentHashMap<>();

    static {
        // 实体生成
        registerTool(new SpawnAllFunctionTool());

        // 信息查询类
        registerTool(new ListPlayersFunctionTool());
        registerTool(new GetPlayerInfoFunctionTool());
        registerTool(new GetServerTpsFunctionTool());
        registerTool(new GetServerUptimeFunctionTool());

        // 游戏管理类
        registerTool(new SetTimeFunctionTool());
        registerTool(new SetWeatherFunctionTool());
        registerTool(new KickPlayerFunctionTool());

        // 玩家传送类
        registerTool(new TeleportPositionFunctionTool());
        registerTool(new TeleportPlayerFunctionTool());
        registerTool(new SummonPlayerFunctionTool());

        // 物品/效果/清理类（clear_inventory 已移除：清空物品栏风险过高）
        registerTool(new GiveEffectFunctionTool());
        registerTool(new GiveItemFunctionTool());
        registerTool(new ClearDroppedItemsFunctionTool());

        // 实体查询/移除类
        registerTool(new GetNearbyEntitiesFunctionTool());
        registerTool(new RemoveEntitiesFunctionTool());

        // 建造类
        registerTool(new FillBlocksFunctionTool());
        registerTool(new PlaceBlockFunctionTool());
        registerTool(new GetBlocksFunctionTool());
    }

    /**
     * 注册函数工具。
     */
    public static void registerTool(AIFunctionTool tool) {
        TOOLS.put(tool.getName(), tool);
    }

    /**
     * 获取所有已注册工具（不含启用过滤）。
     */
    public static Collection<AIFunctionTool> getAllTools() {
        return Collections.unmodifiableCollection(TOOLS.values());
    }

    /**
     * 返回当前启用的工具列表（排除配置禁用名单中的工具）。
     * 此列表用于构造发给 AI 的 tools 定义。
     */
    public static List<AIFunctionTool> getEnabledTools() {
        return TOOLS.values().stream()
                .filter(FunctionToolRegistry::isEnabled)
                .toList();
    }

    /**
     * 工具是否被配置启用（未出现在禁用名单中）。
     */
    public static boolean isEnabled(AIFunctionTool tool) {
        return !AIConfig.getDisabledTools().contains(tool.getName());
    }

    /**
     * 根据名称获取工具。
     */
    public static Optional<AIFunctionTool> getTool(String name) {
        return Optional.ofNullable(TOOLS.get(name));
    }

    /**
     * 检查上下文是否具备执行指定工具的权限。
     */
    public static boolean hasPermissionForTool(ToolContext ctx, String toolName) {
        return getTool(toolName)
                .map(tool -> ctx.hasPermission(tool.getRequiredPermissionLevel()))
                .orElse(false);
    }

    /**
     * 获取工具所需的权限等级（用于权限不足时的提示）。
     *
     * @return 权限等级；工具不存在返回 -1
     */
    public static int getRequiredPermissionLevel(String toolName) {
        return getTool(toolName)
                .map(AIFunctionTool::getRequiredPermissionLevel)
                .orElse(-1);
    }
}
