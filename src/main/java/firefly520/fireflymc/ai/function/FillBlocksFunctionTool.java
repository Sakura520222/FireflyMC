package firefly520.fireflymc.ai.function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import firefly520.fireflymc.ai.ToolContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

/**
 * 区域填充方块的函数工具（自动建造的基础）。
 * <p>
 * 在目标玩家周围、由两个相对偏移角点界定的长方体区域内填充指定方块。
 * 可用于建造地板/墙体/屋顶，填充 {@code minecraft:air} 等于清空区域。
 * AI 在多轮循环里多次调用即可拼出完整结构。
 */
public class FillBlocksFunctionTool implements AIFunctionTool {

    private static final int MIN_OFFSET = -32;
    private static final int MAX_OFFSET = 32;
    /** 单次填充体积上限，防止 AI 一次填爆服务器 tick */
    private static final int MAX_VOLUME = 2000;

    private static final String TARGET_PARAM = "target_player";

    @Override
    public String getName() {
        return "fill_blocks";
    }

    @Override
    public String getDescription() {
        return "在玩家周围填充方块（自动建造）。用两组相对玩家的坐标(x1,y1,z1)-(x2,y2,z2)指定长方体区域，填充指定方块。可建地板/墙/屋顶，填 minecraft:air 可清空。需要多个面时请分多次调用。";
    }

    @Override
    public JsonObject getParametersSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        JsonObject targetParam = new JsonObject();
        targetParam.addProperty("type", "string");
        targetParam.addProperty("description", "建造中心玩家，所有坐标相对该玩家偏移；默认执行者（控制台必填）");
        properties.add(TARGET_PARAM, targetParam);

        properties.add("x1", offsetParam("区域 X 起点偏移"));
        properties.add("y1", offsetParam("区域 Y 起点偏移"));
        properties.add("z1", offsetParam("区域 Z 起点偏移"));
        properties.add("x2", offsetParam("区域 X 终点偏移"));
        properties.add("y2", offsetParam("区域 Y 终点偏移"));
        properties.add("z2", offsetParam("区域 Z 终点偏移"));

        JsonObject blockParam = new JsonObject();
        blockParam.addProperty("type", "string");
        blockParam.addProperty("description", "方块ID，如 minecraft:stone、minecraft:oak_planks、minecraft:glass、minecraft:air（清空）");
        properties.add("block", blockParam);

        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("block");
        schema.add("required", required);

        return schema;
    }

    private JsonObject offsetParam(String description) {
        JsonObject p = new JsonObject();
        p.addProperty("type", "integer");
        p.addProperty("description", description + "（相对玩家脚下，0=玩家所在位置）");
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

        int x1 = clampOffset(FunctionToolHelper.getOptionalInt(arguments, "x1", 0));
        int y1 = clampOffset(FunctionToolHelper.getOptionalInt(arguments, "y1", 0));
        int z1 = clampOffset(FunctionToolHelper.getOptionalInt(arguments, "z1", 0));
        int x2 = clampOffset(FunctionToolHelper.getOptionalInt(arguments, "x2", 0));
        int y2 = clampOffset(FunctionToolHelper.getOptionalInt(arguments, "y2", 0));
        int z2 = clampOffset(FunctionToolHelper.getOptionalInt(arguments, "z2", 0));

        ResourceLocation blockId = ResourceLocation.tryParse(blockStr);
        if (blockId == null) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "无效的方块ID: " + blockStr);
        }
        Optional<Block> blockOpt = BuiltInRegistries.BLOCK.getOptional(blockId);
        if (blockOpt.isEmpty()) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "未知的方块: " + blockStr);
        }
        BlockState state = blockOpt.get().defaultBlockState();

        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2);
        int maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);

        int volume = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > MAX_VOLUME) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "区域过大: " + volume + " 个方块（上限 " + MAX_VOLUME
                            + "），请缩小范围或分多次填充");
        }

        // 建造以触发瞬间锁定的锚点为基准（玩家移动不影响相对坐标对齐）
        BlockPos base = ctx.anchor() != null ? ctx.anchor() : target.blockPosition();
        ServerLevel level = target.serverLevel();
        int count = 0;
        for (int dx = minX; dx <= maxX; dx++) {
            for (int dy = minY; dy <= maxY; dy++) {
                for (int dz = minZ; dz <= maxZ; dz++) {
                    level.setBlock(base.offset(dx, dy, dz), state, Block.UPDATE_ALL);
                    count++;
                }
            }
        }

        return FunctionCallResult.success(String.format(
                "已在 %s 周围 (%d,%d,%d)-(%d,%d,%d) 填充 %d 个 %s",
                target.getGameProfile().getName(),
                minX, minY, minZ, maxX, maxY, maxZ, count, blockId));
    }

    private static int clampOffset(int value) {
        return Math.max(MIN_OFFSET, Math.min(MAX_OFFSET, value));
    }
}
