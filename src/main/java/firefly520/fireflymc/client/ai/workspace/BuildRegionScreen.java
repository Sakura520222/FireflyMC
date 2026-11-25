package firefly520.fireflymc.client.ai.workspace;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.network.AiProtocol;
import firefly520.fireflymc.client.ai.AiClientNetwork;
import firefly520.fireflymc.client.ai.blueprint.BlueprintClientController;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.List;

/** The normal path reviews a server blueprint location; coordinate editing is an optional advanced screen. */
public final class BuildRegionScreen extends Screen {
    private final Screen parent;
    private final BlueprintClientController controller = BlueprintClientController.INSTANCE;
    private Button confirm, plan;
    private String loadedProposal = "";
    private int scroll, contentHeight;
    public BuildRegionScreen(Screen parent) { super(Component.literal("建筑方案 · 世界预览与位置确认")); this.parent = parent; }
    @Override protected void init() {
        int cell = Math.max(34, (width - 36) / 4);
        button("世界审阅", 12, 34, cell, () -> { controller.previewProposal(proposal()); minecraft.setScreen(null); });
        confirm = button("确认此位置", 16 + cell, 34, cell, () -> controller.confirmProposal(proposal()));
        button("更新现场", 20 + cell * 2, 34, cell, () -> controller.proposeRegion(false));
        button("高级调整", 24 + cell * 3, 34, cell, () -> minecraft.setScreen(new ManualBuildRegionScreen(this)));
        button("移到本人前方", 12, 56, cell, () -> controller.proposeRegion(true));
        button("旋转 90°", 16 + cell, 56, cell, controller::rotateProposal);
        button("私人蓝图", 20 + cell * 2, 56, cell, () -> minecraft.setScreen(new BlueprintBrowserScreen(this)));
        button("返回", 24 + cell * 3, 56, cell, this::onClose);
        plan = button("位置已确认 · 请求施工物料审批", 12, height - 28, Math.max(80, width - 24), this::requestPlan);
        AiClientNetwork.send(AiProtocol.Action.BUILD_SELECT, new JsonObject());
    }
    private Button button(String text, int x, int y, int width, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), b -> action.run()).bounds(x, y, width, 18).build());
    }
    private JsonObject proposal() {
        var data = AiClientNetwork.STATE.buildRegionView;
        return data.has("proposal") && data.get("proposal").isJsonObject() ? data.getAsJsonObject("proposal") : new JsonObject();
    }
    @Override public void tick() {
        if (!AiClientNetwork.STATE.ready()) { onClose(); return; }
        var candidate = proposal();
        if (!candidate.isEmpty() && !candidate.get("hash").getAsString().equals(loadedProposal)) {
            loadedProposal = candidate.get("hash").getAsString(); controller.previewProposal(candidate);
        }
        boolean ready = controller.proposalReady(candidate);
        boolean confirmed = !candidate.isEmpty() && candidate.has("regionConfirmed") && candidate.get("regionConfirmed").getAsBoolean();
        confirm.active = ready && !confirmed; plan.active = ready && confirmed;
    }
    private void requestPlan() {
        var candidate = proposal();
        if (!controller.proposalReady(candidate) || !candidate.has("regionConfirmed") || !candidate.get("regionConfirmed").getAsBoolean()) return;
        AiClientNetwork.submit("请为我已在世界预览并确认位置的固定蓝图生成 build_plan。blueprintId="
                + candidate.get("blueprintId").getAsString() + "，revision=" + candidate.get("blueprintRevision").getAsLong()
                + "，blueprintHash=" + candidate.get("blueprintHash").getAsString()
                + "。请展示真实物料与施工风险并请求工作台审批；位置确认不代表扣料或施工批准。");
        minecraft.setScreen(parent);
    }
    @Override public void renderBackground(GuiGraphics graphics, int x, int y, float partial) { }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        graphics.fill(0, 0, width, height, 0xd8141d2a);
        graphics.drawCenteredString(font, title, width / 2, 13, 0xffeed098);
        graphics.enableScissor(12, 84, width - 12, height - 48);
        int y = 0; var candidate = proposal();
        if (candidate.isEmpty()) {
            y = text(graphics, "先让小萤生成建筑方案，服务端会自动给出前方位置候选。也可到私人蓝图选择已有方案并提出位置。\n正常流程无需手填坐标或选两角点。", y);
        } else {
            if (candidate.has("blueprintCurrent") && !candidate.get("blueprintCurrent").getAsBoolean())
                y = text(graphics, "蓝图已有新版本，此旧候选已失效。请到私人蓝图选择最新版本并重新预览位置。", y);
            y = text(graphics, "蓝图 v" + candidate.get("blueprintRevision") + " · " + candidate.get("dimension").getAsString()
                    + "\n" + (candidate.get("regionConfirmed").getAsBoolean() ? "此位置已确认，下一步查看真实物料并审批施工。" : "候选尚未确认。先回世界绕行审阅幽灵建筑，再确认此固定版本的位置。"), y);
            var low = candidate.getAsJsonObject("first"); var high = candidate.getAsJsonObject("second");
            String size = ""; for (String axis : List.of("x", "y", "z")) size += (size.isEmpty() ? "" : " × ")
                    + (high.get(axis).getAsInt() - low.get(axis).getAsInt() + 1);
            y = text(graphics, "候选范围 " + size + "\n起点 " + point(low) + " · 终点 " + point(high), y);
            var survey = candidate.getAsJsonObject("survey");
            y = text(graphics, "现场占用 " + survey.get("occupied") + " · 流体 " + survey.get("fluids") + " · 数据方块 " + survey.get("dataBlocks")
                    + "\n保护拒绝 " + survey.get("protected") + " · 未加载 " + survey.get("unloaded")
                    + "\n容器内容未读取。现有占用不会因位置确认而被清除。", y);
            y = text(graphics, controller.status + "\n绿色为新增，橙色为拆除/替换，红色为保护或数据冲突；灰蓝色尚未观察。", y);
        }
        y = text(graphics, "位置确认只确定蓝图版本、位置和范围；不会扣料、拆除或施工。移动、旋转或现场变化后需要重新预览确认。", y);
        contentHeight = y; graphics.disableScissor(); scroll = Math.clamp(scroll, 0, bar().maximum());
        var bar = bar(); if (bar.visible()) {
            graphics.fill(width - 10, bar.top(), width - 5, bar.top() + bar.height(), 0xff263447);
            graphics.fill(width - 10, bar.thumbTop(), width - 5, bar.thumbTop() + bar.thumbHeight(), 0xffa7b9ca);
        }
        graphics.drawString(font, font.plainSubstrByWidth(AiClientNetwork.STATE.error, Math.max(30, width - 24)), 12, height - 43, 0xffeead98, false);
        super.render(graphics, mouseX, mouseY, partial);
    }
    private static String point(JsonObject p) { return p.get("x") + ", " + p.get("y") + ", " + p.get("z"); }
    private int text(GuiGraphics graphics, String text, int offset) {
        var lines = font.split(Component.literal(text), Math.max(40, width - 40)); int y = 88 + offset - scroll;
        for (var line : lines) { if (y + 11 >= 84 && y < height - 48) graphics.drawString(font, line, 16, y, 0xffdce5ef, false); y += 11; }
        return offset + lines.size() * 11 + 10;
    }
    private ScrollGeometry bar() { return ScrollGeometry.of(84, Math.max(1, height - 132), contentHeight, scroll); }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { scroll = Math.clamp(scroll - (int) (vertical * 26), 0, bar().maximum()); return true; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(AiClientNetwork.STATE.ready() ? parent : null); }
}
