package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.material.NativeSaveObserver;
import net.minecraft.stats.ServerStatsCounter;
import org.apache.commons.io.FileUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import java.io.File;
import java.io.IOException;

@Mixin(value = ServerStatsCounter.class, remap = false)
public abstract class AiStatsSaveMixin {
    @Redirect(method = "save", at = @At(value = "INVOKE", target = "Lorg/apache/commons/io/FileUtils;writeStringToFile(Ljava/io/File;Ljava/lang/String;)V"))
    private void fireflymc$write(File file, String data) throws IOException {
        var snapshot = NativeSaveObserver.snapshot(this);
        FileUtils.writeStringToFile(file, data);
        NativeSaveObserver.written(snapshot, file.toPath());
    }
}
