package firefly520.fireflymc.client.ai.integration.map;

import com.google.gson.JsonObject;
import java.util.Objects;
import java.util.UUID;

/** A deliberately selected coordinate. Every client submission remains an unverified client report. */
public record MapPoint(UUID id, String label, String dimension, int x, Integer y, int z,
                       Source source, String sourceVersion, UUID taskId) {
    public enum Source { CLIENT_POSITION, MANUAL_COORDINATE, XAERO_WAYPOINT, XAERO_MAP_CENTER, ATLAS_MAP_CENTER, SERVER_TASK_REFERENCE }
    public static final int HORIZONTAL_LIMIT = 30_000_000;
    public MapPoint {
        Objects.requireNonNull(id); Objects.requireNonNull(source);
        label = bounded(label, 80, "MAP_LABEL_INVALID");
        sourceVersion = bounded(sourceVersion, 80, "MAP_VERSION_INVALID");
        if (dimension == null || dimension.length() > 160 || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                || Math.abs((long) x) > HORIZONTAL_LIMIT || Math.abs((long) z) > HORIZONTAL_LIMIT
                || y != null && (y < -2048 || y > 2048)) throw new IllegalArgumentException("MAP_COORDINATE_INVALID");
        if (source == Source.SERVER_TASK_REFERENCE && taskId == null) throw new IllegalArgumentException("MAP_TASK_REFERENCE_REQUIRED");
    }
    private static String bounded(String value, int maximum, String code) {
        if (value == null) throw new IllegalArgumentException(code);
        value = value.strip();
        if (value.length() > maximum || value.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0x00a7))
            throw new IllegalArgumentException(code);
        return value;
    }
    public String coordinates() { return dimension + " · " + x + " " + (y == null ? "?" : y) + " " + z; }
    public String sourceLabel() {
        return switch (source) {
            case CLIENT_POSITION -> "客户端当前位置";
            case MANUAL_COORDINATE -> "本人填写的坐标";
            case XAERO_WAYPOINT -> "本人单选的 Xaero 路标";
            case XAERO_MAP_CENTER -> "本人选取的 Xaero 地图中心";
            case ATLAS_MAP_CENTER -> "当前手持地图中心";
            case SERVER_TASK_REFERENCE -> "服务器任务坐标引用";
        };
    }
    public String coordinateCard() {
        return (label.isEmpty() ? "地点" : label) + "\n" + coordinates() + "\n" + sourceLabel()
                + "\n客户端坐标线索；地形、可通行性、库存和权限仍需服务端核验。";
    }
    /** One explicitly confirmed point only; never includes native objects, paths, map pixels or neighboring points. */
    public JsonObject clientReport() {
        var data = new JsonObject(); data.addProperty("version", 1); data.addProperty("requestId", id.toString());
        data.addProperty("label", label); data.addProperty("dimension", dimension); data.addProperty("x", x);
        if (y != null) data.addProperty("y", y);
        data.addProperty("z", z); data.addProperty("source", source.name()); data.addProperty("sourceVersion", sourceVersion);
        data.addProperty("authority", "CLIENT_REPORTED"); data.addProperty("worldVerified", false);
        if (taskId != null) data.addProperty("taskId", taskId.toString());
        return data;
    }
}
