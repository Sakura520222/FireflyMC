package firefly520.fireflymc.story.definition;

import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Immutable chapter presentation metadata. Quest membership is owned by quests. */
public record ChapterDefinition(
    ResourceLocation id,
    Component title,
    Component subtitle,
    int index
) {
    public ChapterDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(subtitle, "subtitle");
    }
}
