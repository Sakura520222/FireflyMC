package firefly520.fireflymc.client.ai.workspace;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.blueprint.BlueprintDocument.Position;
import firefly520.fireflymc.ai.network.AiProtocol;
import firefly520.fireflymc.client.ai.AiClientNetwork;
import firefly520.fireflymc.client.ai.blueprint.*;
import firefly520.fireflymc.client.ai.blueprint.RegionDragMath.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Transparent workbench subview. Mouse gestures edit a local proposal, never the world. */
public final class BuildRegionDragScreen extends Screen {
    private final Screen parent;
    private final RegionDragDraft draft;
    private RegionDragProjection frame;
    private Axis axis = Axis.FREE;
    private int dragging = -1;
    private Position initial;
    private Point anchor, normal, center;
    private String error = "";
    private Button save;
    public BuildRegionDragScreen(Screen parent) {
        super(Component.literal("拖拽施工区域")); this.parent = parent;
        var state = AiClientNetwork.STATE;
        draft = new RegionDragDraft(state.buildRegionView, state.session, state.serverId, state.conversationId);
    }
    @Override protected void init() {
        dragging = -1; frame = null;
        int cell = Math.max(20, (width - 36) / 6);
        for (var mode : Axis.values()) {
            int x = 10 + mode.ordinal() * (cell + 3);
            addRenderableWidget(Button.builder(Component.literal(mode == Axis.FREE ? "自由" : mode.name() + " 轴"), button -> {
                axis = mode; dragging = -1; error = "";
            }).bounds(x, height - 27, cell, 20).build());
        }
        save = addRenderableWidget(Button.builder(Component.literal("保存草稿"), button -> save()).bounds(10 + 4 * (cell + 3), height - 27, cell, 20).build());
        addRenderableWidget(Button.builder(Component.literal("返回"), button -> onClose()).bounds(10 + 5 * (cell + 3), height - 27, cell, 20).build());
    }
    private boolean current() {
        var state = AiClientNetwork.STATE;
        return state.ready() && minecraft != null && minecraft.level != null
                && draft.matches(state.buildRegionView, state.session, state.serverId, state.conversationId)
                && draft.preview().get("dimension").getAsString().equals(minecraft.level.dimension().location().toString());
    }
    public JsonObject preview() { return current() ? draft.preview() : AiClientNetwork.STATE.buildRegionView; }
    public void frame(RegionDragProjection frame) { if (current()) this.frame = frame; }
    private boolean usableFrame() { return current() && frame != null && frame.fresh(width, height); }
    private static Point center(Position p) { return new Point(p.x() + .5, p.y() + .5, p.z() + .5); }
    private java.util.Optional<RegionDragProjection.ScreenPoint> handle(int index) {
        var point = frame.project(center(draft.corner(index)));
        var other = frame.project(center(draft.corner(1 - index)));
        if (point.isPresent() && other.isPresent()) {
            double dx = point.get().x() - other.get().x(), dy = point.get().y() - other.get().y();
            if (dx * dx + dy * dy < 20 * 20) return java.util.Optional.of(new RegionDragProjection.ScreenPoint(
                    point.get().x() + (index == 0 ? -12 : 12), point.get().y(), point.get().depth()));
        }
        return point;
    }
    private void save() {
        if (!current()) { error = "服务端选区或连接已改变，请返回后重新打开。"; return; }
        var state = AiClientNetwork.STATE;
        AiClientNetwork.send(AiProtocol.Action.BUILD_SELECT, draft.request(state.buildRegionView, state.session, state.serverId, state.conversationId));
        state.notice = "选区草稿已发送，等待服务端核验；通过后仍需确认区域。";
        onClose();
    }
    @Override public void tick() {
        if (!AiClientNetwork.STATE.ready()) { minecraft.setScreen(null); return; }
        save.active = current();
        if (!current()) { dragging = -1; error = "服务端选区或连接已改变，旧草稿不能保存；请返回重新打开。"; }
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (super.mouseClicked(x, y, button)) return true;
        if (button != 0 || y < 67 || y > height - 33 || !usableFrame()) return false;
        int selected = -1; double closest = 15 * 15;
        for (int i = 0; i < 2; i++) {
            var point = handle(i);
            if (point.isEmpty()) continue;
            double dx = point.get().x() - x, dy = point.get().y() - y, distance = dx * dx + dy * dy;
            if (distance < closest) { closest = distance; selected = i; }
        }
        if (selected < 0) return false;
        try {
            initial = draft.corner(selected); center = center(initial); normal = frame.ray(width / 2.0, height / 2.0).direction();
            var hit = RegionDragMath.anchor(frame.ray(x, y), center, axis, normal);
            if (hit.isEmpty()) { error = "视线与该轴接近平行，请换轴或返回世界调整视角。"; return true; }
            dragging = selected; anchor = hit.get(); error = "";
        } catch (IllegalArgumentException unavailable) { error = "当前渲染投影不支持拖拽，请返回使用坐标编辑。"; }
        return true;
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button != 0 || dragging < 0) return super.mouseDragged(x, y, button, dx, dy);
        if (!usableFrame()) { dragging = -1; error = "视图已改变，请重新选中手柄。"; return true; }
        try {
            var hit = RegionDragMath.anchor(frame.ray(x, y), center, axis, normal);
            if (hit.isEmpty()) { error = "当前视角不能确定这个方向，请换轴。"; return true; }
            var delta = hit.get().subtract(anchor);
            var next = new Position(Math.toIntExact((long) initial.x() + Math.round(delta.x())),
                    Math.toIntExact((long) initial.y() + Math.round(delta.y())), Math.toIntExact((long) initial.z() + Math.round(delta.z())));
            if (minecraft.level.isOutsideBuildHeight(next.y())) throw new IllegalArgumentException("WORLD_HEIGHT");
            draft.move(dragging, next); error = "";
        } catch (IllegalArgumentException | ArithmeticException invalid) { error = "移动超出世界高度、128 格边长或 16,384 格体积，已保留上个有效位置。"; }
        return true;
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button == 0 && dragging >= 0) { dragging = -1; return true; }
        return super.mouseReleased(x, y, button);
    }
    @Override public void renderBackground(GuiGraphics graphics, int x, int y, float partial) { }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        graphics.fill(0, 0, width, 64, 0xc0141d2a); graphics.fill(0, height - 33, width, height, 0xd0141d2a);
        graphics.drawCenteredString(font, title, width / 2, 7, 0xffeed098);
        graphics.drawString(font, font.plainSubstrByWidth("拖动 ① / ②；方向：" + (axis == Axis.FREE ? "自由拖动" : axis.name() + " 轴") + " · 草稿尚未保存", width - 20), 10, 22, 0xffdce5ef, false);
        graphics.drawString(font, font.plainSubstrByWidth("保存只提交选区，仍需服务端核验和重新确认。", width - 20), 10, 35, 0xffb4c3d0, false);
        if (current()) {
            var bounds = draft.preview().getAsJsonObject("bounds");
            graphics.drawString(font, font.plainSubstrByWidth("① " + coordinates(draft.corner(0)) + "   ② " + coordinates(draft.corner(1)) + " · " + bounds.get("volume") + " 格", width - 20), 10, 48, 0xffb4c3d0, false);
        }
        int visible = 0;
        if (usableFrame()) for (int i = 0; i < 2; i++) {
            var projected = handle(i); if (projected.isEmpty()) continue;
            int x = (int) projected.get().x(), y = (int) projected.get().y();
            if (y < 75 || y >= height - 43) continue;
            int color = i == 0 ? 0xff83c5ff : 0xffe6b2ff;
            graphics.fill(x - 9, y - 9, x + 10, y + 10, dragging == i ? 0xff466573 : 0xc0141d2a);
            graphics.renderOutline(x - 9, y - 9, 19, 19, color);
            graphics.drawCenteredString(font, Integer.toString(i + 1), x, y - 4, color); visible++;
        }
        String status = !error.isEmpty() ? error : visible == 0 ? "手柄不在视野内；返回世界调整视角，再从工作台进入拖拽。" : "Esc 返回并放弃本次未保存拖动";
        graphics.drawCenteredString(font, font.plainSubstrByWidth(status, width - 16), width / 2, height - 45, error.isEmpty() ? 0xffeed098 : 0xffffb4ab);
        super.render(graphics, mouseX, mouseY, partial);
    }
    private static String coordinates(Position p) { return p.x() + "," + p.y() + "," + p.z(); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(AiClientNetwork.STATE.ready() ? parent : null); }
}
