package firefly520.fireflymc.mixin;

import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import firefly520.fireflymc.client.relay.RelayServerEntry;
import firefly520.fireflymc.client.relay.RelayServerListBridge;

/**
 * Mixin：在原版多人游戏界面中处理联机大厅房间的加入。
 * <p>
 * 1. 屏幕初始化后请求大厅列表刷新。
 * 2. 拦截 joinSelectedServer()，当选中的是联机大厅房间时走 RelayGuestJoiner。
 * 3. 屏幕关闭时重置桥接层状态。
 * <p>
 * 注意：onSelectedChange() 不需要拦截，因为原版逻辑已正确处理：
 * RelayServerEntry 不是 OnlineServerEntry，所以编辑/删除按钮会自动禁用。
 */
@Mixin(JoinMultiplayerScreen.class)
public class JoinMultiplayerScreenMixin {

    @Shadow(remap = false)
    protected ServerSelectionList serverSelectionList;

    /**
     * 屏幕初始化后请求联机大厅列表刷新。
     */
    @Inject(
        method = "init",
        at = @At("TAIL"),
        remap = false
    )
    private void fireflymc$onInit(CallbackInfo ci) {
        RelayServerListBridge.requestLobbyRefresh();
    }

    /**
     * 拦截 joinSelectedServer()。
     * 当选中的是 RelayServerEntry 时，走 RelayGuestJoiner.join() 而非原版连接。
     */
    @Inject(
        method = "joinSelectedServer",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void fireflymc$onJoinSelected(CallbackInfo ci) {
        ServerSelectionList.Entry selected = this.serverSelectionList.getSelected();
        if (selected instanceof RelayServerEntry relayEntry) {
            RelayServerListBridge.joinRoom((JoinMultiplayerScreen) (Object) this, relayEntry.getRoom());
            ci.cancel();
        }
    }

    /**
     * 屏幕关闭时重置桥接层状态。
     */
    @Inject(
        method = "removed",
        at = @At("TAIL"),
        remap = false
    )
    private void fireflymc$onRemoved(CallbackInfo ci) {
        RelayServerListBridge.reset();
    }
}
