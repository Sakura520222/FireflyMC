package firefly520.fireflymc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.Collection;
import java.util.stream.Collectors;
import net.minecraft.commands.arguments.EntityArgument;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * SpawnAll命令处理器
 * <p>
 * 用法: /spawnall <生物类型> [targets] [数量] [半径]
 * <p>
 * 在指定玩家附近生成指定数量的生物，未指定时默认为除执行者外的所有玩家
 * 支持原版全部生物 + 其他模组生物
 */
@EventBusSubscriber(modid = "fireflymc", value = Dist.DEDICATED_SERVER)
public class SpawnAllCommandHandler {

    // 默认值
    private static final int DEFAULT_COUNT = 1;
    private static final int DEFAULT_RADIUS = 10;

    // 限制值
    private static final int MIN_COUNT = 1;
    private static final int MAX_COUNT = 50;
    private static final int MIN_RADIUS = 1;
    private static final int MAX_RADIUS = 100;

    /**
     * 生物类型补全建议提供器
     * 提供所有已注册的生物类型供玩家选择（包括原版和模组）
     */
    private static final SuggestionProvider<CommandSourceStack> ENTITY_TYPE_SUGGESTIONS =
            (context, builder) -> {
                String remaining = builder.getRemaining().toLowerCase();

                // 遍历所有已注册的生物类型
                BuiltInRegistries.ENTITY_TYPE.forEach(entityType -> {
                    String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entityType).toString();
                    if (entityId.toLowerCase().contains(remaining)) {
                        builder.suggest(entityId);
                    }
                });

                return builder.buildFuture();
            };

    /**
     * 注册spawnall命令
     */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("spawnall")
                .requires(source -> source.hasPermission(4))  // 4级OP权限
                .then(Commands.argument("entity", ResourceLocationArgument.id())
                        .suggests(ENTITY_TYPE_SUGGESTIONS)
                        // 不带 targets 参数，默认行为（除执行者外）
                        .executes(context -> spawnEntities(context, null, DEFAULT_COUNT, DEFAULT_RADIUS))
                        .then(Commands.argument("count", IntegerArgumentType.integer(MIN_COUNT, MAX_COUNT))
                                .executes(context -> spawnEntities(context, null,
                                        IntegerArgumentType.getInteger(context, "count"), DEFAULT_RADIUS))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(MIN_RADIUS, MAX_RADIUS))
                                        .executes(context -> spawnEntities(context, null,
                                                IntegerArgumentType.getInteger(context, "count"),
                                                IntegerArgumentType.getInteger(context, "radius")))))
                        // 添加 targets 分支（与 count 分支并列）
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(context -> spawnEntities(context, EntityArgument.getPlayers(context, "targets"), DEFAULT_COUNT, DEFAULT_RADIUS))
                                .then(Commands.argument("count", IntegerArgumentType.integer(MIN_COUNT, MAX_COUNT))
                                        .executes(context -> spawnEntities(context, EntityArgument.getPlayers(context, "targets"),
                                                IntegerArgumentType.getInteger(context, "count"), DEFAULT_RADIUS))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(MIN_RADIUS, MAX_RADIUS))
                                                .executes(context -> spawnEntities(context, EntityArgument.getPlayers(context, "targets"),
                                                        IntegerArgumentType.getInteger(context, "count"),
                                                        IntegerArgumentType.getInteger(context, "radius")))))))
                .executes(SpawnAllCommandHandler::sendHelp));
    }

    /**
     * 生成生物核心逻辑
     */
    private static int spawnEntities(CommandContext<CommandSourceStack> context,
                                     Collection<ServerPlayer> targetPlayers,
                                     int count, int radius) {
        var source = context.getSource();

        // 获取执行者（用于默认行为）
        final ServerPlayer executor = source.getEntity() instanceof ServerPlayer
                ? (ServerPlayer) source.getEntity()
                : null;

        // 获取生物类型参数
        ResourceLocation entityId = ResourceLocationArgument.getId(context, "entity");

        // 解析EntityType
        EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.get(entityId);

        // 检查生物类型是否存在
        if (entityType == null) {
            source.sendFailure(Component.literal("§c未知的生物类型: " + entityId));
            return 0;
        }

        // 获取服务器玩家列表
        var server = source.getServer();
        if (server == null) {
            source.sendFailure(Component.literal("§c服务器未就绪"));
            return 0;
        }

        // 确定 target 玩家列表
        Collection<ServerPlayer> players;
        if (targetPlayers != null) {
            // 用户指定了 targets
            players = targetPlayers;
        } else {
            // 未指定，使用默认行为（除执行者外）
            var allPlayers = server.getPlayerList().getPlayers();
            if (executor != null) {
                players = allPlayers.stream()
                        .filter(p -> !p.getUUID().equals(executor.getUUID()))
                        .collect(Collectors.toList());
            } else {
                players = allPlayers;
            }
        }

        if (players.isEmpty()) {
            source.sendFailure(Component.literal("§c没有可用的目标玩家"));
            return 0;
        }

        int totalSpawned = 0;
        int playersProcessed = 0;

        // 为每个玩家生成生物
        for (ServerPlayer player : players) {
            ServerLevel level = player.serverLevel();
            var playerPos = player.blockPosition();

            int spawnedForPlayer = 0;
            for (int i = 0; i < count; i++) {
                // 随机生成位置（在玩家周围半径范围内）
                double angle = level.random.nextDouble() * Math.PI * 2;
                double distance = level.random.nextDouble() * radius;
                int x = (int) Math.round(playerPos.getX() + Math.cos(angle) * distance);
                int z = (int) Math.round(playerPos.getZ() + Math.sin(angle) * distance);

                // 获取地面高度
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos spawnPos = new BlockPos(x, y, z);

                // 生成生物（使用官方推荐API）
                var entity = entityType.spawn(
                        level,
                        null,        // ItemStack (蛋生为null)
                        null,        // Player (关联玩家)
                        spawnPos,
                        MobSpawnType.COMMAND,
                        true,        // 居中对齐
                        false        // 不强制生成
                );

                if (entity != null) {
                    spawnedForPlayer++;
                    totalSpawned++;
                }
            }

            if (spawnedForPlayer > 0) {
                playersProcessed++;

                // 向玩家发送通知
                player.sendSystemMessage(Component.literal(String.format(
                        "§e你的附近生成了 §a%d §e只 §b%s",
                        spawnedForPlayer,
                        entityId.toString()
                )));
            }
        }

        // 向执行者发送结果汇总
        if (totalSpawned > 0) {
            Component message = Component.literal(String.format(
                    "§a成功在 §e%d §a名玩家附近生成 §b%d §a只 §b%s§a！",
                    playersProcessed,
                    totalSpawned,
                    entityId.toString()
            ));
            source.sendSuccess(() -> message, true);
        } else {
            source.sendFailure(Component.literal("§c未能生成任何生物，可能位置不适合"));
        }

        return totalSpawned;
    }

    /**
     * 发送帮助信息
     */
    private static int sendHelp(CommandContext<CommandSourceStack> context) {
        Component helpMessage = Component.literal(
                "§e用法: /spawnall <生物类型> [targets] [数量] [半径]\n" +
                        "§7生物类型: 例如 zombie、skeleton、creeper 等，支持原版全部生物和模组生物\n" +
                        "§7targets: 目标玩家（使用 @a、@p、玩家名等选择器），默认为除执行者外的所有玩家\n" +
                        "§7数量: 每个玩家附近生成的生物数量 (默认: 1, 范围: 1-50)\n" +
                        "§7半径: 生成距离玩家的最大半径 (默认: 10, 范围: 1-100)\n" +
                        "§7权限: 需要OP权限 (等级4)"
        );
        context.getSource().sendSuccess(() -> helpMessage, false);
        return 1;
    }
}
