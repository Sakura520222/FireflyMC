package firefly520.fireflymc.story.loader;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import firefly520.fireflymc.story.StoryEngine;
import firefly520.fireflymc.story.definition.StoryCodecs.StoryDefinitionException;
import firefly520.fireflymc.story.engine.InterestIndex;
import firefly520.fireflymc.story.engine.StoryRuntimeSnapshot;
import java.io.IOException;
import java.io.Reader;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.slf4j.Logger;

/**
 * Two-phase story reload. Preparation is pure datapack work; apply resolves the current reload
 * generation and publishes exactly one immutable snapshot after every reference has been validated.
 *
 * <p>Register the listener from {@link AddReloadListenerEvent} on the NeoForge game event bus.
 * NeoForge runs {@link #prepare} on its background reload executor and {@link #apply} on the
 * game/main executor after the built-in tag and advancement listeners. Concurrent apply/install
 * calls are not part of the contract.</p>
 */
public final class StoryReloadListener
    extends SimplePreparableReloadListener<PreparedStoryData> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final ReferenceResolver resolver;
    private final Consumer<String> emptyTagWarningSink;

    public StoryReloadListener(ReferenceResolver resolver, Consumer<String> emptyTagWarningSink) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.emptyTagWarningSink = Objects.requireNonNull(emptyTagWarningSink, "emptyTagWarningSink");
    }

    public StoryReloadListener(AddReloadListenerEvent event) {
        this(NeoForgeReferenceResolver.fromEvent(event), LOGGER::warn);
    }

    public static void register(AddReloadListenerEvent event) {
        Objects.requireNonNull(event, "event");
        event.addListener(new StoryReloadListener(event));
    }

    @Override
    protected PreparedStoryData prepare(
        ResourceManager resourceManager,
        ProfilerFiller profiler
    ) {
        Objects.requireNonNull(resourceManager, "resourceManager");
        profiler.push("fireflymcStoryDefinitions");
        try {
            return StoryDefinitionLoader.prepare(loadStoryResources(resourceManager));
        } finally {
            profiler.pop();
        }
    }

    @Override
    protected void apply(
        PreparedStoryData prepared,
        ResourceManager resourceManager,
        ProfilerFiller profiler
    ) {
        profiler.push("fireflymcStoryRuntime");
        try {
            apply(prepared);
        } finally {
            profiler.pop();
        }
    }

    public boolean apply(PreparedStoryData prepared) {
        Objects.requireNonNull(prepared, "prepared");
        try {
            StoryValidator.ResolvedReferences resolved =
                StoryValidator.validateResources(prepared, resolver);
            resolved.emptyTagWarnings().forEach(emptyTagWarningSink);

            StoryRuntimeSnapshot candidate = new StoryRuntimeSnapshot(
                prepared.chapters(),
                prepared.quests(),
                InterestIndex.fromDeclarations(resolved.targets()),
                prepared.graph(),
                StoryRevision.sha256(prepared));
            StoryEngine.install(candidate);
            return true;
        } catch (RuntimeException exception) {
            LOGGER.error("Story reload candidate rejected:\n{}", exception.getMessage(), exception);
            return false;
        }
    }

    private static Map<ResourceLocation, JsonElement> loadStoryResources(
        ResourceManager resourceManager
    ) {
        Map<ResourceLocation, JsonElement> resources = new LinkedHashMap<>();
        resourceManager.listResources("story", path -> path.getPath().endsWith(".json"))
            .entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
            .forEach(entry -> resources.put(entry.getKey(), parseResource(entry.getValue())));
        return resources;
    }

    private static JsonElement parseResource(Resource resource) {
        try (Reader reader = resource.openAsReader()) {
            return JsonParser.parseReader(reader);
        } catch (IOException | RuntimeException exception) {
            throw new StoryDefinitionException(
                "Failed to parse story resource from pack " + resource.sourcePackId(), exception);
        }
    }

    /** Apply-time concrete reference validation and staged-tag expansion port. */
    public interface ReferenceResolver {
        boolean exists(
            PreparedStoryData.IndexDeclaration.Kind kind,
            ResourceLocation target
        );

        Set<ResourceLocation> tagMembers(
            PreparedStoryData.IndexDeclaration.Kind kind,
            ResourceLocation target
        );

        boolean tagExists(
            PreparedStoryData.IndexDeclaration.Kind kind,
            ResourceLocation target
        );

        boolean advancementExists(ResourceLocation target);
    }

    private record NeoForgeReferenceResolver(
        RegistryAccess registries,
        ICondition.IContext stagedTags,
        ReloadableServerResources serverResources
    ) implements ReferenceResolver {
        static NeoForgeReferenceResolver fromEvent(AddReloadListenerEvent event) {
            return new NeoForgeReferenceResolver(
                event.getRegistryAccess(),
                event.getConditionContext(),
                event.getServerResources());
        }

        @Override
        public boolean exists(
            PreparedStoryData.IndexDeclaration.Kind kind,
            ResourceLocation target
        ) {
            return switch (kind) {
                case ITEM -> exists(Registries.ITEM, target);
                case ENTITY -> exists(Registries.ENTITY_TYPE, target);
                case DIMENSION -> exists(Registries.LEVEL_STEM, target);
                case BIOME -> exists(Registries.BIOME, target);
                case STRUCTURE -> exists(Registries.STRUCTURE, target);
                case SOUND -> exists(Registries.SOUND_EVENT, target);
                default -> throw new IllegalArgumentException("Not a concrete registry kind: " + kind);
            };
        }

        @Override
        public Set<ResourceLocation> tagMembers(
            PreparedStoryData.IndexDeclaration.Kind kind,
            ResourceLocation target
        ) {
            return switch (kind) {
                case ITEM_TAG -> tagMembers(Registries.ITEM, target);
                case ENTITY_TAG -> tagMembers(Registries.ENTITY_TYPE, target);
                case BIOME_TAG -> tagMembers(Registries.BIOME, target);
                case STRUCTURE_TAG -> tagMembers(Registries.STRUCTURE, target);
                default -> throw new IllegalArgumentException("Not a tag kind: " + kind);
            };
        }

        @Override
        public boolean tagExists(
            PreparedStoryData.IndexDeclaration.Kind kind,
            ResourceLocation target
        ) {
            return switch (kind) {
                case ITEM_TAG -> tagExists(Registries.ITEM, target);
                case ENTITY_TAG -> tagExists(Registries.ENTITY_TYPE, target);
                case BIOME_TAG -> tagExists(Registries.BIOME, target);
                case STRUCTURE_TAG -> tagExists(Registries.STRUCTURE, target);
                default -> throw new IllegalArgumentException("Not a tag kind: " + kind);
            };
        }

        @Override
        public boolean advancementExists(ResourceLocation target) {
            return serverResources.getAdvancements().get(target) != null;
        }

        private <T> boolean exists(
            ResourceKey<? extends Registry<T>> registry,
            ResourceLocation target
        ) {
            return registries.lookup(registry)
                .flatMap(values -> values.get(ResourceKey.create(registry, target)))
                .isPresent();
        }

        private <T> Set<ResourceLocation> tagMembers(
            ResourceKey<? extends Registry<T>> registry,
            ResourceLocation target
        ) {
            return stagedTags.getTag(TagKey.create(registry, target)).stream()
                .map(holder -> holderKey(registry, target, holder))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        private <T> boolean tagExists(
            ResourceKey<? extends Registry<T>> registry,
            ResourceLocation target
        ) {
            return stagedTags.getAllTags(registry).containsKey(target);
        }

        private static <T> ResourceLocation holderKey(
            ResourceKey<? extends Registry<T>> registry,
            ResourceLocation tag,
            Holder<T> holder
        ) {
            return holder.unwrapKey()
                .map(ResourceKey::location)
                .orElseThrow(() -> new IllegalStateException(
                    "Staged tag " + tag + " contains an unkeyed member for registry " + registry.location()));
        }
    }
}
