package firefly520.fireflymc.client.ai;

import com.google.gson.JsonObject;
import java.util.*;

/** An exact pending question reference; free text never guesses which task to answer. */
public record QuestionReplyTarget(UUID id, String digest, String prompt) {
    public QuestionReplyTarget {
        Objects.requireNonNull(id);
        if (digest == null || !digest.matches("[0-9a-f]{64}") || prompt == null)
            throw new IllegalArgumentException("INVALID_QUESTION_REPLY_TARGET");
    }
    public static Optional<QuestionReplyTarget> onlyPending(List<JsonObject> questions) {
        var blockers = questions.stream().filter(question -> question.has("blocking") && question.get("blocking").getAsBoolean()).toList();
        if (!blockers.isEmpty()) questions = blockers;
        if (questions.size() != 1 || !questions.getFirst().has("digest")) return Optional.empty();
        return Optional.of(from(questions.getFirst()));
    }
    public static QuestionReplyTarget from(JsonObject question) {
        return new QuestionReplyTarget(UUID.fromString(question.get("id").getAsString()),
                question.get("digest").getAsString(), question.get("prompt").getAsString());
    }
    public boolean current(List<JsonObject> questions) {
        return questions.stream().anyMatch(question -> question.has("id") && question.has("digest")
                && id.toString().equals(question.get("id").getAsString()) && digest.equals(question.get("digest").getAsString()));
    }
    public AiClientPreferences.Outbox answer(UUID request, String text) {
        return new AiClientPreferences.Outbox(request, text, id, digest);
    }
}
