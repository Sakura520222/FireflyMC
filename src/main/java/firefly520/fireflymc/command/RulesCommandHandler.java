package firefly520.fireflymc.command;

import firefly520.fireflymc.FireflyMCMod;
import firefly520.fireflymc.ServerConfig;
import firefly520.fireflymc.network.ModPayloadHandler;
import firefly520.fireflymc.network.ShowRulesPayload;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Player-accessible recovery when a required rules dialog was dismissed or replaced. */
@EventBusSubscriber(modid = FireflyMCMod.MODID)
public final class RulesCommandHandler {
    private RulesCommandHandler() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("fireflymc")
            .then(Commands.literal("rules").executes(context -> {
                var player = context.getSource().getPlayerOrException();
                if (player.server.isDedicatedServer() && ServerConfig.SERVER.playerAuthEnabled.get()
                    && !ModPayloadHandler.PASSWORD_VERIFIED_PLAYERS.getOrDefault(player.getUUID(), false)) {
                    context.getSource().sendFailure(Component.translatable("fireflymc.rules.password_required"));
                    return 0;
                }
                PacketDistributor.sendToPlayer(player, new ShowRulesPayload(
                    !ModPayloadHandler.CONFIRMED_PLAYERS.getOrDefault(player.getUUID(), false)));
                return 1;
            })));
    }
}
