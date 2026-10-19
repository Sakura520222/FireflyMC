package firefly520.fireflymc.story.progress;

import java.util.function.Supplier;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Deferred story attachment registry entries; registration is wired by the lifecycle task. */
public final class StoryAttachments {
    private static final String MOD_ID = "fireflymc";
    private static final String PROGRESS_NAME = "story_progress";

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
        DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MOD_ID);
    public static final Supplier<AttachmentType<StoryProgress>> PROGRESS =
        ATTACHMENT_TYPES.register(PROGRESS_NAME, () ->
            AttachmentType.serializable((Supplier<StoryProgress>) StoryProgress::new)
                .copyOnDeath()
                .build());

    private StoryAttachments() {}
}
