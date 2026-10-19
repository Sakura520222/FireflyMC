package firefly520.fireflymc.story.loader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable SHA-256 revision over canonical chapter and quest definitions. */
public final class StoryRevision {
    private StoryRevision() {}

    public static String sha256(PreparedStoryData prepared) {
        if (prepared == null) {
            throw new IllegalArgumentException("prepared is required");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (PreparedStoryData.CanonicalDefinition definition : prepared.canonicalDefinitions()) {
                updatePrefixed(digest, definition.kind());
                updatePrefixed(digest, definition.id().toString());
                updatePrefixed(digest, definition.canonicalJson());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void updatePrefixed(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(intBytes(bytes.length));
        digest.update(bytes);
    }

    private static byte[] intBytes(int value) {
        return new byte[] {
            (byte) (value >>> 24),
            (byte) (value >>> 16),
            (byte) (value >>> 8),
            (byte) value
        };
    }
}
