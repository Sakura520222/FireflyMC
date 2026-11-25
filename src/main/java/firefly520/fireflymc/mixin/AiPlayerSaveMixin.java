package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.material.NativeSaveObserver;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;

@Mixin(value = PlayerDataStorage.class, remap = false)
public abstract class AiPlayerSaveMixin {
    @Unique private static final ThreadLocal<ArrayDeque<CompoundTag[]>> fireflymc$saves = ThreadLocal.withInitial(ArrayDeque::new);
    @Inject(method = "save", at = @At("HEAD"))
    private void fireflymc$begin(Player player, CallbackInfo ci) { fireflymc$saves.get().push(new CompoundTag[1]); }
    @Redirect(method = "save", at = @At(value = "INVOKE", target = "Lnet/minecraft/nbt/NbtIo;writeCompressed(Lnet/minecraft/nbt/CompoundTag;Ljava/nio/file/Path;)V"))
    private void fireflymc$write(CompoundTag tag, Path path) throws IOException {
        NbtIo.writeCompressed(tag, path);
        NativeSaveObserver.forceTemporary(tag, path);
        fireflymc$saves.get().peek()[0] = tag;
    }
    @Redirect(method = "save", at = @At(value = "INVOKE", target = "Lnet/minecraft/Util;safeReplaceFile(Ljava/nio/file/Path;Ljava/nio/file/Path;Ljava/nio/file/Path;)V"))
    private void fireflymc$replace(Path current, Path temporary, Path old) {
        CompoundTag tag = fireflymc$saves.get().peek()[0];
        if (!NativeSaveObserver.tracked(tag)) { Util.safeReplaceFile(current, temporary, old); return; }
        // safeReplaceFile is exactly this delegation but discards the boolean failure indication.
        if (Util.safeReplaceOrMoveFile(current, temporary, old, false)) {
            NativeSaveObserver.writtenFile(tag, current);
        }
    }
    @Inject(method = "save", at = @At("RETURN"))
    private void fireflymc$finish(Player player, CallbackInfo ci) {
        var stack = fireflymc$saves.get(); stack.pop(); if (stack.isEmpty()) fireflymc$saves.remove();
    }
}
