package firefly520.fireflymc.ai.blueprint;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BuildProgressProjectionTest {
    @TempDir Path root;
    private final String owner = UUID.randomUUID().toString();
    private final UUID task = UUID.randomUUID(), plan = UUID.randomUUID(), step = UUID.randomUUID();
    private Draft plan(UUID id, int count) {
        var data = new JsonObject(); data.addProperty("planId", id.toString()); data.addProperty("placements", count);
        return new Draft(task, "construction_plan", null, data, null);
    }
    private Draft step(UUID planId, String state, String actor) {
        var data = new JsonObject(); data.addProperty("operationId", step.toString()); data.addProperty("planId", planId.toString());
        data.addProperty("taskId", task.toString()); data.addProperty("state", state); data.addProperty("owner", actor);
        return new Draft(task, "build_step", null, data, null);
    }
    @Test void repeatedReadsAndNativeStepUpdatesNeverInflateVerifiedProgress() throws Exception {
        var store = new ConversationStore(root.resolve("history.jsonl")); var view = new BuildProgressProjection(owner);
        store.append(List.of(plan(plan, 3), step(plan, "DISPATCHING", owner)));
        assertEquals(0, view.view(store).get(task).verifiedOperations()); assertEquals(1, view.view(store).get(task).unsettledOperations());
        store.append(List.of(step(plan, "VERIFIED", owner)));
        assertEquals(1, view.view(store).get(task).verifiedOperations()); assertEquals(1, view.view(store).get(task).verifiedOperations());
        store.append(List.of(step(plan, "RECOVERY_REQUIRED", owner)));
        var result = view.view(store).get(task); assertEquals(0, result.verifiedOperations()); assertTrue(result.recoveryRequired());
    }
    @Test void aNewPlanDoesNotMixOldRevisionStepsAndErasureClearsOnlyTheProjection() throws Exception {
        var store = new ConversationStore(root.resolve("history.jsonl")); var view = new BuildProgressProjection(owner);
        store.append(List.of(plan(plan, 3), step(plan, "VERIFIED", owner))); assertEquals(1, view.view(store).get(task).verifiedOperations());
        UUID next = UUID.randomUUID(); store.append(List.of(plan(next, 2), step(plan, "VERIFIED", owner)));
        assertEquals(next, view.view(store).get(task).planId()); assertEquals(0, view.view(store).get(task).verifiedOperations());
        assertEquals(4, store.getSnapshot(0).events().size());
        long erased = store.erase(store.revision()); assertTrue(view.view(store).isEmpty());
        store.append(List.of(plan(next, 1), step(next, "VERIFIED", owner)));
        assertEquals(1, view.view(store).get(task).verifiedOperations());
        assertEquals(2, store.getSnapshot(erased).events().size()); assertTrue(store.getSnapshot(Long.MAX_VALUE).events().isEmpty());
        assertEquals(3, store.getSnapshot(Long.MIN_VALUE).events().size());
    }
    @Test void anotherOwnersStepCannotEnterThePrivateWorkbenchProjection() throws Exception {
        var store = new ConversationStore(root.resolve("history.jsonl")); var view = new BuildProgressProjection(owner);
        store.append(List.of(plan(plan, 1), step(plan, "VERIFIED", UUID.randomUUID().toString())));
        assertThrows(SecurityException.class, () -> view.view(store));
    }
}
