package firefly520.fireflymc.mixin;

import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import firefly520.fireflymc.client.relay.RelayServerEntry;
import firefly520.fireflymc.client.relay.RelayServerListBridge;
import firefly520.fireflymc.client.serverlist.OfficialServerListBridge;

import java.util.List;

/**
 * Mixin：在原版服务器列表刷新条目后，追加联机大厅房间与官方服务器。
 * <p>
 * 注入点：ServerSelectionList.refreshEntries() 的 TAIL。
 * 每次原版列表重建（updateOnlineServers / updateNetworkServers）后都会触发。
 * <p>
 * 顺序：玩家自己的服务器 → 官方服务器 → 局域网分区 → 联机大厅房间。
 * 官方服务器与大厅房间都必须排在玩家条目之后：原版的上移 / 下移直接拿行号当 servers.dat 下标。
 * <p>
 * 继承 ObjectSelectionList 以获得 addEntry 的 protected 访问权限。
 */
@Mixin(ServerSelectionList.class)
public abstract class ServerSelectionListMixin extends ObjectSelectionList<ServerSelectionList.Entry> {

    /**
     * 列表所属的多人游戏界面。
     * 不用 Minecraft.screen：删除服务器的确认流程里，重建列表时当前界面还是 ConfirmScreen，
     * 那一刻原版列表已经重建完却拿不到界面，注入的条目会丢失。
     */
    @Shadow(remap = false)
    @Final
    private JoinMultiplayerScreen screen;

    // 构造函数由 Mixin 框架处理，不需要显式实现
    private ServerSelectionListMixin() { super(null, 0, 0, 0, 0); }

    /**
     * 在 refreshEntries() 末尾注入联机大厅房间与官方服务器条目。
     * refreshEntries() 是 private 方法，Mojang 映射名不变。
     */
    @Inject(
        method = "refreshEntries",
        at = @At("TAIL"),
        remap = false
    )
    private void fireflymc$afterRefreshEntries(CallbackInfo ci) {
        List<RelayServerEntry> entries = RelayServerListBridge.getEntriesToInject(this.screen);
        for (RelayServerEntry entry : entries) {
            this.addEntry(entry);
        }

        // 官方服务器插在局域网分区之前（见 OfficialServerListBridge.inject）
        OfficialServerListBridge.inject(this.screen, (ServerSelectionList) (Object) this);
    }
}
