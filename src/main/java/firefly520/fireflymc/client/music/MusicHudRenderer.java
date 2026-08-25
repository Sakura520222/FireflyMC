package firefly520.fireflymc.client.music;

import firefly520.fireflymc.client.HudRenderUtil;
import firefly520.fireflymc.network.MusicQueueSyncPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 音乐 HUD 卡片（左侧纵向 stack 的上卡片，与服务器信息卡共用统一宽度与 x）。
 * 无歌时不渲染；单人世界同样显示。
 */
public final class MusicHudRenderer {

    private static final int X = 5;
    private static final int LINE_HEIGHT = 11;
    private static final int PADDING = 8;
    private static final int PROGRESS_HEIGHT = 3;
    private static final int MAX_QUEUE_ITEMS = 3;
    private static final int MAX_WIDTH = 180;
    private static final int MIN_WIDTH = 110;
    private static final int TEXT_COLOR = 0xFFFFFF;
    /** 柔和粉色：与白色信息形成层级，不用过亮的荧光粉 */
    private static final int LYRIC_COLOR = 0xFFF5AFC2;
    private static final int REQUESTER_COLOR = 0xFF8A8A8A;
    private static final int PROGRESS_BG = 0x66333333;
    private static final int PROGRESS_FG = 0xFF6EC9FF;

    /** 歌词纵向滚动动画时长（ms），播放器式上滑切换 */
    private static final long LYRIC_ANIM_MS = 300;

    /**
     * 歌词过渡状态（仅渲染线程访问）。
     * 记录上一行/当前行/变化时间，动画基于时间差计算，与 FPS 无关；
     * 同一句歌词期间 progress 到 1 后静止，不持续滚动。
     */
    private static final class LyricTransitionState {
        String previous;
        String current;
        long changeTime;
    }

    private static final LyricTransitionState LYRIC = new LyricTransitionState();

    private MusicHudRenderer() {}

    /** 有正在播放/等待的曲目即显示 */
    public static boolean isVisible() {
        return MusicPlaybackState.current() != null;
    }

    /** 卡片自身测量宽度（供 ClientHandler 统一取两卡最大值） */
    public static int measureWidth(Minecraft mc) {
        Font font = mc.font;
        MusicPlaybackState.PlayingInfo info = MusicPlaybackState.current();
        if (info == null) {
            return MIN_WIDTH;
        }
        List<MusicQueueSyncPayload.SongSummary> queue = MusicPlaybackState.queue();
        int w = font.width(info.title() + " - " + info.author());
        for (int i = 0; i < Math.min(queue.size(), MAX_QUEUE_ITEMS); i++) {
            MusicQueueSyncPayload.SongSummary s = queue.get(i);
            w = Math.max(w, font.width((i + 1) + ". " + s.title() + " - " + s.author() + " · " + s.requesterName()));
        }
        return Math.min(MAX_WIDTH, Math.max(w + PADDING * 2, MIN_WIDTH));
    }

    /** 缩放后坐标系高度（无歌词文件时歌词行不计入，直接隐藏不留空白） */
    public static int measureHeight() {
        List<MusicQueueSyncPayload.SongSummary> queue = MusicPlaybackState.queue();
        int lines = 1 /*曲名行*/ + 1 /*进度条*/ + 1 /*时间行*/;
        if (hasLyrics()) {
            lines += 1; // 歌词行
        }
        if (!queue.isEmpty()) {
            lines += 1 /*分隔行*/ + Math.min(queue.size(), MAX_QUEUE_ITEMS)
                    + (queue.size() > MAX_QUEUE_ITEMS ? 1 : 0);
        }
        return lines * LINE_HEIGHT + PADDING * 2;
    }

    private static boolean hasLyrics() {
        MusicPlaybackState.PlayingInfo info = MusicPlaybackState.current();
        return info != null && !info.lrc().isEmpty();
    }

    /** 在缩放后坐标系 (X, startY) 按统一宽度渲染 */
    public static void renderAt(GuiGraphics guiGraphics, int startY, int unifiedWidth) {
        MusicPlaybackState.PlayingInfo info = MusicPlaybackState.current();
        if (info == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        List<MusicQueueSyncPayload.SongSummary> queue = MusicPlaybackState.queue();
        int width = unifiedWidth;
        int height = measureHeight();
        float scale = firefly520.fireflymc.Config.CLIENT.HUD_SCALE.get().floatValue();

        guiGraphics.pose().pushPose();
        guiGraphics.pose().scale(scale, scale, 1.0F);

        HudRenderUtil.drawRoundedBorder(guiGraphics, X, startY, width, height);

        int y = startY + PADDING;

        // 1. 曲名 - 歌手（超宽横向滚动，substr 截取保证始终可见；文本绘制一律无阴影）
        String title = info.title() + " - " + info.author();
        drawScrollingText(guiGraphics, font, title, X + PADDING, y, width - PADDING * 2, TEXT_COLOR);
        y += LINE_HEIGHT + 2;

        // 2. 进度条 + 时间（总时长以服务端 durationMs 为权威）
        long pos = info.clock().positionMs();
        long total = Math.max(info.durationMs(), 1);
        float ratio = Math.min(1.0f, pos / (float) total);
        int barWidth = width - PADDING * 2;
        guiGraphics.fill(X + PADDING, y, X + PADDING + barWidth, y + PROGRESS_HEIGHT, PROGRESS_BG);
        guiGraphics.fill(X + PADDING, y, X + PADDING + (int) (barWidth * ratio), y + PROGRESS_HEIGHT, PROGRESS_FG);
        y += PROGRESS_HEIGHT + 2;
        String time = formatTime(Math.min(pos, total)) + " / " + formatTime(total);
        guiGraphics.drawString(font, time, X + PADDING, y, REQUESTER_COLOR, false);
        y += LINE_HEIGHT;

        // 3. 当前歌词：粉色 + 纵向滚动切换动画（无歌词文件时整行隐藏，高度已不计）
        if (hasLyrics()) {
            renderLyric(guiGraphics, font, X + PADDING, y, width - PADDING * 2, scale);
            y += LINE_HEIGHT;
        }

        // 4. 排队列表（最多 3 项 + "还有 N 首"）
        if (!queue.isEmpty()) {
            String header = "── 排队 (" + queue.size() + ") ──";
            guiGraphics.drawString(font, header, X + PADDING, y, REQUESTER_COLOR, false);
            y += LINE_HEIGHT;
            int shown = Math.min(queue.size(), MAX_QUEUE_ITEMS);
            for (int i = 0; i < shown; i++) {
                MusicQueueSyncPayload.SongSummary s = queue.get(i);
                // 截断优先级：歌名 > 歌手 > 点歌者（requester 最先被截断）
                String line = truncateWithPriority(font, (i + 1) + ". " + s.title(), s.author(),
                        " · " + s.requesterName(), width - PADDING * 2);
                guiGraphics.drawString(font, line, X + PADDING, y, TEXT_COLOR, false);
                y += LINE_HEIGHT;
            }
            if (queue.size() > MAX_QUEUE_ITEMS) {
                guiGraphics.drawString(font, "    还有 " + (queue.size() - MAX_QUEUE_ITEMS) + " 首",
                        X + PADDING, y, REQUESTER_COLOR, false);
            }
        }

        guiGraphics.pose().popPose();
    }

    /**
     * 标题行：不超宽直接画；超宽按时间横向滚动。
     * 用 substr 截取可见段（不用 scissor——scissor 是 framebuffer 级坐标，
     * 与 pose scale 不一致会导致文字被裁消失），保证任何时刻至少显示一部分内容。
     */
    private static void drawScrollingText(GuiGraphics g, Font font, String text,
                                          int x, int y, int maxWidth, int color) {
        int textWidth = font.width(text);
        if (textWidth <= maxWidth) {
            g.drawString(font, text, x, y, color, false);
            return;
        }
        // 滚动周期：静置 1.5s → 逐像素滚动（25ms/px）→ 静置 1.5s → 回起点循环
        int maxOffset = textWidth - maxWidth;
        long cycle = 1500 + maxOffset * 25L + 1500;
        long t = System.currentTimeMillis() % cycle;
        int offset;
        if (t < 1500) {
            offset = 0;
        } else if (t < 1500 + maxOffset * 25L) {
            offset = (int) ((t - 1500) / 25);
        } else {
            offset = maxOffset;
        }
        int charIdx = charIndexAtPx(font, text, offset);
        String visible = font.plainSubstrByWidth(text.substring(charIdx), maxWidth);
        if (visible.isEmpty() && !text.isEmpty()) {
            visible = text.substring(0, 1); // 极端情况下至少显示 1 个字符
        }
        g.drawString(font, visible, x, y, color, false);
    }

    /** 像素偏移 → 字符索引（O(n) 逐字符累计宽度） */
    private static int charIndexAtPx(Font font, String text, int px) {
        int acc = 0;
        for (int i = 0; i < text.length(); i++) {
            int w = font.width(String.valueOf(text.charAt(i)));
            if (acc + w > px) {
                return i;
            }
            acc += w;
        }
        return text.length();
    }

    /**
     * 歌词纵向滚动动画（播放器式）：
     * 旧行向上滑出淡出、新行自下滑入淡入，约 300ms，smoothstep 缓动；
     * 同一句期间静止。垂直裁剪用 scissor（参数 ×scale 换算到 gui-scaled 坐标）。
     */
    private static void renderLyric(GuiGraphics g, Font font, int x, int y, int maxWidth, float scale) {
        Optional<String> line = MusicPlaybackState.currentLyricLine();
        String current = line.orElse(null);

        // 歌词变化 → 记录过渡状态
        if (!Objects.equals(current, LYRIC.current)) {
            LYRIC.previous = LYRIC.current;
            LYRIC.current = current;
            LYRIC.changeTime = System.currentTimeMillis();
        }

        String target = LYRIC.current;
        if (target == null || target.isEmpty()) {
            return; // 前奏等空行期：保留区域但不绘制
        }

        long elapsed = System.currentTimeMillis() - LYRIC.changeTime;
        float progress = Math.min(1f, elapsed / (float) LYRIC_ANIM_MS);
        float eased = progress * progress * (3 - 2 * progress); // smoothstep

        // 垂直裁剪（±1px 余量；scissor 坐标须从缩放后逻辑坐标换算回 gui-scaled 坐标）
        g.enableScissor((int) (x * scale), (int) ((y - 1) * scale),
                (int) ((x + maxWidth) * scale), (int) ((y + LINE_HEIGHT + 1) * scale));
        if (progress < 1f) {
            if (LYRIC.previous != null && !LYRIC.previous.isEmpty()) {
                // 旧行：向上滑出 + 淡出
                int oldY = Math.round(y - eased * LINE_HEIGHT);
                int oldAlpha = Math.round((1 - eased) * 255);
                drawClipped(g, font, LYRIC.previous, x, oldY, maxWidth, withAlpha(LYRIC_COLOR, oldAlpha));
            }
            // 新行：自下滑入 + 淡入
            int newY = Math.round(y + (1 - eased) * LINE_HEIGHT);
            int newAlpha = Math.round(eased * 255);
            drawClipped(g, font, target, x, newY, maxWidth, withAlpha(LYRIC_COLOR, newAlpha));
        } else {
            drawClipped(g, font, target, x, y, maxWidth, LYRIC_COLOR);
        }
        g.disableScissor();
    }

    private static int withAlpha(int argb, int alpha) {
        return (argb & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    private static void drawClipped(GuiGraphics g, Font font, String text, int x, int y, int maxWidth, int color) {
        String clipped = font.plainSubstrByWidth(text, maxWidth);
        g.drawString(font, clipped, x, y, color, false);
    }

    /** 截断优先级：歌名 > 歌手 > requester 最先被截断 */
    private static String truncateWithPriority(Font font, String prefixTitle, String author,
                                               String requesterSuffix, int maxWidth) {
        if (font.width(prefixTitle + " - " + author + requesterSuffix) <= maxWidth) {
            return prefixTitle + " - " + author + requesterSuffix;
        }
        // 先砍 requester
        String s = prefixTitle + " - " + author;
        if (font.width(s) <= maxWidth) {
            return s;
        }
        // 再砍 author（保留歌名）
        return font.plainSubstrByWidth(s, maxWidth);
    }

    private static String formatTime(long ms) {
        long totalSec = Math.max(0, ms / 1000);
        return String.format("%d:%02d", totalSec / 60, totalSec % 60);
    }
}
