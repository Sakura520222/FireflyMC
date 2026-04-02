package firefly520.fireflymc.ai.function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
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
        return "在指定玩家附近生成指定生物。未指定目标时，默认为除了执行者以外的所有在线玩家。需要4级OP权限。";
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

        // targets 参数
        JsonObject targetsParam = new JsonObject();
        targetsParam.addProperty("type", "array");
        targetsParam.addProperty("description", "目标玩家名称列表，如 [\"player1\", \"player2\"]。未提供或为空时，默认为除了执行者以外的所有玩家");

        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        targetsParam.add("items", items);
        properties.add("targets", targetsParam);

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
        FunctionCallResult checkResult = FunctionToolHelper.checkPreconditions(player, this);
        if (checkResult != null) return checkResult;

        var server = player.getServer();
        var allPlayers = server.getPlayerList().getPlayers();
        if (allPlayers.isEmpty()) {
            return FunctionCallResult.failure(FunctionCallResult.ErrorType.EXECUTION_FAILED, "当前没有在线玩家");
        }

        // 默认排除执行者
        List<ServerPlayer> defaultTargets = allPlayers.stream()
                .filter(p -> !p.getUUID().equals(player.getUUID()))
                .collect(Collectors.toList());

        var targetResult = parseTargetPlayers(allPlayers, arguments, defaultTargets);
        if (targetResult.error != null) return targetResult.error;

        return executeSpawn(targetResult.players, arguments);
    }

    @Override
    public FunctionCallResult execute(MinecraftServer server, JsonObject arguments) {
        var allPlayers = server.getPlayerList().getPlayers();
        if (allPlayers.isEmpty()) {
            return FunctionCallResult.failure(FunctionCallResult.ErrorType.EXECUTION_FAILED, "当前没有在线玩家");
        }

        // 控制台不排除任何人
        var targetResult = parseTargetPlayers(allPlayers, arguments, new ArrayList<>(allPlayers));
        if (targetResult.error != null) return targetResult.error;

        return executeSpawn(targetResult.players, arguments);
    }

    /**
     * 解析目标玩家列表
     */
    private record TargetListResult(List<ServerPlayer> players, FunctionCallResult error) {}

    private TargetListResult parseTargetPlayers(List<ServerPlayer> allPlayers, JsonObject arguments, List<ServerPlayer> defaultTargets) {
        if (!arguments.has("targets") || !arguments.get("targets").isJsonArray()) {
            return new TargetListResult(defaultTargets, null);
        }
        JsonArray targetsArray = arguments.get("targets").getAsJsonArray();
        if (targetsArray.isEmpty()) {
            return new TargetListResult(defaultTargets, null);
        }
        List<ServerPlayer> targetPlayers = new ArrayList<>();
        for (var targetElement : targetsArray) {
            String targetName = targetElement.getAsString();
            allPlayers.stream()
                    .filter(p -> p.getGameProfile().getName().equalsIgnoreCase(targetName))
                    .findFirst()
                    .ifPresent(targetPlayers::add);
        }
        if (targetPlayers.isEmpty()) {
            return new TargetListResult(null, FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT, "未找到任何指定的目标玩家"));
        }
        return new TargetListResult(targetPlayers, null);
    }

    /**
     * 共享的生成执行逻辑
     */
    private FunctionCallResult executeSpawn(List<ServerPlayer> targetPlayers, JsonObject arguments) {
        var entityTypeResult = FunctionToolHelper.getRequiredString(arguments, "entityType");
        if (entityTypeResult.hasError()) return entityTypeResult.error();
        String entityTypeStr = entityTypeResult.value();

        ResourceLocation entityId = ResourceLocation.tryParse(entityTypeStr);
        if (entityId == null) {
            return FunctionCallResult.failure(FunctionCallResult.ErrorType.INVALID_ARGUMENT, "无效的生物类型: " + entityTypeStr);
        }
        EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.get(entityId);
        if (entityType == null) {
            return FunctionCallResult.failure(FunctionCallResult.ErrorType.INVALID_ARGUMENT, "未知的生物类型: " + entityTypeStr);
        }

        int count = FunctionToolHelper.getOptionalInt(arguments, "count", DEFAULT_COUNT);
        int radius = FunctionToolHelper.getOptionalInt(arguments, "radius", DEFAULT_RADIUS);
        count = Math.max(MIN_COUNT, Math.min(MAX_COUNT, count));
        radius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));

        int totalSpawned = 0;
        int playersProcessed = 0;

        for (ServerPlayer targetPlayer : targetPlayers) {
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
                var entity = entityType.spawn(level, null, null, spawnPos, MobSpawnType.COMMAND, true, false);
                if (entity != null) {
                    spawnedForPlayer++;
                    totalSpawned++;
                }
            }
            if (spawnedForPlayer > 0) {
                playersProcessed++;
                targetPlayer.sendSystemMessage(Component.literal(String.format(
                        "§e你的附近生成了 §a%d §e只 §b%s", spawnedForPlayer, entityId.toString())));
            }
        }

        if (totalSpawned > 0) {
            return FunctionCallResult.success(String.format(
                    "成功在 %d 名玩家附近生成 %d 只 %s", playersProcessed, totalSpawned, entityId.toString()));
        }
        return FunctionCallResult.failure(FunctionCallResult.ErrorType.EXECUTION_FAILED, "未能生成任何生物，可能位置不适合");
    }
}
