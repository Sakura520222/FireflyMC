package firefly520.fireflymc.ai.function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import firefly520.fireflymc.ai.FunctionToolRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * spawnall命令的函数工具实现
 * <p>
 * 允许AI助手在所有在线玩家附近生成指定生物
 */
public class SpawnAllFunctionTool implements AIFunctionTool {

    private static final int MIN_COUNT = 1;
    private static final int MAX_COUNT = 50;
    private static final int MIN_RADIUS = 1;
    private static final int MAX_RADIUS = 100;
    private static final int DEFAULT_COUNT = 1;
    private static final int DEFAULT_RADIUS = 10;

    @Override
    public String getName() {
        return "spawn_entities";
    }

    @Override
    public String getDescription() {
        return "在所有在线玩家附近生成指定生物。需要4级OP权限。";
    }

    @Override
    public JsonObject getParametersSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        // entityType 参数
        JsonObject entityType = new JsonObject();
        entityType.addProperty("type", "string");
        entityType.addProperty("description",
                "生物类型的资源位置，如 'minecraft:zombie'、'minecraft:skeleton'、'minecraft:creeper' 等，支持原版和模组生物");
        properties.add("entityType", entityType);

        // count 参数
        JsonObject count = new JsonObject();
        count.addProperty("type", "integer");
        count.addProperty("description", "每个玩家附近生成的生物数量");
        count.addProperty("default", DEFAULT_COUNT);
        count.addProperty("minimum", MIN_COUNT);
        count.addProperty("maximum", MAX_COUNT);
        properties.add("count", count);

        // radius 参数
        JsonObject radius = new JsonObject();
        radius.addProperty("type", "integer");
        radius.addProperty("description", "生成距离玩家的最大半径（方块）");
        radius.addProperty("default", DEFAULT_RADIUS);
        radius.addProperty("minimum", MIN_RADIUS);
        radius.addProperty("maximum", MAX_RADIUS);
        properties.add("radius", radius);

        schema.add("properties", properties);

        // required
        JsonArray required = new JsonArray();
        required.add("entityType");
        schema.add("required", required);

        return schema;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 4;  // 4级OP权限
    }

    @Override
    public FunctionCallResult execute(ServerPlayer player, JsonObject arguments) {
        // 权限验证（双重保险）
        if (!FunctionToolRegistry.hasPermissionForTool(player, getName())) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.PERMISSION_DENIED,
                    "权限不足：需要4级OP权限"
            );
        }

        var server = player.getServer();
        if (server == null) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.EXECUTION_FAILED,
                    "服务器未就绪"
            );
        }

        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.EXECUTION_FAILED,
                    "当前没有在线玩家"
            );
        }

        // 解析参数
        if (!arguments.has("entityType")) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "缺少必需参数: entityType"
            );
        }

        String entityTypeStr = arguments.get("entityType").getAsString();
        int count = arguments.has("count")
                ? arguments.get("count").getAsInt()
                : DEFAULT_COUNT;
        int radius = arguments.has("radius")
                ? arguments.get("radius").getAsInt()
                : DEFAULT_RADIUS;

        // 验证范围
        count = Math.max(MIN_COUNT, Math.min(MAX_COUNT, count));
        radius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));

        // 解析EntityType
        ResourceLocation entityId = ResourceLocation.tryParse(entityTypeStr);
        if (entityId == null) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "无效的生物类型: " + entityTypeStr
            );
        }

        EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.get(entityId);
        if (entityType == null) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "未知的生物类型: " + entityTypeStr
            );
        }

        // 执行生成逻辑
        int totalSpawned = 0;
        int playersProcessed = 0;

        for (ServerPlayer targetPlayer : players) {
            ServerLevel level = targetPlayer.serverLevel();
            BlockPos playerPos = targetPlayer.blockPosition();

            int spawnedForPlayer = 0;
            for (int i = 0; i < count; i++) {
                double angle = level.random.nextDouble() * Math.PI * 2;
                double distance = level.random.nextDouble() * radius;
                int x = (int) Math.round(playerPos.getX() + Math.cos(angle) * distance);
                int z = (int) Math.round(playerPos.getZ() + Math.sin(angle) * distance);

                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos spawnPos = new BlockPos(x, y, z);

                var entity = entityType.spawn(level, null, null, spawnPos,
                        MobSpawnType.COMMAND, true, false);

                if (entity != null) {
                    spawnedForPlayer++;
                    totalSpawned++;
                }
            }

            if (spawnedForPlayer > 0) {
                playersProcessed++;
                targetPlayer.sendSystemMessage(Component.literal(String.format(
                        "§e你的附近生成了 §a%d §e只 §b%s",
                        spawnedForPlayer, entityId.toString()
                )));
            }
        }

        if (totalSpawned > 0) {
            return FunctionCallResult.success(String.format(
                    "成功在 %d 名玩家附近生成 %d 只 %s",
                    playersProcessed, totalSpawned, entityId.toString()
            ));
        } else {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.EXECUTION_FAILED,
                    "未能生成任何生物，可能位置不适合"
            );
        }
    }
}
