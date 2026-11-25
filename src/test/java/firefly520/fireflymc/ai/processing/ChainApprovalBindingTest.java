package firefly520.fireflymc.ai.processing;

import firefly520.fireflymc.ai.material.MaterialStack;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ChainApprovalBindingTest {
    @TempDir Path directory;
    @Test void wholeChainApprovalBindsEveryRecipeAndRejectsAnotherOwner() throws Exception {
        try (var workspaces = new WorkspaceManager(directory)) {
            var actor = Actor.player(UUID.randomUUID(), true, true, 0);
            var other = Actor.player(UUID.randomUUID(), true, true, 0);
            var workspace = workspaces.getWorkspace(actor);
            var service = new VirtualProcessingService(null, null, null, () -> 1);
            UUID task = UUID.randomUUID(), epoch = UUID.randomUUID(), id = UUID.randomUUID();
            var item = new MaterialStack("{\"id\":\"minecraft:stone\"}", 1);
            var step = new VirtualProcessingService.Plan(UUID.randomUUID(), epoch, actor.owner(), task, "minecraft:stone", "", "minecraft:smelting",
                    1, "minecraft:overworld", 1, "", List.of(item), List.of(item), List.of(), Map.of(), List.of(), List.of(), 0,
                    "FURNACE_RESULT_SLOT", "MATERIALS_ONLY@1", List.of(), "");
            var root = new VirtualProcessingService.Plan(id, epoch, actor.owner(), task, "", "fireflymc:processing_chain", "", 1,
                    "minecraft:overworld", 1, "minecraft:stone", List.of(), List.of(item), List.of(), Map.of(), List.of(), List.of(), 0,
                    "PROCESSING_CHAIN", "MATERIALS_ONLY@1", List.of(step), "");
            String hash = AiJson.digest(AiJson.canonical(root));
            var encoded = AiJson.GSON.toJsonTree(root).getAsJsonObject(); encoded.addProperty("hash", hash);
            Path path = workspace.directory().resolve("processing").resolve(id + ".json"); AiJson.write(path, encoded);
            assertEquals("minecraft:stone", service.load(workspace, actor, id, hash).steps().getFirst().recipeId());
            assertThrows(SecurityException.class, () -> service.load(workspace, other, id, hash));
            encoded.getAsJsonArray("steps").get(0).getAsJsonObject().addProperty("recipeId", "minecraft:diamond_block");
            AiJson.write(path, encoded);
            assertEquals("PROCESSING_PLAN_CHANGED", assertThrows(SecurityException.class, () -> service.load(workspace, actor, id, hash)).getMessage());
        }
    }
}
