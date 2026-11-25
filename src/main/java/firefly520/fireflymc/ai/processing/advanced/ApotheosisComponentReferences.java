package firefly520.fireflymc.ai.processing.advanced;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.resources.ResourceLocation;

/**
 * Two exact registry-reference codecs intern their decoded holders; the native socket codec also
 * interns vanilla's zero-slot container. No mutable per-stack value, other component,
 * DynamicHolder subclass, registry, or optional-mod version is accepted.
 */
public final class ApotheosisComponentReferences {
    private ApotheosisComponentReferences() {}
    public static boolean trustedSharedReference(DataComponentType<?> component, ResourceLocation id, Object value) {
        if (id == null || value == null || !id.getNamespace().equals("apotheosis")
                || !AdvancedReflection.version("apotheosis", "8.6.0") || !AdvancedReflection.version("placebo", "9.9.2")) return false;
        // Its native ItemContainerContents.CODEC canonicalizes a zero-slot value to vanilla EMPTY.
        // Nonempty containers are always recursively isolated by RecipeInputIsolation, never accepted here.
        if (id.getPath().equals("socketed_gems")) {
            try {
                return value == net.minecraft.world.item.component.ItemContainerContents.EMPTY
                        && ((net.minecraft.world.item.component.ItemContainerContents) value).getSlots() == 0
                        && component == AdvancedReflection.field("dev.shadowsoffire.apotheosis.Apoth$Components", "SOCKETED_GEMS");
            } catch (RuntimeException | LinkageError failure) { return false; }
        }
        if (!value.getClass().getName().equals("dev.shadowsoffire.placebo.reload.DynamicHolder")) return false;
        String field, registry;
        switch (id.getPath()) {
            case "rarity" -> { field = "RARITY"; registry = "dev.shadowsoffire.apotheosis.loot.RarityRegistry"; }
            case "gem" -> { field = "GEM"; registry = "dev.shadowsoffire.apotheosis.socket.gem.GemRegistry"; }
            default -> { return false; }
        }
        try {
            if (component != AdvancedReflection.field("dev.shadowsoffire.apotheosis.Apoth$Components", field)) return false;
            ResourceLocation key = (ResourceLocation) AdvancedReflection.call(value, "getId");
            Object singleton = AdvancedReflection.field(registry, "INSTANCE");
            // Identity proof: the value must be the very holder returned by the matching registered codec's registry.
            return value == AdvancedReflection.call(singleton, "holder", new Class<?>[]{ResourceLocation.class}, key);
        } catch (RuntimeException | LinkageError failure) { return false; }
    }
}
