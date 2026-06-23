package firefly520.fireflymc.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import firefly520.fireflymc.FireflyMCMod;
import firefly520.fireflymc.client.eventws.ClientEventNotificationMessage;
import firefly520.fireflymc.client.eventws.ClientEventWebSocketClient;

import java.util.UUID;

/**
 * 客户端绑定命令 /fireflymc bind [token]。
 *
 * - 无令牌：生成一次性 challenge，上报官网(经 relay 转发)，自动打开浏览器到绑定引导页
 * - 带令牌：上报 bind_request，官网回查令牌(网页生成的、关联账号)完成绑定
 *
 * 走 GAME 总线，仅在客户端触发。
 */
@EventBusSubscriber(modid = FireflyMCMod.MODID)
public class BindCommandHandler {

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
            Commands.literal("fireflymc")
                .then(Commands.literal("bind")
                    // /fireflymc bind  （无参数 → 自动开浏览器）
                    .executes(BindCommandHandler::executeBindNoToken)
                    // /fireflymc bind <token>
                    .then(Commands.argument("token", StringArgumentType.string())
                        .executes(BindCommandHandler::executeBindWithToken)
                    )
                )
        );
    }

    private static int executeBindNoToken(CommandContext<CommandSourceStack> context) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            context.getSource().sendFailure(Component.literal("玩家未就绪"));
            return 0;
        }

        String name = player.getGameProfile().getName();
        UUID uuid = player.getGameProfile().getId();
        String uuidStr = (uuid != null) ? uuid.toString() : "";

        // 生成 challenge 上报 + 打开浏览器
        String challenge = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        ClientEventWebSocketClient.getInstance().send(
            ClientEventNotificationMessage.create("bind_challenge")
                .add("challenge", challenge)
                .add("playerName", name)
                .add("playerUuid", uuidStr)
        );

        String bindUrl = "https://mc.firefly520.top/bind?c=" + challenge;
        Util.getPlatform().openUri(bindUrl);

        context.getSource().sendSuccess(
            () -> Component.literal("正在打开绑定页面，请在浏览器中完成验证"), false
        );
        return 1;
    }

    private static int executeBindWithToken(CommandContext<CommandSourceStack> context) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            context.getSource().sendFailure(Component.literal("玩家未就绪"));
            return 0;
        }

        String token = StringArgumentType.getString(context, "token");
        String name = player.getGameProfile().getName();
        UUID uuid = player.getGameProfile().getId();
        String uuidStr = (uuid != null) ? uuid.toString() : "";

        // 带令牌：上报 bind_request
        ClientEventWebSocketClient.getInstance().send(
            ClientEventNotificationMessage.create("bind_request")
                .add("token", token)
                .add("playerName", name)
                .add("playerUuid", uuidStr)
        );

        context.getSource().sendSuccess(
            () -> Component.literal("已发送绑定请求，令牌: " + token), false
        );
        return 1;
    }
}
