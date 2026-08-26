package firefly520.fireflymc.client.music;

import firefly520.fireflymc.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** 客户端音乐事件：音量桥 + 断开连接清理 */
public class MusicClientEvents {

    /**
     * 音量桥核心：MASTER × 点歌音乐独立音量 → AtomicReference
     * （播放线程只读纯数值，不碰 Minecraft 对象）。
     * 不再乘原版 MUSIC 分类——独立滑块的语义：原版"音乐"只管背景音乐，
     * 点歌音乐只管点歌，二者互不影响，均受主音量控制。
     * ClientTick 与设置页滑块回调共用此入口。
     */
    public static void refreshVolume() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) {
            return;
        }
        double master = mc.options.getSoundSourceVolume(SoundSource.MASTER);
        double requestVolume = Config.CLIENT.MUSIC_REQUEST_VOLUME.get();
        MusicPlaybackManager.setEffectiveVolume((float) (master * requestVolume));
    }

    /** 每 tick 刷新音量（配置文件被外部修改等场景也能跟上） */
    public static void onClientTick(ClientTickEvent.Post event) {
        refreshVolume();
    }

    /** 断开连接/退出世界：停止本地播放并清空 HUD 状态 */
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        MusicPlaybackManager.shutdown();
    }
}
