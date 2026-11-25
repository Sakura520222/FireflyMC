package firefly520.fireflymc.client.ai.workspace;

import com.mojang.math.Axis;
import firefly520.fireflymc.client.ai.integration.map.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explicit private coordinate selection, bounded route drafts and optional native map views. */
public final class MapWorkspaceScreen extends Screen {
    private enum Page { POINT, ROUTE, STATUS }
    private final Screen parent;
    private final MapViewBridge bridge;
    private final Consumer<MapPoint> submit;
    private final List<Button> selectedButtons = new ArrayList<>();
    private final List<Button> routePointButtons = new ArrayList<>();
    private Page page = Page.POINT;
    private EditBox label, dimension, x, y, z;
    private String labelDraft = "地点", dimensionDraft = "minecraft:overworld", xDraft = "0", yDraft = "", zDraft = "0";
    private String info = "只选取本人明确指定的地点；地图信息须由服务端另行核验。";
    private List<MapIntegrationPolicy.Status> statuses = List.of();
    private int routePage, ticks;
    public MapWorkspaceScreen(Screen parent, MapViewBridge bridge, Consumer<MapPoint> submit) {
        super(Component.literal("私人地点与路线"));
        this.parent = parent; this.bridge = Objects.requireNonNull(bridge); this.submit = Objects.requireNonNull(submit);
    }
    @Override protected void init() {
        selectedButtons.clear(); routePointButtons.clear(); label = dimension = x = y = z = null;
        int third = Math.max(52, (width - 32) / 3);
        button("地点", 12, 30, third, () -> switchPage(Page.POINT));
        button("路线", 16 + third, 30, third, () -> switchPage(Page.ROUTE));
        button("接口状态", 20 + third * 2, 30, third, () -> switchPage(Page.STATUS));
        switch (page) {
            case POINT -> initPoint(third);
            case ROUTE -> initRoute(third);
            case STATUS -> button("刷新状态", 12, 58, 100, this::refresh);
        }
        button("返回工作台", 12, height - 25, 106, this::onClose);
        var use = button("使用此点", width - 116, height - 25, 104, () -> run(() -> {
            bridge.confirmSelected(submit); info = "已提交这一地点到本人工作台；没有公开分享或发送其他地图内容。";
        }));
        selectedButtons.add(use); refresh(); updateSelected();
    }
    private void initPoint(int third) {
        button("当前位置", 12, 58, third, () -> run(() -> fill(bridge.currentPosition(labelDraft))));
        button("Xaero 选点", 16 + third, 58, third, () -> map(bridge.selectXaero(this)));
        button("读取单选路标", 20 + third * 2, 58, third, () -> map(bridge.readSelectedXaero()));
        button("打开世界地图", 12, 82, third, () -> map(bridge.openWorldMap(this)));
        button("选用地图中心", 16 + third, 82, third, () -> map(bridge.readWorldMapCenter()));
        button("手持地图中心", 20 + third * 2, 82, third, () -> map(bridge.readAtlasCenter()));
        button("打开手持地图册", 12, 106, third, () -> map(bridge.openAtlas()));
        var copy = button("复制坐标卡", 16 + third, 106, third, () -> run(() -> {
            minecraft.keyboardHandler.setClipboard(bridge.selected().orElseThrow(() -> new IllegalStateException("MAP_POINT_NOT_SELECTED")).coordinateCard());
            info = "已复制当前地点坐标卡。";
        }));
        var add = button("添加到路线", 20 + third * 2, 106, third, () -> run(() -> {
            bridge.appendSelected(); info = "已添加到私人路线草稿，尚未向服务器发送路线。";
        }));
        selectedButtons.add(copy); selectedButtons.add(add);
        if (height < 300) {
            button("手动明确选点", 12, 130, third, () -> minecraft.setScreen(new CoordinateEntry()));
            return;
        }
        int half = Math.max(60, (width - 28) / 2);
        label = input(12, 146, half, 80, "地点名称", labelDraft, value -> labelDraft = value);
        dimension = input(16 + half, 146, half, 160, "维度 ID", dimensionDraft, value -> dimensionDraft = value);
        int coordinateWidth = Math.max(40, (width - 110) / 3);
        x = input(12, 185, coordinateWidth, 11, "X", xDraft, value -> xDraft = value);
        y = input(16 + coordinateWidth, 185, coordinateWidth, 6, "Y，可留空", yDraft, value -> yDraft = value);
        z = input(20 + coordinateWidth * 2, 185, coordinateWidth, 11, "Z", zDraft, value -> zDraft = value);
        button("选用", width - 74, 185, 62, () -> run(() -> fill(bridge.manual(labelDraft, dimensionDraft,
                Integer.parseInt(xDraft.strip()), yDraft.isBlank() ? null : Integer.valueOf(yDraft.strip()), Integer.parseInt(zDraft.strip())))));
    }
    private void initRoute(int third) {
        button("添加所选点", 12, 58, third, () -> run(() -> { bridge.appendSelected(); info = "已添加所选地点。"; }));
        button("撤回最后一点", 16 + third, 58, third, () -> run(bridge::removeLast));
        button("清空路线", 20 + third * 2, 58, third, () -> run(() -> { bridge.clearRoute(); routePage = 0; }));
        int half = Math.max(70, (width - 28) / 2);
        button("登记私人临时路标", 12, height - 90, half, () -> map(bridge.publishMarkers()));
        button("撤回本模组路标", 16 + half, height - 90, half, () -> map(bridge.clearMarkers()));
        button("上一页", 12, height - 65, 64, () -> routePage = Math.max(0, routePage - 1));
        button("下一页", 80, height - 65, 64, () -> run(() -> {
            routePage = Math.min(Math.max(0, (bridge.route().points().size() - 1) / routeRows()), routePage + 1);
        }));
        for (int row = 0; row < routeRows(); row++) {
            int selectedRow = row;
            routePointButtons.add(button("选取", width - 70, routeBottom() + 5 + row * 20, 58, () -> run(() -> {
                int index = routePage * routeRows() + selectedRow; var points = bridge.route().points();
                if (index >= points.size()) throw new IllegalStateException("MAP_ROUTE_POINT_NOT_FOUND");
                fill(bridge.selectRoutePoint(points.get(index).id())); info = "已选择这一个路线点；尚未发送到工作台。";
            })));
        }
    }
    private int routeRows() { return height < 320 ? 1 : 3; }
    private int routeBottom() { return Math.max(98, height - (routeRows() * 20 + 108)); }
    private EditBox input(int x, int y, int width, int limit, String name, String value, Consumer<String> update) {
        var field = addRenderableWidget(new EditBox(font, x, y, width, 20, Component.literal(name)));
        field.setMaxLength(limit); field.setValue(value); field.setResponder(update); return field;
    }
    private Button button(String text, int x, int y, int width, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), ignored -> action.run()).bounds(x, y, width, 20).build());
    }
    private void switchPage(Page value) { page = value; rebuildWidgets(); }
    private void run(Runnable action) {
        try { action.run(); updateSelected(); }
        catch (NumberFormatException invalidNumber) { info = "请输入整数坐标；未知 Y 可以留空。"; }
        catch (RuntimeException failure) { info = MapViewBridge.message(failure.getMessage() == null ? "MAP_ACTION_FAILED" : failure.getMessage()); }
    }
    private void map(MapViewBridge.Outcome result) { info = result.message(); run(this::fillSelected); }
    private void fillSelected() { bridge.selected().ifPresent(this::fill); }
    private void fill(MapPoint point) {
        labelDraft = point.label(); dimensionDraft = point.dimension(); xDraft = Integer.toString(point.x());
        yDraft = point.y() == null ? "" : Integer.toString(point.y()); zDraft = Integer.toString(point.z());
        if (label != null) { label.setValue(labelDraft); dimension.setValue(dimensionDraft); x.setValue(xDraft); y.setValue(yDraft); z.setValue(zDraft); }
        updateSelected();
    }
    private void refresh() {
        try { statuses = bridge.statuses(); }
        catch (RuntimeException unavailable) { statuses = List.of(); info = "工作台连接不可用，请返回后重新打开。"; }
    }
    private void updateSelected() {
        boolean hasPoint;
        try { hasPoint = bridge.selected().isPresent(); } catch (RuntimeException unavailable) { hasPoint = false; }
        for (var button : selectedButtons) button.active = hasPoint;
    }
    @Override public void tick() { super.tick(); if (++ticks % 20 == 0) { refresh(); updateSelected(); } }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xee101923);
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, title, 12, 12, 0xffe3c16f, false);
        switch (page) {
            case POINT -> renderPoint(graphics);
            case ROUTE -> renderRoute(graphics);
            case STATUS -> renderStatus(graphics);
        }
        draw(graphics, info, 12, height - 38, width - 24, 0xffb7cbd5);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
    private void renderPoint(GuiGraphics graphics) {
        if (height >= 300) {
            graphics.drawString(font, "手动明确选点：名称 / 维度", 12, 133, 0xffb7cbd5, false);
            graphics.drawString(font, "X / Y（未知留空） / Z", 12, 172, 0xffb7cbd5, false);
        }
        try {
            var point = bridge.selected(); int top = height >= 300 ? 216 : 164;
            if (point.isEmpty()) { draw(graphics, "尚未选定地点。未安装地图模组时仍可用当前位置或手动坐标。", 12, top, width - 24, 0xffaaaaaa); return; }
            draw(graphics, point.get().label() + " · " + point.get().coordinates(), 12, top, width - 24, 0xffe3c16f);
            draw(graphics, point.get().sourceLabel() + " · CLIENT_REPORTED", 12, top + 14, width - 24, 0xff94c5bd);
            if (height >= 300 || height > 265) draw(graphics, "使用此点仅发送这一条私人坐标线索；不代表已核验地形、权限或通行。", 12, top + 28, width - 24, 0xffb7cbd5);
        } catch (RuntimeException unavailable) { /* The footer already describes the unavailable connection. */ }
    }
    private void renderRoute(GuiGraphics graphics) {
        try {
            var route = bridge.route();
            String dimension = bridge.selected().map(MapPoint::dimension).orElse(dimensionDraft);
            int top = 86, bottom = routeBottom();
            graphics.fill(12, top, width - 12, bottom, 0xff0b1118);
            graphics.enableScissor(13, top + 1, width - 13, bottom - 1);
            var view = MapProjection.project(route, dimension, 25, top + 18, Math.max(1, width - 50), Math.max(1, bottom - top - 34));
            for (var line : view.lines()) line(graphics, line.from(), line.to());
            for (var point : view.points()) {
                int px = (int) Math.round(point.x()), pz = (int) Math.round(point.z());
                graphics.fill(px - 2, pz - 2, px + 3, pz + 3, 0xff86d6bf);
                graphics.drawString(font, Integer.toString(point.routeIndex() + 1), px + 4, pz - 5, 0xffe3c16f, false);
            }
            graphics.disableScissor();
            draw(graphics, "北↑ · " + dimension + " · 直线示意，不代表可通行路线", 17, top + 4, width - 34, 0xffb7cbd5);
            int maximum = Math.max(0, (route.points().size() - 1) / routeRows()); routePage = Math.min(routePage, maximum);
            for (int row = 0; row < routeRows(); row++) {
                int index = routePage * routeRows() + row;
                if (row < routePointButtons.size()) routePointButtons.get(row).active = index < route.points().size();
                if (index >= route.points().size()) continue;
                var point = route.points().get(index); String tail = "";
                if (index > 0) {
                    var leg = route.legs().get(index - 1);
                    tail = leg.sameDimension() ? String.format(Locale.ROOT, " · 直线 %.1f m", leg.horizontalDistance()) : " · 跨维度段待核验";
                }
                draw(graphics, (index + 1) + ". " + point.label() + " · " + point.coordinates() + tail,
                        12, bottom + 10 + row * 20, width - 92, 0xffcfdae2);
            }
            draw(graphics, (route.points().isEmpty() ? "尚无路线点" : "第 " + (routePage + 1) + "/" + (maximum + 1) + " 页")
                    + " · " + route.points().size() + "/32 点", 155, height - 59, width - 170, 0xff94c5bd);
        } catch (RuntimeException unavailable) { draw(graphics, "工作台连接不可用。", 12, 90, width - 24, 0xffcf9c8e); }
    }
    private void line(GuiGraphics graphics, MapProjection.Point from, MapProjection.Point to) {
        double dx = to.x() - from.x(), dz = to.z() - from.z();
        graphics.pose().pushPose(); graphics.pose().translate(from.x(), from.z(), 0);
        graphics.pose().mulPose(Axis.ZP.rotation((float) Math.atan2(dz, dx)));
        graphics.fill(0, 0, Math.max(1, (int) Math.ceil(Math.hypot(dx, dz))), 1, 0xff597a93);
        graphics.pose().popPose();
    }
    private void renderStatus(GuiGraphics graphics) {
        int y = 88;
        for (var status : statuses) {
            draw(graphics, status.provider().label() + " · " + (status.installedVersion().isEmpty() ? "未安装" : status.installedVersion())
                    + " · " + status.state(), 12, y, width - 24, status.ready() ? 0xff86d6bf : 0xffd5b987);
            draw(graphics, status.reason(), 12, y + 14, width - 24, 0xffc3d0d9); y += 42;
        }
        draw(graphics, "接口匹配或本地就绪不等于图形组合验收通过。", 12, y + 8, width - 24, 0xffa9bac9);
        draw(graphics, "不读取地图图像、整份私人路标、缓存目录或其他玩家地图。", 12, y + 23, width - 24, 0xffa9bac9);
    }
    private void draw(GuiGraphics graphics, String text, int x, int y, int maximum, int color) {
        if (y < height - 27 && maximum > 0) graphics.drawString(font, font.plainSubstrByWidth(text, maximum), x, y, color, false);
    }
    private final class CoordinateEntry extends Screen {
        private String error = "只选定这一地点；返回后仍需点“使用此点”才发送工作台。";
        CoordinateEntry() { super(Component.literal("手动明确选点")); }
        @Override protected void init() {
            int half = Math.max(60, (width - 28) / 2), third = Math.max(45, (width - 32) / 3);
            field(12, 65, half, 80, "名称", labelDraft, value -> labelDraft = value);
            field(16 + half, 65, half, 160, "维度", dimensionDraft, value -> dimensionDraft = value);
            field(12, 110, third, 11, "X", xDraft, value -> xDraft = value);
            field(16 + third, 110, third, 6, "Y", yDraft, value -> yDraft = value);
            field(20 + third * 2, 110, third, 11, "Z", zDraft, value -> zDraft = value);
            addRenderableWidget(Button.builder(Component.literal("返回"), ignored -> onClose()).bounds(12, height - 25, 90, 20).build());
            addRenderableWidget(Button.builder(Component.literal("选定此点"), ignored -> {
                try {
                    var point = bridge.manual(labelDraft, dimensionDraft, Integer.parseInt(xDraft.strip()),
                            yDraft.isBlank() ? null : Integer.valueOf(yDraft.strip()), Integer.parseInt(zDraft.strip()));
                    fill(point); info = "已选定手动坐标；尚未发送给工作台。"; onClose();
                } catch (NumberFormatException invalid) { error = "请输入整数坐标；未知 Y 可留空。"; }
                catch (RuntimeException failure) { error = MapViewBridge.message(failure.getMessage()); }
            }).bounds(width - 112, height - 25, 100, 20).build());
        }
        private void field(int x, int y, int width, int limit, String name, String value, Consumer<String> update) {
            var field = addRenderableWidget(new EditBox(font, x, y, width, 20, Component.literal(name)));
            field.setMaxLength(limit); field.setValue(value); field.setResponder(update);
        }
        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(0, 0, width, height, 0xee101923);
            graphics.drawString(font, title, 12, 15, 0xffe3c16f, false);
            graphics.drawString(font, "地点名称 / 维度 ID", 12, 51, 0xffb7cbd5, false);
            graphics.drawString(font, "X / Y（未知留空） / Z", 12, 96, 0xffb7cbd5, false);
            graphics.drawWordWrap(font, Component.literal(error), 12, 150, Math.max(80, width - 24), 0xffb7cbd5);
            super.render(graphics, mouseX, mouseY, partialTick);
        }
        @Override public boolean isPauseScreen() { return false; }
        @Override public void onClose() { minecraft.setScreen(MapWorkspaceScreen.this); }
    }
}
