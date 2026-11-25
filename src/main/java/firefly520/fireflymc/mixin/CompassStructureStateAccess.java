package firefly520.fireflymc.mixin;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only readiness inspection avoids joining a native ring-search preparation future on the server thread. */
// NeoForge 21.1.264 uses official Mojang names in development and production (the modern MDG pipeline).
// The locked 1.21.1 mapped class declares ringPositions; the dedicated scenario also verifies the actual getter/field identity.
// This is deliberately not an SRG/refmap target; see integrations/exploration/minecraft-structure-state-api-20261026.txt.
@Mixin(value = ChunkGeneratorStructureState.class, remap = false)
public interface CompassStructureStateAccess {
    @Accessor("ringPositions")
    Map<ConcentricRingsStructurePlacement, CompletableFuture<List<ChunkPos>>> fireflymc$ringPositions();
}
