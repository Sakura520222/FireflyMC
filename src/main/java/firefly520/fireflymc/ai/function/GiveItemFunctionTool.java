package firefly520.fireflymc.ai.function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import firefly520.fireflymc.ai.ToolContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 给予物品的函数工具。
 */
public class GiveItemFunctionTool implements AIFunctionTool {

    private static final int MIN_COUNT = 1;
    private static final int MAX_COUNT = 64;
    private static final int DEFAULT_COUNT = 1;

    private static final String TARGET_PARAM = "target_player";

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

        JsonObject itemParam = new JsonObject();
        itemParam.addProperty("type", "string");
        itemParam.addProperty("description", "物品ID，如 'minecraft:diamond'、'minecraft:iron_sword' 等");
        properties.add("item", itemParam);

        JsonObject countParam = new JsonObject();
        countParam.addProperty("type", "integer");
        countParam.addProperty("description", "物品数量");
        countParam.addProperty("default", DEFAULT_COUNT);
        countParam.addProperty("minimum", MIN_COUNT);
        countParam.addProperty("maximum", MAX_COUNT);
        properties.add("count", countParam);

        JsonObject targetParam = new JsonObject();
        targetParam.addProperty("type", "string");
        targetParam.addProperty("description", "目标玩家名称，默认为执行者（控制台调用时必填）");
        properties.add(TARGET_PARAM, targetParam);

        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("item");
        schema.add("required", required);

        return schema;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 4;
    }

    @Override
    public FunctionCallResult execute(ToolContext ctx, JsonObject arguments) {
        var itemResult = FunctionToolHelper.getRequiredString(arguments, "item");
        if (itemResult.hasError()) {
            return itemResult.error();
        }
        String itemStr = itemResult.value().toLowerCase();

        int count = FunctionToolHelper.getOptionalInt(arguments, "count", DEFAULT_COUNT);
        count = Math.max(MIN_COUNT, Math.min(MAX_COUNT, count));

        var targetResult = FunctionToolHelper.getOptionalTargetPlayer(ctx, arguments, TARGET_PARAM);
        if (targetResult.hasError()) {
            return targetResult.error();
        }
        ServerPlayer target = targetResult.player();
        String targetName = target.getGameProfile().getName();

        return giveItem(target, targetName, itemStr, count);
    }

    private FunctionCallResult giveItem(ServerPlayer target, String targetName, String itemStr, int count) {
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
        boolean added = target.getInventory().add(itemStack);
        if (!added) {
            target.drop(itemStack, false);
            return FunctionCallResult.success(
                    String.format("已给予 %s %dx %s（背包已满，物品掉落在地上）",
                            targetName, count, itemId));
        }
        return FunctionCallResult.success(
                String.format("已给予 %s %dx %s", targetName, count, itemId));
    }
}
