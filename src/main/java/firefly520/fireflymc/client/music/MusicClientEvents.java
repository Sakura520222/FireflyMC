package firefly520.fireflymc.client.music;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** 客户端音乐事件：音量桥 + 断开连接清理 */
public class MusicClientEvents {

    /** 每 tick：MASTER × MUSIC → AtomicReference（播放线程只读纯数值，不碰 Minecraft 对象） */
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) {
            return;
        }
        double master = mc.options.getSoundSourceVolume(SoundSource.MASTER);
        double music = mc.options.getSoundSourceVolume(SoundSource.MUSIC);
        MusicPlaybackManager.setEffectiveVolume((float) (master * music));
    }

    /** 断开连接/退出世界：停止本地播放并清空 HUD 状态 */
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        MusicPlaybackManager.shutdown();
    }
}
