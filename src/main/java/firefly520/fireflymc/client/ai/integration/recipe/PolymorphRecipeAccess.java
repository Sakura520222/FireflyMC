package firefly520.fireflymc.client.ai.integration.recipe;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.attachment.AttachmentType;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Supplier;
import static firefly520.fireflymc.ai.recipe.view.RecipeViewData.*;
import static firefly520.fireflymc.client.ai.integration.recipe.RecipeReflection.*;

/** Reads existing own-player state only. The API's ordinary getter would create an attachment. */
final class PolymorphRecipeAccess {
    private final Class<?> data = type("com.illusivesoulworks.polymorph.api.common.capability.IRecipeData");
    private final Class<?> playerData = type("com.illusivesoulworks.polymorph.api.common.capability.IPlayerRecipeData");
    private final Class<?> pair = type("com.illusivesoulworks.polymorph.api.common.base.IRecipePair");
    private final Class<?> attachment = type("com.illusivesoulworks.polymorph.common.PolymorphNeoForgeCapabilities$RecipeDataAttachment");
    private final Field attachmentType = type("com.illusivesoulworks.polymorph.common.PolymorphNeoForgeCapabilities").getField("RECIPE_DATA");
    private final Method read = method(attachment, "getRecipeData", false, data);
    private final Method owner = method(data, "getOwner", false, Object.class);
    private final Method menu = method(playerData, "getContainerMenu", false, AbstractContainerMenu.class);
    private final Method selected = method(data, "getSelectedRecipe", false, RecipeHolder.class);
    private final Method candidates = method(data, "getRecipesList", false, SortedSet.class);
    private final Method output = method(pair, "getOutput", false, ItemStack.class);
    private final Method id = method(pair, "getResourceLocation", false, ResourceLocation.class);
    PolymorphRecipeAccess() throws ReflectiveOperationException {
        if (attachmentType.getType() != Supplier.class || !Modifier.isStatic(attachmentType.getModifiers()))
            throw new NoSuchFieldException("POLYMORPH_ATTACHMENT_TYPE");
    }
    @SuppressWarnings("unchecked")
    ConflictReport conflicts(Authority authority) throws ReflectiveOperationException {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) throw unavailable("PLAYER_OFFLINE");
        Object type = ((Supplier<?>) attachmentType.get(null)).get();
        if (!(type instanceof AttachmentType<?>)) throw unavailable("POLYMORPH_ATTACHMENT_MISMATCH");
        Object wrapper = mc.player.getExistingDataOrNull((AttachmentType<Object>) type);
        if (wrapper == null || !attachment.isInstance(wrapper)) throw unavailable("POLYMORPH_RUNTIME_NOT_READY");
        Object existing = nonnull(call(read, wrapper));
        if (!playerData.isInstance(existing) || call(owner, existing) != mc.player
                || call(menu, existing) != mc.player.containerMenu) throw unavailable("POLYMORPH_MENU_STALE");
        var selectedRecipe = (RecipeHolder<?>) call(selected, existing);
        var choices = (SortedSet<?>) call(candidates, existing);
        var result = new ArrayList<Candidate>();
        for (Object value : choices) {
            if (result.size() >= MAX_RESULTS) break;
            String recipeId = RecipeReflection.id(call(id, value), false);
            result.add(new Candidate(Viewer.POLYMORPH, "polymorph:menu_candidates", recipeId, recipeId, recipeId,
                    List.of(), List.of(hint(call(output, value), 0, true)), false, false));
        }
        return new ConflictReport(authority, mc.player.containerMenu.containerId,
                selectedRecipe == null ? "" : selectedRecipe.id().toString(), result, choices.size() > MAX_RESULTS, false);
    }
}
