package firefly520.fireflymc.client.ai.blueprint;

import com.google.gson.JsonObject;
import firefly520.fireflymc.client.ai.AiClientNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Server selection or explicitly labelled local edit draft; neither grants construction. */
final class BuildRegionRenderer {
    private static JsonObject failedView;
    private BuildRegionRenderer() { }
    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || !AiClientNetwork.STATE.ready()) return;
        var mc = Minecraft.getInstance();
        var editor = mc.screen instanceof firefly520.fireflymc.client.ai.workspace.BuildRegionDragScreen screen ? screen : null;
        var data = editor == null ? AiClientNetwork.STATE.buildRegionView : editor.preview();
        if (data == failedView) return;
        if (mc.level == null || !data.has("dimension") || !data.get("dimension").getAsString().equals(mc.level.dimension().location().toString())) return;
        var pose = event.getPoseStack(); var camera = event.getCamera().getPosition(); var buffers = mc.renderBuffers().bufferSource();
        pose.pushPose(); pose.translate(-camera.x, -camera.y, -camera.z);
        try {
            if (editor != null) editor.frame(new RegionDragProjection(event.getProjectionMatrix(), event.getModelViewMatrix(),
                    new RegionDragMath.Point(camera.x, camera.y, camera.z), mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()));
            if (data.has("bounds")) {
                var bounds = data.getAsJsonObject("bounds"); var low = bounds.getAsJsonObject("minimum"); var high = bounds.getAsJsonObject("maximum");
                var box = new AABB(low.get("x").getAsDouble(), low.get("y").getAsDouble(), low.get("z").getAsDouble(),
                        high.get("x").getAsDouble() + 1, high.get("y").getAsDouble() + 1, high.get("z").getAsDouble() + 1);
                boolean confirmed = data.has("confirmed") && data.get("confirmed").getAsBoolean();
                if (event.getFrustum().isVisible(box)) LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()), box,
                        confirmed ? .35f : 1f, confirmed ? .9f : .75f, .35f, .9f);
            }
            for (String corner : java.util.List.of("first", "second")) if (data.has(corner) && data.get(corner).isJsonObject()) {
                JsonObject point = data.getAsJsonObject(corner); var box = new AABB(point.get("x").getAsDouble(), point.get("y").getAsDouble(), point.get("z").getAsDouble(),
                        point.get("x").getAsDouble() + 1, point.get("y").getAsDouble() + 1, point.get("z").getAsDouble() + 1).inflate(.01);
                if (event.getFrustum().isVisible(box)) LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()), box,
                        corner.equals("first") ? .3f : 1f, .6f, 1f, 1f);
            }
            buffers.endBatch(RenderType.lines());
        } catch (RuntimeException | LinkageError unavailable) { failedView = data; AiClientNetwork.STATE.error = "当前渲染组合不能显示选区线框，可在工作台查看服务器坐标。"; }
        finally { pose.popPose(); }
    }
}
