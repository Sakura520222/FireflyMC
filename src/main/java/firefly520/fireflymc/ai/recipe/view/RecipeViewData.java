package firefly520.fireflymc.ai.recipe.view;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Client display hints only. No record in this protocol grants crafting or inventory authority. */
public final class RecipeViewData {
    public static final int PROTOCOL_VERSION = 1;
    public static final int MAX_RESULTS = 32;
    public static final int MAX_HINTS = 32;
    public static final int MAX_OFFSET = 512;
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private RecipeViewData() {}

    public enum Viewer { JEI, EMI, POLYMORPH }
    public enum Role { INPUT, OUTPUT }
    public enum IngredientKind { ITEM, FLUID, CUSTOM }
    public enum DisplayMode { EXACT_SERVER_RECIPE_ID, CLIENT_HINT_ONLY }
    public record Authority(String serverIdentity, long recipeGeneration) {
        public Authority {
            text(serverIdentity, 128);
            if (recipeGeneration < 0) throw new IllegalArgumentException("RECIPE_GENERATION_INVALID");
        }
    }
    public record Query(Authority authority, Viewer viewer, String itemId, Role role, int offset, int limit) {
        public Query {
            Objects.requireNonNull(authority); Objects.requireNonNull(viewer); Objects.requireNonNull(role);
            id(itemId, false);
            if (offset < 0 || offset > MAX_OFFSET || limit < 1 || limit > MAX_RESULTS)
                throw new IllegalArgumentException("RECIPE_PAGE_INVALID");
        }
    }
    public record DisplayRequest(Authority authority, Viewer viewer, String recipeId, String displayIdHint) {
        public DisplayRequest {
            Objects.requireNonNull(authority); Objects.requireNonNull(viewer);
            id(recipeId, false); id(displayIdHint, true);
        }
    }
    /** Quantities/chances are display values, not validated recipe inputs, outputs, or costs. */
    public record IngredientHint(IngredientKind kind, String id, long displayAmount, double displayChance,
                                 boolean componentsOmitted, int ingredientGroup, boolean groupingKnown) {
        public IngredientHint {
            Objects.requireNonNull(kind); RecipeViewData.id(id, true);
            if (displayAmount < 0 || displayAmount > 1_000_000_000L || !Double.isFinite(displayChance)
                    || displayChance < 0 || displayChance > 1 || ingredientGroup < -1 || ingredientGroup >= MAX_HINTS
                    || groupingKnown != (ingredientGroup >= 0))
                throw new IllegalArgumentException("RECIPE_HINT_VALUE_INVALID");
        }
    }
    public record Candidate(Viewer viewer, String categoryId, String displayId, String backingRecipeId,
                            String clientKey, List<IngredientHint> inputs, List<IngredientHint> outputs,
                            boolean ingredientsTruncated, boolean authoritative) {
        public Candidate {
            Objects.requireNonNull(viewer);
            id(categoryId, false); id(displayId, true); id(backingRecipeId, true);
            text(clientKey, 96);
            inputs = boundedHints(inputs); outputs = boundedHints(outputs);
            if (authoritative) throw new IllegalArgumentException("CLIENT_RECIPE_CANNOT_BE_AUTHORITATIVE");
        }
        public String deduplicationKey(Authority authority) {
            Objects.requireNonNull(authority);
            String generation = authority.serverIdentity() + "|" + authority.recipeGeneration() + "|";
            if (!backingRecipeId.isEmpty()) return generation + "native|" + backingRecipeId;
            return generation + viewer + "|" + categoryId + "|" + (displayId.isEmpty() ? clientKey : displayId);
        }
    }
    public record CandidateReport(Authority authority, Viewer viewer, long viewEpoch, List<Candidate> candidates,
                                  int nextOffset, boolean truncated, String trust) {
        public CandidateReport {
            Objects.requireNonNull(authority); Objects.requireNonNull(viewer);
            if (viewEpoch < 0 || candidates == null || candidates.size() > MAX_RESULTS
                    || nextOffset < -1 || nextOffset > MAX_OFFSET + MAX_RESULTS)
                throw new IllegalArgumentException("RECIPE_REPORT_INVALID");
            candidates = List.copyOf(candidates);
            if (candidates.stream().anyMatch(c -> c.viewer() != viewer) || !"CLIENT_HINT".equals(trust))
                throw new IllegalArgumentException("RECIPE_REPORT_TRUST_INVALID");
        }
    }
    public record DisplayReceipt(Authority authority, Viewer viewer, long viewEpoch, String recipeId,
                                 String displayId, DisplayMode mode) {
        public DisplayReceipt {
            Objects.requireNonNull(authority); Objects.requireNonNull(viewer); Objects.requireNonNull(mode);
            id(recipeId, mode == DisplayMode.CLIENT_HINT_ONLY); id(displayId, true);
            if (viewEpoch < 0) throw new IllegalArgumentException("RECIPE_VIEW_EPOCH_INVALID");
        }
    }
    public record ConflictReport(Authority authority, int containerId, String selectedRecipeId,
                                 List<Candidate> candidates, boolean truncated, boolean authoritative) {
        public ConflictReport {
            Objects.requireNonNull(authority); id(selectedRecipeId, true);
            if (containerId < 0 || candidates == null || candidates.size() > MAX_RESULTS || authoritative)
                throw new IllegalArgumentException("RECIPE_CONFLICT_REPORT_INVALID");
            candidates = List.copyOf(candidates);
            if (candidates.stream().anyMatch(c -> c.viewer() != Viewer.POLYMORPH))
                throw new IllegalArgumentException("RECIPE_CONFLICT_SOURCE_INVALID");
        }
    }
    private static List<IngredientHint> boundedHints(List<IngredientHint> hints) {
        if (hints == null || hints.size() > MAX_HINTS) throw new IllegalArgumentException("RECIPE_HINT_LIMIT");
        return List.copyOf(hints);
    }
    public static void id(String value, boolean allowEmpty) {
        if (value == null || value.length() > 256 || !(allowEmpty && value.isEmpty()) && !ID.matcher(value).matches())
            throw new IllegalArgumentException("RECIPE_ID_INVALID");
    }
    private static void text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("RECIPE_TEXT_INVALID");
    }
}
