package firefly520.fireflymc.client.ai.blueprint;

import java.util.Optional;

/** Camera-ray geometry independent of a running client; an ambiguous drag never moves a corner. */
public final class RegionDragMath {
    public record Point(double x, double y, double z) {
        public Point { if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("DRAG_NON_FINITE"); }
        public Point add(Point p) { return new Point(x + p.x, y + p.y, z + p.z); }
        public Point subtract(Point p) { return new Point(x - p.x, y - p.y, z - p.z); }
        public Point scale(double value) { return new Point(x * value, y * value, z * value); }
        public double dot(Point p) { return x * p.x + y * p.y + z * p.z; }
        public Point unit() { double length = Math.sqrt(dot(this)); if (length < 1e-9) throw new IllegalArgumentException("DRAG_ZERO_VECTOR"); return scale(1 / length); }
    }
    public record Ray(Point origin, Point direction) { public Ray { direction = direction.unit(); } }
    public enum Axis {
        FREE(null), X(new Point(1, 0, 0)), Y(new Point(0, 1, 0)), Z(new Point(0, 0, 1));
        private final Point direction;
        Axis(Point direction) { this.direction = direction; }
    }
    private RegionDragMath() { }
    public static Optional<Point> anchor(Ray ray, Point corner, Axis axis, Point planeNormal) {
        if (axis == Axis.FREE) {
            double denominator = ray.direction.dot(planeNormal);
            if (Math.abs(denominator) < 1e-7) return Optional.empty();
            double distance = corner.subtract(ray.origin).dot(planeNormal) / denominator;
            return distance < 0 ? Optional.empty() : Optional.of(ray.origin.add(ray.direction.scale(distance)));
        }
        var u = axis.direction; var w = corner.subtract(ray.origin);
        double b = u.dot(ray.direction), denominator = 1 - b * b;
        if (denominator < 1e-5) return Optional.empty();
        double d = u.dot(w), e = ray.direction.dot(w);
        double along = (b * e - d) / denominator;
        double distance = (e - b * d) / denominator;
        return distance < 0 ? Optional.empty() : Optional.of(corner.add(u.scale(along)));
    }
}
