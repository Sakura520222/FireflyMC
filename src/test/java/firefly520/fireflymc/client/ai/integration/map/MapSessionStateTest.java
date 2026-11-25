package firefly520.fireflymc.client.ai.integration.map;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapSessionStateTest {
    private final UUID server = UUID.randomUUID(), player = UUID.randomUUID(), connection = UUID.randomUUID();
    private final MapSessionState.Scope original = new MapSessionState.Scope(server, player, connection);
    private MapPoint point() { return new MapPoint(UUID.randomUUID(), "私有地点", "minecraft:overworld", 1, 64, 2, MapPoint.Source.CLIENT_POSITION, "", null); }
    @Test void changingAnyIdentityClearsBothSelectionAndRouteAndDoesNotExposeAnotherPlayersDraft() {
        for (var other : List.of(new MapSessionState.Scope(UUID.randomUUID(), player, connection),
                new MapSessionState.Scope(server, UUID.randomUUID(), connection), new MapSessionState.Scope(server, player, UUID.randomUUID()))) {
            var state = new MapSessionState(); state.bind(original); state.select(original, point()); state.appendSelected(original);
            assertTrue(state.bind(other)); assertTrue(state.selected().isEmpty()); assertTrue(state.route().points().isEmpty());
            assertThrows(IllegalStateException.class, () -> state.confirmSelected(original));
        }
    }
    @Test void reusingTheSameConnectionKeepsThePrivateDraftButConfirmingItTwiceIsRejected() {
        var state = new MapSessionState(); state.bind(original); var point = point(); state.select(original, point); state.appendSelected(original);
        assertFalse(state.bind(original)); assertEquals(point, state.selected().orElseThrow());
        assertEquals(point, state.confirmSelected(original)); assertThrows(IllegalStateException.class, () -> state.confirmSelected(original));
        assertEquals(List.of(point), state.route().points());
    }
    @Test void disconnectAndUnboundRequestsCannotReuseAnOldSelection() {
        var state = new MapSessionState(); assertThrows(IllegalStateException.class, () -> state.select(original, point()));
        state.bind(original); state.select(original, point()); state.clear();
        assertThrows(IllegalStateException.class, () -> state.confirmSelected(original));
        assertTrue(state.selected().isEmpty()); assertTrue(state.route().points().isEmpty());
    }
    @Test void failingToQueueAPointCanRetryButAnOldFailureCannotReleaseANewSessionsSubmission() {
        var state = new MapSessionState(); state.bind(original); var point = point(); state.select(original, point);
        assertEquals(point, state.confirmSelected(original));
        state.submissionFailed(original, point.id());
        assertEquals(point, state.confirmSelected(original));
        var next = new MapSessionState.Scope(server, player, UUID.randomUUID()); state.bind(next); state.select(next, point);
        assertEquals(point, state.confirmSelected(next)); state.submissionFailed(original, point.id());
        assertThrows(IllegalStateException.class, () -> state.confirmSelected(next));
    }
    @Test void selectingAnotherTasksRouteCannotReuseThePreviousTasksCoordinateSelection() {
        var state = new MapSessionState(); state.bind(original); var firstTask = UUID.randomUUID(); var secondTask = UUID.randomUUID();
        var point = new MapPoint(UUID.randomUUID(), "任务地点", "minecraft:overworld", 1, 64, 2,
                MapPoint.Source.SERVER_TASK_REFERENCE, "server-route-v1", firstTask);
        state.setRoute(original, new MapRoute(UUID.randomUUID(), firstTask, 1, List.of(point))); state.select(original, point);
        state.clearRoute(original); assertEquals(firstTask, state.route().taskId()); assertEquals(point, state.selected().orElseThrow());
        state.setRoute(original, new MapRoute(UUID.randomUUID(), secondTask, 1, List.of()));
        assertTrue(state.selected().isEmpty()); assertThrows(IllegalStateException.class, () -> state.confirmSelected(original));
    }
}
