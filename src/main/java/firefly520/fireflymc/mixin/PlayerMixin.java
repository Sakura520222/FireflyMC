package firefly520.fireflymc.mixin;

import firefly520.fireflymc.title.TitleManager;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin：在玩家显示名称前插入称号
 * <p>
 * 拦截 Player.getDisplayName() 方法，在名称前添加称号。
 * 此方法被聊天系统、命令反馈等场景调用，
 * 因此称号会自动显示在聊天消息中。
 * <p>
 * 客户端调用时 TitleManager 无数据（称号通过 ClientState.titleMap 存储），
 * getTitle() 返回 null，不影响客户端渲染。
 */
@Mixin(Player.class)
public class PlayerMixin {

    @Inject(
        method = "getDisplayName",
        at = @At("RETURN"),
        cancellable = true,
        remap = false
    )
    private void fireflymc$appendTitle(CallbackInfoReturnable<Component> cir) {
        Player self = (Player) (Object) this;
        String title = TitleManager.getInstance().getTitle(self.getUUID());
        if (title != null && !title.isEmpty()) {
            Component original = cir.getReturnValue();
            // 格式：[称号]玩家ID
            Component withTitle = Component.literal("")
                    .append(Component.literal("§7[§r" + title + "§7]§r"))
                    .append(original);
            cir.setReturnValue(withTitle);
        }
    }
}
