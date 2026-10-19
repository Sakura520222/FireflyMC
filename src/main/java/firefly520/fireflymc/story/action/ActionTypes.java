package firefly520.fireflymc.story.action;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceLocation;
import firefly520.fireflymc.story.condition.StoryCondition;

/** Typed Gson dispatch registry for built-in and addon-provided actions. */
public final class ActionTypes {
    private static final Map<ResourceLocation, MapCodec<? extends StoryAction>> CODECS =
        new ConcurrentHashMap<>();

    static {
        register(type("fireflymc:set_flag"), gsonCodec(SetFlagAction::fromJson));
        register(type("fireflymc:set_var"), gsonCodec(SetVarAction::fromJson));
        register(type("fireflymc:unlock_quest"), gsonCodec(UnlockQuestAction::fromJson));
        register(type("fireflymc:give_item"), gsonCodec(GiveItemAction::fromJson));
        register(type("fireflymc:play_sound"), gsonCodec(PlaySoundAction::fromJson));
        register(type("fireflymc:show_title"), gsonCodec(ShowTitleAction::fromJson));
        register(type("fireflymc:send_message"), gsonCodec(SendMessageAction::fromJson));
        register(type("fireflymc:execute_command"), gsonCodec(ExecuteCommandAction::fromJson));
        register(type("fireflymc:teleport"), gsonCodec(TeleportAction::fromJson));
    }

    private ActionTypes() {}

    public static synchronized <T extends StoryAction> void register(
        ResourceLocation type, MapCodec<T> codec
    ) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(codec, "codec");
        MapCodec<? extends StoryAction> previous = CODECS.putIfAbsent(type, codec);
        if (previous != null) {
            throw new IllegalStateException("Action type already registered: " + type);
        }
    }

    public static StoryAction decode(ResourceLocation type, JsonElement json) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(json, "json");
        MapCodec<? extends StoryAction> codec = CODECS.get(type);
        if (codec == null) {
            throw new IllegalArgumentException("Unknown action type: " + type);
        }

        return codec.codec()
            .parse(JsonOps.INSTANCE, json)
            .getOrThrow(message -> new IllegalArgumentException(type + ": " + message));
    }

    public record SetFlagAction(String flag, boolean value, StoryCondition.Scope scope) implements StoryAction {
        public SetFlagAction {
            validateStateName(flag, "flag");
            Objects.requireNonNull(scope, "scope");
        }

        static SetFlagAction fromJson(JsonObject json) {
            return new SetFlagAction(
                requiredString(json, "flag"),
                requiredBoolean(json, "value"),
                requiredScope(json));
        }
    }

    public record SetVarAction(
        String var,
        Operation operation,
        int amount,
        Integer min,
        Integer max
    ) implements StoryAction {
        public SetVarAction {
            validateStateName(var, "var");
            Objects.requireNonNull(operation, "op");
            if (min != null && max != null && min > max) {
                throw new IllegalArgumentException("min/max requires min <= max");
            }
        }

        static SetVarAction fromJson(JsonObject json) {
            return new SetVarAction(
                requiredString(json, "var"),
                Operation.fromSerializedName(requiredString(json, "op")),
                requiredInt(json, "amount"),
                optionalInt(json, "min"),
                optionalInt(json, "max"));
        }

        public enum Operation {
            SET("set"), ADD("add");

            private final String serializedName;

            Operation(String serializedName) {
                this.serializedName = serializedName;
            }

            public String serializedName() {
                return serializedName;
            }

            public static Operation fromSerializedName(String value) {
                return switch (value) {
                    case "set" -> SET;
                    case "add" -> ADD;
                    default -> throw new IllegalArgumentException("op must be set or add");
                };
            }
        }
    }

    public record UnlockQuestAction(ResourceLocation quest) implements StoryAction {
        public UnlockQuestAction {
            Objects.requireNonNull(quest, "quest");
        }

        static UnlockQuestAction fromJson(JsonObject json) {
            return new UnlockQuestAction(requiredResource(json, "quest"));
        }
    }

    public record GiveItemAction(ResourceLocation item, int count) implements StoryAction {
        public GiveItemAction {
            Objects.requireNonNull(item, "item");
            if (count <= 0) {
                throw new IllegalArgumentException("count must be strictly positive");
            }
        }

        static GiveItemAction fromJson(JsonObject json) {
            return new GiveItemAction(requiredResource(json, "item"), requiredPositiveInt(json, "count"));
        }
    }

    public record PlaySoundAction(ResourceLocation sound, float volume, float pitch) implements StoryAction {
        public PlaySoundAction {
            Objects.requireNonNull(sound, "sound");
            requireFinite(volume, "volume");
            requireFinite(pitch, "pitch");
        }

        static PlaySoundAction fromJson(JsonObject json) {
            return new PlaySoundAction(
                requiredResource(json, "sound"),
                requiredFloat(json, "volume"),
                requiredFloat(json, "pitch"));
        }
    }

    public record ShowTitleAction(
        Component title,
        Component subtitle,
        int fadeIn,
        int stay,
        int fadeOut
    ) implements StoryAction {
        public ShowTitleAction {
            Objects.requireNonNull(title, "title");
        }

        static ShowTitleAction fromJson(JsonObject json) {
            return new ShowTitleAction(
                requiredComponent(json, "title"),
                optionalComponent(json, "subtitle"),
                requiredInt(json, "fade_in"),
                requiredInt(json, "stay"),
                requiredInt(json, "fade_out"));
        }
    }

    public record SendMessageAction(Component text) implements StoryAction {
        public SendMessageAction {
            Objects.requireNonNull(text, "text");
        }

        static SendMessageAction fromJson(JsonObject json) {
            return new SendMessageAction(requiredComponent(json, "text"));
        }
    }

    public record ExecuteCommandAction(String command) implements StoryAction {
        public ExecuteCommandAction {
            Objects.requireNonNull(command, "command");
            if (command.isBlank()) {
                throw new IllegalArgumentException("command must not be blank");
            }
        }

        static ExecuteCommandAction fromJson(JsonObject json) {
            return new ExecuteCommandAction(requiredString(json, "command"));
        }
    }

    public record TeleportAction(ResourceLocation dimension, double x, double y, double z) implements StoryAction {
        public TeleportAction {
            Objects.requireNonNull(dimension, "dimension");
            requireFinite(x, "x");
            requireFinite(y, "y");
            requireFinite(z, "z");
        }

        static TeleportAction fromJson(JsonObject json) {
            return new TeleportAction(
                requiredResource(json, "dimension"),
                requiredDouble(json, "x"),
                requiredDouble(json, "y"),
                requiredDouble(json, "z"));
        }
    }

    private static <T> MapCodec<T> gsonCodec(Function<JsonObject, T> decoder) {
        return MapCodec.<T>unit(() -> null).mapResult(new MapCodec.ResultFunction<T>() {
            @Override
            public <S> DataResult<T> apply(
                DynamicOps<S> ops, MapLike<S> input, DataResult<T> previous
            ) {
                if (ops != JsonOps.INSTANCE) {
                    return DataResult.error(() -> "Action codec requires JsonOps");
                }
                try {
                    return DataResult.success(decoder.apply(asJsonObject(ops, input)));
                } catch (RuntimeException exception) {
                    return DataResult.error(() -> exception.getMessage() == null
                        ? exception.getClass().getSimpleName()
                        : exception.getMessage());
                }
            }

            @Override
            public <S> RecordBuilder<S> coApply(
                DynamicOps<S> ops, T input, RecordBuilder<S> prefix
            ) {
                return prefix;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <S> JsonObject asJsonObject(DynamicOps<S> ops, MapLike<S> input) {
        JsonObject result = new JsonObject();
        for (Pair<S, S> entry : input.entries().toList()) {
            JsonElement key = (JsonElement) entry.getFirst();
            JsonElement value = (JsonElement) entry.getSecond();
            if (!key.isJsonPrimitive() || !key.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("action field names must be strings");
            }
            result.add(key.getAsString(), value);
        }
        return result;
    }

    private static ResourceLocation type(String value) {
        return ResourceLocation.parse(value);
    }

    static void validateStateName(String name, String field) {
        Objects.requireNonNull(name, field);
        if (!StoryCondition.isValidStateName(name)) {
            throw new IllegalArgumentException(
                field + " must match [a-z0-9_./-]+, be a valid ResourceLocation path, and reject '..' segments");
        }
    }

    static ResourceLocation requiredResource(JsonObject json, String field) {
        String value = requiredString(json, field);
        ResourceLocation parsed = ResourceLocation.tryParse(value);
        if (parsed == null) {
            throw new IllegalArgumentException(field + " is not a valid ResourceLocation: " + value);
        }
        return parsed;
    }

    static String requiredString(JsonObject json, String field) {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return element.getAsString();
    }

    static boolean requiredBoolean(JsonObject json, String field) {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return element.getAsBoolean();
    }

    static int requiredInt(JsonObject json, String field) {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            return new BigDecimal(element.getAsString()).intValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException(field + " must be an exact integer", exception);
        }
    }

    static int requiredPositiveInt(JsonObject json, String field) {
        int value = requiredInt(json, field);
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be strictly positive");
        }
        return value;
    }

    static Integer optionalInt(JsonObject json, String field) {
        if (!isPresent(json, field)) {
            return null;
        }
        return requiredInt(json, field);
    }

    static double requiredDouble(JsonObject json, String field) {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be a number");
        }
        double value = element.getAsDouble();
        requireFinite(value, field);
        return value;
    }

    static float requiredFloat(JsonObject json, String field) {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be a number");
        }
        float value = element.getAsFloat();
        requireFinite(value, field);
        return value;
    }

    static StoryCondition.Scope requiredScope(JsonObject json) {
        return StoryCondition.Scope.fromSerializedName(requiredString(json, "scope"));
    }

    static Component requiredComponent(JsonObject json, String field) {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return ComponentSerialization.CODEC
            .parse(JsonOps.INSTANCE, element)
            .result()
            .orElseThrow(() -> new IllegalArgumentException(field + " is not a valid Component"));
    }

    static Component optionalComponent(JsonObject json, String field) {
        if (!isPresent(json, field)) {
            return null;
        }
        return requiredComponent(json, field);
    }

    static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }

    static void requireFinite(float value, String field) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }

    private static boolean isPresent(JsonObject json, String field) {
        return json.has(field);
    }
}
