package firefly520.fireflymc.ai.tools;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import com.google.gson.*;
import firefly520.fireflymc.ai.context.ContextCompactor;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.model.ModelGateway;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;

public final class ToolRegistry {
    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();
    private static final Set<String> FORBIDDEN = Set.of("set_time", "set_weather", "kick_player",
            "teleport_position", "teleport_player", "summon_player", "give_item", "give_effect",
            "spawn_entities", "remove_entities", "clear_dropped_items", "fill_blocks", "place_block",
            "execute_command", "execute_shell", "admin.config.change", "admin.manage_player");
    public void register(AgentTool tool) {
        if (tool.descriptor().risk() == AgentTool.Risk.MEMORY_WRITE
                && (!tool.descriptor().name().equals("memory_remember")
                || !tool.descriptor().requiredCapability().equals("memory.own.write")))
            throw new IllegalArgumentException("MEMORY_RISK_REQUIRES_OWN_MEMORY_TOOL");
        if (FORBIDDEN.contains(tool.descriptor().name()) || tool.descriptor().requiredCapability().startsWith("admin")
                || tool.descriptor().permissionLevel() != 0)
            throw new IllegalArgumentException("ADMIN_TOOLS_NOT_AVAILABLE_TO_COMPANIONS");
        if (tools.putIfAbsent(tool.descriptor().name(), tool) != null)
            throw new IllegalArgumentException("Duplicate AI tool");
    }
    public AgentTool get(String name) { return tools.get(name); }
    public List<AgentTool> all() {
        return tools.values().stream().sorted(Comparator.comparing(t -> t.descriptor().name())).toList();
    }
    public void registerDiscovery(Predicate<String> enabled) {
        JsonObject schema = AiJson.object("""
                {"type":"object","properties":{"query":{"type":"string","maxLength":160},
                "names":{"type":"array","maxItems":6,"uniqueItems":true,"items":{"type":"string","maxLength":128}},
                "intent":{"type":"string","enum":["use","list"]},
                "offset":{"type":"integer","minimum":0}},"additionalProperties":false}
                """, 2048, 8);
        register(new DefinedTool(new AgentTool.Descriptor("tools_discover",
                "检索并装入工具：已知名称用 names，否则用 query。同一任务下一次模型请求即可调用，不等玩家回复。仅介绍能力时设 intent=list。",
                schema, AgentTool.Risk.READ_ONLY, "workspace.read", 0, AgentTool.ExecutionThread.IO, 5000, false, ""),
                (ctx, args) -> {
                    ctx.actor().requireAccess(ctx.workspace().owner()); ctx.control().check();
                    String query = args.has("query") ? args.get("query").getAsString().strip().toLowerCase(Locale.ROOT) : "";
                    int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
                    Set<String> requested = new LinkedHashSet<>();
                    if (args.has("names")) args.getAsJsonArray("names").forEach(name -> requested.add(name.getAsString()));
                    var matches = available(ctx.actor(), enabled).stream()
                            .filter(tool -> requested.isEmpty() ? score(tool, query) > 0 : requested.contains(tool.name()))
                            .sorted(Comparator.<ModelGateway.ToolDefinition>comparingInt(tool -> score(tool, query)).reversed()
                                    .thenComparing(ModelGateway.ToolDefinition::name)).toList();
                    if (offset < 0 || offset > matches.size()) throw new IllegalArgumentException("TOOL_CATALOG_OFFSET");
                    var page = new ArrayList<>(matches.subList(offset, Math.min(matches.size(), offset + 6)));
                    boolean use = !args.has("intent") || !args.get("intent").getAsString().equals("list");
                    AgentTool.Result result;
                    int descriptionLimit = 96;
                    while (true) {
                        JsonArray rows = new JsonArray();
                        for (var definition : page) {
                            var row = new JsonObject(); row.addProperty("name", definition.name());
                            row.addProperty("description", definition.description().substring(0, Math.min(descriptionLimit, definition.description().length())));
                            rows.add(row);
                        }
                        var data = new JsonObject(); data.add("matches", rows); data.addProperty("total", matches.size());
                        data.addProperty("nextOffset", offset + page.size()); data.addProperty("requiresUserReply", false);
                        data.addProperty("intent", use ? "use" : "list");
                        result = new AgentTool.Result(true, "", page.isEmpty() ? "未匹配到可用工具；可按准确工具名重试。"
                                : "工具已选入本任务；在接下来的模型请求中直接使用，无需玩家发新消息。", data, "enabled-server-tools", "", false);
                        if (ContextCompactor.estimate(result) <= ctx.modelResultBytes()) break;
                        if (descriptionLimit > 0) { descriptionLimit = 0; continue; }
                        if (page.size() > 1) { page.removeLast(); continue; }
                        return AgentTool.Result.error("TOOL_CATALOG_BUDGET_TOO_SMALL", "当前目录结果预算不足，未装入工具。");
                    }
                    JsonObject selection = new JsonObject();
                    selection.add("names", AiJson.GSON.toJsonTree(page.stream().map(ModelGateway.ToolDefinition::name).toList()));
                    selection.addProperty("intent", use ? "use" : "list");
                    if (ctx.taskId() != null) ctx.workspace().conversation().append(
                            List.of(new Draft(ctx.taskId(), "tool_catalog", null, selection, null)));
                    return result;
                }));
    }
    private static int score(ModelGateway.ToolDefinition tool, String query) {
        if (query.isEmpty()) return 1;
        if (tool.name().equalsIgnoreCase(query)) return 10000;
        int score = 0;
        String name = tool.name().toLowerCase(Locale.ROOT), description = tool.description().toLowerCase(Locale.ROOT);
        for (String term : query.split("\\s+")) {
            if (name.equals(term)) score += 1000;
            else if (name.contains(term)) score += 100;
            else if (description.contains(term)) score++;
        }
        return score;
    }
    private List<ModelGateway.ToolDefinition> available(Actor actor, Predicate<String> enabled) {
        return all().stream().filter(t -> enabled.test(t.descriptor().name())
                && (t.descriptor().unavailableReason() == null || t.descriptor().unavailableReason().isBlank())
                && actor.permissionLevel() >= t.descriptor().permissionLevel()).map(t -> t.descriptor().modelDefinition()).toList();
    }
    public List<ModelGateway.ToolDefinition> exposed(Workspace workspace, UUID taskId, Actor actor,
                                                     Predicate<String> enabled, int budget) {
        var available = available(actor, enabled);
        if (ContextCompactor.estimate(available) <= budget) return available;
        // Reading a result is part of using a tool, not an optional capability to rediscover.
        LinkedHashSet<String> names = new LinkedHashSet<>(List.of("crafting_discover", "artifact_read", "conversation_read", "tools_discover"));
        var events = workspace.conversation().getSnapshot(0).events();
        for (int i = events.size() - 1; i >= 0; i--) {
            var event = events.get(i);
            if ("tool_catalog".equals(event.kind()) && taskId.equals(event.taskId())) {
                event.data().getAsJsonArray("names").forEach(n -> names.add(n.getAsString()));
                if (names.size() >= 32) break;
            }
        }
        names.addAll(List.of("task_ask", "task_lookup", "task_list", "conversation_search", "memory_lookup", "memory_remember"));
        List<ModelGateway.ToolDefinition> selected = new ArrayList<>();
        int used = 64;
        for (String name : names) {
            var definition = available.stream().filter(t -> t.name().equals(name)).findFirst();
            if (definition.isEmpty()) continue;
            int cost = ContextCompactor.estimate(definition.get());
            if (used + cost > budget) {
                if (Set.of("artifact_read", "conversation_read", "tools_discover").contains(name))
                    throw new IllegalStateException("TOOL_DEFINITION_BUDGET_EXHAUSTED");
                continue;
            }
            selected.add(definition.get()); used += cost;
        }
        return List.copyOf(selected);
    }
}
