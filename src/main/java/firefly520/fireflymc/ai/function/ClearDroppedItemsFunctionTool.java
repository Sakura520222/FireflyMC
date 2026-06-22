package firefly520.fireflymc.ai.function;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import firefly520.fireflymc.ai.ToolContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.List;

/**
 * 清理掉落物实体的函数工具。
 * <p>
 * 可指定半径（以某玩家为中心、在该玩家所在维度内清理）或全服清理所有维度的掉落物。
 * 只移除地面上的 {@link ItemEntity}，不影响玩家物品栏内的物品。
 */
public class ClearDroppedItemsFunctionTool implements AIFunctionTool {

    private static final int MIN_RADIUS = 1;
    private static final int MAX_RADIUS = 1000;

    private static final String TARGET_PARAM = "target_player";

    @Override
    public String getName() {
        return "clear_dropped_items";
    }

    @Override
    public String getDescription() {
        return "清理地面上的掉落物。可指定半径（以某玩家为中心）或全服清理。不影响玩家物品栏。";
    }

    @Override
    public JsonObject getParametersSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        JsonObject radiusParam = new JsonObject();
        radiusParam.addProperty("type", "integer");
        radiusParam.addProperty("description", "清理半径（方块），以 target_player 为中心，仅清理该玩家所在维度内的掉落物；不填则全服清理所有维度");
        radiusParam.addProperty("minimum", MIN_RADIUS);
        radiusParam.addProperty("maximum", MAX_RADIUS);
        properties.add("radius", radiusParam);

        JsonObject targetParam = new JsonObject();
        targetParam.addProperty("type", "string");
        targetParam.addProperty("description", "清理中心玩家，默认为执行者（仅 radius 模式有效；控制台+radius 时必填）");
        properties.add(TARGET_PARAM, targetParam);

        schema.add("properties", properties);

        return schema;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 4;
    }

    @Override
    public FunctionCallResult execute(ToolContext ctx, JsonObject arguments) {
        int radius = FunctionToolHelper.getOptionalInt(arguments, "radius", 0);
        if (radius > 0) {
            var targetResult = FunctionToolHelper.getOptionalTargetPlayer(ctx, arguments, TARGET_PARAM);
            if (targetResult.hasError()) {
                return targetResult.error();
            }
            return clearInRange(targetResult.player(), radius);
        }
        return clearAll(ctx);
    }

    /**
     * 范围清理：以目标玩家为中心，清理其所在维度 radius 半径内的掉落物。
     */
    private FunctionCallResult clearInRange(ServerPlayer target, int radius) {
        radius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
        ServerLevel level = target.serverLevel();
        double radiusSq = (double) radius * radius;
        List<? extends ItemEntity> items = level.getEntities(EntityType.ITEM,
                item -> item.distanceToSqr(target) <= radiusSq);
        for (ItemEntity item : items) {
            item.discard();
        }
        return FunctionCallResult.success(String.format(
                "已在 %s 周围 %d 格内清理 %d 个掉落物（维度 %s）",
                target.getGameProfile().getName(), radius, items.size(),
                level.dimension().location()));
    }

    /**
     * 全服清理：清理所有维度的掉落物。
     */
    private FunctionCallResult clearAll(ToolContext ctx) {
        int total = 0;
        for (ServerLevel level : ctx.server().getAllLevels()) {
            List<? extends ItemEntity> items = level.getEntities(EntityType.ITEM, item -> true);
            total += items.size();
            for (ItemEntity item : items) {
                item.discard();
            }
        }
        return FunctionCallResult.success(String.format("已全服清理 %d 个掉落物", total));
    }
}
