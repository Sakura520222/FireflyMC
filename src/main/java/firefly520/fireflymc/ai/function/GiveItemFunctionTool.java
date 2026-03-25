package firefly520.fireflymc.ai.function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import firefly520.fireflymc.ai.FunctionToolRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;

/**
 * 给予物品的函数工具
 */
public class GiveItemFunctionTool implements AIFunctionTool {

    private static final int MIN_COUNT = 1;
    private static final int MAX_COUNT = 64;
    private static final int DEFAULT_COUNT = 1;

    @Override
    public String getName() {
        return "give_item";
    }

    @Override
    public String getDescription() {
        return "给予玩家指定物品。支持原版和模组物品。";
    }

    @Override
    public JsonObject getParametersSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        // item 参数
        JsonObject itemParam = new JsonObject();
        itemParam.addProperty("type", "string");
        itemParam.addProperty("description", "物品ID，如 'minecraft:diamond'、'minecraft:iron_sword' 等");
        properties.add("item", itemParam);

        // count 参数
        JsonObject countParam = new JsonObject();
        countParam.addProperty("type", "integer");
        countParam.addProperty("description", "物品数量");
        countParam.addProperty("default", DEFAULT_COUNT);
        countParam.addProperty("minimum", MIN_COUNT);
        countParam.addProperty("maximum", MAX_COUNT);
        properties.add("count", countParam);

        // targetPlayer 参数
        JsonObject targetPlayerParam = new JsonObject();
        targetPlayerParam.addProperty("type", "string");
        targetPlayerParam.addProperty("description", "目标玩家名称，默认为执行者");
        properties.add("targetPlayer", targetPlayerParam);

        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("item");
        schema.add("required", required);

        return schema;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 4;  // 4级OP权限
    }

    @Override
    public FunctionCallResult execute(ServerPlayer player, JsonObject arguments) {
        // 权限验证
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

        // 解析必需参数
        if (!arguments.has("item")) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "缺少必需参数: item"
            );
        }

        String itemStr = arguments.get("item").getAsString();

        // 解析count参数并添加类型验证
        int count = DEFAULT_COUNT;
        if (arguments.has("count")) {
            try {
                count = arguments.get("count").getAsInt();
            } catch (IllegalStateException e) {
                return FunctionCallResult.failure(
                        FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                        "count参数必须是整数"
                );
            }
        }

        // 验证范围
        count = Math.max(MIN_COUNT, Math.min(MAX_COUNT, count));

        // 确定目标玩家
        ServerPlayer targetPlayer = player;
        String targetName = player.getGameProfile().getName();

        if (arguments.has("targetPlayer") && !arguments.get("targetPlayer").isJsonNull()) {
            targetName = arguments.get("targetPlayer").getAsString();
            targetPlayer = server.getPlayerList().getPlayerByName(targetName);
            if (targetPlayer == null) {
                return FunctionCallResult.failure(
                        FunctionCallResult.ErrorType.EXECUTION_FAILED,
                        "玩家 " + targetName + " 不在线"
                );
            }
        }

        // 解析物品ID
        ResourceLocation itemId = ResourceLocation.tryParse(itemStr);
        if (itemId == null) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "无效的物品ID: " + itemStr
            );
        }

        Item item = BuiltInRegistries.ITEM.get(itemId);
        if (item == null || item == Items.AIR) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "未知的物品: " + itemStr
            );
        }

        // 创建物品堆并给予玩家
        ItemStack itemStack = new ItemStack(item, count);

        // 尝试添加到玩家背包
        boolean added = targetPlayer.getInventory().add(itemStack);

        // 如果背包满了，扔在地上
        if (!added) {
            targetPlayer.drop(itemStack, false);
            return FunctionCallResult.success(
                    String.format("已给予 %s %dx %s（背包已满，物品掉落在地上）",
                            targetName, count, itemId.toString())
            );
        }

        return FunctionCallResult.success(
                String.format("已给予 %s %dx %s",
                        targetName, count, itemId.toString())
        );
    }
}
