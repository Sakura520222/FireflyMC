package firefly520.fireflymc.ai.function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
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
        FunctionCallResult checkResult = FunctionToolHelper.checkPreconditions(player, this);
        if (checkResult != null) return checkResult;

        var server = player.getServer();

        var itemResult = FunctionToolHelper.getRequiredString(arguments, "item");
        if (itemResult.hasError()) return itemResult.error();
        String itemStr = itemResult.value().toLowerCase();

        int count = FunctionToolHelper.getOptionalInt(arguments, "count", DEFAULT_COUNT);
        count = Math.max(MIN_COUNT, Math.min(MAX_COUNT, count));

        var targetResult = FunctionToolHelper.getOptionalTargetPlayer(server, arguments, "targetPlayer", player);
        if (targetResult.hasError()) return targetResult.error();

        String targetName = targetResult.player().getGameProfile().getName();
        return giveItem(targetResult.player(), targetName, itemStr, count);
    }

    @Override
    public FunctionCallResult execute(MinecraftServer server, JsonObject arguments) {
        if (!arguments.has("targetPlayer")) {
            return FunctionCallResult.failure(FunctionCallResult.ErrorType.INVALID_ARGUMENT, "从控制台执行必须指定 targetPlayer");
        }

        var itemResult = FunctionToolHelper.getRequiredString(arguments, "item");
        if (itemResult.hasError()) return itemResult.error();
        String itemStr = itemResult.value().toLowerCase();

        int count = FunctionToolHelper.getOptionalInt(arguments, "count", DEFAULT_COUNT);
        count = Math.max(MIN_COUNT, Math.min(MAX_COUNT, count));

        var targetResult = FunctionToolHelper.getRequiredTargetPlayer(server, arguments, "targetPlayer");
        if (targetResult.hasError()) return targetResult.error();

        String targetName = targetResult.player().getGameProfile().getName();
        return giveItem(targetResult.player(), targetName, itemStr, count);
    }

    /**
     * 共享的物品给予逻辑
     */
    private FunctionCallResult giveItem(ServerPlayer targetPlayer, String targetName, String itemStr, int count) {
        ResourceLocation itemId = ResourceLocation.tryParse(itemStr);
        if (itemId == null) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "无效的物品ID: " + itemStr);
        }
        Item item = BuiltInRegistries.ITEM.get(itemId);
        if (item == null || item == Items.AIR) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "未知的物品: " + itemStr);
        }

        ItemStack itemStack = new ItemStack(item, count);
        boolean added = targetPlayer.getInventory().add(itemStack);
        if (!added) {
            targetPlayer.drop(itemStack, false);
            return FunctionCallResult.success(
                    String.format("已给予 %s %dx %s（背包已满，物品掉落在地上）",
                            targetName, count, itemId.toString()));
        }
        return FunctionCallResult.success(
                String.format("已给予 %s %dx %s", targetName, count, itemId.toString()));
    }
}
