package firefly520.fireflymc.ai.blueprint;

import java.util.*;

/** Portable audited reductions, shared by signed plans and their durable native evidence. */
public final class RemovalTransitions {
    private RemovalTransitions() {}
    public static boolean air(BlueprintDocument.State state) { return state.blockId().equals("minecraft:air") && state.properties().isEmpty(); }
    public static boolean halfSlab(BlueprintDocument.State before, BlueprintDocument.State after) {
        if (!before.blockId().equals(after.blockId()) || !"double".equals(before.properties().get("type"))
                || !Set.of("top", "bottom").contains(after.properties().getOrDefault("type", ""))
                || !"false".equals(before.properties().get("waterlogged"))) return false;
        var properties = new HashMap<>(before.properties()); properties.put("type", after.properties().get("type"));
        return properties.equals(after.properties());
    }
    public static boolean valid(BuildJournal.Cell cell) {
        return cell.target() == null || !air(cell.before()) && (air(cell.target()) || halfSlab(cell.before(), cell.target()));
    }
    public static boolean partialOnly(List<BuildJournal.Cell> cells) {
        return cells.stream().anyMatch(c -> c.target() != null) && cells.stream().filter(c -> c.target() != null).allMatch(c -> halfSlab(c.before(), c.target()));
    }
}
