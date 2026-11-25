package firefly520.fireflymc.client.ai.integration.map;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapPointTest {
    private MapPoint point(Integer y, MapPoint.Source source, UUID task) {
        return new MapPoint(UUID.randomUUID(), "本人地点", "minecraft:overworld", -30_000_000, y, 30_000_000, source, "26.4.2", task);
    }
    @Test void selectedPointReportHasAnExplicitWhitelistAndNeverPromotesAClientToWorldAuthority() {
        for (var source : MapPoint.Source.values()) {
            var report = point(null, source, source == MapPoint.Source.SERVER_TASK_REFERENCE ? UUID.randomUUID() : null).clientReport();
            assertEquals("CLIENT_REPORTED", report.get("authority").getAsString(), source.name());
            assertFalse(report.get("worldVerified").getAsBoolean()); assertFalse(report.has("y"));
            var allowed = new HashSet<>(Set.of("version", "requestId", "label", "dimension", "x", "z", "source", "sourceVersion", "authority", "worldVerified"));
            if (source == MapPoint.Source.SERVER_TASK_REFERENCE) allowed.add("taskId");
            assertEquals(allowed, report.keySet());
        }
    }
    @Test void unknownHeightRemainsUnknownInTheCardAndCannotAcquireAnInventedHeightDuringExport() {
        var point = point(null, MapPoint.Source.XAERO_WAYPOINT, null);
        assertTrue(point.coordinates().contains(" ? ")); assertFalse(point.clientReport().has("y"));
        assertEquals(72, point(72, MapPoint.Source.CLIENT_POSITION, null).clientReport().get("y").getAsInt());
    }
    @Test void hugeCoordinatesInvalidDimensionsAndFormattingPayloadsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MapPoint(UUID.randomUUID(), "地点", "minecraft:overworld", Integer.MIN_VALUE, null, 0, MapPoint.Source.MANUAL_COORDINATE, "", null));
        assertThrows(IllegalArgumentException.class, () -> new MapPoint(UUID.randomUUID(), "地点", "../private/path", 0, null, 0, MapPoint.Source.MANUAL_COORDINATE, "", null));
        for (String label : List.of("越界\n第二条", "\u00a7c格式", "a".repeat(81)))
            assertThrows(IllegalArgumentException.class, () -> new MapPoint(UUID.randomUUID(), label, "minecraft:overworld", 0, null, 0, MapPoint.Source.MANUAL_COORDINATE, "", null));
        assertThrows(IllegalArgumentException.class, () -> point(2049, MapPoint.Source.CLIENT_POSITION, null));
    }
    @Test void taskReferencesRequireTheOriginalTaskButStillRemainUnverifiedWhenSentBackByAClient() {
        assertThrows(IllegalArgumentException.class, () -> point(null, MapPoint.Source.SERVER_TASK_REFERENCE, null));
        var task = UUID.randomUUID(); var point = point(64, MapPoint.Source.SERVER_TASK_REFERENCE, task);
        assertEquals(task.toString(), point.clientReport().get("taskId").getAsString());
        assertFalse(point.clientReport().get("worldVerified").getAsBoolean());
    }
}
