package firefly520.fireflymc.mixin;

import firefly520.fireflymc.client.eventws.ClientEventNotificationEvents;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 监听客户端成就进度完成状态。
 */
@Mixin(ClientAdvancements.class)
public class ClientAdvancementsMixin {
    @Shadow(remap = false)
    @Final
    private AdvancementTree tree;

    @Shadow(remap = false)
    @Final
    private Map<AdvancementHolder, AdvancementProgress> progress;

    @Unique
    private Set<ResourceLocation> fireflymc$completedBeforeUpdate = Set.of();

    @Unique
    private boolean fireflymc$readyForAdvancementNotifications;

    @Inject(
        method = "update",
        at = @At("HEAD"),
        remap = false
    )
    private void fireflymc$beforeUpdate(ClientboundUpdateAdvancementsPacket packet, CallbackInfo ci) {
        if (packet.shouldReset()) {
            fireflymc$completedBeforeUpdate = Set.of();
            fireflymc$readyForAdvancementNotifications = false;
            return;
        }

        Set<ResourceLocation> completed = new HashSet<>();
        for (ResourceLocation id : packet.getProgress().keySet()) {
            AdvancementNode node = this.tree.get(id);
            if (node == null) {
                continue;
            }
            AdvancementProgress current = this.progress.get(node.holder());
            if (current != null && current.isDone()) {
                completed.add(id);
            }
        }
        fireflymc$completedBeforeUpdate = completed;
    }

    @Inject(
        method = "update",
        at = @At("TAIL"),
        remap = false
    )
    private void fireflymc$afterUpdate(ClientboundUpdateAdvancementsPacket packet, CallbackInfo ci) {
        if (packet.shouldReset()) {
            fireflymc$completedBeforeUpdate = Set.of();
            fireflymc$readyForAdvancementNotifications = true;
            return;
        }

        if (!fireflymc$readyForAdvancementNotifications) {
            fireflymc$readyForAdvancementNotifications = true;
            fireflymc$completedBeforeUpdate = Set.of();
            return;
        }

        for (ResourceLocation id : packet.getProgress().keySet()) {
            AdvancementNode node = this.tree.get(id);
            if (node == null) {
                continue;
            }

            AdvancementProgress current = this.progress.get(node.holder());
            if (current == null || !current.isDone() || fireflymc$completedBeforeUpdate.contains(id)) {
                continue;
            }

            AdvancementHolder holder = node.holder();
            Optional<DisplayInfo> display = holder.value().display();
            if (display.isEmpty() || !display.get().shouldShowToast()) {
                continue;
            }

            ClientEventNotificationEvents.notifyAdvancementEarned(
                holder.id().toString(),
                display.get().getTitle(),
                display.get().getDescription()
            );
        }
        fireflymc$completedBeforeUpdate = Set.of();
    }
}
