package firefly520.fireflymc.story.definition;

import java.util.List;
import firefly520.fireflymc.story.action.StoryAction;
import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Immutable quest content; runtime progress never mutates this definition. */
public record QuestDefinition(
    ResourceLocation id,
    ResourceLocation chapter,
    Component title,
    Component subtitle,
    List<Component> description,
    String icon,
    boolean initial,
    List<RequirementDefinition> requirements,
    List<StoryAction> onComplete
) {
    public QuestDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(chapter, "chapter");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(subtitle, "subtitle");
        description = List.copyOf(description);
        requirements = List.copyOf(requirements);
        onComplete = List.copyOf(onComplete);
    }
}
