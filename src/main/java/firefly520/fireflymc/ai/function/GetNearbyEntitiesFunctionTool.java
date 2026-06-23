package firefly520.fireflymc.ai.function;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import firefly520.fireflymc.ai.ToolContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * 查询玩家周围实体及其状态的函数工具（只读）。
 * <p>
 * 返回半径内实体的 id、类型、自定义名、相对坐标、血量、分类。
 * 其中的 id 可供 {@link RemoveEntitiesFunctionTool} 精确移除。
 */
public class GetNearbyEntitiesFunctionTool implements AIFunctionTool {

    private static final int MIN_RADIUS = 1;
    private static final int MAX_RADIUS = 128;
    private static final int DEFAULT_RADIUS = 16;

    private static final String TARGET_PARAM = "target_player";
    private static final String TYPE_PARAM = "entity_type";

    @Override
    public String getName() {
        return "get_nearby_entities";
    }

    @Override
    public String getDescription() {
        return "获取玩家周围一定半径内的实体列表及状态（类型、相对位置、血量、分类），用于查看附近有什么。返回的 id 可供 remove_entities 精确移除。";
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
        radiusParam.addProperty("description", "查询半径（方块）");
        radiusParam.addProperty("default", DEFAULT_RADIUS);
        radiusParam.addProperty("minimum", MIN_RADIUS);
        radiusParam.addProperty("maximum", MAX_RADIUS);
        properties.add("radius", radiusParam);

        JsonObject typeParam = new JsonObject();
        typeParam.addProperty("type", "string");
        typeParam.addProperty("description", "可选：只返回该类型的实体，如 minecraft:cow、minecraft:zombie");
        properties.add(TYPE_PARAM, typeParam);

        schema.add("properties", properties);
        return schema;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public FunctionCallResult execute(ToolContext ctx, JsonObject arguments) {
        var targetResult = FunctionToolHelper.getOptionalTargetPlayer(ctx, arguments, TARGET_PARAM);
        if (targetResult.hasError()) {
            return targetResult.error();
        }
        ServerPlayer target = targetResult.player();

        int radius = FunctionToolHelper.getOptionalInt(arguments, "radius", DEFAULT_RADIUS);
        radius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));

        String typeFilter = FunctionToolHelper.getOptionalString(arguments, TYPE_PARAM, null);
        ResourceLocation filterId = (typeFilter != null && !typeFilter.isBlank())
                ? ResourceLocation.tryParse(typeFilter.toLowerCase(java.util.Locale.ROOT))
                : null;

        return listNearby(target, radius, filterId);
    }

    private FunctionCallResult listNearby(ServerPlayer target, int radius, ResourceLocation filterId) {
        double radiusSq = (double) radius * radius;
        AABB box = AABB.ofSize(target.position(), radius * 2.0, radius * 2.0, radius * 2.0);

        List<Entity> entities = target.serverLevel().getEntitiesOfClass(Entity.class, box, e -> {
            if (e == target) {
                return false;
            }
            if (e.distanceToSqr(target) > radiusSq) {
                return false;
            }
            if (filterId != null) {
                return filterId.equals(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()));
            }
            return true;
        });

        if (entities.isEmpty()) {
            return FunctionCallResult.success(String.format(
                    "%s 周围 %d 格内没有实体", target.getGameProfile().getName(), radius));
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s 周围 %d 格内 %d 个实体：",
                target.getGameProfile().getName(), radius, entities.size()));

        for (Entity e : entities) {
            ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            sb.append("\n- id=").append(e.getId())
                    .append(" ").append(typeId);
            if (e.hasCustomName()) {
                sb.append(" \"").append(e.getCustomName().getString()).append("\"");
            }
            sb.append(String.format(" @(%.1f, %.1f, %.1f)",
                    e.getX() - target.getX(),
                    e.getY() - target.getY(),
                    e.getZ() - target.getZ()));
            if (e instanceof LivingEntity le) {
                sb.append(String.format(" HP:%.0f/%.0f", le.getHealth(), le.getMaxHealth()));
            }
            sb.append(" [").append(categoryOf(e)).append("]");
        }

        return FunctionCallResult.success(sb.toString());
    }

    private String categoryOf(Entity e) {
        if (e instanceof ServerPlayer) {
            return "玩家";
        }
        if (e instanceof Enemy) {
            return "敌对";
        }
        if (e instanceof Animal) {
            return "动物";
        }
        if (e instanceof ItemEntity item) {
            ItemStack stack = item.getItem();
            return "掉落物:" + BuiltInRegistries.ITEM.getKey(stack.getItem());
        }
        return "其他";
    }
}
