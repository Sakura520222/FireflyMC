package firefly520.fireflymc.auth;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 玩家密码管理命令
 * <p>
 * /fireflyauth reset &lt;playerName&gt; — 重置指定玩家密码（OP4）
 */
@EventBusSubscriber(modid = "fireflymc", value = Dist.DEDICATED_SERVER)
public class PlayerPasswordCommandHandler {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("fireflyauth")
                .then(Commands.literal("reset")
                        .requires(source -> source.hasPermission(4))
                        .then(Commands.argument("playerName", StringArgumentType.word())
                                .executes(PlayerPasswordCommandHandler::resetPassword)))
                .executes(PlayerPasswordCommandHandler::sendHelp)
        );
    }

    private static int resetPassword(CommandContext<CommandSourceStack> context) {
        String playerName = StringArgumentType.getString(context, "playerName");
        boolean removed = PlayerPasswordManager.getInstance().resetPasswordByName(playerName);
        if (removed) {
            context.getSource().sendSuccess(() -> Component.literal(
                    "§a[FireflyMC] 已重置玩家 §f" + playerName + " §a的密码，下次登录需重新设置"
            ), true);
        } else {
            context.getSource().sendFailure(Component.literal(
                    "§c[FireflyMC] 玩家 §f" + playerName + " §c没有密码记录"
            ));
        }
        return 1;
    }

    private static int sendHelp(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(
                "§e[FireflyMC] 密码管理命令:\n" +
                "§7  /fireflyauth reset <玩家名> §f— 重置指定玩家密码 (需OP4)"
        ), false);
        return 1;
    }
}
