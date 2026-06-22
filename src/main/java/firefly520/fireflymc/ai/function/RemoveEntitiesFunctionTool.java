package firefly520.fireflymc.ai.function;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import firefly520.fireflymc.ai.ToolContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * 移除玩家周围实体的函数工具。
 * <p>
 * 可按 {@code entity_id} 精确移除单个（id 来自 {@link GetNearbyEntitiesFunctionTool}），
 * 或按 {@code entity_type + radius} 批量移除。默认 {@code exclude_players=true} 不移除玩家，
 * 防止 AI 误杀玩家。
 */
public class RemoveEntitiesFunctionTool implements AIFunctionTool {

    private static final int MIN_RADIUS = 1;
    private static final int MAX_RADIUS = 128;
    private static final int DEFAULT_RADIUS = 16;

    private static final String TARGET_PARAM = "target_player";
    private static final String TYPE_PARAM = "entity_type";
    private static final String ID_PARAM = "entity_id";
    private static final String EXCLUDE_PLAYERS_PARAM = "exclude_players";

    @Override
    public String getName() {
        return "remove_entities";
    }

    @Override
    public String getDescription() {
        return "移除玩家周围的实体。可按 entity_id 精确移除单个（来自 get_nearby_entities 的 id），或按 entity_type + radius 批量移除。默认不移除玩家。";
    }

    @Override
    public JsonObject getParametersSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        JsonObject targetParam = new JsonObject();
        targetParam.addProperty("type", "string");
        targetParam.addProperty("description", "中心玩家，默认为执行者（控制台必填）");
        properties.add(TARGET_PARAM, targetParam);

        JsonObject radiusParam = new JsonObject();
        radiusParam.addProperty("type", "integer");
        radiusParam.addProperty("description", "移除半径（方块），仅批量模式有效");
        radiusParam.addProperty("default", DEFAULT_RADIUS);
        radiusParam.addProperty("minimum", MIN_RADIUS);
        radiusParam.addProperty("maximum", MAX_RADIUS);
        properties.add("radius", radiusParam);

        JsonObject typeParam = new JsonObject();
        typeParam.addProperty("type", "string");
        typeParam.addProperty("description", "可选：只移除该类型，如 minecraft:zombie。不填则移除半径内所有非玩家实体");
        properties.add(TYPE_PARAM, typeParam);

        JsonObject idParam = new JsonObject();
        idParam.addProperty("type", "integer");
        idParam.addProperty("description", "精确移除单个实体的 id（来自 get_nearby_entities）。提供时优先于其他筛选");
        properties.add(ID_PARAM, idParam);

        JsonObject excludeParam = new JsonObject();
        excludeParam.addProperty("type", "boolean");
        excludeParam.addProperty("description", "是否排除玩家（不移除玩家），默认 true");
        excludeParam.addProperty("default", true);
        properties.add(EXCLUDE_PLAYERS_PARAM, excludeParam);

        schema.add("properties", properties);
        return schema;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 4;
    }

    @Override
    public FunctionCallResult execute(ToolContext ctx, JsonObject arguments) {
        var targetResult = FunctionToolHelper.getOptionalTargetPlayer(ctx, arguments, TARGET_PARAM);
        if (targetResult.hasError()) {
            return targetResult.error();
        }
        ServerPlayer target = targetResult.player();
        boolean excludePlayers = FunctionToolHelper.getOptionalBoolean(arguments, EXCLUDE_PLAYERS_PARAM, true);

        // 精确移除单个
        if (arguments.has(ID_PARAM) && arguments.get(ID_PARAM).isJsonPrimitive()
                && arguments.get(ID_PARAM).getAsJsonPrimitive().isNumber()) {
            int id = arguments.get(ID_PARAM).getAsInt();
            Entity e = target.serverLevel().getEntity(id);
            if (e == null) {
                return FunctionCallResult.failure(
                        FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                        "未找到 id=" + id + " 的实体（可能已离开范围或消失）");
            }
            if (e instanceof ServerPlayer && excludePlayers) {
                return FunctionCallResult.failure(
                        FunctionCallResult.ErrorType.PERMISSION_DENIED,
                        "拒绝移除玩家（exclude_players=true）");
            }
            ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            String name = e.hasCustomName() ? " \"" + e.getCustomName().getString() + "\"" : "";
            e.discard();
            return FunctionCallResult.success("已移除 " + typeId + name);
        }

        // 批量移除
        int radius = FunctionToolHelper.getOptionalInt(arguments, "radius", DEFAULT_RADIUS);
        radius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));

        String typeFilter = FunctionToolHelper.getOptionalString(arguments, TYPE_PARAM, null);
        ResourceLocation filterId = (typeFilter != null && !typeFilter.isBlank())
                ? ResourceLocation.tryParse(typeFilter.toLowerCase())
                : null;

        double radiusSq = (double) radius * radius;
        AABB box = AABB.ofSize(target.position(), radius * 2.0, radius * 2.0, radius * 2.0);

        List<Entity> entities = target.serverLevel().getEntitiesOfClass(Entity.class, box, e -> {
            if (e == target) {
                return false;
            }
            if (e.distanceToSqr(target) > radiusSq) {
                return false;
            }
            if (excludePlayers && e instanceof ServerPlayer) {
                return false;
            }
            if (filterId != null) {
                return filterId.equals(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()));
            }
            return true;
        });

        for (Entity e : entities) {
            e.discard();
        }

        String scope = filterId != null ? filterId.toString() : "所有非玩家实体";
        return FunctionCallResult.success(String.format(
                "已在 %s 周围 %d 格内移除 %d 个实体（%s）",
                target.getGameProfile().getName(), radius, entities.size(), scope));
    }
}
