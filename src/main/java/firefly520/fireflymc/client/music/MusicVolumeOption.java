package firefly520.fireflymc.client.music;

import firefly520.fireflymc.Config;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * 点歌音乐独立音量的 OptionInstance（注入原版"音乐和声音选项"页面）。
 * 与原版声音分类滑块完全同款：半宽双列、"点歌音乐：100%"、0% 显示"关"。
 * 语义：点歌实际音量 = MASTER × 本值（不再乘原版 MUSIC 分类，实现真正独立）。
 */
public final class MusicVolumeOption {

    /** 滑块标题翻译键（与 lang 文件的 fireflymc.options.music_request_volume 对应） */
    static final String TRANSLATION_KEY = "fireflymc.options.music_request_volume";

    private MusicVolumeOption() {}

    public static OptionInstance<Double> create() {
        return new OptionInstance<>(
                TRANSLATION_KEY,
                OptionInstance.noTooltip(),
                // 复刻原版 Options#percentValueOrOffLabel（private 无法直接引用）：
                // 0% → "关"，其余 → "点歌音乐：NN%"
                (caption, value) -> value == 0.0
                        ? Component.translatable("options.generic_value", caption, CommonComponents.OPTION_OFF)
                        : Component.translatable("options.percent_value", caption, (int) (value * 100.0)),
                OptionInstance.UnitDouble.INSTANCE,
                Config.CLIENT.MUSIC_REQUEST_VOLUME.get(),
                value -> {
                    // 拖动即时生效：只更新内存（ConfigValue.set 不写盘），关屏时统一落盘一次
                    Config.CLIENT.MUSIC_REQUEST_VOLUME.set(value);
                    MusicClientEvents.refreshVolume();
                });
    }
}
