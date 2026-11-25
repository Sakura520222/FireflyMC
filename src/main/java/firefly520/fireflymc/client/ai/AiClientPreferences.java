package firefly520.fireflymc.client.ai;

import firefly520.fireflymc.ai.storage.AiJson;
import java.nio.file.*;
import java.util.*;

/** Only display preferences and unsent/unacknowledged input live on the client. */
public final class AiClientPreferences {
    public record Outbox(UUID requestId, String text, UUID questionId, String questionDigest) {
        public Outbox(UUID requestId, String text) { this(requestId, text, null, ""); }
        public Outbox {
            questionDigest = questionDigest == null ? "" : questionDigest;
            if (requestId == null || text == null || text.isBlank() || text.length() > (questionId == null ? 4096 : 2048)
                    || questionId == null && !questionDigest.isEmpty()
                    || questionId != null && !questionDigest.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("INVALID_CLIENT_OUTBOX");
        }
        public firefly520.fireflymc.ai.network.AiProtocol.Action action() {
            return questionId == null ? firefly520.fireflymc.ai.network.AiProtocol.Action.SUBMIT
                    : firefly520.fireflymc.ai.network.AiProtocol.Action.QUESTION_ANSWER;
        }
        public com.google.gson.JsonObject body() {
            var body = new com.google.gson.JsonObject();
            if (questionId == null) body.addProperty("text", text);
            else {
                body.addProperty("id", questionId.toString()); body.addProperty("digest", questionDigest);
                body.addProperty("answer", text);
            }
            return body;
        }
    }
    public record Data(int version, double x, double y, double scale, double opacity,
                       boolean collapsed, boolean autoHide, String draft, List<Outbox> outbox, int noticeIntervalSeconds) {
        public Data(int version, double x, double y, double scale, double opacity, boolean collapsed, boolean autoHide,
                    String draft, List<Outbox> outbox) {
            this(version, x, y, scale, opacity, collapsed, autoHide, draft, outbox, 15);
        }
        public Data {
            if (version != 1 || !Double.isFinite(x) || !Double.isFinite(y) || x < 0 || x > 1 || y < 0 || y > 1
                    || !Double.isFinite(scale) || scale < .5 || scale > 2
                    || !Double.isFinite(opacity) || opacity < .2 || opacity > 1
                    || draft == null || draft.length() > 4096 || outbox == null || outbox.size() > 8
                    || noticeIntervalSeconds < 0 || noticeIntervalSeconds > 300)
                throw new IllegalArgumentException("INVALID_CLIENT_PREFERENCES");
            outbox = List.copyOf(outbox);
        }
        public static Data defaults() { return new Data(1, .02, .78, 1, .85, false, true, "", List.of()); }
        public Data withDraft(String draft, List<Outbox> outbox) {
            return new Data(1, x, y, scale, opacity, collapsed, autoHide, draft, outbox, noticeIntervalSeconds);
        }
        public Data move(double x, double y) {
            return new Data(1, Math.clamp(x, 0, 1), Math.clamp(y, 0, 1), scale, opacity, collapsed, autoHide, draft, outbox, noticeIntervalSeconds);
        }
    }
    private AiClientPreferences() {}
    public static Path path(Path gameDirectory, String server, UUID player) {
        if (server.isBlank()) throw new IllegalArgumentException("SERVER_ID_REQUIRED");
        return gameDirectory.resolve("config/fireflymc-ai-ui").resolve(AiJson.digest(server + ":" + player) + ".json");
    }
    public static Data load(Path path) {
        try {
            if (!Files.exists(path) || Files.isSymbolicLink(path) || Files.size(path) > 65536) return Data.defaults();
            return AiJson.GSON.fromJson(Files.readString(path), Data.class);
        } catch (Exception e) { return Data.defaults(); }
    }
    public static void save(Path path, Data value) throws java.io.IOException { AiJson.write(path, value); }
}
