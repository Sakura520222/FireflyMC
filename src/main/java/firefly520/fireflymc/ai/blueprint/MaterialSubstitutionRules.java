package firefly520.fireflymc.ai.blueprint;

import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.*;
import java.util.*;

/** Hard native compatibility checks precede any availability/style ranking. Unknown functional blocks stay exact. */
public final class MaterialSubstitutionRules {
    public record Assessment(boolean allowed, String role, String reason, int appearanceDistance,
                             BlueprintDocument.State replacement, List<String> evidence) {
        public Assessment { evidence = List.copyOf(evidence); }
    }
    private MaterialSubstitutionRules() {}
    public static Assessment assess(BlockState source, Block target, BlockGetter level, BlockPos position) {
        String family = family(source);
        if (family.isEmpty() || !family.equals(family(target.defaultBlockState()))) return rejected("FUNCTIONAL_EXACT", "FUNCTIONAL_OR_UNKNOWN_FAMILY_REQUIRES_EXACT_BLOCK");
        String targetId = BuiltInRegistries.BLOCK.getKey(target).toString();
        BlockState replacement;
        try { replacement = BlueprintBlockStateCodec.resolve(new BlueprintDocument.State(targetId, BlueprintBlockStateCodec.describe(source).properties())); }
        catch (IllegalArgumentException mismatch) { return rejected("STRUCTURAL", "STATE_PROPERTIES_NOT_EQUIVALENT"); }
        if (!source.getValues().keySet().stream().map(property -> property.getName()).collect(java.util.stream.Collectors.toSet())
                .equals(replacement.getValues().keySet().stream().map(property -> property.getName()).collect(java.util.stream.Collectors.toSet())))
            return rejected("STRUCTURAL", "STATE_PROPERTIES_NOT_EQUIVALENT");
        if (source.hasBlockEntity() || replacement.hasBlockEntity() || source.isSignalSource() || replacement.isSignalSource())
            return rejected("FUNCTIONAL_EXACT", "FUNCTIONAL_STATE_REQUIRES_EXACT_BLOCK");
        if (Shapes.joinIsNotEmpty(source.getCollisionShape(level, position), replacement.getCollisionShape(level, position), BooleanOp.NOT_SAME)
                || Shapes.joinIsNotEmpty(source.getOcclusionShape(level, position), replacement.getOcclusionShape(level, position), BooleanOp.NOT_SAME)
                || source.canOcclude() != replacement.canOcclude()
                || source.propagatesSkylightDown(level, position) != replacement.propagatesSkylightDown(level, position)
                || source.getLightBlock(level, position) != replacement.getLightBlock(level, position)
                || source.getLightEmission(level, position) != replacement.getLightEmission(level, position))
            return rejected("STRUCTURAL", "COLLISION_OR_LIGHTING_CHANGED");
        if (!source.getFluidState().equals(replacement.getFluidState())) return rejected("HAZARD_SENSITIVE", "FLUID_STATE_CHANGED");
        for (Direction face : Direction.values()) if (source.getFlammability(level, position, face) != replacement.getFlammability(level, position, face)
                || source.getFireSpreadSpeed(level, position, face) != replacement.getFireSpreadSpeed(level, position, face))
            return rejected("HAZARD_SENSITIVE", "FIRE_BEHAVIOR_CHANGED");
        if (source.getDestroySpeed(level, position) != replacement.getDestroySpeed(level, position)
                || source.getBlock().getExplosionResistance() != replacement.getBlock().getExplosionResistance())
            return rejected("STRUCTURAL", "STRENGTH_CHANGED");
        int left = source.getMapColor(level, position).col, right = replacement.getMapColor(level, position).col;
        int difference = Math.abs((left >> 16 & 255) - (right >> 16 & 255)) + Math.abs((left >> 8 & 255) - (right >> 8 & 255)) + Math.abs((left & 255) - (right & 255));
        return new Assessment(true, "DECORATIVE", "", difference, BlueprintBlockStateCodec.describe(replacement),
                List.of("minecraft:1.21.1/" + family, "exact-state-properties", "equal-collision-and-occlusion", "equal-light-and-fluid", "equal-fire-and-strength", "new-blueprint-review-required"));
    }
    private static Assessment rejected(String role, String reason) { return new Assessment(false, role, reason, 0, null, List.of()); }
    private static String family(BlockState state) {
        var block = state.getBlock(); var id = BuiltInRegistries.BLOCK.getKey(block);
        if (!id.getNamespace().equals("minecraft")) return ""; // Mod families need their own reviewed semantic adapter.
        Class<?> type = block.getClass();
        if (type == Block.class && state.is(BlockTags.PLANKS)) return "planks";
        if (type == Block.class && state.is(BlockTags.WOOL)) return "wool";
        if (type == Block.class && state.is(BlockTags.STONE_BRICKS)) return "stone_bricks";
        if (type == Block.class && state.is(BlockTags.TERRACOTTA)) return "terracotta";
        if (type == RotatedPillarBlock.class && state.is(BlockTags.LOGS)) return "logs";
        if (type == SlabBlock.class) return "slabs";
        if (type == StairBlock.class) return "stairs";
        if (type == StainedGlassBlock.class) return "stained_glass";
        if (type == StainedGlassPaneBlock.class) return "stained_glass_panes";
        return "";
    }
}
