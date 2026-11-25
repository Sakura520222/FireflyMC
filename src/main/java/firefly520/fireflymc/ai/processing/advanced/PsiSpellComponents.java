package firefly520.fireflymc.ai.processing.advanced;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;

/** Psi 108 Spell.equals compares its grid by identity; this exact codec needs semantic equality plus graph separation. */
public final class PsiSpellComponents {
    private static final net.minecraft.resources.ResourceLocation SPELL_ID = AdvancedReflection.id("psi:spell");
    private PsiSpellComponents() {}

    public static boolean isSpellComponent(DataComponentType<?> component) {
        return SPELL_ID.equals(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component));
    }

    @SuppressWarnings("unchecked")
    public static boolean equivalentDetached(DataComponentType<?> component, Object original, Object detached, HolderLookup.Provider registries) {
        if (original == null || detached == null || original == detached
                || !original.getClass().getName().equals("vazkii.psi.api.spell.Spell")
                || detached.getClass() != original.getClass() || !AdvancedReflection.version("psi", "1.21.1-108")
                || !SPELL_ID.equals(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component))) return false;
        try {
            Object registered = AdvancedReflection.field("vazkii.psi.common.item.base.ModDataComponents", "SPELL");
            if (component != AdvancedReflection.call(registered, "get")) return false;
            var codec = ((DataComponentType<Object>) component).codec();
            if (codec == null) return false;
            var ops = registries.createSerializationContext(NbtOps.INSTANCE);
            var left = codec.encodeStart(ops, original).result();
            var right = codec.encodeStart(ops, detached).result();
            if (left.isEmpty() || !left.equals(right)) return false;
            Set<Object> source = Collections.newSetFromMap(new IdentityHashMap<>());
            collect(original, source, null);
            collect(detached, Collections.newSetFromMap(new IdentityHashMap<>()), source);
            return true;
        } catch (RuntimeException | LinkageError failure) { return false; }
    }

    private static void collect(Object spell, Set<Object> own, Set<Object> forbidden) {
        add(spell, own, forbidden);
        Class<?> spellType = AdvancedReflection.type("vazkii.psi.api.spell.Spell");
        Class<?> gridType = AdvancedReflection.type("vazkii.psi.api.spell.SpellGrid");
        Class<?> pieceType = AdvancedReflection.type("vazkii.psi.api.spell.SpellPiece");
        Object grid = field(spellType, spell, "grid", gridType);
        add(grid, own, forbidden);
        Object rows = field(gridType, grid, "gridData", Array.newInstance(pieceType, 0, 0).getClass());
        add(rows, own, forbidden);
        if (Array.getLength(rows) != 9) throw AdvancedReflection.failure("PSI_SPELL_GRID_INVALID");
        for (int x = 0; x < 9; x++) {
            Object row = Array.get(rows, x); add(row, own, forbidden);
            if (Array.getLength(row) != 9) throw AdvancedReflection.failure("PSI_SPELL_GRID_INVALID");
            for (int y = 0; y < 9; y++) {
                Object piece = Array.get(row, y);
                if (piece == null) continue;
                // Third-party piece implementations need their own component audit; native recipes remain usable for known pieces.
                if (!piece.getClass().getName().startsWith("vazkii.psi.common.spell.")) throw AdvancedReflection.failure("PSI_SPELL_PIECE_UNREVIEWED");
                add(piece, own, forbidden);
                if (field(pieceType, piece, "spell", spellType) != spell) throw AdvancedReflection.failure("PSI_SPELL_PARENT_INVALID");
                Map<?, ?> params = (Map<?, ?>) field(pieceType, piece, "params", Map.class);
                Map<?, ?> sides = (Map<?, ?>) field(pieceType, piece, "paramSides", Map.class);
                if (params.size() > 64 || sides.size() > 64) throw AdvancedReflection.failure("PSI_SPELL_PARAM_LIMIT");
                add(params, own, forbidden); add(sides, own, forbidden);
                for (Object parameter : params.values()) add(parameter, own, forbidden);
            }
        }
    }
    private static void add(Object value, Set<Object> own, Set<Object> forbidden) {
        if (value == null || forbidden != null && forbidden.contains(value)) throw AdvancedReflection.failure("PSI_SPELL_SHARED_MUTABLE_VALUE");
        own.add(value);
    }
    private static Object field(Class<?> owner, Object value, String name, Class<?> result) {
        try { return MethodHandles.publicLookup().findGetter(owner, name, result).invoke(value); }
        catch (Throwable failure) { throw AdvancedReflection.failure("PSI_SPELL_API_MISMATCH"); }
    }
}
