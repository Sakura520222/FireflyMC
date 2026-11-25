package firefly520.fireflymc.ai.processing.advanced;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import firefly520.fireflymc.ai.processing.VerifiedTransformation.SamplingEvidence;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;

/** Psi 108: actual recipe, dimension and knowledge extension checks; no CAD, Psi charge, spell cast or item entities. */
public final class PsiTrickAdapter {
    private static final String ROOT = "vazkii.psi.common.crafting.recipe.";
    private static final String API = "vazkii.psi.api.PsiAPI";
    private PsiTrickAdapter() {}

    public static boolean supports(Recipe<?> recipe) {
        if (recipe == null || !AdvancedReflection.version("psi", "1.21.1-108")) return false;
        String type = recipe.getClass().getName().equals(ROOT + "TrickRecipe") ? "psi:trick_crafting"
                : recipe.getClass().getName().equals(ROOT + "DimensionTrickRecipe") ? "psi:dimension_trick_crafting" : "";
        return !type.isEmpty() && AdvancedReflection.id(type).equals(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()));
    }

    public static List<AdvancedInput> inputRequirements(RecipeHolder<?> holder, ServerPlayer player) {
        require(holder, player);
        Ingredient ingredient = (Ingredient) AdvancedReflection.call(holder.value(), "getInput");
        return List.of(AdvancedInput.one(0, "real_transmutation_input", 1, ingredient::test));
    }

    @SuppressWarnings("unchecked")
    public static AdvancedTransformation verify(RecipeHolder<?> holder, ItemStack supplied, ServerPlayer player) {
        require(holder, player);
        if (supplied.isEmpty() || supplied.getCount() != 1) throw AdvancedReflection.failure("PSI_INPUT_UNIT_REQUIRED");
        var input = RecipeInputIsolation.copy(supplied, player.registryAccess());
        var recipe = (Recipe<SingleRecipeInput>) holder.value();
        var recipeInput = new SingleRecipeInput(input);
        if (!recipe.matches(recipeInput, player.serverLevel())) throw AdvancedReflection.failure("PSI_INPUT_OR_DIMENSION_MISMATCH");
        Object piece = AdvancedReflection.call(recipe, "getPiece");
        String key = knowledge(piece, player);
        ItemStack output = AdvancedReflection.copy(recipe.assemble(recipeInput, player.registryAccess()), player.registryAccess());
        if (output.isEmpty()) throw AdvancedReflection.failure("PSI_RESULT_EMPTY");
        return new AdvancedTransformation(holder, "psi:trick@108", List.of(supplied), List.of(1), List.of(output),
                List.of(ItemStack.EMPTY), SamplingEvidence.deterministic(),
                Map.of("owner", player.getUUID().toString(), "dimension", player.serverLevel().dimension().location().toString(),
                        "piece", key, "virtualCad", "native-recipe-piece", "knowledgePolicy", "native-event-v1", "psiCost", "0"),
                AdvancedTransformation.references(holder), List.of(piece == null ? "native_psidust_progress" : "native_piece_executed"),
                actor -> progress(piece, actor), player.registryAccess());
    }

    @SuppressWarnings("unchecked")
    private static String knowledge(Object piece, ServerPlayer player) {
        if (piece == null) return "none";
        Class<?> craftingPiece = AdvancedReflection.type("vazkii.psi.api.spell.piece.PieceCraftingTrick");
        if (!(boolean) AdvancedReflection.invoke(craftingPiece, piece, "canCraft", new Class<?>[]{craftingPiece}, piece))
            throw AdvancedReflection.failure("PSI_PIECE_CONTEXT_REJECTED");
        ResourceLocation key;
        try {
            // Resolve only this common field. Enumerating SpellPiece's fields also resolves optional client render types.
            key = (ResourceLocation) java.lang.invoke.MethodHandles.publicLookup().findGetter(
                    AdvancedReflection.type("vazkii.psi.api.spell.SpellPiece"), "registryKey", ResourceLocation.class).invoke(piece);
        } catch (Throwable failure) { throw AdvancedReflection.failure("PSI_PIECE_ID_API_MISMATCH"); }
        Registry<Collection<Class<?>>> groups = (Registry<Collection<Class<?>>>) AdvancedReflection.field(API, "ADVANCEMENT_GROUP_REGISTRY");
        if (groups.size() > 2048) throw AdvancedReflection.failure("PSI_GROUP_REGISTRY_LIMIT");
        var group = groups.entrySet().stream().filter(entry -> entry.getValue().contains(piece.getClass())).findFirst();
        if (group.isPresent() && !(boolean) AdvancedReflection.invoke(AdvancedReflection.type("vazkii.psi.api.internal.IPlayerData"), data(player),
                "isPieceGroupUnlocked", new Class<?>[]{ResourceLocation.class, ResourceLocation.class}, group.get().getKey().location(), key))
            throw AdvancedReflection.failure("PSI_KNOWLEDGE_DENIED");
        return key.toString();
    }

    private static Object data(ServerPlayer player) {
        Object handler = AdvancedReflection.field(API, "internalHandler");
        return AdvancedReflection.invoke(AdvancedReflection.type("vazkii.psi.api.internal.IInternalMethodHandler"), handler,
                "getDataForPlayer", new Class<?>[]{Player.class}, player);
    }

    private static void progress(Object piece, ServerPlayer player) {
        Object data = data(player);
        if (piece != null) {
            knowledge(piece, player);
            AdvancedReflection.invoke(AdvancedReflection.type("vazkii.psi.api.internal.IPlayerData"), data, "markPieceExecuted",
                    new Class<?>[]{AdvancedReflection.type("vazkii.psi.api.spell.SpellPiece")}, piece);
        } else {
            ResourceLocation group = (ResourceLocation) AdvancedReflection.field("vazkii.psi.common.lib.LibPieceGroups", "FAKE_LEVEL_PSIDUST");
            if (!(boolean) AdvancedReflection.call(data, "hasAdvancement", new Class<?>[]{ResourceLocation.class}, group)) {
                Event event = (Event) AdvancedReflection.construct("vazkii.psi.api.spell.PieceGroupAdvancementComplete",
                        new Class<?>[]{AdvancedReflection.type("vazkii.psi.api.spell.SpellPiece"), Player.class, ResourceLocation.class}, null, player, group);
                NeoForge.EVENT_BUS.post(event);
            }
        }
    }

    private static void require(RecipeHolder<?> holder, ServerPlayer player) {
        AdvancedReflection.current(holder, player.serverLevel());
        if (!supports(holder.value())) throw AdvancedReflection.failure("UNSUPPORTED_PROCESSING_ADAPTER");
    }
}
