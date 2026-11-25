package firefly520.fireflymc.ai;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.network.AiProtocol;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.client.ai.AiClientPreferences;
import firefly520.fireflymc.client.ai.QuestionReplyTarget;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuestionReplyTargetTest {
    private JsonObject question(UUID id, String version) {
        var q = new JsonObject(); q.addProperty("id", id.toString());
        q.addProperty("digest", AiJson.digest(version)); q.addProperty("prompt", "请确认屋顶颜色"); return q;
    }
    @Test void soleQuestionIsExplicitlyBoundAndMultipleQuestionsAreNeverGuessed() {
        var first = question(UUID.randomUUID(), "first"); var second = question(UUID.randomUUID(), "second");
        var target = QuestionReplyTarget.onlyPending(List.of(first)).orElseThrow();
        assertTrue(target.current(List.of(first, second)));
        assertTrue(QuestionReplyTarget.onlyPending(List.of(first, second)).isEmpty());
        assertTrue(QuestionReplyTarget.onlyPending(List.of()).isEmpty());
        assertFalse(target.current(List.of(second)));
        assertFalse(target.current(List.of(question(target.id(), "changed"))));
    }
    @Test void disconnectedAnswerRetainsQuestionVersionRequestAndAnswerAction() {
        var target = QuestionReplyTarget.from(question(UUID.randomUUID(), "original"));
        var original = target.answer(UUID.randomUUID(), "只是测试，没有具体需求");
        var restored = AiJson.GSON.fromJson(AiJson.GSON.toJson(original), AiClientPreferences.Outbox.class);
        assertEquals(original, restored);
        assertEquals(AiProtocol.Action.QUESTION_ANSWER, restored.action());
        assertEquals(target.id().toString(), restored.body().get("id").getAsString());
        assertEquals(target.digest(), restored.body().get("digest").getAsString());
        assertEquals(original.text(), restored.body().get("answer").getAsString());
        assertFalse(restored.body().has("text"));
    }
    @Test void theOnlyCurrentBlockerWinsOverOptionalSuggestionsWithoutGuessingBetweenBlockers() {
        var advisory = question(UUID.randomUUID(), "advisory"); advisory.addProperty("blocking", false);
        var current = question(UUID.randomUUID(), "current"); current.addProperty("blocking", true);
        assertEquals(current.get("id").getAsString(), QuestionReplyTarget.onlyPending(List.of(advisory, current)).orElseThrow().id().toString());
        var other = question(UUID.randomUUID(), "other"); other.addProperty("blocking", true);
        assertTrue(QuestionReplyTarget.onlyPending(List.of(current, other, advisory)).isEmpty());
    }
    @Test void plainUnsentInputStaysPlainAndMalformedReplyCannotBecomeNewMessage() {
        UUID request = UUID.randomUUID();
        var restored = AiJson.GSON.fromJson("{\"requestId\":\"" + request + "\",\"text\":\"你好\"}", AiClientPreferences.Outbox.class);
        assertEquals(AiProtocol.Action.SUBMIT, restored.action());
        assertEquals("你好", restored.body().get("text").getAsString());
        assertThrows(IllegalArgumentException.class, () -> new AiClientPreferences.Outbox(request, "回答", UUID.randomUUID(), ""));
        assertThrows(IllegalArgumentException.class, () -> new AiClientPreferences.Outbox(request, "回答", null, AiJson.digest("forged")));
    }
}
