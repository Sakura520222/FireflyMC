package firefly520.fireflymc.client.screen;

import firefly520.fireflymc.client.ClientState;
import firefly520.fireflymc.client.relay.SingleplayerRelayManager;
import firefly520.fireflymc.client.relay.SingleplayerRelayManager.HostingState;
import firefly520.fireflymc.client.relay.ipv6.Ipv6ConnectivityChecker;
import firefly520.fireflymc.client.relay.ipv6.Ipv6ProbeResult;
import firefly520.fireflymc.client.relay.ipv6.Ipv6ConnectivityChecker.Ipv6ProbeSnapshot;
import firefly520.fireflymc.client.relay.ipv6.Ipv6ProbeStatus;
import firefly520.fireflymc.client.relay.p2p.Ipv6AddressCollector;
import firefly520.fireflymc.client.relay.RelayConfig;
import firefly520.fireflymc.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** 单人世界联机控制面板:启停联机 + IPv6 出站检测 + 状态展示。 */
public class SingleplayerRelayControlScreen extends Screen {
    private static final int ACCENT_PRIMARY = 0xFFFF69B4;
    private static final int ACCENT_SECONDARY = 0xFFFF1493;
    private static final int TEXT_PRIMARY = 0xFF2D2D2D;
    private static final int TEXT_SECONDARY = 0xFF666666;
    private static final int OK_COLOR = 0xFF228B22;
    private static final int WARN_COLOR = 0xFFFFAA00;
    private static final int SHADOW_LIGHT = 0x30FFFFFF;
    private static final int SHADOW_DARK = 0x40000000;

    private final Screen parent;
    private Button mainButton;
    private Button ipv6TestButton;
    private Button doneButton;
    private List<String> guaAddresses;
    private Instant lastSeenCheckedAt;
    private int scrollOffset = 0;

    public SingleplayerRelayControlScreen(Screen parent) {
        super(Component.translatable("gui.fireflymc.singleplayer_relay.control.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        this.guaAddresses = Ipv6AddressCollector.collectGlobalIpv6();
        this.lastSeenCheckedAt = currentCheckedAt();

        int bw = 140, bh = 20;
        int cx = this.width / 2;
        mainButton = Button.builder(mainButtonLabel(), b -> onMain())
                .bounds(cx - bw / 2, 0, bw, bh).build();
        ipv6TestButton = Button.builder(testButtonLabel(), b -> onTestIpv6())
                .bounds(cx - bw / 2, 0, bw, bh).build();
        this.doneButton = Button.builder(Component.translatable("gui.fireflymc.singleplayer_relay.action.done"),
                b -> onClose()).bounds(cx - bw / 2, 0, bw, bh).build();
        addRenderableWidget(mainButton);
        addRenderableWidget(ipv6TestButton);
        addRenderableWidget(doneButton);
        relayout();
    }

    private void relayout() {
        // 滚动模型:标题区固定 56px,底部按钮区固定 82px,中间内容区滚动
        int headerHeight = 56;
        int footerHeight = 82;
        int dialogHeight = Math.min(this.height - 24, 360);
        int dialogY = (this.height - dialogHeight) / 2;
        int footerY = dialogY + dialogHeight - footerHeight;
        if (mainButton != null) mainButton.setY(footerY + 4);
        if (ipv6TestButton != null) ipv6TestButton.setY(footerY + 28);
        if (doneButton != null) doneButton.setY(footerY + 54);
        this.headerHeight = headerHeight;
        this.dialogY = dialogY;
        this.dialogHeight = dialogHeight;
        this.viewportTop = dialogY + headerHeight;
        this.viewportBottom = footerY - 6;
    }

    private int headerHeight, dialogY, dialogHeight, viewportTop, viewportBottom;

    @Override
    public void tick() {
        super.tick();
        HostingState s = SingleplayerRelayManager.getInstance().getHostingState();
        mainButton.active = (s == HostingState.STOPPED || s == HostingState.HOSTING);
        mainButton.setMessage(mainButtonLabel());

        Ipv6ProbeSnapshot snap = Ipv6ConnectivityChecker.getInstance().snapshot();
        boolean enabled = Config.CLIENT.IPV6_PROBE_ENABLED.get();
        ipv6TestButton.active = enabled && !snap.probing();
        ipv6TestButton.setMessage(testButtonLabel());

        // probing 完成(probing true→false 且 checkedAt 变化)时刷新 GUA
        if (lastSeenCheckedAt == null && snap.lastResult() != null
                || (lastSeenCheckedAt != null && snap.lastResult() != null
                    && !lastSeenCheckedAt.equals(snap.lastResult().checkedAt()))) {
            this.guaAddresses = Ipv6AddressCollector.collectGlobalIpv6();
        }
        lastSeenCheckedAt = currentCheckedAt();
    }

    private Instant currentCheckedAt() {
        Ipv6ProbeResult r = Ipv6ConnectivityChecker.getInstance().snapshot().lastResult();
        return r == null ? null : r.checkedAt();
    }

    private Component mainButtonLabel() {
        HostingState s = SingleplayerRelayManager.getInstance().getHostingState();
        return switch (s) {
            case STOPPED -> Component.translatable("gui.fireflymc.singleplayer_relay.action.start");
            case STARTING -> Component.translatable("gui.fireflymc.singleplayer_relay.action.starting");
            case HOSTING -> Component.translatable("gui.fireflymc.singleplayer_relay.action.stop");
            case STOPPING -> Component.translatable("gui.fireflymc.singleplayer_relay.action.stopping");
        };
    }

    private Component testButtonLabel() {
        boolean probing = Ipv6ConnectivityChecker.getInstance().snapshot().probing();
        return probing
                ? Component.translatable("gui.fireflymc.ipv6.action.testing")
                : Component.translatable("gui.fireflymc.ipv6.action.test");
    }

    private void onMain() {
        SingleplayerRelayManager m = SingleplayerRelayManager.getInstance();
        HostingState s = m.getHostingState();
        if (s == HostingState.STOPPED) m.startHosting();
        else if (s == HostingState.HOSTING) m.stopHosting();
    }

    private void onTestIpv6() {
        Ipv6ConnectivityChecker.getInstance().checkAsync(true);
    }

    @Override
    public boolean shouldCloseOnEsc() { return true; }

    @Override
    public void onClose() {
        if (minecraft != null && minecraft.level != null) minecraft.setScreen(parent);
        else if (minecraft != null) minecraft.setScreen(null);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int contentHeight = estimateContentHeight();
        int viewportHeight = viewportBottom - viewportTop;
        int maxScroll = Math.max(0, contentHeight - viewportHeight);
        scrollOffset = (int) Math.max(0, Math.min(maxScroll, scrollOffset - scrollY * 12));
        return true;
    }

    private int estimateContentHeight() {
        HostingState s = SingleplayerRelayManager.getInstance().getHostingState();
        int relayHeight = s == HostingState.HOSTING ? 50 : 14;

        Ipv6ProbeSnapshot snap = Ipv6ConnectivityChecker.getInstance().snapshot();
        boolean enabled = Config.CLIENT.IPV6_PROBE_ENABLED.get();
        int ipv6Height;
        if (!enabled || snap.probing() || snap.lastResult() == null) {
            ipv6Height = 28;
        } else {
            int guaRows = guaAddresses.isEmpty() ? 1 : Math.min(2, guaAddresses.size());
            if (guaAddresses.size() > 2) guaRows++;
            int hintRows = 3;
            ipv6Height = 28 + 14 + 12 + guaRows * 12 + 6 + hintRows * 12;
        }
        return 8 + relayHeight + 8 + ipv6Height;
    }

    @Override
    public void renderBackground(@Nonnull GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 不调用 super,避免额外遮罩
    }

    @Override
    public void render(@Nonnull GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int dialogWidth = Math.min(380, this.width - 24);
        int dialogHeight = this.dialogHeight;
        int dialogX = (this.width - dialogWidth) / 2;
        int dialogY = this.dialogY;

        drawRoundedRect(g, dialogX + 6, dialogY + 6, dialogWidth, dialogHeight, 10, SHADOW_DARK);
        drawFrostedGlassBackground(g, dialogX, dialogY, dialogWidth, dialogHeight, 10);
        drawGradientBorder(g, dialogX, dialogY, dialogWidth, dialogHeight, 10);

        Component title = Component.translatable("gui.fireflymc.singleplayer_relay.control.title");
        int titleX = this.width / 2 - this.font.width(title) / 2;
        g.drawString(this.font, title.getVisualOrderText(), (float) titleX, (float) (dialogY + 20), ACCENT_SECONDARY, false);
        int sepY = dialogY + 48;
        drawGradientLine(g, dialogX + 20, sepY, dialogX + dialogWidth - 20, sepY, ACCENT_PRIMARY, ACCENT_SECONDARY);

        // 中间内容区:scissor 裁剪 + scrollOffset
        g.enableScissor(dialogX, viewportTop, dialogX + dialogWidth, viewportBottom);
        int y = viewportTop + 8 - scrollOffset;
        y = renderRelaySection(g, dialogX + 20, y, dialogWidth - 40);
        y += 8;
        y = renderIpv6Section(g, dialogX + 20, y, dialogWidth - 40);
        g.disableScissor();

        super.render(g, mouseX, mouseY, partialTick);
    }

    private int renderRelaySection(GuiGraphics g, int x, int y, int w) {
        HostingState s = SingleplayerRelayManager.getInstance().getHostingState();
        g.drawString(this.font, Component.translatable("gui.fireflymc.singleplayer_relay.state." + s.name().toLowerCase(Locale.ROOT)),
                x, y, stateColor(s), false);
        y += 14;
        if (s == HostingState.HOSTING) {
            String roomId = SingleplayerRelayManager.getInstance().getCurrentRoomId();
            String roomIdText = roomId == null
                    ? Component.translatable("gui.fireflymc.singleplayer_relay.room_id.pending").getString()
                    : Component.translatable("gui.fireflymc.singleplayer_relay.room_id", abbreviate(roomId, w)).getString();
            g.drawString(this.font, roomIdText, x, y, TEXT_SECONDARY, false);
            y += 12;
            int port = ClientState.singleplayerRelayLanPort;
            g.drawString(this.font, Component.translatable("gui.fireflymc.singleplayer_relay.lan_port", port).getString(),
                    x, y, TEXT_SECONDARY, false);
            y += 12;
            g.drawString(this.font, Component.translatable("gui.fireflymc.singleplayer_relay.max_players",
                    RelayConfig.RELAY.SINGLEPLAYER_RELAY_MAX_PLAYERS.get()).getString(), x, y, TEXT_SECONDARY, false);
            y += 12;
        }
        return y;
    }

    private int renderIpv6Section(GuiGraphics g, int x, int y, int w) {
        Ipv6ProbeSnapshot snap = Ipv6ConnectivityChecker.getInstance().snapshot();
        boolean enabled = Config.CLIENT.IPV6_PROBE_ENABLED.get();
        if (!enabled) {
            g.drawString(this.font, Component.translatable("gui.fireflymc.ipv6.disabled"), x, y, WARN_COLOR, false);
            g.drawString(this.font, Component.translatable("gui.fireflymc.ipv6.subtitle.disabled"), x, y + 12, TEXT_SECONDARY, false);
            return y + 28;
        }
        if (snap.probing()) {
            g.drawString(this.font, Component.translatable("gui.fireflymc.ipv6.action.testing"), x, y, ACCENT_SECONDARY, false);
            if (snap.lastResult() != null) {
                g.drawString(this.font, Component.translatable("gui.fireflymc.ipv6.probing_with_last",
                        previousLabel(snap.lastResult().status())).getString(), x, y + 12, TEXT_SECONDARY, false);
            }
            return y + 28;
        }
        Ipv6ProbeResult r = snap.lastResult();
        if (r == null) {
            g.drawString(this.font, Component.translatable("gui.fireflymc.ipv6.not_detected"), x, y, TEXT_PRIMARY, false);
            g.drawString(this.font, Component.translatable("gui.fireflymc.ipv6.subtitle.not_detected"), x, y + 12, TEXT_SECONDARY, false);
            return y + 28;
        }
        g.drawString(this.font, statusMainKey(r.status()).getString(), x, y, statusColor(r.status()), false);
        g.drawString(this.font, statusSubtitleKey(r.status(), r.httpStatus()).getString(), x, y + 12, TEXT_SECONDARY, false);
        y += 28;
        g.drawString(this.font, Component.translatable("gui.fireflymc.ipv6.last_check",
                relativeTime(r.checkedAt()), r.durationMs()).getString(), x, y, TEXT_SECONDARY, false);
        y += 14;

        g.drawString(this.font, Component.translatable("gui.fireflymc.ipv6.gua.label"), x, y, TEXT_PRIMARY, false);
        y += 12;
        if (guaAddresses.isEmpty()) {
            g.drawString(this.font, Component.translatable("gui.fireflymc.ipv6.gua.none"), x + 8, y, TEXT_SECONDARY, false);
            y += 12;
        } else {
            int shown = Math.min(2, guaAddresses.size());
            for (int i = 0; i < shown; i++) {
                g.drawString(this.font, abbreviate(guaAddresses.get(i), w - 8), x + 8, y, TEXT_SECONDARY, false);
                y += 12;
            }
            if (guaAddresses.size() > 2) {
                g.drawString(this.font, Component.translatable("gui.fireflymc.ipv6.gua.more",
                        guaAddresses.size() - 2).getString(), x + 8, y, TEXT_SECONDARY, false);
                y += 12;
            }
        }
        y += 6;
        // 条件提示行(font.split 自动换行)
        Component hint = hintForCurrent();
        for (var line : this.font.split(hint, w)) {
            g.drawString(this.font, line, x, y, TEXT_SECONDARY, false);
            y += 12;
        }
        return y;
    }

    private Component hintForCurrent() {
        HostingState hs = SingleplayerRelayManager.getInstance().getHostingState();
        if (hs != HostingState.HOSTING) {
            return Component.translatable("gui.fireflymc.ipv6.hint.idle");
        }
        Ipv6ProbeResult r = Ipv6ConnectivityChecker.getInstance().snapshot().lastResult();
        if (r == null) return Component.translatable("gui.fireflymc.ipv6.hint.idle");
        return switch (r.status()) {
            case AVAILABLE -> Component.translatable("gui.fireflymc.ipv6.hint.available");
            case DNS_FAILED, CONNECT_FAILED, CONNECT_TIMEOUT ->
                    Component.translatable("gui.fireflymc.ipv6.hint.not_detected");
            default -> Component.translatable("gui.fireflymc.ipv6.hint.probe_failed");
        };
    }

    private String previousLabel(Ipv6ProbeStatus s) {
        return switch (s) {
            case AVAILABLE -> Component.translatable("gui.fireflymc.ipv6.previous.available").getString();
            case DNS_FAILED, CONNECT_FAILED, CONNECT_TIMEOUT ->
                    Component.translatable("gui.fireflymc.ipv6.previous.not_detected").getString();
            default -> Component.translatable("gui.fireflymc.ipv6.previous.probe_failed").getString();
        };
    }

    private Component statusMainKey(Ipv6ProbeStatus s) {
        return Component.translatable("gui.fireflymc.ipv6." + statusKey(s));
    }

    private Component statusSubtitleKey(Ipv6ProbeStatus s, Integer httpStatus) {
        if (s == Ipv6ProbeStatus.HTTP_FAILED && httpStatus != null) {
            return Component.translatable("gui.fireflymc.ipv6.subtitle.http_failed", httpStatus);
        }
        return Component.translatable("gui.fireflymc.ipv6.subtitle." + statusKey(s));
    }

    private String statusKey(Ipv6ProbeStatus s) {
        return switch (s) {
            case AVAILABLE -> "available";
            case DNS_FAILED -> "dns_failed";
            case CONNECT_FAILED -> "connect_failed";
            case CONNECT_TIMEOUT -> "connect_timeout";
            case TLS_FAILED -> "tls_failed";
            case HTTP_FAILED -> "http_failed";
            case UNKNOWN -> "unknown";
        };
    }

    private int statusColor(Ipv6ProbeStatus s) {
        return switch (s) {
            case AVAILABLE -> OK_COLOR;
            case DNS_FAILED, CONNECT_FAILED, CONNECT_TIMEOUT -> WARN_COLOR;
            default -> 0xFFCC0000;
        };
    }

    private int stateColor(HostingState s) {
        return s == HostingState.HOSTING ? OK_COLOR : (s == HostingState.STARTING || s == HostingState.STOPPING ? WARN_COLOR : TEXT_PRIMARY);
    }

    private String relativeTime(Instant t) {
        long mins = Duration.between(t, Instant.now()).toMinutes();
        if (mins < 1) return Component.translatable("gui.fireflymc.time.just_now").getString();
        if (mins < 60) return Component.translatable("gui.fireflymc.time.minutes_ago", mins).getString();
        return Component.translatable("gui.fireflymc.time.hours_ago", mins / 60).getString();
    }

    private String abbreviate(String s, int maxWidth) {
        int maxChars = Math.max(8, maxWidth / 6);
        return s.length() <= maxChars ? s : s.substring(0, maxChars - 1) + "…";
    }

    // —— 粉色毛玻璃绘制工具(复用 SingleplayerSharePromptScreen 风格) ——
    private void drawRoundedRect(GuiGraphics g, int x, int y, int w, int h, int r, int c) {
        g.fill(x + r, y, x + w - r, y + h, c);
        g.fill(x, y + r, x + w, y + h - r, c);
        g.fill(x + r, y, x + w - r, y + r, c);
        g.fill(x + r, y + h - r, x + w - r, y + h, c);
        fillCircle(g, x + r, y + r, r, c);
        fillCircle(g, x + w - r, y + r, r, c);
        fillCircle(g, x + r, y + h - r, r, c);
        fillCircle(g, x + w - r, y + h - r, r, c);
    }
    private void fillCircle(GuiGraphics g, int cx, int cy, int r, int c) {
        for (int i = -r; i <= r; i++) for (int j = -r; j <= r; j++)
            if (i * i + j * j <= r * r) g.fill(cx + i, cy + j, cx + i + 1, cy + j + 1, c);
    }
    private void drawFrostedGlassBackground(GuiGraphics g, int x, int y, int w, int h, int r) {
        drawRoundedRect(g, x, y, w, h, r, 0xDDFAFAFA);
        drawRoundedRect(g, x + 1, y + 1, w - 2, h - 2, r - 1, 0x40FFFFFF);
        drawRoundedRect(g, x + 2, y + 2, w - 4, h / 2 - 2, r - 2, SHADOW_LIGHT);
    }
    private void drawGradientBorder(GuiGraphics g, int x, int y, int w, int h, int r) {
        for (int i = 0; i < 3; i++) {
            float ratio = i / 2f;
            int c = lerpColor(ACCENT_PRIMARY, ACCENT_SECONDARY, ratio);
            g.fill(x + r, y + i, x + w - r, y + i + 1, c);
        }
        for (int i = 0; i < 3; i++) {
            float ratio = i / 2f;
            int c = lerpColor(ACCENT_SECONDARY, ACCENT_PRIMARY, ratio);
            g.fill(x + r, y + h - 3 + i, x + w - r, y + h - 2 + i, c);
        }
        for (int i = 0; i < 3; i++) g.fill(x + i, y + r, x + i + 1, y + h - r, ACCENT_PRIMARY);
        for (int i = 0; i < 3; i++) g.fill(x + w - 3 + i, y + r, x + w - 2 + i, y + h - r, ACCENT_SECONDARY);
    }
    private void drawGradientLine(GuiGraphics g, int x1, int y, int x2, int y2, int c1, int c2) {
        int len = x2 - x1;
        for (int i = 0; i < len; i++) {
            int c = lerpColor(c1, c2, i / (float) len);
            g.fill(x1 + i, y, x1 + i + 1, y + 1, c);
        }
    }
    private int lerpColor(int c1, int c2, float ratio) {
        int a1 = (c1 >> 24) & 0xFF, r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int a2 = (c2 >> 24) & 0xFF, r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;
        return ((int)(a1 + (a2 - a1) * ratio) << 24) | ((int)(r1 + (r2 - r1) * ratio) << 16)
                | ((int)(g1 + (g2 - g1) * ratio) << 8) | (int)(b1 + (b2 - b1) * ratio);
    }
}
