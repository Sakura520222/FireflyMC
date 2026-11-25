package firefly520.fireflymc.client.ai.blueprint;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import java.util.Optional;
import firefly520.fireflymc.client.ai.blueprint.RegionDragMath.Point;
import firefly520.fireflymc.client.ai.blueprint.RegionDragMath.Ray;

/** A copied render frame, using camera-relative coordinates to avoid distant-world precision loss. */
public final class RegionDragProjection {
    public record ScreenPoint(double x, double y, double depth) {}
    private final Matrix4f matrix, inverse;
    private final Point camera;
    private final int width, height;
    private final long captured = System.nanoTime();
    public RegionDragProjection(Matrix4f projection, Matrix4f modelView, Point camera, int width, int height) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("DRAG_VIEWPORT_INVALID");
        matrix = new Matrix4f(projection).mul(modelView);
        if (!Float.isFinite(matrix.determinant()) || Math.abs(matrix.determinant()) < 1e-9) throw new IllegalArgumentException("DRAG_PROJECTION_INVALID");
        inverse = new Matrix4f(matrix).invert(); this.camera = camera; this.width = width; this.height = height;
    }
    public boolean fresh(int width, int height) { return this.width == width && this.height == height && System.nanoTime() - captured < 500_000_000L; }
    public Optional<ScreenPoint> project(Point world) {
        var relative = world.subtract(camera);
        var clip = matrix.transform(new Vector4f((float) relative.x(), (float) relative.y(), (float) relative.z(), 1));
        if (!clip.isFinite() || clip.w <= 1e-6) return Optional.empty();
        double x = clip.x / clip.w, y = clip.y / clip.w, z = clip.z / clip.w;
        if (Math.abs(x) > 1 || Math.abs(y) > 1 || z < -1 || z > 1) return Optional.empty();
        return Optional.of(new ScreenPoint((x + 1) * width / 2, (1 - y) * height / 2, z));
    }
    public Ray ray(double x, double y) {
        var near = unproject(x, y, -1); var far = unproject(x, y, 1);
        return new Ray(near, far.subtract(near));
    }
    private Point unproject(double x, double y, float depth) {
        var value = inverse.transform(new Vector4f((float) (x * 2 / width - 1), (float) (1 - y * 2 / height), depth, 1));
        if (!value.isFinite() || Math.abs(value.w) < 1e-8) throw new IllegalArgumentException("DRAG_RAY_INVALID");
        return camera.add(new Point(value.x / value.w, value.y / value.w, value.z / value.w));
    }
}
