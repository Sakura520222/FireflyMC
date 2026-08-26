package firefly520.fireflymc.client.music;

import firefly520.fireflymc.Config;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.SoundOptionsScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * 往原版"音乐和声音选项"注入点歌音乐独立音量滑块（ScreenEvent，无 Mixin）。
 * - Init.Post：追加半宽滑块到声音列表末尾（每次进入该页面都会新建 screen，不会重复）
 * - Closing：关闭声音设置页时统一写盘一次（拖动期间只改内存，避免频繁磁盘写入）
 */
public class MusicSoundOptionsEvents {

    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof SoundOptionsScreen)) {
            return;
        }
        event.getListenersList().stream()
                .filter(OptionsList.class::isInstance)
                .map(OptionsList.class::cast)
                .findFirst()
                .ifPresent(list -> list.addSmall(MusicVolumeOption.create()));
    }

    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (!(event.getScreen() instanceof SoundOptionsScreen)) {
            return;
        }
        Config.CLIENT_SPEC.save();
    }
}
