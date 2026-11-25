package firefly520.fireflymc.story;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AiServerService;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.tools.*;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real game registration and unsupported-environment boundary tests; no provider requests. */
@GameTestHolder("fireflymc")
@PrefixGameTestTemplate(false)
public final class AiCoreGameTests {
    private AiCoreGameTests() {}

    @GameTest(template = "story_empty", batch = "ai-boundary", timeoutTicks = 100)
    public static void aiHasNoCommandAndMusicKeepsBothEntries(GameTestHelper helper) {
        var root = helper.getLevel().getServer().getCommands().getDispatcher().getRoot();
        helper.assertTrue(root.getChild("fireflymc") != null, "non-AI root is registered");
        helper.assertTrue(root.getChild("fireflymc").getChild("ai") == null, "no AI command subtree");
        helper.assertTrue(root.getChild("fireflymc").getChild("music") != null, "music subtree is preserved");
        helper.assertTrue(root.getChild("点歌") != null, "explicit music alias is preserved");
        for (String alias : List.of("ai", "companion", "伙伴"))
            helper.assertTrue(root.getChild(alias) == null, "AI root must be absent: " + alias);
        helper.succeed();
    }

    @GameTest(template = "story_empty", batch = "ai-boundary", timeoutTicks = 100)
    public static void nonDedicatedGameServerCannotCreateServiceOrDispatchTools(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        helper.assertTrue(!server.isDedicatedServer(), "GameTestServer is not a dedicated server");
        try {
            new AiServerService(server, null);
            helper.fail("non-dedicated service creation was allowed");
            return;
        } catch (IllegalStateException expected) {
            helper.assertTrue(expected.getMessage().equals("AI_DEDICATED_SERVER_REQUIRED"), "gate precedes configuration access");
        }
        AtomicBoolean executed = new AtomicBoolean();
        var bridge = new GameToolBridge(server, owner -> { throw new AssertionError("Identity must not be accessed"); },
                ignored -> true, () -> false);
        AgentTool tool = new AgentTool() {
            public Descriptor descriptor() {
                return new Descriptor("inspect", "read", new JsonObject(), Risk.READ_ONLY, "player.read", 0,
                        ExecutionThread.SERVER, 1000, false, "");
            }
            public Result execute(Context context, JsonObject args) { executed.set(true); return Result.ok("read"); }
        };
        var result = bridge.execute(tool, null, new JsonObject(), "",
                new ModelMessage.ToolCall("test", "inspect", "{}")).join();
        helper.assertTrue(!result.success() && result.errorCode().equals("NOT_SUPPORTED"), "unsupported environment fails closed");
        helper.assertTrue(!executed.get(), "no tool execution before the dedicated-server gate");
        helper.succeed();
    }
}
