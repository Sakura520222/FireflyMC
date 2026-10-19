package firefly520.fireflymc.story.definition;

import firefly520.fireflymc.story.condition.StoryCondition;
import java.util.Objects;

/** A named AND-requirement with a persisted schema version. */
public record RequirementDefinition(
    String id,
    int stateVersion,
    StoryCondition condition
) {
    public RequirementDefinition {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (stateVersion < 1) {
            throw new IllegalArgumentException("state_version must be at least 1");
        }
        Objects.requireNonNull(condition, "condition");
    }
}
