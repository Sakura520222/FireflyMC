package firefly520.fireflymc.client.ai.workspace;

import com.google.gson.*;
import firefly520.fireflymc.ai.network.AiProtocol;
import firefly520.fireflymc.client.ai.AiClientNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Explicit player's own material protection; no model grant or global gameplay setting. */
public final class MaterialPreferencesScreen extends Screen {
    private final Screen parent;
    private EditBox input;
    private Button save;
    private long revision = -1;
    private String status = "正在读取本人规则…";
    public MaterialPreferencesScreen(Screen parent) { super(Component.literal("保留材料")); this.parent = parent; }
    @Override protected void init() {
        input = new EditBox(font, 24, 78, Math.max(60, width - 48), 24, Component.literal("保留的物品 ID"));
        input.setMaxLength(4096); input.setHint(Component.literal("minecraft:diamond, minecraft:netherite_ingot")); input.active = false;
        addRenderableWidget(input);
        save = addRenderableWidget(Button.builder(Component.literal("保存规则"), ignored -> save()).bounds(width / 2 + 8, height - 34, 100, 20).build()); save.active = false;
        addRenderableWidget(Button.builder(Component.literal("返回"), ignored -> onClose()).bounds(width / 2 - 108, height - 34, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("重新读取"), ignored -> {
            revision = -1; save.active = false; input.active = false; AiClientNetwork.send(AiProtocol.Action.MATERIAL_PREFERENCES, new JsonObject());
        }).bounds(24, 110, 100, 20).build());
        AiClientNetwork.send(AiProtocol.Action.MATERIAL_PREFERENCES, new JsonObject());
    }
    public void receive(JsonObject data) {
        revision = data.get("revision").getAsLong(); var ids = new ArrayList<String>(); data.getAsJsonArray("protectedItems").forEach(value -> ids.add(value.getAsString()));
        input.setValue(String.join(", ", ids)); input.active = true; save.active = true; status = "已读取 / 保存规则 · " + ids.size() + " 类物品";
    }
    private void save() {
        if (revision < 0) return;
        var ids = new TreeSet<String>();
        for (String value : input.getValue().split("[,，\\s]+")) if (!value.isBlank()) ids.add(value.strip());
        if (ids.size() > 128 || ids.stream().anyMatch(value -> !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))) { status = "请填有效物品 ID，最多 128 类，用逗号分隔。"; return; }
        var args = new JsonObject(); args.addProperty("revision", revision); args.add("protectedItems", new com.google.gson.Gson().toJsonTree(ids));
        save.active = false; status = "正在保存…"; AiClientNetwork.send(AiProtocol.Action.MATERIAL_PREFERENCES, args);
    }
    @Override public void renderBackground(GuiGraphics graphics, int x, int y, float partial) {}
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        graphics.fill(0, 0, width, height, 0xf0141d2a); graphics.drawCenteredString(font, title, width / 2, 16, 0xffe8cb8b);
        int y = 38;
        for (var line : font.split(Component.literal("这些物品不会被 AI 后续取料。已托管的任务请先暂停或取消；修改只影响本人 AI，其他玩家和模组规则不变。"), Math.max(60, width - 48))) {
            if (y < 72) graphics.drawString(font, line, 24, y, 0xffdce5ef, false); y += 11;
        }
        y = 142;
        for (var line : font.split(Component.literal(status + (AiClientNetwork.STATE.error.isEmpty() ? "" : "\n" + AiClientNetwork.STATE.error)), Math.max(60, width - 48))) {
            if (y < height - 42) graphics.drawString(font, line, 24, y, 0xffdce5ef, false); y += 11;
        }
        super.render(graphics, mouseX, mouseY, partial);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
