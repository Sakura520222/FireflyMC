package firefly520.fireflymc.ai.function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import firefly520.fireflymc.ai.ToolContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Optional;

/**
 * 精细放置单个方块的函数工具（可设置朝向等状态属性）。
 * <p>
 * 适合放楼梯/门/原木/台阶等朝向敏感方块。与 {@link FillBlocksFunctionTool}（批量默认状态）互补：
 * fill 搭大结构骨架，place 做朝向精细块。坐标相对玩家脚下偏移。
 */
public class PlaceBlockFunctionTool implements AIFunctionTool {

    private static final int MIN_OFFSET = -32;
    private static final int MAX_OFFSET = 32;

    private static final String TARGET_PARAM = "target_player";
    private static final String STATE_PARAM = "state";

    @Override
    public String getName() {
        return "place_block";
    }

    @Override
    public String getDescription() {
        return "精细放置单个方块，可设置朝向等状态属性。适合放楼梯/门/原木/台阶等需要朝向的方块。坐标相对玩家脚下偏移。";
    }

    @Override
    public JsonObject getParametersSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        JsonObject targetParam = new JsonObject();
        targetParam.addProperty("type", "string");
        targetParam.addProperty("description", "坐标参考玩家，默认执行者（控制台必填）");
        properties.add(TARGET_PARAM, targetParam);

        properties.add("x", offsetParam("X 偏移"));
        properties.add("y", offsetParam("Y 偏移"));
        properties.add("z", offsetParam("Z 偏移"));

        JsonObject blockParam = new JsonObject();
        blockParam.addProperty("type", "string");
        blockParam.addProperty("description", "方块ID，如 minecraft:oak_stairs、minecraft:oak_door、minecraft:oak_log");
        properties.add("block", blockParam);

        JsonObject stateParam = new JsonObject();
        stateParam.addProperty("type", "object");
        stateParam.addProperty("description", "可选：方块状态属性键值对，如 {\"facing\":\"north\",\"half\":\"top\",\"axis\":\"y\",\"shape\":\"straight\"}，用于控制朝向。不匹配的属性会被忽略");
        JsonObject additional = new JsonObject();
        additional.addProperty("type", "string");
        stateParam.add("additionalProperties", additional);
        properties.add(STATE_PARAM, stateParam);

        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("block");
        schema.add("required", required);

        return schema;
    }

    private JsonObject offsetParam(String desc) {
        JsonObject p = new JsonObject();
        p.addProperty("type", "integer");
        p.addProperty("description", desc + "（相对玩家脚下，0=玩家位置）");
        p.addProperty("default", 0);
        p.addProperty("minimum", MIN_OFFSET);
        p.addProperty("maximum", MAX_OFFSET);
        return p;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 4;
    }

    @Override
    public FunctionCallResult execute(ToolContext ctx, JsonObject arguments) {
        var blockResult = FunctionToolHelper.getRequiredString(arguments, "block");
        if (blockResult.hasError()) {
            return blockResult.error();
        }
        String blockStr = blockResult.value().toLowerCase();

        var targetResult = FunctionToolHelper.getOptionalTargetPlayer(ctx, arguments, TARGET_PARAM);
        if (targetResult.hasError()) {
            return targetResult.error();
        }
        ServerPlayer target = targetResult.player();

        int x = clampOffset(FunctionToolHelper.getOptionalInt(arguments, "x", 0));
        int y = clampOffset(FunctionToolHelper.getOptionalInt(arguments, "y", 0));
        int z = clampOffset(FunctionToolHelper.getOptionalInt(arguments, "z", 0));

        ResourceLocation blockId = ResourceLocation.tryParse(blockStr);
        if (blockId == null) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT, "无效的方块ID: " + blockStr);
        }
        Optional<Block> blockOpt = BuiltInRegistries.BLOCK.getOptional(blockId);
        if (blockOpt.isEmpty()) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT, "未知的方块: " + blockStr);
        }

        BlockState state = blockOpt.get().defaultBlockState();
        if (arguments.has(STATE_PARAM) && arguments.get(STATE_PARAM).isJsonObject()) {
            state = applyState(state, arguments.getAsJsonObject(STATE_PARAM));
        }

        // 建造以触发瞬间锁定的锚点为基准（玩家移动不影响相对坐标对齐）
        BlockPos base = ctx.anchor() != null ? ctx.anchor() : target.blockPosition();
        BlockPos pos = base.offset(x, y, z);
        target.serverLevel().setBlock(pos, state, Block.UPDATE_ALL);

        return FunctionCallResult.success(String.format(
                "已在 %s 相对(%d,%d,%d) 放置 %s",
                target.getGameProfile().getName(), x, y, z, describeBlock(state)));
    }

    private BlockState applyState(BlockState state, JsonObject stateObj) {
        for (String name : stateObj.keySet()) {
            if (!stateObj.get(name).isJsonPrimitive()) {
                continue;
            }
            state = applyProperty(state, name, stateObj.get(name).getAsString());
        }
        return state;
    }

    private BlockState applyProperty(BlockState state, String name, String value) {
        for (Property<?> p : state.getProperties()) {
            if (p.getName().equals(name)) {
                return applyValue(state, p, value);
            }
        }
        return state; // 该方块无此属性，忽略
    }

    private <T extends Comparable<T>> BlockState applyValue(BlockState state, Property<T> prop, String value) {
        Optional<T> opt = prop.getValue(value);
        return opt.map(v -> state.setValue(prop, v)).orElse(state);
    }

    /**
     * 描述方块及其状态（含朝向），供结果消息与 {@link GetBlocksFunctionTool} 复用。
     */
    static String describeBlock(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        StringBuilder sb = new StringBuilder(id.toString());
        var props = state.getProperties();
        if (!props.isEmpty()) {
            sb.append("[");
            boolean first = true;
            for (Property<?> p : props) {
                if (!first) sb.append(",");
                sb.append(p.getName()).append("=").append(state.getValue(p));
                first = false;
            }
            sb.append("]");
        }
        return sb.toString();
    }

    private static int clampOffset(int value) {
        return Math.max(MIN_OFFSET, Math.min(MAX_OFFSET, value));
    }
}
