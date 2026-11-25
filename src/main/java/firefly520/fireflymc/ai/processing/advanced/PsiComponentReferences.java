package firefly520.fireflymc.ai.processing.advanced;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/** The exact Psi sensor codec stores an Item registry reference, never mutable per-stack sensor state. */
public final class PsiComponentReferences {
    private PsiComponentReferences() {}
    public static boolean trustedSharedReference(DataComponentType<?> component, ResourceLocation id, Object value) {
        if (!AdvancedReflection.version("psi", "1.21.1-108")) return false;
        if (AdvancedReflection.id("psi:bullets").equals(id)) {
            return value == net.minecraft.world.item.component.ItemContainerContents.EMPTY && registered(component, "BULLETS");
        }
        if (!AdvancedReflection.id("psi:sensor").equals(id) || !(value instanceof Item item)) return false;
        try {
            if (!registered(component, "SENSOR")) return false;
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
            return key != null && BuiltInRegistries.ITEM.containsKey(key) && BuiltInRegistries.ITEM.get(key) == item;
        } catch (RuntimeException | LinkageError failure) { return false; }
    }
    public static boolean equivalentBulletContents(DataComponentType<?> component, Object original, Object detached,
                                                   net.minecraft.core.HolderLookup.Provider registries) {
        if (!AdvancedReflection.id("psi:bullets").equals(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component))
                || !AdvancedReflection.version("psi", "1.21.1-108") || !registered(component, "BULLETS")
                || !(original instanceof net.minecraft.world.item.component.ItemContainerContents left)
                || !(detached instanceof net.minecraft.world.item.component.ItemContainerContents right)
                || original == detached || left.getSlots() != right.getSlots() || left.getSlots() > 64) return false;
        try {
            for (int slot = 0; slot < left.getSlots(); slot++) {
                if (!AdvancedReflection.same(left.getStackInSlot(slot), right.getStackInSlot(slot), registries)) return false;
            }
            return true;
        } catch (RuntimeException | LinkageError failure) { return false; }
    }
    private static boolean registered(DataComponentType<?> component, String field) {
        try {
            Object holder = AdvancedReflection.field("vazkii.psi.common.item.base.ModDataComponents", field);
            return component == AdvancedReflection.call(holder, "get");
        } catch (RuntimeException | LinkageError failure) { return false; }
    }
}
