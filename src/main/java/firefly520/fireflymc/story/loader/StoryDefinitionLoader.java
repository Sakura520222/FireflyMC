package firefly520.fireflymc.story.loader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import firefly520.fireflymc.story.condition.StoryCondition;
import firefly520.fireflymc.story.definition.ChapterDefinition;
import firefly520.fireflymc.story.definition.QuestDefinition;
import firefly520.fireflymc.story.definition.StoryCodecs;
import firefly520.fireflymc.story.definition.StoryCodecs.StoryDefinitionException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;

/** Pure datapack preparation: parse, canonicalize, validate, and graph story definitions. */
public final class StoryDefinitionLoader {
    private static final String CHAPTER_PREFIX = "story/chapters/";
    private static final String QUEST_PREFIX = "story/quests/";
    private static final String JSON_SUFFIX = ".json";

    private StoryDefinitionLoader() {}

    public static PreparedStoryData prepare(Map<ResourceLocation, JsonElement> resources) {
        Objects.requireNonNull(resources, "resources");
        List<String> errors = new ArrayList<>();
        List<Map.Entry<ResourceLocation, JsonElement>> entries = new ArrayList<>(resources.entrySet());

        Map<ResourceLocation, ChapterDefinition> chapters = new LinkedHashMap<>();
        Map<ResourceLocation, QuestDefinition> quests = new LinkedHashMap<>();
        List<PreparedStoryData.CanonicalDefinition> canonicalDefinitions = new ArrayList<>();

        List<Map.Entry<ResourceLocation, JsonElement>> validEntries = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : entries) {
            if (entry.getKey() == null) {
                errors.add("resource path is required");
            } else if (entry.getValue() == null) {
                errors.add(resourcePath(entry.getKey()) + ": JSON is required");
            } else {
                validEntries.add(entry);
            }
        }
        validEntries.sort(Comparator.comparing(entry -> entry.getKey().toString()));

        for (Map.Entry<ResourceLocation, JsonElement> entry : validEntries) {
            ResourceLocation resource = entry.getKey();
            String path = resource.getPath();
            if (path.startsWith(CHAPTER_PREFIX)) {
                parseChapter(
                    resource, entry.getValue(), chapters, canonicalDefinitions, errors);
            } else if (path.startsWith(QUEST_PREFIX)) {
                parseQuest(
                    resource, entry.getValue(), quests, canonicalDefinitions, errors);
            }
        }
        canonicalDefinitions.sort(
            Comparator.comparing(
                    PreparedStoryData.CanonicalDefinition::id,
                    Comparator.comparing(ResourceLocation::toString))
                .thenComparing(PreparedStoryData.CanonicalDefinition::kind));
        if (!errors.isEmpty()) {
            throw new StoryDefinitionException(String.join("\n", errors));
        }

        StoryGraph graph = StoryGraph.fromQuests(quests);
        PreparedStoryData prepared = new PreparedStoryData(
            chapters, quests, graph, StoryValidator.indexDeclarations(quests), canonicalDefinitions);
        StoryValidator.validate(prepared);
        return prepared;
    }

    private static void parseChapter(
        ResourceLocation resource,
        JsonElement json,
        Map<ResourceLocation, ChapterDefinition> chapters,
        List<PreparedStoryData.CanonicalDefinition> canonicalDefinitions,
        List<String> errors
    ) {
        String definitionPath = definitionPath(resource, CHAPTER_PREFIX, 1);
        if (definitionPath == null) {
            errors.add(resourcePath(resource) + ": path must match story/chapters/<id>.json");
            return;
        }
        if (!StoryCondition.isValidStateName(definitionPath)) {
            errors.add(resourcePath(resource) + ": definition path must match [a-z0-9_./-]+"
                + ", be a valid ResourceLocation path, and reject '..' segments");
            return;
        }
        ResourceLocation id = definitionId(resource, definitionPath);
        if (id == null) {
            errors.add(resourcePath(resource) + ": path does not derive a valid chapter id");
            return;
        }
        try {
            ChapterDefinition chapter = StoryCodecs.parseChapter(id, json);
            if (chapters.putIfAbsent(id, chapter) != null) {
                errors.add(resourcePath(resource) + ": duplicate chapter id " + id);
            }
        } catch (RuntimeException exception) {
            errors.add(message(exception));
            return;
        }
        addCanonicalDefinition("chapter", resource, id, json, canonicalDefinitions, errors);
    }

    private static void parseQuest(
        ResourceLocation resource,
        JsonElement json,
        Map<ResourceLocation, QuestDefinition> quests,
        List<PreparedStoryData.CanonicalDefinition> canonicalDefinitions,
        List<String> errors
    ) {
        String definitionPath = definitionPath(resource, QUEST_PREFIX, 2);
        if (definitionPath == null) {
            errors.add(resourcePath(resource)
                + ": path must match story/quests/<chapter>/<id>.json");
            return;
        }
        if (!StoryCondition.isValidStateName(definitionPath)) {
            errors.add(resourcePath(resource) + ": definition path must match [a-z0-9_./-]+"
                + ", be a valid ResourceLocation path, and reject '..' segments");
            return;
        }
        ResourceLocation id = definitionId(resource, definitionPath);
        if (id == null) {
            errors.add(resourcePath(resource) + ": path does not derive a valid quest id");
            return;
        }
        try {
            QuestDefinition quest = StoryCodecs.parseQuest(id, json);
            if (quests.putIfAbsent(id, quest) != null) {
                errors.add(resourcePath(resource) + ": duplicate quest id " + id);
            }
        } catch (RuntimeException exception) {
            errors.add(message(exception));
            return;
        }
        addCanonicalDefinition("quest", resource, id, json, canonicalDefinitions, errors);
    }

    private static String definitionPath(
        ResourceLocation resource,
        String prefix,
        int requiredSegments
    ) {
        String path = resource.getPath();
        if (!path.endsWith(JSON_SUFFIX)) {
            return null;
        }
        String result = path.substring(prefix.length(), path.length() - JSON_SUFFIX.length());
        if (result.isEmpty() || result.startsWith("/") || result.endsWith("/")) {
            return null;
        }
        if (result.split("/", -1).length != requiredSegments) {
            return null;
        }
        return result;
    }

    private static void addCanonicalDefinition(
        String kind,
        ResourceLocation resource,
        ResourceLocation id,
        JsonElement json,
        List<PreparedStoryData.CanonicalDefinition> canonicalDefinitions,
        List<String> errors
    ) {
        try {
            canonicalDefinitions.add(
                new PreparedStoryData.CanonicalDefinition(kind, id, canonicalJson(json)));
        } catch (RuntimeException exception) {
            errors.add(resourcePath(resource) + ": canonicalization failed: " + message(exception));
        }
    }

    private static ResourceLocation definitionId(ResourceLocation resource, String definitionPath) {
        return ResourceLocation.tryParse(resource.getNamespace() + ":" + definitionPath);
    }

    private static String canonicalJson(JsonElement element) {
        return canonicalize(element).toString();
    }

    private static JsonElement canonicalize(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return JsonNull.INSTANCE;
        }
        if (element.isJsonArray()) {
            JsonArray result = new JsonArray();
            element.getAsJsonArray().forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        if (element.isJsonObject()) {
            TreeMap<String, JsonElement> members = new TreeMap<>();
            element.getAsJsonObject().entrySet()
                .forEach(entry -> members.put(entry.getKey(), canonicalize(entry.getValue())));
            JsonObject result = new JsonObject();
            members.forEach(result::add);
            return result;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            BigDecimal number = primitive.getAsBigDecimal().stripTrailingZeros();
            return new JsonPrimitive(number);
        }
        return primitive.deepCopy();
    }

    private static String resourcePath(ResourceLocation resource) {
        return "data/" + resource.getNamespace() + "/" + resource.getPath();
    }

    private static String message(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }
}
