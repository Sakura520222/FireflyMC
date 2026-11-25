package firefly520.fireflymc.ai.processing.advanced;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;

/** Server-thread candidate predicates. A slot's options are alternatives, never cumulative fees. */
public record AdvancedInput(int slot, String role, List<Option> alternatives) {
    public record Option(int count, Predicate<ItemStack> matches) {
        public Option {
            if (count < 1 || count > 512 || matches == null) throw new IllegalArgumentException("ADVANCED_INPUT_INVALID");
        }
    }
    public AdvancedInput {
        if (slot < 0 || slot >= 81 || role == null || alternatives.isEmpty()) throw new IllegalArgumentException("ADVANCED_INPUT_INVALID");
        alternatives = List.copyOf(alternatives);
    }
    public static AdvancedInput one(int slot, String role, int count, Predicate<ItemStack> predicate) {
        return new AdvancedInput(slot, role, List.of(new Option(count, predicate)));
    }
}
