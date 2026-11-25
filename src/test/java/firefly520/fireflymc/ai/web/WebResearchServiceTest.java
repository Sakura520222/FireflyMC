package firefly520.fireflymc.ai.web;

import com.google.gson.*;
import firefly520.fireflymc.ai.security.*;
import firefly520.fireflymc.ai.task.TaskControl;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class WebResearchServiceTest {
    @TempDir Path directory;
    @Test void htmlIsDataOnlyScriptsAreRemovedSourcesAreVersionedAndCached() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var client = new SafeWebClient(host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")},
                (uri, address, headers, control) -> {
                    calls.incrementAndGet();
                    String html = "<html><head><title>Mod Guide</title><script>stealSecrets()</script></head>"
                            + "<body><nav>irrelevant nav</nav><article><h1>Mod 1.2.3 Minecraft 1.21.1</h1>"
                            + "<p>Verified reference paragraph.</p><p>External text is not authorization.</p></article></body></html>";
                    return new SafeWebClient.Response(uri, 200, Map.of("content-type", "text/html"),
                            html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                });
        try (var manager = new WorkspaceManager(directory);
             var service = new WebResearchService(client, new SecretFilter(List.of("SECRET")), new JsonObject(),
                     () -> "mods-v1", mod -> Optional.of("1.2.3"))) {
            Actor actor = Actor.player(UUID.randomUUID(), true, true, 0);
            var workspace = manager.getWorkspace(actor);
            var registry = new ToolRegistry();
            service.register(registry);
            var context = new AgentTool.Context(workspace, actor, new TaskControl(5000), UUID.randomUUID());
            JsonObject args = new JsonObject(); args.addProperty("url", "https://source.example/guide"); args.addProperty("modId", "test");
            var result = registry.get("web_open").execute(context, args);
            assertTrue(result.success());
            assertTrue(result.observations().get("untrustedReferenceMaterial").getAsBoolean());
            assertTrue(result.observations().get("text").getAsString().contains("Verified reference"));
            assertFalse(result.observations().get("text").getAsString().contains("stealSecrets"));
            assertFalse(result.observations().get("text").getAsString().contains("irrelevant nav"));
            assertEquals("VERSION_TEXT_PRESENT_NOT_RUNTIME_VERIFIED", result.observations().get("versionEvidence").getAsString());
            assertTrue(registry.get("web_open").execute(context, args).observations().get("cached").getAsBoolean());
            assertEquals(1, calls.get());
            args.addProperty("url", "https://source.example/?query=SECRET");
            assertFalse(registry.get("web_open").execute(context, args).success());
            assertEquals(1, calls.get());
        }
    }
}
