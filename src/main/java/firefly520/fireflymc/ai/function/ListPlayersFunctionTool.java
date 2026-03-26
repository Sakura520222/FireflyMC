package firefly520.fireflymc.ai.function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 获取在线玩家列表的函数工具
 */
public class ListPlayersFunctionTool implements AIFunctionTool {

    @Override
    public String getName() {
        return "list_players";
    }

    @Override
    public String getDescription() {
        return "获取当前在线玩家列表，包括玩家数量和玩家名称。";
    }

    @Override
    public JsonObject getParametersSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        // includeDetails 参数
        JsonObject includeDetails = new JsonObject();
        includeDetails.addProperty("type", "boolean");
        includeDetails.addProperty("description", "是否包含详细信息（游戏模式、位置等）");
        includeDetails.addProperty("default", false);
        properties.add("includeDetails", includeDetails);

        schema.add("properties", properties);

        return schema;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;  // 无权限要求
    }

    @Override
    public FunctionCallResult execute(ServerPlayer player, JsonObject arguments) {
        // 检查服务器就绪状态
        FunctionCallResult checkResult = FunctionToolHelper.checkServerReady(player);
        if (checkResult != null) {
            return checkResult;
        }

        var server = player.getServer();
        var players = server.getPlayerList().getPlayers();
        int maxPlayers = server.getPlayerList().getMaxPlayers();

        // 获取includeDetails参数（默认false）
        boolean includeDetails = false;
        if (arguments.has("includeDetails") && !arguments.get("includeDetails").isJsonNull()) {
            var elem = arguments.get("includeDetails");
            if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isBoolean()) {
                includeDetails = elem.getAsBoolean();
            }
        }

        StringBuilder result = new StringBuilder();
        result.append(String.format("当前在线: %d/%d 人", players.size(), maxPlayers));

        if (players.isEmpty()) {
            return FunctionCallResult.success(result.toString());
        }

        result.append("\n玩家列表:");
        for (ServerPlayer p : players) {
            String gameMode = p.gameMode.getGameModeForPlayer().getName();
            if (includeDetails) {
                String dimension = p.serverLevel().dimension().location().toString();
                result.append(String.format("\n- %s (%s, %s)",
                        p.getGameProfile().getName(), gameMode, dimension));
            } else {
                result.append(String.format("\n- %s (%s)",
                        p.getGameProfile().getName(), gameMode));
            }
        }

        return FunctionCallResult.success(result.toString());
    }

    @Override
    public FunctionCallResult execute(MinecraftServer server, JsonObject arguments) {
        var players = server.getPlayerList().getPlayers();
        int maxPlayers = server.getPlayerList().getMaxPlayers();

        boolean includeDetails = false;
        if (arguments.has("includeDetails") && !arguments.get("includeDetails").isJsonNull()) {
            var elem = arguments.get("includeDetails");
            if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isBoolean()) {
                includeDetails = elem.getAsBoolean();
            }
        }

        StringBuilder result = new StringBuilder();
        result.append(String.format("当前在线: %d/%d 人", players.size(), maxPlayers));

        if (players.isEmpty()) {
            return FunctionCallResult.success(result.toString());
        }

        result.append("\n玩家列表:");
        for (ServerPlayer p : players) {
            String gameMode = p.gameMode.getGameModeForPlayer().getName();
            if (includeDetails) {
                String dimension = p.serverLevel().dimension().location().toString();
                result.append(String.format("\n- %s (%s, %s)",
                        p.getGameProfile().getName(), gameMode, dimension));
            } else {
                result.append(String.format("\n- %s (%s)",
                        p.getGameProfile().getName(), gameMode));
            }
        }

        return FunctionCallResult.success(result.toString());
    }
}
