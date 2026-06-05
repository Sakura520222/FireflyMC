package firefly520.fireflymc.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import firefly520.fireflymc.client.ClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin：在玩家头顶名牌上方始终显示称号
 * <p>
 * 注入到 EntityRenderer.render() 的末尾，独立于名牌可见性判断，
 * 因此即使玩家隐身（包括其他mod的隐身效果），称号仍然可见。
 * <p>
 * 自行处理坐标变换（平移到名牌锚点、朝向相机、缩放），
 * 不依赖 renderNameTag 方法的 PoseStack 状态。
 */
@Mixin(EntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    @Shadow(remap = false)
    public abstract Font getFont();

    /**
     * 在 render() 末尾注入，此时 PoseStack 为实体的基础变换状态
     */
    @Inject(
        method = "render",
        at = @At("TAIL"),
        remap = false
    )
    private void fireflymc$renderTitleAlways(
            Entity entity, float entityYaw, float partialTick,
            PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
            CallbackInfo ci) {

        if (!(entity instanceof Player player)) {
            return;
        }

        String uuid = player.getUUID().toString();
        String title = ClientState.titleMap.get(uuid);
        if (title == null || title.isEmpty()) {
            return;
        }

        // 获取名牌锚点（与原版 renderNameTag 一致）
        Vec3 nameTagPos = entity.getAttachments().getNullable(
                EntityAttachment.NAME_TAG, 0, entity.getViewYRot(partialTick));
        if (nameTagPos == null) {
            return;
        }

        EntityRenderer<?> self = (EntityRenderer<?>) (Object) this;

        // 设置变换：平移到名牌位置 → 朝向相机 → 缩小并翻转Y轴
        poseStack.pushPose();
        poseStack.translate(nameTagPos.x, nameTagPos.y + 0.5, nameTagPos.z);
        poseStack.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(0.025F, -0.025F, 0.025F);

        Component titleComponent = Component.literal("§7[§r" + title + "§7]§r");
        Font font = self.getFont();
        float halfWidth = font.width(titleComponent) / 2.0F;
        float bgOpacity = Minecraft.getInstance().options.getBackgroundOpacity(0.25F);
        int bgColor = (int) (bgOpacity * 255.0F) << 24;
        Matrix4f matrix4f = poseStack.last().pose();

        // 在名牌上方（y=-11）绘制称号，与原版名称渲染模式一致
        font.drawInBatch(titleComponent, -halfWidth, -11, 553648127, false,
                matrix4f, bufferSource, Font.DisplayMode.SEE_THROUGH, bgColor, packedLight);
        font.drawInBatch(titleComponent, -halfWidth, -11, -1, false,
                matrix4f, bufferSource, Font.DisplayMode.NORMAL, 0, packedLight);

        poseStack.popPose();
    }
}
