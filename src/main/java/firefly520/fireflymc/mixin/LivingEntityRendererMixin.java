package firefly520.fireflymc.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import firefly520.fireflymc.client.ClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin：在玩家头顶名牌上方显示称号
 * <p>
 * 在 EntityRenderer.renderNameTag 方法中 popPose 之前注入，
 * 此时 PoseStack 仍处于变换状态（已平移、旋转、缩放），
 * 在名称上方渲染称号文本。
 * <p>
 * 注意：scale(0.025, -0.025, 0.025) 中 Y 轴被翻转，
 * 所以正 y 值表示向上（远离实体）。
 */
@Mixin(EntityRenderer.class)
public class LivingEntityRendererMixin {

    /**
     * 在 popPose() 之前注入，此时 pose 仍处于名牌的变换状态
     * 名称绘制在 y≈0，我们在 y=10 绘制称号（正 y 向上）
     */
    @Inject(
        method = "renderNameTag",
        at = @At(value = "INVOKE",
                target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V",
                shift = At.Shift.BEFORE),
        remap = false
    )
    private void fireflymc$renderTitleAboveName(
            Entity entity, Component displayName, PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight, float partialTick,
            CallbackInfo ci) {

        if (!(entity instanceof Player player)) {
            return;
        }

        String uuid = player.getUUID().toString();
        String title = ClientState.titleMap.get(uuid);
        if (title == null || title.isEmpty()) {
            return;
        }

        Component titleComponent = Component.literal(title);
        Font font = ((EntityRenderer<?>) (Object) this).getFont();
        float halfWidth = font.width(titleComponent) / 2.0F;
        float bgOpacity = Minecraft.getInstance().options.getBackgroundOpacity(0.25F);
        int bgColor = (int) (bgOpacity * 255.0F) << 24;
        Matrix4f matrix4f = poseStack.last().pose();

        // 在名牌上方（y=10）绘制称号，与原版名称渲染模式一致
        // 第一次：半透明背景模式
        font.drawInBatch(titleComponent, -halfWidth, 10, 553648127, false,
                matrix4f, bufferSource, Font.DisplayMode.SEE_THROUGH, bgColor, packedLight);
        // 第二次：不透明文字
        font.drawInBatch(titleComponent, -halfWidth, 10, -1, false,
                matrix4f, bufferSource, Font.DisplayMode.NORMAL, 0, packedLight);
    }
}
