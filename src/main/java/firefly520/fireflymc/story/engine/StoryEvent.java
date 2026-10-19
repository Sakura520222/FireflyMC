package firefly520.fireflymc.story.engine;

import firefly520.fireflymc.story.condition.StoryCondition;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * One immutable, concrete runtime event. Context is transport metadata only and is never
 * interpolated into commands.
 */
public record StoryEvent(
    StoryEventType type,
    ResourceLocation key,
    int amount,
    Map<String, String> context
) {
    private static final String SCOPE_CONTEXT_KEY = "scope";

    public StoryEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(key, "key");
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be strictly positive");
        }
        Objects.requireNonNull(context, "context");
        context.forEach((name, value) -> {
            Objects.requireNonNull(name, "context key");
            Objects.requireNonNull(value, "context value for " + name);
        });
        context = Map.copyOf(context);

        boolean scoped = isScoped(type);
        if (context.containsKey(SCOPE_CONTEXT_KEY) == !scoped) {
            throw new IllegalArgumentException(
                scoped
                    ? type + " requires context scope=player or world"
                    : type + " does not accept a context scope");
        }
        if (scoped) {
            Optional<StoryCondition.Scope> parsedScope = scope(context);
            if (type == StoryEventType.VAR_CHANGED
                && parsedScope.orElseThrow() != StoryCondition.Scope.PLAYER) {
                throw new IllegalArgumentException("VAR_CHANGED only supports player scope");
            }
        }
    }

    /** Returns the explicit scope required by FLAG_CHANGED and VAR_CHANGED. */
    public Optional<StoryCondition.Scope> scope() {
        return scope(context);
    }

    private static Optional<StoryCondition.Scope> scope(Map<String, String> values) {
        String value = values.get(SCOPE_CONTEXT_KEY);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(StoryCondition.Scope.fromSerializedName(value));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("context scope must be player or world", exception);
        }
    }

    private static boolean isScoped(StoryEventType type) {
        return type == StoryEventType.FLAG_CHANGED || type == StoryEventType.VAR_CHANGED;
    }
}
