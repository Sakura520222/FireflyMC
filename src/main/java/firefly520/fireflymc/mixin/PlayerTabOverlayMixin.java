package firefly520.fireflymc.mixin;

import firefly520.fireflymc.client.ClientState;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin：在 Tab 列表中显示玩家称号
 * <p>
 * 拦截 PlayerTabOverlay 的玩家名称获取方法，
 * 在名称前插入称号（带颜色）。
 */
@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {

    /**
     * 拦截获取玩家显示名称的方法，在名称前插入称号
     */
    @Inject(
        method = "getNameForDisplay",
        at = @At("RETURN"),
        cancellable = true,
        remap = false
    )
    private void fireflymc$appendTitle(PlayerInfo playerInfo, CallbackInfoReturnable<Component> cir) {
        String uuid = playerInfo.getProfile().getId().toString();
        String title = ClientState.titleMap.get(uuid);
        if (title != null && !title.isEmpty()) {
            Component original = cir.getReturnValue();
            // 称号 + 空格 + 原名
            Component withTitle = Component.literal("")
                    .append(Component.literal(title + " "))
                    .append(original);
            cir.setReturnValue(withTitle);
        }
    }
}
