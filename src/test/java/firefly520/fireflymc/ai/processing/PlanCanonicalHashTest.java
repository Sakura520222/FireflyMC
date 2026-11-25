package firefly520.fireflymc.ai.processing;
import com.google.gson.JsonParser;
import firefly520.fireflymc.ai.storage.AiJson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PlanCanonicalHashTest {
    @Test void nestedMapOrderDoesNotInvalidateAnUnchangedPersistedPlan() {
        var a = JsonParser.parseString("{\"policy\":\"MATERIALS_ONLY\",\"params\":{\"fortune\":0,\"tool\":\"iron\"},\"inputs\":[1,2]}");
        var b = JsonParser.parseString("{\"inputs\":[1,2],\"params\":{\"tool\":\"iron\",\"fortune\":0},\"policy\":\"MATERIALS_ONLY\"}");
        assertEquals(AiJson.digest(AiJson.canonical(a)), AiJson.digest(AiJson.canonical(b)));
        b.getAsJsonObject().getAsJsonArray("inputs").set(0, new com.google.gson.JsonPrimitive(2));
        assertNotEquals(AiJson.digest(AiJson.canonical(a)), AiJson.digest(AiJson.canonical(b)));
    }
}
