package firefly520.fireflymc.story.condition;

import java.util.regex.Pattern;
import net.minecraft.resources.ResourceLocation;

/** A typed, immutable story condition produced by a registered codec. */
public interface StoryCondition {
    Pattern STATE_NAME_PATTERN = Pattern.compile("[a-z0-9_./-]+");

    ConditionPersistence persistence();

    /** Player/world scope shared by flag conditions and flag-changing actions. */
    enum Scope {
        PLAYER("player"),
        WORLD("world");

        private final String serializedName;

        Scope(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        public static Scope fromSerializedName(String value) {
            return switch (value) {
                case "player" -> PLAYER;
                case "world" -> WORLD;
                default -> throw new IllegalArgumentException("scope must be player or world");
            };
        }
    }

    /** Names double as typed ResourceLocation index keys, so '..' path segments are forbidden. */
    static boolean isValidStateName(String name) {
        return name != null
            && STATE_NAME_PATTERN.matcher(name).matches()
            && ResourceLocation.tryParse(name) != null
            && !name.isBlank()
            && java.util.Arrays.stream(name.split("/", -1)).noneMatch(".."::equals);
    }
}
