package firefly520.fireflymc.client.ai.integration.map;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapProjectionTest {
    private MapPoint point(String dimension, int x, int z) {
        return new MapPoint(UUID.randomUUID(), "点", dimension, x, null, z, MapPoint.Source.MANUAL_COORDINATE, "", null);
    }
    @Test void projectionNeverJoinsAcrossAMissingDimensionOrChangesTheOriginalRouteOrder() {
        var route = MapRoute.empty().append(point("minecraft:overworld", 0, 0)).append(point("minecraft:the_nether", 0, 0))
                .append(point("minecraft:overworld", 20, 20)).append(point("minecraft:overworld", 30, 40));
        var view = MapProjection.project(route, "minecraft:overworld", 10, 20, 200, 100);
        assertEquals(List.of(0, 2, 3), view.points().stream().map(MapProjection.Point::routeIndex).toList());
        assertEquals(1, view.lines().size()); assertEquals(2, view.lines().getFirst().from().routeIndex());
        assertEquals(3, view.lines().getFirst().to().routeIndex());
        assertThrows(UnsupportedOperationException.class, () -> view.points().clear());
    }
    @Test void extremeAndCoincidentCoordinatesStayFiniteAndInsideTheViewport() {
        for (var route : List.of(MapRoute.empty().append(point("minecraft:overworld", 2, 3)),
                MapRoute.empty().append(point("minecraft:overworld", -30_000_000, -30_000_000)).append(point("minecraft:overworld", 30_000_000, 30_000_000)))) {
            var view = MapProjection.project(route, "minecraft:overworld", 10, 20, 200, 100);
            for (var point : view.points()) {
                assertTrue(Double.isFinite(point.x()) && Double.isFinite(point.z()));
                assertTrue(point.x() >= 10 && point.x() <= 210); assertTrue(point.z() >= 20 && point.z() <= 120);
            }
        }
    }
    @Test void unavailableDimensionStaysEmptyAndAnInvalidViewportIsRejected() {
        var route = MapRoute.empty().append(point("minecraft:overworld", 0, 0));
        assertTrue(MapProjection.project(route, "minecraft:the_nether", 0, 0, 100, 100).points().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> MapProjection.project(route, "minecraft:overworld", 0, 0, Double.NaN, 100));
        assertThrows(IllegalArgumentException.class, () -> MapProjection.project(route, "minecraft:overworld", 0, 0, 0, 100));
    }
}
