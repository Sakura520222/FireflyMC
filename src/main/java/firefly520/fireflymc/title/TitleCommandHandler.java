package firefly520.fireflymc.title;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import firefly520.fireflymc.network.TitleSyncPayload;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.UUID;

/**
 * 称号管理命令
 * <p>
 * /fireflymc title set <player> <title...> — 设置称号（OP4）
 * /fireflymc title remove <player>         — 移除称号（OP4）
 * /fireflymc title list                    — 查看所有称号（OP2）
 * /fireflymc title                         — 显示帮助
 */
@EventBusSubscriber(modid = "fireflymc")
public class TitleCommandHandler {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("fireflymc")
                .then(Commands.literal("title")
                        .then(Commands.literal("set")
                                .requires(source -> source.hasPermission(4))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("title", net.minecraft.commands.arguments.MessageArgument.message())
                                                .executes(TitleCommandHandler::setTitle))))
                        .then(Commands.literal("remove")
                                .requires(source -> source.hasPermission(4))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(TitleCommandHandler::removeTitle)))
                        .then(Commands.literal("list")
                                .requires(source -> source.hasPermission(2))
                                .executes(TitleCommandHandler::listTitles))
                        .executes(TitleCommandHandler::sendHelp))
                .executes(TitleCommandHandler::sendHelp)
        );
    }

    /**
     * 设置玩家称号
     */
    private static int setTitle(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(context, "player");
        String rawTitle = net.minecraft.commands.arguments.MessageArgument.getMessage(context, "title").getString();

        if (rawTitle.isEmpty()) {
            context.getSource().sendFailure(Component.literal("§c称号不能为空"));
            return 0;
        }

        // 转换颜色代码：& → §（如果输入已包含 § 则不做二次转换）
        String title = TitleManager.convertColorCodes(rawTitle);

        UUID uuid = target.getUUID();
        TitleManager.getInstance().setTitle(uuid, title);

        // 广播同步给所有客户端
        broadcastTitleSync(context.getSource().getServer());

        String playerName = target.getName().getString();
        context.getSource().sendSuccess(() -> Component.literal(
                "§a[FireflyMC] 已为 §f" + playerName + " §a设置称号: §r" + title
        ), true);

        // 通知目标玩家
        target.sendSystemMessage(Component.literal(
                "§a[FireflyMC] 你获得了新称号: §r" + title
        ));

        return 1;
    }

    /**
     * 移除玩家称号
     */
    private static int removeTitle(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(context, "player");
        UUID uuid = target.getUUID();

        if (!TitleManager.getInstance().hasTitle(uuid)) {
            String playerName = target.getName().getString();
            context.getSource().sendFailure(Component.literal("§c" + playerName + " 没有称号"));
            return 0;
        }

        TitleManager.getInstance().removeTitle(uuid);

        // 广播同步给所有客户端
        broadcastTitleSync(context.getSource().getServer());

        String playerName = target.getName().getString();
        context.getSource().sendSuccess(() -> Component.literal(
                "§a[FireflyMC] 已移除 §f" + playerName + " §a的称号"
        ), true);

        // 通知目标玩家
        target.sendSystemMessage(Component.literal(
                "§a[FireflyMC] 你的称号已被移除"
        ));

        return 1;
    }

    /**
     * 列出所有称号
     */
    private static int listTitles(CommandContext<CommandSourceStack> context) {
        Map<UUID, String> allTitles = TitleManager.getInstance().getAllTitles();

        if (allTitles.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal("§e[FireflyMC] 当前没有任何玩家拥有称号"), false);
            return 1;
        }

        StringBuilder sb = new StringBuilder("§e[FireflyMC] 称号列表:\n");
        for (Map.Entry<UUID, String> entry : allTitles.entrySet()) {
            // 尝试获取在线玩家名称
            ServerPlayer player = context.getSource().getServer().getPlayerList().getPlayer(entry.getKey());
            String playerName = player != null ? player.getName().getString() : entry.getKey().toString().substring(0, 8) + "...";
            sb.append("§7  ").append(playerName).append(" §f→ §r").append(entry.getValue()).append("\n");
        }

        final String message = sb.toString();
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return allTitles.size();
    }

    /**
     * 广播称号同步包给所有在线玩家
     */
    public static void broadcastTitleSync(net.minecraft.server.MinecraftServer server) {
        Map<UUID, String> allTitles = TitleManager.getInstance().getAllTitles();
        Map<String, String> stringKeyMap = new java.util.HashMap<>();
        for (Map.Entry<UUID, String> entry : allTitles.entrySet()) {
            stringKeyMap.put(entry.getKey().toString(), entry.getValue());
        }

        TitleSyncPayload payload = new TitleSyncPayload(stringKeyMap);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    /**
     * 发送帮助信息
     */
    private static int sendHelp(CommandContext<CommandSourceStack> context) {
        Component help = Component.literal(
                "§e[FireflyMC] 称号管理命令:\n" +
                "§7  /fireflymc title set <玩家> <称号> §f— 设置称号 (需OP4)\n" +
                "§7  /fireflymc title remove <玩家> §f— 移除称号 (需OP4)\n" +
                "§7  /fireflymc title list §f— 查看所有称号 (需OP2)\n" +
                "§7  §8支持 & 颜色代码: &a &b &c &l &m &n 等"
        );
        context.getSource().sendSuccess(() -> help, false);
        return 1;
    }
}
