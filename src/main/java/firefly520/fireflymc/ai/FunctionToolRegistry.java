package firefly520.fireflymc.ai;

import firefly520.fireflymc.ai.function.*;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * AI函数工具注册中心
 * <p>
 * 管理所有可被AI助手调用的函数工具
 */
public class FunctionToolRegistry {
    private static final Map<String, AIFunctionTool> TOOLS = new ConcurrentHashMap<>();

    static {
        // 注册所有函数工具
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

        // 物品/效果类
        registerTool(new ClearInventoryFunctionTool());
        registerTool(new GiveEffectFunctionTool());
        registerTool(new GiveItemFunctionTool());
    }

    /**
     * 注册函数工具
     *
     * @param tool 要注册的工具
     */
    public static void registerTool(AIFunctionTool tool) {
        TOOLS.put(tool.getName(), tool);
    }

    /**
     * 获取所有函数工具
     *
     * @return 所有已注册的工具的不可修改集合
     */
    public static Collection<AIFunctionTool> getAllTools() {
        return Collections.unmodifiableCollection(TOOLS.values());
    }

    /**
     * 根据名称获取函数工具
     *
     * @param name 函数名称
     * @return 包含工具的Optional，如果不存在则为空
     */
    public static Optional<AIFunctionTool> getTool(String name) {
        return Optional.ofNullable(TOOLS.get(name));
    }

    /**
     * 检查玩家是否有权限执行指定工具
     *
     * @param player   要检查的玩家
     * @param toolName 工具名称
     * @return 如果玩家有权限则返回true
     */
    public static boolean hasPermissionForTool(ServerPlayer player, String toolName) {
        Optional<AIFunctionTool> toolOpt = getTool(toolName);
        if (toolOpt.isEmpty()) {
            return false;
        }
        AIFunctionTool tool = toolOpt.get();
        // 通过玩家的CommandSourceStack检查权限
        return player.createCommandSourceStack().hasPermission(tool.getRequiredPermissionLevel());
    }

    /**
     * 获取工具所需的权限等级
     *
     * @param toolName 工具名称
     * @return 权限等级，如果工具不存在则返回-1
     */
    public static int getRequiredPermissionLevel(String toolName) {
        return getTool(toolName)
                .map(AIFunctionTool::getRequiredPermissionLevel)
                .orElse(-1);
    }
}
