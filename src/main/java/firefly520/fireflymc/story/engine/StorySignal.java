package firefly520.fireflymc.story.engine;

import firefly520.fireflymc.story.condition.StoryCondition;
import java.util.Objects;

/** Invalidation signals produced after an action changes observable story state. */
public sealed interface StorySignal permits
    StorySignal.FlagChanged,
    StorySignal.VarChanged,
    StorySignal.InventoryChanged,
    StorySignal.LocationChanged {
    Kind kind();

    enum Kind {
        FLAG_CHANGED,
        VAR_CHANGED,
        INVENTORY_CHANGED,
        LOCATION_CHANGED
    }

    record FlagChanged(String flag, StoryCondition.Scope scope) implements StorySignal {
        public FlagChanged {
            if (!StoryCondition.isValidStateName(flag)) {
                throw new IllegalArgumentException("invalid story state name: " + flag);
            }
            Objects.requireNonNull(scope, "scope");
        }

        @Override
        public Kind kind() {
            return Kind.FLAG_CHANGED;
        }
    }

    record VarChanged(String var, StoryCondition.Scope scope) implements StorySignal {
        public VarChanged {
            if (!StoryCondition.isValidStateName(var)) {
                throw new IllegalArgumentException("invalid story state name: " + var);
            }
            Objects.requireNonNull(scope, "scope");
            if (scope != StoryCondition.Scope.PLAYER) {
                throw new IllegalArgumentException("VAR_CHANGED only supports player scope");
            }
        }

        @Override
        public Kind kind() {
            return Kind.VAR_CHANGED;
        }
    }

    record InventoryChanged() implements StorySignal {
        @Override
        public Kind kind() {
            return Kind.INVENTORY_CHANGED;
        }
    }

    record LocationChanged() implements StorySignal {
        @Override
        public Kind kind() {
            return Kind.LOCATION_CHANGED;
        }
    }
}
