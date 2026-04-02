package firefly520.fireflymc.ai.function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import net.minecraft.server.MinecraftServer;
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
        // 检查前置条件
        FunctionCallResult checkResult = FunctionToolHelper.checkPreconditions(player, this);
        if (checkResult != null) {
            return checkResult;
        }

        var server = player.getServer();

        // 确定目标玩家
        ServerPlayer targetPlayer = player;
        String targetName = player.getGameProfile().getName();

        String targetPlayerName = FunctionToolHelper.getOptionalString(arguments, "targetPlayer", null);
        if (targetPlayerName != null && !targetPlayerName.isBlank()) {
            targetPlayer = server.getPlayerList().getPlayerByName(targetPlayerName);
            if (targetPlayer == null) {
                return FunctionCallResult.failure(
                        FunctionCallResult.ErrorType.EXECUTION_FAILED,
                        "玩家 " + targetPlayerName + " 不在线"
                );
            }
            targetName = targetPlayerName;
        }

        // 解析清除范围
        String slot = FunctionToolHelper.getOptionalString(arguments, "slot", "all");

        // 验证slot参数
        if (!slot.matches("all|hotbar|inventory|armor")) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "无效的清除范围: " + slot + "。支持: all, hotbar, inventory, armor"
            );
        }

        int clearedCount = 0;

        // 使用switch表达式避免fall-through
        clearedCount = switch (slot) {
            case "all" -> {
                // 统计被清除的物品数量，然后清空
                int containerSize = targetPlayer.getInventory().getContainerSize();
                int count = 0;
                for (int i = 0; i < containerSize; i++) {
                    if (!targetPlayer.getInventory().getItem(i).isEmpty()) {
                        count++;
                    }
                }
                targetPlayer.getInventory().clearContent();
                yield count;
            }
            case "hotbar" -> {
                // 清空快捷栏 (0-8)
                int count = 0;
                for (int i = 0; i < 9; i++) {
                    if (!targetPlayer.getInventory().getItem(i).isEmpty()) {
                        count++;
                    }
                    targetPlayer.getInventory().setItem(i, ItemStack.EMPTY);
                }
                yield count;
            }
            case "inventory" -> {
                // 清空背包 (9-35)
                int count = 0;
                for (int i = 9; i < 36; i++) {
                    if (!targetPlayer.getInventory().getItem(i).isEmpty()) {
                        count++;
                    }
                    targetPlayer.getInventory().setItem(i, ItemStack.EMPTY);
                }
                yield count;
            }
            case "armor" -> {
                // 清空装备 (36-39)
                int count = 0;
                for (int i = 36; i < 40; i++) {
                    if (!targetPlayer.getInventory().getItem(i).isEmpty()) {
                        count++;
                    }
                    targetPlayer.getInventory().setItem(i, ItemStack.EMPTY);
                }
                yield count;
            }
            default -> 0; // 不会执行，因为上面已经验证过
        };

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

    @Override
    public FunctionCallResult execute(MinecraftServer server, JsonObject arguments) {
        if (!arguments.has("targetPlayer")) {
            return FunctionCallResult.failure(FunctionCallResult.ErrorType.INVALID_ARGUMENT, "从控制台执行必须指定 targetPlayer");
        }

        var playerResult = FunctionToolHelper.getRequiredTargetPlayer(server, arguments, "targetPlayer");
        if (playerResult.hasError()) return playerResult.error();

        ServerPlayer targetPlayer = playerResult.player();
        String targetName = arguments.get("targetPlayer").getAsString();

        String slot = FunctionToolHelper.getOptionalString(arguments, "slot", "all");
        if (!slot.matches("all|hotbar|inventory|armor")) {
            return FunctionCallResult.failure(FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "无效的清除范围: " + slot + "。支持: all, hotbar, inventory, armor");
        }

        int clearedCount = switch (slot) {
            case "all" -> {
                int containerSize = targetPlayer.getInventory().getContainerSize();
                int count = 0;
                for (int i = 0; i < containerSize; i++) {
                    if (!targetPlayer.getInventory().getItem(i).isEmpty()) count++;
                }
                targetPlayer.getInventory().clearContent();
                yield count;
            }
            case "hotbar" -> {
                int count = 0;
                for (int i = 0; i < 9; i++) {
                    if (!targetPlayer.getInventory().getItem(i).isEmpty()) count++;
                    targetPlayer.getInventory().setItem(i, ItemStack.EMPTY);
                }
                yield count;
            }
            case "inventory" -> {
                int count = 0;
                for (int i = 9; i < 36; i++) {
                    if (!targetPlayer.getInventory().getItem(i).isEmpty()) count++;
                    targetPlayer.getInventory().setItem(i, ItemStack.EMPTY);
                }
                yield count;
            }
            case "armor" -> {
                int count = 0;
                for (int i = 36; i < 40; i++) {
                    if (!targetPlayer.getInventory().getItem(i).isEmpty()) count++;
                    targetPlayer.getInventory().setItem(i, ItemStack.EMPTY);
                }
                yield count;
            }
            default -> 0;
        };

        String slotDesc = switch (slot) {
            case "all" -> "全部物品";
            case "hotbar" -> "快捷栏";
            case "inventory" -> "背包";
            case "armor" -> "装备栏";
            default -> slot;
        };

        return FunctionCallResult.success(
                String.format("已清除 %s 的 %s，共清除 %d 件物品", targetName, slotDesc, clearedCount));
    }
}
