package firefly520.fireflymc.client.ai.workspace;

import com.google.gson.*;
import firefly520.fireflymc.ai.network.AiProtocol;
import firefly520.fireflymc.client.ai.AiClientNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.util.*;

/** Workbench question detail. Selecting an answer never approves an operation. */
public final class TaskQuestionScreen extends Screen {
    private record Line(int y, FormattedCharSequence text, String choice) {}
    private final Screen parent;
    private final JsonObject question;
    private final List<Line> lines = new ArrayList<>();
    private MultiLineEditBox answer;
    private int scroll, totalHeight;
    public TaskQuestionScreen(Screen parent, JsonObject question) {
        super(Component.literal("任务问题"));
        this.parent = parent;
        this.question = question.deepCopy();
    }
    @Override protected void init() {
        String draft = answer == null ? "" : answer.getValue();
        lines.clear();
        int y = 0;
        for (var line : font.split(Component.literal(question.get("prompt").getAsString()), width - 48)) {
            lines.add(new Line(y, line, "")); y += 12;
        }
        y += 8;
        for (var choice : question.getAsJsonArray("choices")) {
            for (var line : font.split(Component.literal("○ " + choice.getAsString()), width - 48)) {
                lines.add(new Line(y, line, choice.getAsString())); y += 12;
            }
            y += 6;
        }
        totalHeight = y;
        answer = addRenderableWidget(new MultiLineEditBox(font, 20, height - 87, width - 40, 48,
                Component.literal("选择上方建议或输入自己的回答"), Component.literal("回答")));
        answer.setCharacterLimit(2048);
        boolean answered = question.has("answerRequestId") && !question.get("answerRequestId").isJsonNull();
        answer.setValue(answered ? question.get("answer").getAsString() : draft);
        answer.active = !answered;
        if (!answered) addRenderableWidget(Button.builder(Component.literal("发送回答"), button -> {
            if (answer.getValue().isBlank()) return;
            if (!AiClientNetwork.answer(firefly520.fireflymc.client.ai.QuestionReplyTarget.from(question), answer.getValue())) return;
            if (parent instanceof CompanionWorkspaceScreen workspace) workspace.messageSent();
            minecraft.setScreen(parent);
        }).bounds(20, height - 30, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("稍后回答"), button -> onClose())
                .bounds(width - 110, height - 30, 90, 20).build());
    }
    @Override public void renderBackground(GuiGraphics graphics, int x, int y, float tick) {}
    @Override public void render(GuiGraphics graphics, int x, int y, float tick) {
        graphics.fill(0, 0, width, height, 0xf0111925);
        graphics.drawString(font, title, 20, 12, 0xffefcf88, false);
        String scope = question.has("blockingScope") ? question.get("blockingScope").getAsString()
                : question.get("blocking").getAsBoolean() ? "TASK" : "ADVISORY";
        graphics.drawString(font, switch (scope) {
            case "STEPS" -> "仅相关步骤等待此回答；其他已授权独立操作可继续";
            case "TASK" -> "任务等待此回答；答案不代替操作审批";
            default -> "可稍后回答；建议不阻塞已授权独立操作";
        }, 20, 26, 0xffadbdc9, false);
        scroll = Math.clamp(scroll, 0, Math.max(0, totalHeight - Math.max(1, height - 138)));
        graphics.enableScissor(20, 42, width - 20, height - 94);
        for (Line line : lines) {
            int top = 42 + line.y() - scroll;
            if (top >= 30 && top < height - 94)
                graphics.drawString(font, line.text(), 24, top, line.choice().isEmpty() ? 0xffdbe3ef : 0xff9ad4b7, false);
        }
        graphics.disableScissor();
        super.render(graphics, x, y, tick);
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (super.mouseClicked(x, y, button)) return true;
        if (button == 0 && x >= 20 && x < width - 20 && y >= 42 && y < height - 94) {
            int local = (int) y - 42 + scroll;
            for (Line line : lines) if (!line.choice().isEmpty() && local >= line.y() && local < line.y() + 12) {
                answer.setValue(line.choice()); setFocused(answer); return true;
            }
        }
        return false;
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (y >= 42 && y < height - 94) { scroll = Math.max(0, scroll - (int) (vertical * 24)); return true; }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    @Override public void tick() { if (!AiClientNetwork.STATE.ready()) minecraft.setScreen(null); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
