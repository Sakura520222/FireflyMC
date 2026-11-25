package firefly520.fireflymc.ai.processing.special;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import firefly520.fireflymc.ai.storage.AiJson;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.fluids.FluidStack;

/** Persistable native fluid value, separate from items and from waived fuel/energy. */
public record FluidMaterial(String fluidId, int amount, String encoded, String digest) {
    public static final int MAX_AMOUNT = 1_000_000;
    public FluidMaterial {
        if (fluidId == null || amount < 1 || amount > MAX_AMOUNT || encoded == null
                || encoded.getBytes(StandardCharsets.UTF_8).length > 65_536 || !AiJson.digest(encoded).equals(digest))
            throw new IllegalArgumentException("FLUID_MATERIAL_INVALID");
    }

    public static FluidMaterial capture(FluidStack source, HolderLookup.Provider registries) {
        FluidStack detached = isolate(source, registries);
        var encoded = FluidStack.CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), detached)
                .getOrThrow(error -> new IllegalArgumentException("FLUID_COMPONENT_ENCODING_FAILED"));
        String json = AiJson.canonical(encoded);
        return new FluidMaterial(BuiltInRegistries.FLUID.getKey(detached.getFluid()).toString(), detached.getAmount(), json, AiJson.digest(json));
    }

    public FluidStack restore(HolderLookup.Provider registries) {
        FluidStack stack = FluidStack.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), JsonParser.parseString(encoded))
                .getOrThrow(error -> new IllegalArgumentException("FLUID_COMPONENT_DECODING_FAILED"));
        if (!BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString().equals(fluidId) || stack.getAmount() != amount)
            throw new IllegalArgumentException("FLUID_MATERIAL_IDENTITY_CHANGED");
        return isolate(stack, registries);
    }

    public static FluidStack isolate(FluidStack source, HolderLookup.Provider registries) {
        if (source == null || source.isEmpty() || source.getAmount() < 1 || source.getAmount() > MAX_AMOUNT)
            throw new IllegalArgumentException("FLUID_MATERIAL_INVALID");
        // The existing audited component isolator traverses nested mutable values. This temporary
        // in-memory carrier is never registered as an ingredient, product or inventory item.
        ItemStack carrier = new ItemStack(Items.BARRIER.builtInRegistryHolder(), 1, source.getComponentsPatch());
        ItemStack isolated = RecipeInputIsolation.copy(carrier, registries);
        FluidStack copy = new FluidStack(source.getFluidHolder(), source.getAmount(), isolated.getComponentsPatch());
        if (!FluidStack.matches(source, copy)) throw new IllegalArgumentException("FLUID_COMPONENT_ROUND_TRIP_MISMATCH");
        return copy;
    }
}
