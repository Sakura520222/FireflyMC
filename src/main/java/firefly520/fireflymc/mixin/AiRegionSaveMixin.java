package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.material.NativeSaveObserver;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import net.minecraft.world.level.chunk.storage.RegionFile;

@Mixin(value = RegionFileStorage.class, remap = false)
public abstract class AiRegionSaveMixin {
    @Shadow @Final private Path folder;
    @Shadow private RegionFile getRegionFile(ChunkPos pos) throws IOException { throw new AssertionError(); }
    @Inject(method = "write", at = @At("RETURN"))
    private void fireflymc$written(ChunkPos position, CompoundTag tag, CallbackInfo ci) throws IOException {
        if (!NativeSaveObserver.trackedRegion(tag, folder, position.toLong())) return;
        // Runs on the native region writer. A failed write/flush never acknowledges the snapshot.
        RegionFile region = getRegionFile(position);
        Path external = folder.resolve("c." + position.x + "." + position.z + ".mcc");
        if (Files.isSymbolicLink(external)) throw new IOException("AI_NATIVE_EXTERNAL_CHUNK_UNSAFE");
        if (Files.exists(external, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(external, LinkOption.NOFOLLOW_LINKS)) throw new IOException("AI_NATIVE_EXTERNAL_CHUNK_NOT_FILE");
            try (var channel = FileChannel.open(external, StandardOpenOption.WRITE)) { channel.force(true); }
        }
        region.flush();
        if (!region.doesChunkExist(position)) throw new IOException("AI_NATIVE_CHUNK_WRITE_NOT_READABLE");
        NativeSaveObserver.writtenRegion(tag, folder, position.toLong());
    }
}
