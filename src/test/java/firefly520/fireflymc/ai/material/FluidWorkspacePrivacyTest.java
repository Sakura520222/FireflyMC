package firefly520.fireflymc.ai.material;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FluidWorkspacePrivacyTest {
    @TempDir Path directory;

    @Test void explicitErasureRequiresJointSettlementAndPreservesUnknownFiles() throws Exception {
        Actor actor = Actor.player(UUID.randomUUID(), true, true, 0);
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor); var items = new MaterialLedger(directory); var fluids = new FluidLedger(directory);
            var joint = new MixedResourceJournal(workspace, items, fluids);
            var input = new MaterialStack("{\"id\":\"minecraft:water_bucket\",\"count\":1}", 1);
            var output = new MaterialStack("{\"id\":\"minecraft:bucket\",\"count\":1}", 1);
            var water = new FluidAmount("{\"id\":\"minecraft:water\",\"amount\":1}", 1000);
            UUID id = UUID.randomUUID(), task = UUID.randomUUID(); String hash = AiJson.digest("fixed-test-plan");
            joint.create(actor, new MixedResourceJournal.Definition(id, task, hash, List.of(input), List.of(), List.of(output), List.of(water), "test reservation only"));
            var source = new FluidInventoryService.Source(UUID.randomUUID(), 1, "test-native-tank", AiJson.digest("host"),
                    new FluidTankSnapshot(List.of(new FluidTankSnapshot.Tank(4000, water))));
            UUID reservation = UUID.randomUUID(); fluids.reserve(actor, task, reservation, hash, source, water);
            var privacy = new WorkspacePrivacyService(() -> 1000, ignored -> items.settled(actor.owner()) && fluids.settled(actor) && joint.resourcesSettled(actor));
            assertThrows(IllegalStateException.class, () -> privacy.preview(actor, workspace));
            joint.beginReturn(actor, id); fluids.cancel(actor, reservation);
            assertTrue(joint.resourcesSettled(actor)); assertTrue(fluids.settled(actor));
            var unknown = workspace.directory().resolve("mixed-materials/user-notes.txt"); Files.writeString(unknown, "preserve");
            var preview = privacy.preview(actor, workspace); privacy.confirm(actor, workspace, preview.token());
            assertFalse(Files.exists(workspace.directory().resolve("mixed-materials/journal.jsonl")));
            assertFalse(Files.exists(workspace.directory().resolve("fluids/journal.jsonl")));
            assertEquals("preserve", Files.readString(unknown));
            assertFalse(joint.journalAvailable());
            assertThrows(IllegalStateException.class, () -> joint.beginReturn(actor, id));
            fluids.forgetSettled(actor); items.forgetSettled(actor);
            assertTrue(new FluidLedger(directory).list(actor).isEmpty());
        }
    }
}
