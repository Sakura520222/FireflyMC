package firefly520.fireflymc.mixin;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.stream.JsonWriter;
import firefly520.fireflymc.ai.material.NativeSaveObserver;
import net.minecraft.server.PlayerAdvancements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import java.io.IOException;
import java.nio.file.Path;

@Mixin(value = PlayerAdvancements.class, remap = false)
public abstract class AiAdvancementsSaveMixin {
    @Shadow @Final private Path playerSavePath;
    @Redirect(method = "save", at = @At(value = "INVOKE", target = "Lcom/google/gson/Gson;toJson(Lcom/google/gson/JsonElement;Lcom/google/gson/stream/JsonWriter;)V"))
    private void fireflymc$write(Gson gson, JsonElement data, JsonWriter writer) throws IOException {
        var snapshot = NativeSaveObserver.snapshot(this);
        gson.toJson(data, writer);
        if (!snapshot.empty()) {
            writer.flush();
            NativeSaveObserver.written(snapshot, playerSavePath);
        }
    }
}
