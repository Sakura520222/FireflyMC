package firefly520.fireflymc.story.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import firefly520.fireflymc.story.action.ActionTypes;
import static firefly520.fireflymc.story.action.ActionTypes.SetFlagAction;
import static firefly520.fireflymc.story.action.ActionTypes.SetVarAction;
import firefly520.fireflymc.story.action.StoryAction;
import firefly520.fireflymc.story.condition.ConditionTypes;
import static firefly520.fireflymc.story.condition.ConditionTypes.FlagCondition;
import firefly520.fireflymc.story.condition.StoryCondition;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceLocation;

/** Strict parsers for immutable chapter and quest definitions. */
public final class StoryCodecs {
    private StoryCodecs() {}

    public static ChapterDefinition parseChapter(ResourceLocation id, JsonElement json) {
        if (id == null) {
            throw new StoryDefinitionException("id is required");
        }
        String file = chapterFile(id);
        if (json == null) {
            throw new StoryDefinitionException(file + ": root must be an object");
        }
        return withFileContext(file, () -> parseChapterUnchecked(id, json));
    }

    public static QuestDefinition parseQuest(ResourceLocation id, JsonElement json) {
        if (id == null) {
            throw new StoryDefinitionException("id is required");
        }
        String file = questFile(id);
        if (json == null) {
            throw new StoryDefinitionException(file + ": root must be an object");
        }
        return withFileContext(file, () -> parseQuestUnchecked(id, json));
    }

    private static ChapterDefinition parseChapterUnchecked(ResourceLocation id, JsonElement json) {
        JsonObject root = requireObject(json, "");
        return new ChapterDefinition(
            id,
            requiredComponent(root, "title"),
            requiredComponent(root, "subtitle"),
            requiredInt(root, "index"));
    }

    private static QuestDefinition parseQuestUnchecked(ResourceLocation id, JsonElement json) {
        JsonObject root = requireObject(json, "");

        ResourceLocation chapter = requiredResource(root, "chapter");
        Component title = requiredComponent(root, "title");
        Component subtitle = optionalComponent(root, "subtitle");
        if (subtitle == null) {
            subtitle = Component.empty();
        }
        List<Component> description = root.has("description")
            ? parseDescription(root.get("description"))
            : List.of();
        String icon = optionalIcon(root);
        boolean initial = optionalBoolean(root, "initial", false);
        List<RequirementDefinition> requirements = root.has("requirements")
            ? parseRequirements(root.get("requirements"))
            : List.of();
        List<StoryAction> onComplete = root.has("on_complete")
            ? parseActions(root.get("on_complete"))
            : List.of();

        rejectStateNameCollisions(requirements, onComplete);

        return new QuestDefinition(
            id,
            chapter,
            title,
            subtitle,
            description,
            icon,
            initial,
            requirements,
            onComplete);
    }

    private static List<Component> parseDescription(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            throw new StoryDefinitionException("description must be an array");
        }
        if (!element.isJsonArray()) {
            throw new StoryDefinitionException("description must be an array");
        }
        List<Component> result = new ArrayList<>();
        int index = 0;
        for (JsonElement item : element.getAsJsonArray()) {
            try {
                result.add(parseComponent(item));
            } catch (RuntimeException exception) {
                throw new StoryDefinitionException("description[" + index + "]: " + message(exception));
            }
            index++;
        }
        return result;
    }

    private static String optionalIcon(JsonObject root) {
        if (!root.has("icon")) {
            return null;
        }
        JsonElement element = root.get("icon");
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new StoryDefinitionException("icon must be a string");
        }
        String icon = element.getAsString();
        if (icon.isBlank()) {
            throw new StoryDefinitionException("icon must not be blank");
        }
        return icon;
    }

    private static List<RequirementDefinition> parseRequirements(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            throw new StoryDefinitionException("requirements must be an array");
        }
        if (!element.isJsonArray()) {
            throw new StoryDefinitionException("requirements must be an array");
        }

        JsonArray array = element.getAsJsonArray();
        List<RequirementDefinition> result = new ArrayList<>(array.size());
        Set<String> seenIds = new HashSet<>();
        for (int index = 0; index < array.size(); index++) {
            String field = "requirements[" + index + "]";
            JsonElement item = array.get(index);
            if (!item.isJsonObject()) {
                throw new StoryDefinitionException(field + " must be an object");
            }
            JsonObject object = item.getAsJsonObject();

            String id = requiredString(object, "id");
            if (!seenIds.add(id)) {
                throw new StoryDefinitionException(field + ".id duplicates requirement " + id);
            }
            int stateVersion;
            try {
                stateVersion = optionalInt(object, "state_version", 1);
            } catch (RuntimeException exception) {
                throw new StoryDefinitionException(field + "." + message(exception));
            }
            StoryCondition condition = parseCondition(object, field);
            try {
                result.add(new RequirementDefinition(id, stateVersion, condition));
            } catch (RuntimeException exception) {
                throw new StoryDefinitionException(field + ": " + message(exception));
            }
        }
        return result;
    }

    private static StoryCondition parseCondition(JsonObject object, String field) {
        String rawType = requiredString(object, "type");
        ResourceLocation type = tryParseResource(rawType);
        if (type == null) {
            throw new StoryDefinitionException(field + ".type is not a valid ResourceLocation: " + rawType);
        }
        try {
            return ConditionTypes.decode(type, object);
        } catch (RuntimeException exception) {
            String detail = message(exception);
            if (detail.startsWith("Unknown condition type:")) {
                throw new StoryDefinitionException(field + ".type: " + detail);
            }
            String typePrefix = type + ": ";
            if (detail.startsWith(typePrefix)) {
                detail = detail.substring(typePrefix.length());
            }
            throw new StoryDefinitionException(field + "." + detail);
        }
    }

    private static List<StoryAction> parseActions(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            throw new StoryDefinitionException("on_complete must be an array");
        }
        if (!element.isJsonArray()) {
            throw new StoryDefinitionException("on_complete must be an array");
        }

        JsonArray array = element.getAsJsonArray();
        List<StoryAction> result = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            String field = "on_complete[" + index + "]";
            JsonElement item = array.get(index);
            if (!item.isJsonObject()) {
                throw new StoryDefinitionException(field + " must be an object");
            }
            JsonObject object = item.getAsJsonObject();
            String rawType = requiredString(object, "type");
            ResourceLocation type = tryParseResource(rawType);
            if (type == null) {
                throw new StoryDefinitionException(field + ".type is not a valid ResourceLocation: " + rawType);
            }
            try {
                result.add(ActionTypes.decode(type, object));
            } catch (RuntimeException exception) {
                String detail = message(exception);
                if (detail.startsWith("Unknown action type:")) {
                    throw new StoryDefinitionException(field + ".type: " + detail);
                }
                String typePrefix = type + ": ";
                if (detail.startsWith(typePrefix)) {
                    detail = detail.substring(typePrefix.length());
                }
                throw new StoryDefinitionException(field + "." + detail);
            }
        }
        return result;
    }

    private static void rejectStateNameCollisions(
        List<RequirementDefinition> requirements,
        List<StoryAction> actions
    ) {
        Map<StoryCondition.Scope, Set<String>> flags = new EnumMap<>(StoryCondition.Scope.class);
        Map<StoryCondition.Scope, Set<String>> vars = new EnumMap<>(StoryCondition.Scope.class);
        for (StoryCondition.Scope scope : StoryCondition.Scope.values()) {
            flags.put(scope, new HashSet<>());
            vars.put(scope, new HashSet<>());
        }

        for (RequirementDefinition requirement : requirements) {
            if (requirement.condition() instanceof FlagCondition flag) {
                flags.get(flag.scope()).add(flag.flag());
            }
        }

        for (int index = 0; index < actions.size(); index++) {
            StoryAction action = actions.get(index);
            String field = "on_complete[" + index + "]";
            if (action instanceof SetFlagAction flag) {
                if (vars.get(flag.scope()).contains(flag.flag())) {
                    throw new StoryDefinitionException(field + ".flag collides with a var in the same scope");
                }
                flags.get(flag.scope()).add(flag.flag());
            } else if (action instanceof SetVarAction var) {
                if (flags.get(StoryCondition.Scope.PLAYER).contains(var.var())) {
                    throw new StoryDefinitionException(field + ".var collides with a flag in the same scope");
                }
                vars.get(StoryCondition.Scope.PLAYER).add(var.var());
            }
        }
    }

    private static <T> T withFileContext(String file, Supplier<T> parser) {
        try {
            return parser.get();
        } catch (StoryDefinitionException exception) {
            throw new StoryDefinitionException(file + ": " + exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            throw new StoryDefinitionException(file + ": " + message(exception), exception);
        }
    }

    private static JsonObject requireObject(JsonElement json, String field) {
        if (!json.isJsonObject()) {
            throw new StoryDefinitionException((field.isEmpty() ? "root" : field) + " must be an object");
        }
        return json.getAsJsonObject();
    }

    private static Component requiredComponent(JsonObject json, String field) {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull()) {
            throw new StoryDefinitionException(field + " is required");
        }
        return parseComponentOrThrow(element, field);
    }

    private static Component optionalComponent(JsonObject json, String field) {
        if (!json.has(field)) {
            return null;
        }
        return parseComponentOrThrow(json.get(field), field);
    }

    private static Component parseComponentOrThrow(JsonElement element, String field) {
        return ComponentSerialization.CODEC
            .parse(com.mojang.serialization.JsonOps.INSTANCE, element)
            .result()
            .orElseThrow(() -> new StoryDefinitionException(field + " is not a valid Component"));
    }

    private static Component parseComponent(JsonElement element) {
        return ComponentSerialization.CODEC
            .parse(com.mojang.serialization.JsonOps.INSTANCE, element)
            .result()
            .orElseThrow(() -> new IllegalArgumentException("invalid Component"));
    }

    private static ResourceLocation requiredResource(JsonObject json, String field) {
        String value = requiredString(json, field);
        ResourceLocation parsed = tryParseResource(value);
        if (parsed == null) {
            throw new StoryDefinitionException(field + " is not a valid ResourceLocation: " + value);
        }
        return parsed;
    }

    private static ResourceLocation tryParseResource(String value) {
        return ResourceLocation.tryParse(value);
    }

    private static String requiredString(JsonObject json, String field) {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull()) {
            throw new StoryDefinitionException(field + " is required");
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new StoryDefinitionException(field + " must be a string");
        }
        return element.getAsString();
    }

    private static boolean optionalBoolean(JsonObject json, String field, boolean fallback) {
        if (!json.has(field)) {
            return fallback;
        }
        JsonElement element = json.get(field);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new StoryDefinitionException(field + " must be a boolean");
        }
        return element.getAsBoolean();
    }

    private static int requiredInt(JsonObject json, String field) {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull()) {
            throw new StoryDefinitionException(field + " is required");
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new StoryDefinitionException(field + " must be an integer");
        }
        try {
            return new BigDecimal(element.getAsString()).intValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new StoryDefinitionException(field + " must be an exact integer");
        }
    }

    private static int optionalInt(JsonObject json, String field, int fallback) {
        if (!json.has(field)) {
            return fallback;
        }
        int value = requiredInt(json, field);
        if (value < 1) {
            throw new StoryDefinitionException(field + " must be at least 1");
        }
        return value;
    }

    private static String chapterFile(ResourceLocation id) {
        return "data/" + id.getNamespace() + "/story/chapters/" + id.getPath() + ".json";
    }

    private static String questFile(ResourceLocation id) {
        return "data/" + id.getNamespace() + "/story/quests/" + id.getPath() + ".json";
    }

    private static String message(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    /** A strict parse failure carrying the definition file and field path. */
    public static final class StoryDefinitionException extends RuntimeException {
        public StoryDefinitionException(String message) {
            super(message);
        }

        public StoryDefinitionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
