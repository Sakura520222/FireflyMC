package firefly520.fireflymc.playtime;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import firefly520.fireflymc.ServerConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.UUID;

/**
 * 在线时长管理命令
 * <p>
 * /playtime check [player] — 查看剩余时间
 * /playtime reset <player> — 重置指定玩家每日时间
 * /playtime resetdaily     — 重置所有玩家每日时间
 */
@EventBusSubscriber(modid = "fireflymc", value = Dist.DEDICATED_SERVER)
public class PlaytimeCommandHandler {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("playtime")
                .then(Commands.literal("check")
                        .executes(PlaytimeCommandHandler::checkSelf)
                        .then(Commands.argument("player", EntityArgument.player())
                                .requires(source -> source.hasPermission(2))
                                .executes(PlaytimeCommandHandler::checkOther)))
                .then(Commands.literal("reset")
                        .requires(source -> source.hasPermission(4))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(PlaytimeCommandHandler::resetPlayer)))
                .then(Commands.literal("resetdaily")
                        .requires(source -> source.hasPermission(4))
                        .executes(PlaytimeCommandHandler::resetAll))
                .executes(PlaytimeCommandHandler::sendHelp)
        );
    }

    private static int checkSelf(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        showPlaytimeInfo(context.getSource(), player);
        return 1;
    }

    private static int checkOther(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(context, "player");
        showPlaytimeInfo(context.getSource(), target);
        return 1;
    }

    private static void showPlaytimeInfo(CommandSourceStack source, ServerPlayer target) {
        UUID uuid = target.getUUID();
        PlaytimeManager mgr = PlaytimeManager.getInstance();

        long dailySeconds = mgr.getPlayerDailySeconds(uuid);
        long continuousSeconds = mgr.getPlayerContinuousSeconds(uuid);
        long dailyLimit = (long) ServerConfig.SERVER.playtimeDailyLimitMinutes.get() * 60;
        long continuousLimit = (long) ServerConfig.SERVER.playtimeContinuousLimitMinutes.get() * 60;

        long dailyRemaining = Math.max(0, dailyLimit - dailySeconds);
        long continuousRemaining = Math.max(0, continuousLimit - continuousSeconds);

        String playerName = target.getName().getString();
        Component message = Component.literal(String.format(
                "§e[FireflyMC] §f%s §e的在线时长:\n" +
                "§7  每日: §a%s§7/§f%s §7(剩余 §a%s§7)\n" +
                "§7  本次: §a%s§7/§f%s §7(剩余 §a%s§7)",
                playerName,
                PlaytimeManager.formatDuration(dailySeconds),
                PlaytimeManager.formatDuration(dailyLimit),
                PlaytimeManager.formatDuration(dailyRemaining),
                PlaytimeManager.formatDuration(continuousSeconds),
                PlaytimeManager.formatDuration(continuousLimit),
                PlaytimeManager.formatDuration(continuousRemaining)
        ));
        source.sendSuccess(() -> message, false);
    }

    private static int resetPlayer(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(context, "player");
        PlaytimeManager.getInstance().resetDailyPlaytime(target.getUUID());
        String name = target.getName().getString();
        context.getSource().sendSuccess(() -> Component.literal("§a[FireflyMC] 已重置 §f" + name + " §a的每日在线时长"), true);
        return 1;
    }

    private static int resetAll(CommandContext<CommandSourceStack> context) {
        int count = PlaytimeManager.getInstance().resetAllDaily();
        int finalCount = count;
        context.getSource().sendSuccess(() -> Component.literal(
                "§a[FireflyMC] 已重置所有玩家的每日在线时长 (共 §e" + finalCount + " §a名玩家)"
        ), true);
        return 1;
    }

    private static int sendHelp(CommandContext<CommandSourceStack> context) {
        Component help = Component.literal(
                "§e[FireflyMC] 在线时长管理命令:\n" +
                "§7  /playtime check §f— 查看自己的剩余时间\n" +
                "§7  /playtime check <玩家> §f— 查看他人剩余时间 (需OP2)\n" +
                "§7  /playtime reset <玩家> §f— 重置指定玩家每日时间 (需OP4)\n" +
                "§7  /playtime resetdaily §f— 重置所有玩家每日时间 (需OP4)"
        );
        context.getSource().sendSuccess(() -> help, false);
        return 1;
    }
}
