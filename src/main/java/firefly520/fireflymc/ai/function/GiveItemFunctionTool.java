package firefly520.fireflymc.ai.function;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import firefly520.fireflymc.ai.ToolContext;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 给予物品的函数工具，支持附加附魔。
 */
public class GiveItemFunctionTool implements AIFunctionTool {

    private static final int MIN_COUNT = 1;
    private static final int MAX_COUNT = 64;
    private static final int DEFAULT_COUNT = 1;
    private static final int MAX_ENCHANTMENT_LEVEL = 255;

    private static final String TARGET_PARAM = "target_player";

    @Override
    public String getName() {
        return "give_item";
    }

    @Override
    public String getDescription() {
        return "给予玩家指定物品，可附加附魔。支持原版和模组物品。";
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

        // 附魔列表
        JsonObject enchantParam = new JsonObject();
        enchantParam.addProperty("type", "array");
        enchantParam.addProperty("description", "附加的附魔列表，如 [{\"id\":\"minecraft:sharpness\",\"level\":5}]");

        JsonObject items = new JsonObject();
        items.addProperty("type", "object");
        JsonObject itemProps = new JsonObject();

        JsonObject enchId = new JsonObject();
        enchId.addProperty("type", "string");
        enchId.addProperty("description", "附魔ID，如 minecraft:sharpness、minecraft:unbreaking、minecraft:protection");
        itemProps.add("id", enchId);

        JsonObject enchLevel = new JsonObject();
        enchLevel.addProperty("type", "integer");
        enchLevel.addProperty("description", "附魔等级");
        enchLevel.addProperty("default", 1);
        enchLevel.addProperty("minimum", 1);
        enchLevel.addProperty("maximum", MAX_ENCHANTMENT_LEVEL);
        itemProps.add("level", enchLevel);

        items.add("properties", itemProps);
        JsonArray itemRequired = new JsonArray();
        itemRequired.add("id");
        items.add("required", itemRequired);
        enchantParam.add("items", items);
        properties.add("enchantments", enchantParam);

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

        List<EnchantmentEntry> enchantments = parseEnchantments(arguments);

        return giveItem(target, targetName, itemStr, count, enchantments);
    }

    private record EnchantmentEntry(ResourceLocation id, int level) {
    }

    /**
     * 解析附魔列表参数。容错：跳过非法条目而非整体失败。
     */
    private List<EnchantmentEntry> parseEnchantments(JsonObject arguments) {
        if (!arguments.has("enchantments") || !arguments.get("enchantments").isJsonArray()) {
            return List.of();
        }
        List<EnchantmentEntry> result = new ArrayList<>();
        for (JsonElement el : arguments.getAsJsonArray("enchantments")) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject obj = el.getAsJsonObject();
            if (!obj.has("id") || !obj.get("id").isJsonPrimitive()) {
                continue;
            }
            String idStr = obj.get("id").getAsString();
            if (idStr == null || idStr.isBlank()) {
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(idStr);
            if (id == null) {
                continue;
            }
            int level = 1;
            if (obj.has("level") && obj.get("level").isJsonPrimitive()
                    && obj.get("level").getAsJsonPrimitive().isNumber()) {
                level = obj.get("level").getAsInt();
            }
            level = Math.max(1, Math.min(MAX_ENCHANTMENT_LEVEL, level));
            result.add(new EnchantmentEntry(id, level));
        }
        return result;
    }

    private FunctionCallResult giveItem(ServerPlayer target, String targetName,
                                        String itemStr, int count,
                                        List<EnchantmentEntry> enchantments) {
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

        // 应用附魔（附魔注册表通过 RegistryAccess 获取，兼容 1.21.1）
        Registry<Enchantment> enchRegistry = target.getServer().registryAccess()
                .registryOrThrow(Registries.ENCHANTMENT);
        List<String> applied = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        applyEnchantments(itemStack, enchantments, enchRegistry, applied, unknown);

        boolean added = target.getInventory().add(itemStack);
        if (!added) {
            target.drop(itemStack, false);
        }

        // 构建结果消息
        StringBuilder msg = new StringBuilder();
        msg.append(String.format("已给予 %s %dx %s", targetName, count, itemId));
        if (!added) {
            msg.append("（背包已满，物品掉落在地上）");
        }
        if (!applied.isEmpty()) {
            msg.append("，附魔: ").append(String.join(", ", applied));
        }
        if (!unknown.isEmpty()) {
            msg.append("（未知附魔被忽略: ").append(String.join(", ", unknown)).append("）");
        }
        return FunctionCallResult.success(msg.toString());
    }

    /**
     * 将附魔应用到物品栈。未知附魔 ID 记入 unknown 列表（不阻断整体给予）。
     */
    private void applyEnchantments(ItemStack stack, List<EnchantmentEntry> enchantments,
                                   Registry<Enchantment> registry,
                                   List<String> applied, List<String> unknown) {
        if (enchantments.isEmpty()) {
            return;
        }
        ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(
                stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY));
        for (EnchantmentEntry entry : enchantments) {
            Optional<Holder.Reference<Enchantment>> opt = registry.getHolder(entry.id());
            if (opt.isEmpty()) {
                unknown.add(entry.id().toString());
                continue;
            }
            mutable.set(opt.get(), entry.level());
            String label = entry.id().getPath() + (entry.level() > 1 ? entry.level() : "");
            applied.add(label);
        }
        stack.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());
    }
}
