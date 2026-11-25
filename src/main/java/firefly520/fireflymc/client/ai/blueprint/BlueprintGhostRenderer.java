package firefly520.fireflymc.client.ai.blueprint;

import com.mojang.blaze3d.vertex.VertexConsumer;
import firefly520.fireflymc.ai.blueprint.*;
import firefly520.fireflymc.client.ai.AiClientNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import java.util.*;

/** Bounded built-in fallback; real block models are translucent and never placed in a ClientLevel. */
public final class BlueprintGhostRenderer {
    private record Cell(BlueprintDocument.Position relative, BlockPos pos, BlockState state) {}
    private static final Map<Long, List<Cell>> CHUNKS = new HashMap<>();
    private static final Map<Integer, Map<Long, List<Cell>>> LAYERS = new HashMap<>();
    private static final Map<BlueprintDocument.State, BlockState> STATES = new HashMap<>();
    private static BlueprintDocument document;
    private static int cursor;
    private static AABB bounds;
    private BlueprintGhostRenderer() {}
    public static void begin(BlueprintDocument value) { clear(); document = value; }
    public static void clear() { document = null; CHUNKS.clear(); LAYERS.clear(); STATES.clear(); cursor = 0; bounds = null; }
    public static void tick() {
        if (document == null) return;
        long end = System.nanoTime() + 1_000_000;
        try {
            for (int count = 0; cursor < document.blocks().size() && count < 128 && System.nanoTime() < end; count++, cursor++) {
                var block = document.blocks().get(cursor); var p = document.origin().add(block.position()); var pos = new BlockPos(p.x(), p.y(), p.z());
                var state = STATES.computeIfAbsent(block.state(), BlueprintBlockStateCodec::resolve);
                var cell = new Cell(block.position(), pos, state);
                CHUNKS.computeIfAbsent(ChunkPos.asLong(pos), ignored -> new ArrayList<>()).add(cell);
                LAYERS.computeIfAbsent(block.position().y(), ignored -> new HashMap<>()).computeIfAbsent(ChunkPos.asLong(pos), ignored -> new ArrayList<>()).add(cell);
                var box = new AABB(pos); bounds = bounds == null ? box : bounds.minmax(box);
            }
        } catch (RuntimeException invalid) { clear(); BlueprintClientController.INSTANCE.status = "客户端注册表不能解释此蓝图"; }
    }
    public static void render(RenderLevelStageEvent event) {
        BuildRegionRenderer.render(event);
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || !AiClientNetwork.STATE.ready() || document == null) return;
        var mc = Minecraft.getInstance(); var control = BlueprintClientController.INSTANCE;
        if (control.nativeChanged) return; // Edited native placement is an unaccepted proposal, not this server revision.
        if (mc.level == null || mc.player == null || !mc.level.dimension().location().toString().equals(control.dimension)) return;
        var pose = event.getPoseStack(); var camera = event.getCamera().getPosition(); var buffers = mc.renderBuffers().bufferSource();
        pose.pushPose(); pose.translate(-camera.x, -camera.y, -camera.z);
        try {
            if (bounds != null) LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()), bounds, 0.9f, 0.77f, 0.4f, 0.8f);
            int drawn = 0, models = 0; long end = System.nanoTime() + 1_500_000;
            ChunkPos center = mc.player.chunkPosition();
            var visibleChunks = control.layer == null ? CHUNKS : LAYERS.getOrDefault(control.layer, Map.of());
            outer: for (int radius = 0; radius <= 4; radius++) for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
                if (Math.max(Math.abs(x), Math.abs(z)) != radius) continue;
                for (Cell cell : visibleChunks.getOrDefault(ChunkPos.asLong(center.x + x, center.z + z), List.of())) {
                    if (drawn >= 512 || System.nanoTime() > end) break outer;
                    if (control.layer != null && control.layer != cell.relative().y() || cell.pos().distToCenterSqr(camera) > 64 * 64) continue;
                    var box = new AABB(cell.pos()); if (!event.getFrustum().isVisible(box)) continue;
                    drawn++;
                    String status = control.observations.getOrDefault(cell.relative(), "NOT_OBSERVED");
                    float r = 0.45f, g = 0.65f, b = 0.9f;
                    switch (status) {
                        case "ADD" -> { r = 0.35f; g = 0.9f; b = 0.55f; }
                        case "SATISFIED" -> { r = 0.4f; g = 0.7f; b = 0.55f; }
                        case "PROTECTED", "DATA_BLOCK_CONFLICT" -> { r = 1; g = 0.2f; b = 0.25f; }
                        case "DEMOLITION", "REPLACE" -> { r = 1; g = 0.6f; b = 0.15f; }
                        default -> { }
                    }
                    LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()), box.inflate(0.002), r, g, b, 0.65f);
                    if (!control.forgeVisible && !cell.state().isAir() && !status.equals("SATISFIED") && models++ < 128) {
                        pose.pushPose(); pose.translate(cell.pos().getX(), cell.pos().getY(), cell.pos().getZ());
                        try {
                            MultiBufferSource translucent = type -> new Alpha(buffers.getBuffer(RenderType.translucent()));
                            mc.getBlockRenderer().renderSingleBlock(cell.state(), pose, translucent, LightTexture.FULL_BRIGHT,
                                    OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.translucent());
                        } finally { pose.popPose(); }
                    }
                }
            }
            buffers.endBatch(RenderType.translucent()); buffers.endBatch(RenderType.lines());
        } catch (RuntimeException | LinkageError failure) { control.status = "当前模型渲染不兼容，已隐藏内置预览"; clear(); }
        finally { pose.popPose(); }
    }
    private record Alpha(VertexConsumer delegate) implements VertexConsumer {
        @Override public VertexConsumer addVertex(float x, float y, float z) { delegate.addVertex(x, y, z); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { delegate.setColor(r, g, b, Math.min(a, 85)); return this; }
        @Override public VertexConsumer setUv(float u, float v) { delegate.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { delegate.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { delegate.setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { delegate.setNormal(x, y, z); return this; }
    }
}
