package firefly520.fireflymc.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import firefly520.fireflymc.client.relay.RelayServerEntry;
import firefly520.fireflymc.client.relay.RelayServerListBridge;
import firefly520.fireflymc.client.serverlist.OfficialServerEntry;
import firefly520.fireflymc.client.serverlist.OfficialServerListBridge;

/**
 * Mixin：在原版多人游戏界面中处理联机大厅房间的加入，并接入后台下发的官方服务器列表。
 * <p>
 * 1. 屏幕初始化后请求大厅列表刷新，并拉取官方服务器列表。
 * 2. 拦截 joinSelectedServer()，当选中的是联机大厅房间时走 RelayGuestJoiner。
 * 3. 选中官方服务器条目时禁用「编辑 / 删除」（官方条目由后台统一管理）。
 * 4. 屏幕关闭时重置桥接层状态，并把官方条目的图标 / 资源包选择写回本地缓存。
 * <p>
 * 注意：onSelectedChange() 只在选中官方条目时需要补充处理（置灰编辑 / 删除），
 * 联机大厅房间不是 OnlineServerEntry，原版逻辑已正确禁用这两个按钮。
 */
@Mixin(JoinMultiplayerScreen.class)
public class JoinMultiplayerScreenMixin {

    @Shadow(remap = false)
    protected ServerSelectionList serverSelectionList;

    @Shadow(remap = false)
    private Button editButton;

    @Shadow(remap = false)
    private Button deleteButton;

    /**
     * 屏幕初始化后请求联机大厅列表刷新，并拉取官方服务器列表。
     */
    @Inject(
        method = "init",
        at = @At("TAIL"),
        remap = false
    )
    private void fireflymc$onInit(CallbackInfo ci) {
        RelayServerListBridge.requestLobbyRefresh();
        OfficialServerListBridge.onScreenInit((JoinMultiplayerScreen) (Object) this, this.serverSelectionList);
    }

    /**
     * 原版「刷新」按钮：重新拉取官方服务器列表（跳过节流）。
     */
    @Inject(
        method = "refreshServerList",
        at = @At("HEAD"),
        remap = false
    )
    private void fireflymc$onRefreshServerList(CallbackInfo ci) {
        OfficialServerListBridge.onManualRefresh();
    }

    /**
     * 拦截 joinSelectedServer()。
     * 当选中的是 RelayServerEntry 时，走 RelayGuestJoiner.join() 而非原版连接。
     * 官方服务器条目继承自原版 OnlineServerEntry，由原版逻辑直接连接。
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
     * 官方服务器由后台统一管理：选中官方条目时不提供「编辑 / 删除」。
     */
    @Inject(
        method = "onSelectedChange",
        at = @At("TAIL"),
        remap = false
    )
    private void fireflymc$afterSelectedChange(CallbackInfo ci) {
        if (this.serverSelectionList.getSelected() instanceof OfficialServerEntry) {
            this.editButton.active = false;
            this.deleteButton.active = false;
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
        OfficialServerListBridge.onScreenRemoved();
    }
}
