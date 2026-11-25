package firefly520.fireflymc.client.ai.integration.map;

import java.util.*;

/** A dimension-local schematic projection. It does not sample world or map-cache data. */
public final class MapProjection {
    private MapProjection() {}
    public record Point(int routeIndex, double x, double z) {}
    public record Line(Point from, Point to) {}
    public record View(List<Point> points, List<Line> lines) {
        public View { points = List.copyOf(points); lines = List.copyOf(lines); }
    }
    public static View project(MapRoute route, String dimension, double left, double top, double width, double height) {
        if (!Double.isFinite(left) || !Double.isFinite(top) || !Double.isFinite(width) || !Double.isFinite(height)
                || width < 1 || height < 1) throw new IllegalArgumentException("MAP_VIEWPORT_INVALID");
        var selected = new ArrayList<Integer>();
        for (int index = 0; index < route.points().size(); index++) if (route.points().get(index).dimension().equals(dimension)) selected.add(index);
        if (selected.isEmpty()) return new View(List.of(), List.of());
        double minX = Double.POSITIVE_INFINITY, minZ = minX, maxX = Double.NEGATIVE_INFINITY, maxZ = maxX;
        for (int index : selected) {
            var point = route.points().get(index);
            minX = Math.min(minX, point.x()); maxX = Math.max(maxX, point.x());
            minZ = Math.min(minZ, point.z()); maxZ = Math.max(maxZ, point.z());
        }
        double scale = Math.min(width / Math.max(1, maxX - minX), height / Math.max(1, maxZ - minZ));
        double centerX = minX + (maxX - minX) / 2, centerZ = minZ + (maxZ - minZ) / 2;
        var points = new LinkedHashMap<Integer, Point>();
        for (int index : selected) {
            var point = route.points().get(index);
            points.put(index, new Point(index, left + width / 2 + (point.x() - centerX) * scale,
                    top + height / 2 + (point.z() - centerZ) * scale));
        }
        var lines = new ArrayList<Line>();
        for (var leg : route.legs()) if (leg.sameDimension() && points.containsKey(leg.fromIndex()) && points.containsKey(leg.toIndex()))
            lines.add(new Line(points.get(leg.fromIndex()), points.get(leg.toIndex())));
        return new View(List.copyOf(points.values()), lines);
    }
}
