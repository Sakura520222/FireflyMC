package firefly520.fireflymc.client.ai.hud;

import firefly520.fireflymc.client.ai.AiClientNetwork;
import net.minecraft.client.*;
import net.minecraft.client.gui.GuiGraphics;

public final class CompanionStatusHud {
    private CompanionStatusHud() {}
    public static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        var state = AiClientNetwork.STATE;
        var settings = AiClientNetwork.display;
        boolean visible = state.ready() && !state.conversationId.isEmpty() && mc.player != null && mc.screen == null
                && !mc.options.hideGui;
        long active = state.tasks.stream().filter(t -> !java.util.Set.of("COMPLETED", "FAILED", "CANCELLED")
                .contains(t.get("state").getAsString())).count();
        if (settings.autoHide() && active == 0 && state.notice.isBlank()) visible = false;
        if (!visible) return;
        int viewportWidth = mc.getWindow().getGuiScaledWidth(), viewportHeight = mc.getWindow().getGuiScaledHeight();
        double scale = Math.min(settings.scale(), Math.min(viewportWidth / 210.0, viewportHeight / (settings.collapsed() ? 24.0 : 55.0)));
        int width = Math.min(viewportWidth, (int) (210 * scale));
        int height = (int) ((settings.collapsed() ? 24 : 55) * scale);
        int left = (int) ((viewportWidth - width) * settings.x());
        int top = (int) ((viewportHeight - height) * settings.y());
        graphics.pose().pushPose();
        graphics.pose().translate(left, top, 0);
        graphics.pose().scale((float) scale, (float) scale, 1);
        int background = ((int) (settings.opacity() * 255) << 24) | 0x151e2c;
        graphics.fill(0, 0, 210, settings.collapsed() ? 24 : 55, background);
        graphics.fill(0, 0, 3, settings.collapsed() ? 24 : 55, 0xffe4bc72);
        graphics.drawString(mc.font, state.name + " · " + active + " 个任务", 9, 8, 0xfff1e8d4, false);
        if (!settings.collapsed()) {
            String status = state.tasks.stream().filter(t -> !java.util.Set.of("COMPLETED", "FAILED", "CANCELLED")
                    .contains(t.get("state").getAsString())).findFirst()
                    .map(t -> t.get("state").getAsString()).orElse("已完成");
            if (!state.questions.isEmpty()) status = state.questions.size() + " 个待答问题 · " + status;
            graphics.drawString(mc.font, mc.font.plainSubstrByWidth(status, 194), 9, 24, 0xffa4d6c3, false);
            graphics.drawString(mc.font, "K 打开 AI 工作台", 9, 40, 0xff9eabbc, false);
        }
        graphics.pose().popPose();
    }
}
