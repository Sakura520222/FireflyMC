package firefly520.fireflymc.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import firefly520.fireflymc.client.relay.RelayServerEntry;
import firefly520.fireflymc.client.relay.RelayServerListBridge;

import java.util.List;

/**
 * Mixin：在原版服务器列表刷新条目后，追加联机大厅房间。
 * <p>
 * 注入点：ServerSelectionList.refreshEntries() 的 TAIL。
 * 每次原版列表重建（updateOnlineServers / updateNetworkServers）后都会触发。
 * <p>
 * 继承 ObjectSelectionList 以获得 addEntry 的 protected 访问权限。
 */
@Mixin(ServerSelectionList.class)
public abstract class ServerSelectionListMixin extends ObjectSelectionList<ServerSelectionList.Entry> {

    // 构造函数由 Mixin 框架处理，不需要显式实现
    private ServerSelectionListMixin() { super(null, 0, 0, 0, 0); }

    /**
     * 在 refreshEntries() 末尾注入联机大厅房间条目。
     * refreshEntries() 是 private 方法，Mojang 映射名不变。
     */
    @Inject(
        method = "refreshEntries",
        at = @At("TAIL"),
        remap = false
    )
    private void fireflymc$afterRefreshEntries(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof JoinMultiplayerScreen screen)) {
            return;
        }

        List<RelayServerEntry> entries = RelayServerListBridge.getEntriesToInject(screen);
        for (RelayServerEntry entry : entries) {
            this.addEntry(entry);
        }
    }
}
