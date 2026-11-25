package firefly520.fireflymc.ai.tools;

import com.google.gson.*;
import firefly520.fireflymc.ai.memory.MemoryStore;
import firefly520.fireflymc.ai.storage.AiJson;
import java.util.*;

public final class WorkspaceTools {
    private WorkspaceTools() {}
    @FunctionalInterface private interface Action {
        AgentTool.Result run(AgentTool.Context context, JsonObject arguments) throws Exception;
    }

    public static void register(ToolRegistry registry) {
        register(registry, "conversation_search", "检索本人的完整主会话，含压缩前原文；query 可为任务 ID 或关键词。",
                "{\"query\":{\"type\":\"string\"}}", List.of("query"), (ctx, args) -> {
                    var events = ctx.workspace().conversation().searchComplete(args.get("query").getAsString(), Long.MAX_VALUE, 10);
                    return AgentTool.Result.ok(AiJson.GSON.toJson(events.stream().map(e -> Map.of(
                            "seq", e.seq(), "taskId", e.taskId() == null ? "" : e.taskId().toString(),
                            "kind", e.kind(), "artifactId", e.artifactId(), "text", e.text().substring(0,
                                    Math.min(1024, e.text().length())))).toList()));
                });
        register(registry, "conversation_read", "按 seq 读取本人消息全文的一页；offset 按字符计。",
                "{\"seq\":{\"type\":\"integer\",\"minimum\":1},\"offset\":{\"type\":\"integer\",\"minimum\":0}}",
                List.of("seq"), (ctx, args) -> {
                    long seq = args.get("seq").getAsLong();
                    try {
                        var event = ctx.workspace().conversation().readableEvent(seq);
                        if (event.isEmpty()) return AgentTool.Result.error("NOT_FOUND", "消息不存在。");
                        return page(AiJson.GSON.toJson(event.get()), args, ctx.modelResultBytes());
                    } catch (SecurityException internal) {
                        return AgentTool.Result.error("INTERNAL_EVENT_NOT_READABLE", "内部执行记录请通过对应任务、加工或施工状态工具查询。");
                    }
                });
        register(registry, "task_lookup", "读取小萤后台请求的状态与结果引用，taskId来自本工作区。不是游戏剧情/成就；COMPLETED不证明制作或施工成功，需实际工具证据。",
                "{\"taskId\":{\"type\":\"string\"}}", List.of("taskId"), (ctx, args) -> {
                    var task = ctx.workspace().conversation().task(UUID.fromString(args.get("taskId").getAsString()));
                    if (task == null) return AgentTool.Result.error("NOT_FOUND", "任务不存在。");
                    var events = ctx.workspace().conversation().getSnapshot(0).events().stream()
                            .filter(e -> task.id().equals(e.taskId())).toList();
                    var artifacts = events.stream().filter(e -> "tool_result".equals(e.kind())
                                    && e.data() != null && e.data().has("artifactId") && e.data().has("fullResult"))
                            .map(e -> e.data().get("artifactId").getAsString()).distinct().toList();
                    return AgentTool.Result.ok(AiJson.GSON.toJson(Map.of("domain", "AI_REQUEST_HISTORY", "task", task, "artifactIds", artifacts,
                            "messageSequences", events.stream().filter(e -> e.message() != null).map(e -> e.seq()).toList())));
                });
        register(registry, "task_list", "查询本人交给小萤的游戏操作代办。默认scope=work只列真实计划/审批/操作，排除普通聊天和查询；scope=all才列所有请求。不是游戏剧情或成就；状态不能替代真实成果。",
                "{\"query\":{\"type\":\"string\",\"maxLength\":160},\"scope\":{\"type\":\"string\",\"enum\":[\"work\",\"all\"]},\"offset\":{\"type\":\"integer\",\"minimum\":0}}",
                List.of(), (ctx, args) -> {
                    String query = args.has("query") ? args.get("query").getAsString().toLowerCase(Locale.ROOT) : "";
                    int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
                    var snapshot = ctx.workspace().conversation().getSnapshot(0);
                    boolean all = args.has("scope") && args.get("scope").getAsString().equals("all");
                    var work = firefly520.fireflymc.ai.task.WorkTaskIndex.from(snapshot);
                    var found = snapshot.tasks().stream().filter(t -> all || work.contains(t.id())).filter(t -> (t.id() + " "
                            + t.prompt() + " " + t.state()).toLowerCase(Locale.ROOT).contains(query))
                            .sorted(Comparator.comparingLong(firefly520.fireflymc.ai.task.AgentTask::createdAt).reversed()).toList();
                    if (offset < 0 || offset > found.size()) throw new IllegalArgumentException("offset");
                    int end = Math.min(found.size(), offset + 8);
                    return AgentTool.Result.ok(AiJson.GSON.toJson(Map.of("domain", all ? "AI_REQUEST_HISTORY" : "AI_GAMEPLAY_WORK", "tasks", found.subList(offset, end).stream()
                            .map(t -> Map.of("taskId", t.id(), "goal", t.prompt().substring(0, Math.min(160, t.prompt().length())),
                                    "state", t.state(), "createdAt", t.createdAt())).toList(),
                            "nextOffset", end, "total", found.size())));
                });
        register(registry, "artifact_read", "分页读取本人工具结果完整原文，沿nextOffset读完再回答。只需摘录时mode=excerpt；默认full。不接受路径或他人身份；内部计划用对应状态工具。",
                "{\"artifactId\":{\"type\":\"string\"},\"offset\":{\"type\":\"integer\",\"minimum\":0},\"mode\":{\"type\":\"string\",\"enum\":[\"full\",\"excerpt\"]}}",
                List.of("artifactId"), (ctx, args) -> {
                    var text = ctx.workspace().conversation().readableArtifact(UUID.fromString(args.get("artifactId").getAsString()));
                    if (text.isEmpty()) return AgentTool.Result.error("NOT_FOUND", "未找到可读取的工具结果产物；内部计划请使用对应状态工具。");
                    return page(text.get(), args, ctx.modelResultBytes());
                });
        register(registry, "memory_lookup", "查询本人明确保存的长期记忆。",
                "{}", List.of(), (ctx, args) ->
                        AgentTool.Result.ok(AiJson.GSON.toJson(new MemoryStore(ctx.workspace()).view(ctx.actor()))));
        JsonObject memorySchema = AiJson.object("""
                {"type":"object","properties":{"text":{"type":"string","maxLength":1024},
                "sourceSeq":{"type":"integer","minimum":1},"replaceId":{"type":"string"},
                "expectedUpdatedAt":{"type":"integer","minimum":0}},"required":["text","sourceSeq"],
                "additionalProperties":false}
                """, 2000, 8);
        registry.register(new DefinedTool(new AgentTool.Descriptor("memory_remember",
                "自动保存本人长期偏好或已核验事实。sourceSeq 必须是真实用户消息/工具证据序号，可用 conversation_search 查找。"
                        + "先 memory_lookup 去重；更新旧 AI 记忆时提供 replaceId 与其 expectedUpdatedAt。不得保存凭据或扩张权限。",
                memorySchema, AgentTool.Risk.MEMORY_WRITE, "memory.own.write", 0, AgentTool.ExecutionThread.IO, 5000, false, ""),
                (ctx, args) -> {
                    ctx.control().check();
                    var note = new MemoryStore(ctx.workspace()).rememberAutomatically(ctx.actor(), args.get("text").getAsString(),
                            args.get("sourceSeq").getAsLong(), args.has("replaceId") ? UUID.fromString(args.get("replaceId").getAsString()) : null,
                            args.has("expectedUpdatedAt") ? args.get("expectedUpdatedAt").getAsLong() : 0);
                    JsonObject data = new JsonObject(); data.add("note", AiJson.GSON.toJsonTree(note));
                    return new AgentTool.Result(true, "", "已写入本人长期记忆；可在工作台查看、修改或删除。", data,
                            note.source(), "", false);
                }));
    }

    private static AgentTool.Result page(String text, JsonObject args, int budget) {
        int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
        if (offset < 0 || offset > text.length()) throw new IllegalArgumentException("offset");
        int end = Math.min(text.length(), offset + 2048);
        while (true) {
            if (end > offset && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            JsonObject data = new JsonObject();
            data.addProperty("text", text.substring(offset, end));
            data.addProperty("nextOffset", end); data.addProperty("totalCharacters", text.length());
            AgentTool.Result result = new AgentTool.Result(true, "", "原文分页。", data, "", "", false);
            if (firefly520.fireflymc.ai.context.ContextCompactor.estimate(result) <= budget) return result;
            if (end <= offset + 2) return AgentTool.Result.error("PAGE_BUDGET_TOO_SMALL", "分页预算不足。");
            end = offset + (end - offset) / 2;
        }
    }

    private static void register(ToolRegistry registry, String name, String description,
                                 String properties, List<String> required, Action action) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", JsonParser.parseString(properties));
        schema.add("required", AiJson.GSON.toJsonTree(required));
        schema.addProperty("additionalProperties", false);
        registry.register(new AgentTool() {
            @Override public Descriptor descriptor() {
                return new Descriptor(name, description, schema, Risk.READ_ONLY, "workspace.read",
                        0, ExecutionThread.IO, 5000, false, "");
            }
            @Override public Result execute(Context context, JsonObject arguments) throws Exception {
                context.actor().requireAccess(context.workspace().owner());
                context.control().check();
                return action.run(context, arguments);
            }
        });
    }
}
