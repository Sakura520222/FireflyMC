package firefly520.fireflymc.ai.tools;

import com.google.gson.JsonObject;

/** A small typed registration helper, with no command or old-function compatibility surface. */
public final class DefinedTool implements AgentTool {
    @FunctionalInterface public interface Action { Result run(Context context, JsonObject arguments) throws Exception; }
    private final Descriptor descriptor;
    private final Action action;
    public DefinedTool(Descriptor descriptor, Action action) { this.descriptor = descriptor; this.action = action; }
    @Override public Descriptor descriptor() { return descriptor; }
    @Override public Result execute(Context context, JsonObject arguments) throws Exception {
        context.actor().requireAccess(context.workspace().owner());
        context.control().check();
        return action.run(context, arguments);
    }
}
