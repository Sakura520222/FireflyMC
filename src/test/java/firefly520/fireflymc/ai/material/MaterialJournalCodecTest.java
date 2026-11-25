package firefly520.fireflymc.ai.material;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MaterialJournalCodecTest {
    private JsonObject state(long revision) {
        var value = new JsonObject();
        value.addProperty("version", 1); value.addProperty("operationId", "67ef17a8-c446-4e12-a9cb-8b5b2a4d27aa");
        value.addProperty("owner", "c63b4ed6-a5ef-4729-8e8a-264548f9b9a9"); value.addProperty("taskId", "70a7df99-51b1-438a-83c0-b597c0673963");
        value.addProperty("planHash", "a".repeat(64)); value.addProperty("policy", "MATERIALS_ONLY");
        value.addProperty("state", "ESCROWED"); value.addProperty("reason", "真实材料 💡"); value.addProperty("samplingEvidence", "");
        value.addProperty("revision", revision);
        for (String name : List.of("claims", "escrow", "products", "delivered", "sampledOutputs", "worldDebits", "externalDebits", "nativeSaves")) value.add(name, new JsonArray());
        return value;
    }
    @Test void appendUpdatesTruncationAndIntentRemovalRestoreEveryByteOfHistoricalValues() throws Exception {
        var expected = state(1); var restored = expected.deepCopy();
        long fullBytes = 0, encodedBytes = 0; int deltas = 0;
        for (int i = 1; i <= 256; i++) {
            var next = expected.deepCopy(); next.addProperty("revision", i + 1);
            JsonObject receipt = new JsonObject(); receipt.addProperty("step", i); receipt.addProperty("digest", "c".repeat(64));
            receipt.addProperty("nativeResult", "原生结果🙂:" + i); next.getAsJsonArray("worldDebits").add(receipt);
            if (i % 2 == 0) next.add("intent", JsonParser.parseString("{\"kind\":\"world\",\"witness\":\"fixed\"}")); else next.remove("intent");
            if (i % 3 == 0) next.getAsJsonArray("delivered").add(i);
            if (i % 5 == 0 && !next.getAsJsonArray("delivered").isEmpty()) next.getAsJsonArray("delivered").set(0, new JsonPrimitive(i * 2));
            if (i % 11 == 0) next.add("delivered", new JsonArray());
            var encoded = MaterialJournalCodec.encode(expected, next);
            restored = MaterialJournalCodec.decode(restored, encoded.kind(), encoded.data());
            assertEquals(next, restored); assertEquals("真实材料 💡", expected.get("reason").getAsString());
            fullBytes += next.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            encodedBytes += encoded.data().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (encoded.kind().equals(MaterialJournalCodec.DELTA)) deltas++;
            expected = next;
        }
        assertTrue(deltas > 200); assertTrue(encodedBytes < fullBytes / 10, "Growing steps must not rewrite every earlier receipt");
    }
    @Test void staleDuplicateForeignAndIncompleteDeltasFailClosed() throws Exception {
        var before = state(1); var after = before.deepCopy(); after.addProperty("revision", 2);
        after.getAsJsonArray("nativeSaves").add(JsonParser.parseString("{\"witness\":\"real-native\",\"receipt\":\"\"}"));
        var encoded = MaterialJournalCodec.encode(before, after); assertEquals(MaterialJournalCodec.DELTA, encoded.kind());
        var stale = encoded.data().deepCopy(); stale.addProperty("baseRevision", 2);
        assertThrows(IOException.class, () -> MaterialJournalCodec.decode(before, encoded.kind(), stale));
        var foreign = encoded.data().deepCopy(); foreign.getAsJsonObject("fields").addProperty("owner", "someone-else");
        assertThrows(IOException.class, () -> MaterialJournalCodec.decode(before, encoded.kind(), foreign));
        var missing = encoded.data().deepCopy(); missing.getAsJsonObject("arrays").getAsJsonObject("nativeSaves").add("entries", new JsonArray());
        assertThrows(IOException.class, () -> MaterialJournalCodec.decode(before, encoded.kind(), missing));
        var repeated = encoded.data().deepCopy(); var entries = repeated.getAsJsonObject("arrays").getAsJsonObject("nativeSaves").getAsJsonArray("entries"); entries.add(entries.get(0).deepCopy());
        assertThrows(IOException.class, () -> MaterialJournalCodec.decode(before, encoded.kind(), repeated));
        assertThrows(IOException.class, () -> MaterialJournalCodec.decode(after, encoded.kind(), encoded.data()));
        assertThrows(IOException.class, () -> MaterialJournalCodec.decode(null, encoded.kind(), encoded.data()));
    }
    @Test void arrayLimitsFractionalIndicesAndIdentityChangesAreRejected() throws Exception {
        var before = state(1); var after = before.deepCopy(); after.addProperty("revision", 2); after.getAsJsonArray("delivered").add(1);
        var encoded = MaterialJournalCodec.encode(before, after);
        var tooLarge = encoded.data().deepCopy(); tooLarge.getAsJsonObject("arrays").getAsJsonObject("delivered").addProperty("length", 16385);
        assertThrows(IOException.class, () -> MaterialJournalCodec.decode(before, encoded.kind(), tooLarge));
        var fractional = encoded.data().deepCopy(); fractional.getAsJsonObject("arrays").getAsJsonObject("delivered").getAsJsonArray("entries").get(0).getAsJsonObject().addProperty("index", 0.5);
        assertThrows(IOException.class, () -> MaterialJournalCodec.decode(before, encoded.kind(), fractional));
        var wrongOwner = after.deepCopy(); wrongOwner.addProperty("owner", "another-owner");
        assertThrows(IllegalStateException.class, () -> MaterialJournalCodec.encode(before, wrongOwner));
        assertThrows(IOException.class, () -> MaterialJournalCodec.decode(before, MaterialJournalCodec.FULL, wrongOwner));
    }
    @Test void unknownFutureFieldsFallBackToFullSnapshotsInsteadOfSilentlyLosingThem() throws Exception {
        var before = state(1); var after = before.deepCopy(); after.addProperty("revision", 2); after.addProperty("futureEvidence", "must survive");
        var encoded = MaterialJournalCodec.encode(before, after);
        assertEquals(MaterialJournalCodec.FULL, encoded.kind());
        assertEquals(after, MaterialJournalCodec.decode(before, encoded.kind(), encoded.data()));
    }
}
