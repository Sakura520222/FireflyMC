package firefly520.fireflymc.client.ai.blueprint;

import firefly520.fireflymc.client.ai.blueprint.RegionDragMath.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegionDragGeometryTest {
    @Test void constrainedDragMovesOnlyTheSelectedAxis() {
        var corner = new Point(0, 0, 0); var ray = new Ray(new Point(0, 0, 10), new Point(2, 3, -10));
        var x = RegionDragMath.anchor(ray, corner, Axis.X, new Point(0, 0, 1)).orElseThrow();
        assertTrue(x.x() > 1); assertEquals(0, x.y()); assertEquals(0, x.z());
        var y = RegionDragMath.anchor(ray, corner, Axis.Y, new Point(0, 0, 1)).orElseThrow();
        assertEquals(0, y.x()); assertTrue(y.y() > 2); assertEquals(0, y.z());
    }
    @Test void parallelAxesAndBehindCameraIntersectionsCannotInventMovement() {
        var ray = new Ray(new Point(0, 0, 10), new Point(0, 0, -1));
        assertTrue(RegionDragMath.anchor(ray, new Point(0, 0, 0), Axis.Z, new Point(0, 0, 1)).isEmpty());
        assertTrue(RegionDragMath.anchor(ray, new Point(0, 0, 20), Axis.FREE, new Point(0, 0, 1)).isEmpty());
        assertTrue(RegionDragMath.anchor(ray, new Point(0, 0, 0), Axis.FREE, new Point(1, 0, 0)).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new Point(Double.NaN, 0, 0));
    }
    @Test void projectionAndPointerRayRoundTripAtDistantWorldCoordinates() {
        var projection = new Matrix4f().perspective((float) Math.toRadians(70), 1.6f, .05f, 512);
        var camera = new Point(20_000_000.25, 74.5, -20_000_000.75);
        var frame = new RegionDragProjection(projection, new Matrix4f(), camera, 960, 600);
        var point = camera.add(new Point(2, 1, -10)); var screen = frame.project(point).orElseThrow();
        var restored = RegionDragMath.anchor(frame.ray(screen.x(), screen.y()), point, Axis.FREE, new Point(0, 0, 1)).orElseThrow();
        assertEquals(point.x(), restored.x(), .0001); assertEquals(point.y(), restored.y(), .0001); assertEquals(point.z(), restored.z(), .0001);
        assertTrue(frame.project(camera.add(new Point(0, 0, 1))).isEmpty());
        assertTrue(frame.project(camera.add(new Point(1000, 0, -1))).isEmpty());
        assertFalse(frame.fresh(480, 300));
    }
    @Test void projectionOwnsItsMatricesAndRejectsNonInvertibleViews() {
        var projection = new Matrix4f().perspective((float) Math.toRadians(70), 1, .05f, 512);
        var view = new Matrix4f(); var frame = new RegionDragProjection(projection, view, new Point(0, 0, 0), 600, 600);
        var before = frame.project(new Point(1, 1, -10)); projection.zero(); view.rotateY(1.3f);
        assertEquals(before, frame.project(new Point(1, 1, -10)));
        assertThrows(IllegalArgumentException.class, () -> new RegionDragProjection(new Matrix4f().zero(), new Matrix4f(), new Point(0, 0, 0), 600, 600));
    }
    @Test void rotatedCameraUsesTheRenderedViewRatherThanAssumingWorldNorth() {
        var frame = new RegionDragProjection(new Matrix4f().perspective((float) Math.toRadians(85), 1, .05f, 512),
                new Matrix4f().rotateY((float) (Math.PI / 2)), new Point(0, 0, 0), 600, 600);
        var target = new Point(10, 2, 0); var screen = frame.project(target).orElseThrow();
        var hit = RegionDragMath.anchor(frame.ray(screen.x(), screen.y()), target, Axis.FREE, new Point(1, 0, 0)).orElseThrow();
        assertEquals(target.x(), hit.x(), .0001); assertEquals(target.y(), hit.y(), .0001); assertEquals(target.z(), hit.z(), .0001);
    }
}
