package firefly520.fireflymc.ai.function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import firefly520.fireflymc.ai.FunctionToolRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 清除物品栏的函数工具
 */
public class ClearInventoryFunctionTool implements AIFunctionTool {

    @Override
    public String getName() {
        return "clear_inventory";
    }

    @Override
    public String getDescription() {
        return "清除玩家的物品栏。支持清除全部、快捷栏、背包或装备。";
    }

    @Override
    public JsonObject getParametersSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        // targetPlayer 参数
        JsonObject targetPlayerParam = new JsonObject();
        targetPlayerParam.addProperty("type", "string");
        targetPlayerParam.addProperty("description", "目标玩家名称，默认为执行者");
        properties.add("targetPlayer", targetPlayerParam);

        // slot 参数
        JsonObject slotParam = new JsonObject();
        slotParam.addProperty("type", "string");
        slotParam.addProperty("description", "清除范围");
        slotParam.addProperty("default", "all");
        JsonArray enumValues = new JsonArray();
        enumValues.add("all");
        enumValues.add("hotbar");
        enumValues.add("inventory");
        enumValues.add("armor");
        slotParam.add("enum", enumValues);
        properties.add("slot", slotParam);

        schema.add("properties", properties);

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

        // 解析清除范围
        String slot = arguments.has("slot") ? arguments.get("slot").getAsString() : "all";
        int clearedCount = 0;

        switch (slot) {
            case "all" -> {
                // 清空所有物品
                for (int i = 0; i < targetPlayer.getInventory().getContainerSize(); i++) {
                    if (!targetPlayer.getInventory().getItem(i).isEmpty()) {
                        clearedCount++;
                    }
                }
                targetPlayer.getInventory().clearContent();
            }
            case "hotbar" -> {
                // 清空快捷栏 (0-8)
                for (int i = 0; i < 9; i++) {
                    if (!targetPlayer.getInventory().getItem(i).isEmpty()) {
                        clearedCount++;
                    }
                    targetPlayer.getInventory().setItem(i, ItemStack.EMPTY);
                }
            }
            case "inventory" -> {
                // 清空背包 (9-35)
                for (int i = 9; i < 36; i++) {
                    if (!targetPlayer.getInventory().getItem(i).isEmpty()) {
                        clearedCount++;
                    }
                    targetPlayer.getInventory().setItem(i, ItemStack.EMPTY);
                }
            }
            case "armor" -> {
                // 清空装备 (36-39)
                for (int i = 36; i < 40; i++) {
                    if (!targetPlayer.getInventory().getItem(i).isEmpty()) {
                        clearedCount++;
                    }
                    targetPlayer.getInventory().setItem(i, ItemStack.EMPTY);
                }
            }
            default -> {
                return FunctionCallResult.failure(
                        FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                        "无效的清除范围: " + slot + "。支持: all, hotbar, inventory, armor"
                );
            }
        }

        String slotDesc = switch (slot) {
            case "all" -> "全部物品";
            case "hotbar" -> "快捷栏";
            case "inventory" -> "背包";
            case "armor" -> "装备栏";
            default -> slot;
        };

        return FunctionCallResult.success(
                String.format("已清除 %s 的 %s，共清除 %d 件物品", targetName, slotDesc, clearedCount)
        );
    }
}
