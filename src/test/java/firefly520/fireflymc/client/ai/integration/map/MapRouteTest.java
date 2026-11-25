package firefly520.fireflymc.client.ai.integration.map;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapRouteTest {
    private MapPoint point(int x, String dimension, UUID task) {
        return new MapPoint(UUID.randomUUID(), "点", dimension, x, null, 0, task == null ? MapPoint.Source.MANUAL_COORDINATE : MapPoint.Source.SERVER_TASK_REFERENCE, "", task);
    }
    @Test void routeChangesAreImmutableVersionedAndBounded() {
        var point = point(0, "minecraft:overworld", null); var empty = MapRoute.empty(); var first = empty.append(point);
        assertTrue(empty.points().isEmpty()); assertEquals(empty.id(), first.id()); assertEquals(1, first.revision());
        assertThrows(UnsupportedOperationException.class, () -> first.points().clear());
        assertThrows(IllegalArgumentException.class, () -> first.append(point));
        var route = empty;
        for (int index = 0; index < 32; index++) route = route.append(point(index, "minecraft:overworld", null));
        var full = route;
        assertThrows(IllegalArgumentException.class, () -> full.append(point(33, "minecraft:overworld", null)));
        assertEquals(31, full.removeLast().points().size()); assertEquals(33, full.removeLast().revision());
    }
    @Test void crossDimensionLegHasNoFabricatedTravelDistance() {
        var route = MapRoute.empty().append(point(-30_000_000, "minecraft:overworld", null))
                .append(point(30_000_000, "minecraft:overworld", null)).append(point(0, "minecraft:the_nether", null));
        assertEquals(60_000_000, route.legs().getFirst().horizontalDistance());
        assertTrue(route.legs().getFirst().sameDimension()); assertFalse(route.legs().get(1).sameDimension());
        assertTrue(Double.isNaN(route.legs().get(1).horizontalDistance()));
    }
    @Test void taskRoutesCannotSilentlyIncludeAnotherTasksCoordinateReference() {
        var task = UUID.randomUUID(); var route = new MapRoute(UUID.randomUUID(), task, 4, List.of(point(0, "minecraft:overworld", task)));
        assertThrows(IllegalArgumentException.class, () -> route.append(point(1, "minecraft:overworld", UUID.randomUUID())));
        assertEquals(4, route.revision()); assertEquals(1, route.points().size());
    }
    @Test void clearingPointsKeepsTheOriginalTaskAndRouteIdentityWhileAdvancingItsRevision() {
        var task = UUID.randomUUID(); var route = new MapRoute(UUID.randomUUID(), task, 7, List.of(point(0, "minecraft:overworld", task)));
        var empty = route.clear(); assertTrue(empty.points().isEmpty());
        assertEquals(route.id(), empty.id()); assertEquals(task, empty.taskId()); assertEquals(8, empty.revision());
        assertEquals(1, route.points().size()); assertSame(empty, empty.clear());
    }
}
