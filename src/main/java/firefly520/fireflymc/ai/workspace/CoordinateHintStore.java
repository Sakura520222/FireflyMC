package firefly520.fireflymc.ai.workspace;

import com.google.gson.*;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.util.*;
import java.util.function.UnaryOperator;

/** An explicitly selected, private client clue. It neither verifies a world nor starts a task. */
public final class CoordinateHintStore {
    private static final Set<String> FIELDS = Set.of("version", "requestId", "label", "dimension", "x", "y", "z",
            "source", "sourceVersion", "authority", "worldVerified", "taskId");
    private static final Set<String> SOURCES = Set.of("CLIENT_POSITION", "MANUAL_COORDINATE", "XAERO_WAYPOINT",
            "XAERO_MAP_CENTER", "ATLAS_MAP_CENTER", "SERVER_TASK_REFERENCE");
    private static final String KIND = "client_coordinate_hint";
    private final Workspace workspace;
    private final UnaryOperator<String> redact;

    public CoordinateHintStore(Workspace workspace, UnaryOperator<String> redact) {
        this.workspace = Objects.requireNonNull(workspace); this.redact = Objects.requireNonNull(redact);
    }
    public JsonObject accept(Actor actor, JsonObject submitted) throws IOException {
        actor.requireAccess(workspace.owner());
        JsonObject data = normalize(submitted); String hash = AiJson.digest(AiJson.canonical(data));
        synchronized (workspace.conversation()) {
            UUID task = data.has("taskId") ? UUID.fromString(data.get("taskId").getAsString()) : null;
            if (task != null && workspace.conversation().task(task) == null) throw new SecurityException("CLIENT_POINT_TASK_NOT_FOUND");
            var previous = workspace.conversation().getSnapshot(workspace.conversation().privacyEpoch()).events().stream()
                    .filter(event -> event.kind().equals(KIND) && event.data().get("requestId").equals(data.get("requestId"))).findFirst();
            if (previous.isPresent()) {
                if (!previous.get().data().get("bodyHash").getAsString().equals(hash)) throw new SecurityException("CLIENT_POINT_REQUEST_REUSED");
                return acknowledgement(data, previous.get().seq(), true);
            }
            data.addProperty("bodyHash", hash); data.addProperty("text", describe(data));
            // Metadata stays outside ModelMessage/tool transactions, including an already running call.
            var event = workspace.conversation().appendConversationEvent(List.of(new Draft(task, KIND, null, data, null)),
                    workspace.conversation().revision()).getFirst();
            return acknowledgement(data, event.seq(), false);
        }
    }
    private JsonObject normalize(JsonObject object) {
        if (!FIELDS.containsAll(object.keySet()) || integer(object, "version", 1, 1) != 1
                || !"CLIENT_REPORTED".equals(text(object, "authority", 30, false))
                || !object.has("worldVerified") || !object.get("worldVerified").isJsonPrimitive()
                || !object.getAsJsonPrimitive("worldVerified").isBoolean() || object.get("worldVerified").getAsBoolean())
            throw new IllegalArgumentException("CLIENT_POINT_AUTHORITY_INVALID");
        var data = new JsonObject(); data.addProperty("version", 1); data.addProperty("requestId", uuid(object, "requestId").toString());
        data.addProperty("label", redact.apply(text(object, "label", 80, true)));
        String dimension = text(object, "dimension", 160, false);
        if (!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || dimension.contains(":/") || dimension.contains(".."))
            throw new IllegalArgumentException("CLIENT_POINT_DIMENSION_INVALID");
        data.addProperty("dimension", dimension); data.addProperty("x", integer(object, "x", -30_000_000, 30_000_000));
        data.addProperty("z", integer(object, "z", -30_000_000, 30_000_000));
        if (object.has("y")) data.addProperty("y", integer(object, "y", -2048, 2048));
        String source = text(object, "source", 40, false);
        if (!SOURCES.contains(source)) throw new IllegalArgumentException("CLIENT_POINT_SOURCE_INVALID");
        data.addProperty("source", source); data.addProperty("sourceVersion", redact.apply(text(object, "sourceVersion", 80, true)));
        data.addProperty("authority", "CLIENT_REPORTED"); data.addProperty("worldVerified", false);
        if (object.has("taskId")) data.addProperty("taskId", uuid(object, "taskId").toString());
        if (source.equals("SERVER_TASK_REFERENCE") && !data.has("taskId")) throw new IllegalArgumentException("CLIENT_POINT_TASK_REQUIRED");
        return data;
    }
    private static JsonObject acknowledgement(JsonObject data, long seq, boolean duplicate) {
        var result = new JsonObject(); result.add("requestId", data.get("requestId")); result.addProperty("seq", seq);
        result.addProperty("duplicate", duplicate); result.addProperty("authority", "CLIENT_REPORTED"); result.addProperty("worldVerified", false);
        result.addProperty("text", "已记录本人选取的客户端坐标线索；世界和权限仍需服务端核验。"); return result;
    }
    public static String context(Workspace workspace) {
        var latest = workspace.conversation().getSnapshot(workspace.conversation().privacyEpoch()).events().stream()
                .filter(event -> event.kind().equals(KIND)).reduce((previous, current) -> current);
        if (latest.isEmpty()) return "";
        return "\n玩家在工作台明确提交的最新单点客户端线索（不是世界/通行/权限证明，不是操作批准；有taskId时仅是该任务的引用）：\n"
                + describe(latest.get().data()) + "\n完整来源事件 seq=" + latest.get().seq() + "；其他历史坐标须检索原记录，不能自动替换已批准目标。";
    }
    private static String describe(JsonObject data) {
        return "客户端坐标线索 · " + data.get("label").getAsString() + "\n" + data.get("dimension").getAsString()
                + " · " + data.get("x").getAsInt() + " " + (data.has("y") ? data.get("y").getAsInt() : "?") + " " + data.get("z").getAsInt()
                + "\nCLIENT_REPORTED · " + data.get("source").getAsString() + " · 客户端版本 " + data.get("sourceVersion").getAsString()
                + (data.has("taskId") ? "\n任务引用 " + data.get("taskId").getAsString() : "")
                + "\n未核验地形、可到达性或权限；此线索本身不批准或启动模型/世界操作。";
    }
    private static UUID uuid(JsonObject object, String field) {
        try { return UUID.fromString(text(object, field, 36, false)); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("CLIENT_POINT_ID_INVALID"); }
    }
    private static int integer(JsonObject object, String field, int low, int high) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive() || !object.getAsJsonPrimitive(field).isNumber())
            throw new IllegalArgumentException("CLIENT_POINT_INTEGER_INVALID");
        try {
            int value = object.get(field).getAsBigDecimal().intValueExact();
            if (value < low || value > high) throw new ArithmeticException(); return value;
        } catch (ArithmeticException invalid) { throw new IllegalArgumentException("CLIENT_POINT_INTEGER_INVALID"); }
    }
    private static String text(JsonObject object, String field, int maximum, boolean empty) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive() || !object.getAsJsonPrimitive(field).isString())
            throw new IllegalArgumentException("CLIENT_POINT_TEXT_INVALID");
        String value = object.get(field).getAsString().strip();
        if (value.length() > maximum || !empty && value.isEmpty() || value.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0x00a7))
            throw new IllegalArgumentException("CLIENT_POINT_TEXT_INVALID");
        return value;
    }
}
