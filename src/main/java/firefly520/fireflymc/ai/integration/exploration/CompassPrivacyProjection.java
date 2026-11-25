package firefly520.fireflymc.ai.integration.exploration;

import com.google.gson.*;
import firefly520.fireflymc.ai.storage.AiJson;
import java.util.*;

/** Client/model projections only. Full server journals and signed approval evidence are never modified. */
public final class CompassPrivacyProjection {
    private CompassPrivacyProjection() { }
    public static boolean coordinatesVisible(CompassSearchJournal.Operation operation) {
        return operation != null && operation.before().coordinatesVisible() && operation.after().coordinatesVisible()
                && (operation.result() == null || !operation.result().verified() || operation.result().coordinatesVisible());
    }
    public static JsonObject preparation(CompassPreparationJournal.Operation operation, boolean visible) {
        var data = AiJson.GSON.toJsonTree(operation).getAsJsonObject();
        data.addProperty("coordinatesHidden", !visible);
        data.addProperty("coordinatePolicy", visible ? "NATIVE_COORDINATES_VISIBLE" : "HIDDEN_BY_SERVER_COMPASS_POLICY");
        if (visible) return data;
        for (String key : List.of("originX", "originZ", "searchHash", "preparationHash", "executionEpoch", "serverInstanceId", "conversationId")) data.remove(key);
        var candidates = new JsonArray();
        for (var candidate : operation.candidates()) {
            var row = new JsonObject(); row.addProperty("state", candidate.state().name()); row.addProperty("reason", candidate.reason());
            row.addProperty("nativeReady", candidate.nativeReady()); row.addProperty("saveConfirmed", !candidate.saveProof().isEmpty()); candidates.add(row);
        }
        data.add("candidates", candidates);
        data.addProperty("coordinateNotice", "候选精确坐标及其不透明原生证明按服务器罗盘设置隐藏；范围、预算和实际状态仍保留。");
        return data;
    }
    public static JsonObject search(CompassSearchJournal.Operation operation, boolean visible) {
        var data = AiJson.GSON.toJsonTree(operation).getAsJsonObject();
        data.addProperty("coordinatesHidden", !visible);
        if (visible) return data;
        for (String field : List.of("before", "after")) data.getAsJsonObject(field).remove("itemFingerprint");
        for (String field : List.of("startSave", "terminalSave", "planHash", "executionEpoch")) data.remove(field);
        if (data.has("result") && data.get("result").isJsonObject()) {
            var result = data.getAsJsonObject("result"); result.remove("x"); result.remove("z"); result.remove("sampleY");
            result.addProperty("coordinatesVisible", false);
        }
        data.addProperty("coordinatePolicy", "HIDDEN_BY_SERVER_COMPASS_POLICY");
        return data;
    }
    public static JsonObject result(JsonObject view, boolean visible) {
        var projected = view.deepCopy();
        if (!visible && projected.has("result") && projected.get("result").isJsonObject()) {
            var result = projected.getAsJsonObject("result"); result.remove("x"); result.remove("z"); result.remove("sampleY");
            result.addProperty("coordinatesVisible", false);
        }
        return projected;
    }
    /** Only for artifact IDs indexed under these exact internal Compass event kinds. Exported projections are not executable approvals. */
    public static JsonObject artifact(JsonObject source, String indexedKind, boolean serverCoordinatesVisible) {
        var data = source.deepCopy(); boolean visible;
        switch (indexedKind) {
            case "compass_reconcile_preview" -> {
                var review = AiJson.GSON.fromJson(source, CompassReconciliation.class);
                visible = serverCoordinatesVisible && coordinatesVisible(review.evidence());
                data.add("evidence", search(review.evidence(), visible));
                if (review.preparation() != null) data.add("preparation", preparation(review.preparation(), visible));
            }
            case "compass_prepared_search_plan" -> {
                var plan = AiJson.GSON.fromJson(source, CompassPreparationPlan.class);
                visible = serverCoordinatesVisible && plan.search().coordinatesVisible();
                data.add("search", searchPlan(data.getAsJsonObject("search"), visible));
            }
            case "compass_search_plan" -> {
                var plan = AiJson.GSON.fromJson(source, CompassSearchPlan.class);
                visible = serverCoordinatesVisible && plan.coordinatesVisible(); data = searchPlan(data, visible);
            }
            default -> throw new IllegalArgumentException("COMPASS_EXPORT_ARTIFACT_KIND_INVALID");
        }
        data.remove("hash"); data.remove("executionEpoch");
        data.addProperty("exportProjectionOnly", true); data.addProperty("coordinatesHidden", !visible);
        data.addProperty("coordinatePolicy", visible ? "NATIVE_COORDINATES_VISIBLE" : "HIDDEN_BY_SERVER_COMPASS_POLICY");
        return data;
    }
    private static JsonObject searchPlan(JsonObject source, boolean visible) {
        var plan = source.deepCopy(); plan.remove("itemFingerprint"); plan.remove("hash"); plan.remove("executionEpoch");
        if (!visible) { plan.remove("originX"); plan.remove("originY"); plan.remove("originZ"); }
        return plan;
    }
    public static boolean currentStructureCoordinatesVisible() { return CompassNativeAccess.structureCoordinatesConfigured(); }
}
