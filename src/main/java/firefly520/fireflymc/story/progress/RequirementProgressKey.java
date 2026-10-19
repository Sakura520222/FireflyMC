package firefly520.fireflymc.story.progress;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Persisted identity of one requirement's runtime value, including its definition schema. */
public record RequirementProgressKey(
    ResourceLocation questId,
    String requirementId,
    int stateVersion
) {
    public RequirementProgressKey {
        Objects.requireNonNull(questId, "questId");
        if (requirementId == null || requirementId.isBlank()) {
            throw new IllegalArgumentException("requirementId must not be blank");
        }
        if (stateVersion < 1) {
            throw new IllegalArgumentException("stateVersion must be at least 1");
        }
    }
}
