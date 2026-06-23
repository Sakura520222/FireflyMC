package firefly520.fireflymc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * 称号世界空间渲染器。
 * <p>
 * 监听 {@link RenderLevelStageEvent}，在每帧世界渲染后独立遍历所有玩家，
 * 对拥有称号的玩家在其头顶绘制称号。
 * <p>
 * 本渲染器与实体渲染流程<strong>完全解耦</strong>：即使玩家因其他 mod（如天境"隐形斗篷"）
 * 在渲染层被整体跳过，称号依然会显示，因为绘制不依赖于
 * {@code EntityRenderer.render()} 是否被调用。
 * <p>
 * 坐标变换参照 {@code LevelRenderer#renderLevel}：事件传入的 PoseStack 处于
 * 原始状态（未预置相机平移），需手动 {@code translate(worldX - cameraX, ...)}。
 */
public final class TitleWorldRenderer {

    /** 原版 nameTag 渲染距离上限的平方（64 格）。超出此距离的玩家称号不渲染。 */
    private static final double NAME_TAG_RENDER_DISTANCE_SQ = 64.0 * 64.0;

    private TitleWorldRenderer() {
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        double camX = cameraPos.x();
        double camY = cameraPos.y();
        double camZ = cameraPos.z();
        boolean cameraDetached = camera.isDetached();

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        Font font = mc.font;
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();

        for (Player player : level.players()) {
            // 第一人称时绘制自己的称号没有意义（看不到自己），与原版 nameTag 行为一致
            if (player == mc.player && !cameraDetached) {
                continue;
            }

            // 距离裁剪：与原版 nameTag 一致，超出 64 格的玩家不渲染称号，避免无谓的 GPU 提交
            double dx = player.getX() - camX;
            double dy = player.getY() - camY;
            double dz = player.getZ() - camZ;
            if (dx * dx + dy * dy + dz * dz > NAME_TAG_RENDER_DISTANCE_SQ) {
                continue;
            }

            String title = ClientState.titleMap.get(player.getUUID().toString());
            if (title == null || title.isEmpty()) {
                continue;
            }

            // 插值后的渲染坐标（世界坐标）
            double x = Mth.lerp(partialTick, player.xOld, player.getX());
            double y = Mth.lerp(partialTick, player.yOld, player.getY());
            double z = Mth.lerp(partialTick, player.zOld, player.getZ());

            // nameTag 锚点（相对实体局部坐标）
            Vec3 nameTagPos = player.getAttachments().getNullable(
                    EntityAttachment.NAME_TAG, 0, player.getViewYRot(partialTick));
            if (nameTagPos == null) {
                continue;
            }

            int packedLight = LevelRenderer.getLightColor(level, player.blockPosition());

            poseStack.pushPose();
            // 定位到世界中的名牌锚点上方（+0.5 抬高，与原版称号渲染一致）
            poseStack.translate(x + nameTagPos.x - camX,
                    y + nameTagPos.y + 0.5 - camY,
                    z + nameTagPos.z - camZ);
            // 朝向相机（billboard）
            poseStack.mulPose(dispatcher.cameraOrientation());
            // 缩放并翻转 Y 轴（与原版文本渲染一致）
            poseStack.scale(0.025F, -0.025F, 0.025F);

            Component titleComponent = Component.literal("§7[§r" + title + "§7]§r");
            float halfWidth = font.width(titleComponent) / 2.0F;
            float bgOpacity = mc.options.getBackgroundOpacity(0.25F);
            int bgColor = (int) (bgOpacity * 255.0F) << 24;
            Matrix4f matrix = poseStack.last().pose();

            // 两次绘制：SEE_THROUGH（穿透方块可见）+ NORMAL（正常叠层）
            font.drawInBatch(titleComponent, -halfWidth, -11, 553648127, false,
                    matrix, bufferSource, Font.DisplayMode.SEE_THROUGH, bgColor, packedLight);
            font.drawInBatch(titleComponent, -halfWidth, -11, -1, false,
                    matrix, bufferSource, Font.DisplayMode.NORMAL, 0, packedLight);

            poseStack.popPose();
        }

        // 提交本帧绘制的文本批次
        bufferSource.endBatch();
    }
}
