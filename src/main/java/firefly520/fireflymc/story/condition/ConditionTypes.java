package firefly520.fireflymc.story.condition;

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
import net.minecraft.resources.ResourceLocation;

/** Typed Gson dispatch registry for built-in and addon-provided conditions. */
public final class ConditionTypes {
    private static final Map<ResourceLocation, MapCodec<? extends StoryCondition>> CODECS =
        new ConcurrentHashMap<>();

    static {
        register(type("fireflymc:pickup"), gsonCodec(PickupCondition::fromJson));
        register(type("fireflymc:kill"), gsonCodec(KillCondition::fromJson));
        register(type("fireflymc:custom_event"), gsonCodec(CustomEventCondition::fromJson));
        register(type("fireflymc:enter_dimension"), gsonCodec(EnterDimensionCondition::fromJson));
        register(type("fireflymc:enter_biome"), gsonCodec(EnterBiomeCondition::fromJson));
        register(type("fireflymc:enter_structure"), gsonCodec(EnterStructureCondition::fromJson));
        register(type("fireflymc:advancement"), gsonCodec(AdvancementCondition::fromJson));
        register(type("fireflymc:location"), gsonCodec(LocationCondition::fromJson));
        register(type("fireflymc:inventory"), gsonCodec(InventoryCondition::fromJson));
        register(type("fireflymc:flag"), gsonCodec(FlagCondition::fromJson));
    }

    private ConditionTypes() {}

    public static synchronized <T extends StoryCondition> void register(
        ResourceLocation type, MapCodec<T> codec
    ) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(codec, "codec");
        MapCodec<? extends StoryCondition> previous = CODECS.putIfAbsent(type, codec);
        if (previous != null) {
            throw new IllegalStateException("Condition type already registered: " + type);
        }
    }

    public static StoryCondition decode(ResourceLocation type, JsonElement json) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(json, "json");
        MapCodec<? extends StoryCondition> codec = CODECS.get(type);
        if (codec == null) {
            throw new IllegalArgumentException("Unknown condition type: " + type);
        }

        return codec.codec()
            .parse(JsonOps.INSTANCE, json)
            .getOrThrow(message -> new IllegalArgumentException(type + ": " + message));
    }

    public record PickupCondition(ResourceLocation item, int count) implements StoryCondition {
        public PickupCondition {
            Objects.requireNonNull(item, "item");
            requirePositive(count, "count");
        }

        @Override
        public ConditionPersistence persistence() {
            return ConditionPersistence.COUNTER;
        }

        static PickupCondition fromJson(JsonObject json) {
            return new PickupCondition(requiredResource(json, "item"), requiredPositiveInt(json, "count"));
        }
    }

    public record KillCondition(
        ResourceLocation entity,
        ResourceLocation entityTag,
        int count
    ) implements StoryCondition {
        public KillCondition {
            requireExactlyOne(entity, entityTag, "entity", "entity_tag");
            Objects.requireNonNull(entity == null ? entityTag : entity, "entity/entity_tag");
            requirePositive(count, "count");
        }

        @Override
        public ConditionPersistence persistence() {
            return ConditionPersistence.COUNTER;
        }

        static KillCondition fromJson(JsonObject json) {
            requireExactlyOneField(json, "entity", "entity_tag");
            return new KillCondition(
                optionalResource(json, "entity"),
                optionalResource(json, "entity_tag"),
                requiredPositiveInt(json, "count"));
        }
    }

    public record CustomEventCondition(ResourceLocation event, int count) implements StoryCondition {
        public CustomEventCondition {
            Objects.requireNonNull(event, "event");
            requirePositive(count, "count");
        }

        @Override
        public ConditionPersistence persistence() {
            return ConditionPersistence.COUNTER;
        }

        static CustomEventCondition fromJson(JsonObject json) {
            return new CustomEventCondition(requiredResource(json, "event"), requiredPositiveInt(json, "count"));
        }
    }

    public record EnterDimensionCondition(ResourceLocation dimension) implements StoryCondition {
        public EnterDimensionCondition {
            Objects.requireNonNull(dimension, "dimension");
        }

        @Override
        public ConditionPersistence persistence() {
            return ConditionPersistence.STICKY;
        }

        static EnterDimensionCondition fromJson(JsonObject json) {
            return new EnterDimensionCondition(requiredResource(json, "dimension"));
        }
    }

    public record EnterBiomeCondition(ResourceLocation biome, ResourceLocation biomeTag) implements StoryCondition {
        public EnterBiomeCondition {
            requireExactlyOne(biome, biomeTag, "biome", "biome_tag");
            Objects.requireNonNull(biome == null ? biomeTag : biome, "biome/biome_tag");
        }

        @Override
        public ConditionPersistence persistence() {
            return ConditionPersistence.STICKY;
        }

        static EnterBiomeCondition fromJson(JsonObject json) {
            requireExactlyOneField(json, "biome", "biome_tag");
            return new EnterBiomeCondition(optionalResource(json, "biome"), optionalResource(json, "biome_tag"));
        }
    }

    public record EnterStructureCondition(
        ResourceLocation structure,
        ResourceLocation structureTag
    ) implements StoryCondition {
        public EnterStructureCondition {
            requireExactlyOne(structure, structureTag, "structure", "structure_tag");
            Objects.requireNonNull(structure == null ? structureTag : structure, "structure/structure_tag");
        }

        @Override
        public ConditionPersistence persistence() {
            return ConditionPersistence.STICKY;
        }

        static EnterStructureCondition fromJson(JsonObject json) {
            requireExactlyOneField(json, "structure", "structure_tag");
            return new EnterStructureCondition(
                optionalResource(json, "structure"),
                optionalResource(json, "structure_tag"));
        }
    }

    public record AdvancementCondition(ResourceLocation advancement) implements StoryCondition {
        public AdvancementCondition {
            Objects.requireNonNull(advancement, "id");
        }

        @Override
        public ConditionPersistence persistence() {
            return ConditionPersistence.STICKY;
        }

        static AdvancementCondition fromJson(JsonObject json) {
            return new AdvancementCondition(requiredResource(json, "id"));
        }
    }

    public record LocationCondition(
        ResourceLocation dimension,
        double x,
        double y,
        double z,
        float radius
    ) implements StoryCondition {
        public LocationCondition {
            Objects.requireNonNull(dimension, "dimension");
            requireFinite(x, "x");
            requireFinite(y, "y");
            requireFinite(z, "z");
            requireFinite(radius, "radius");
            if (radius < 0.0F) {
                throw new IllegalArgumentException("radius must be nonnegative");
            }
        }

        @Override
        public ConditionPersistence persistence() {
            return ConditionPersistence.STICKY;
        }

        static LocationCondition fromJson(JsonObject json) {
            return new LocationCondition(
                requiredResource(json, "dimension"),
                requiredDouble(json, "x"),
                requiredDouble(json, "y"),
                requiredDouble(json, "z"),
                requiredFloat(json, "radius"));
        }
    }

    public record InventoryCondition(ResourceLocation item, int count) implements StoryCondition {
        public InventoryCondition {
            Objects.requireNonNull(item, "item");
            requirePositive(count, "count");
        }

        @Override
        public ConditionPersistence persistence() {
            return ConditionPersistence.DYNAMIC;
        }

        static InventoryCondition fromJson(JsonObject json) {
            return new InventoryCondition(requiredResource(json, "item"), requiredPositiveInt(json, "count"));
        }
    }

    public record FlagCondition(String flag, boolean value, Scope scope) implements StoryCondition {
        public FlagCondition {
            validateStateName(flag, "flag");
            Objects.requireNonNull(scope, "scope");
        }

        @Override
        public ConditionPersistence persistence() {
            return ConditionPersistence.DYNAMIC;
        }

        static FlagCondition fromJson(JsonObject json) {
            return new FlagCondition(
                requiredString(json, "flag"),
                requiredBoolean(json, "value"),
                requiredScope(json));
        }
    }

    private static <T> MapCodec<T> gsonCodec(Function<JsonObject, T> decoder) {
        return MapCodec.<T>unit(() -> null).mapResult(new MapCodec.ResultFunction<T>() {
            @Override
            public <S> DataResult<T> apply(
                DynamicOps<S> ops, MapLike<S> input, DataResult<T> previous
            ) {
                if (ops != JsonOps.INSTANCE) {
                    return DataResult.error(() -> "Condition codec requires JsonOps");
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
                throw new IllegalArgumentException("condition field names must be strings");
            }
            result.add(key.getAsString(), value);
        }
        return result;
    }

    static void requirePositive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be strictly positive");
        }
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

    static void validateStateName(String name, String field) {
        Objects.requireNonNull(name, field);
        if (!StoryCondition.isValidStateName(name)) {
            throw new IllegalArgumentException(
                field + " must match [a-z0-9_./-]+, be a valid ResourceLocation path, and reject '..' segments");
        }
    }

    private static void requireExactlyOne(Object first, Object second, String firstName, String secondName) {
        if (first == null && second == null) {
            throw new IllegalArgumentException(firstName + "/" + secondName + " requires exactly one field");
        }
        if (first != null && second != null) {
            throw new IllegalArgumentException(firstName + "/" + secondName + " allows only one field");
        }
    }

    private static void requireExactlyOneField(JsonObject json, String firstName, String secondName) {
        boolean hasFirst = isPresent(json, firstName);
        boolean hasSecond = isPresent(json, secondName);
        if (hasFirst == hasSecond) {
            throw new IllegalArgumentException(firstName + "/" + secondName + " requires exactly one field");
        }
    }

    private static boolean isPresent(JsonObject json, String field) {
        return json.has(field);
    }

    private static ResourceLocation type(String value) {
        return ResourceLocation.parse(value);
    }

    static ResourceLocation requiredResource(JsonObject json, String field) {
        String value = requiredString(json, field);
        ResourceLocation parsed = ResourceLocation.tryParse(value);
        if (parsed == null) {
            throw new IllegalArgumentException(field + " is not a valid ResourceLocation: " + value);
        }
        return parsed;
    }

    static ResourceLocation optionalResource(JsonObject json, String field) {
        if (!isPresent(json, field)) {
            return null;
        }
        return requiredResource(json, field);
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
        requirePositive(value, field);
        return value;
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

}
