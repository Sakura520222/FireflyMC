package firefly520.fireflymc.client.ai.integration.map;

import java.util.*;

/** Bounded coordinate itinerary, not a pathfinder or a statement that unexplored terrain is passable. */
public record MapRoute(UUID id, UUID taskId, long revision, List<MapPoint> points) {
    public static final int MAX_POINTS = 32;
    public MapRoute {
        Objects.requireNonNull(id); points = List.copyOf(points);
        if (revision < 0 || points.size() > MAX_POINTS) throw new IllegalArgumentException("MAP_ROUTE_LIMIT");
        var ids = new HashSet<UUID>();
        for (var point : points) {
            if (!ids.add(point.id()) || point.taskId() != null && !Objects.equals(point.taskId(), taskId))
                throw new IllegalArgumentException("MAP_ROUTE_TASK_OR_POINT_CHANGED");
        }
    }
    public static MapRoute empty() { return new MapRoute(UUID.randomUUID(), null, 0, List.of()); }
    public MapRoute append(MapPoint point) {
        var next = new ArrayList<>(points); next.add(point);
        return new MapRoute(id, taskId, Math.addExact(revision, 1), next);
    }
    public MapRoute removeLast() {
        return points.isEmpty() ? this : new MapRoute(id, taskId, Math.addExact(revision, 1), points.subList(0, points.size() - 1));
    }
    public MapRoute clear() { return points.isEmpty() ? this : new MapRoute(id, taskId, Math.addExact(revision, 1), List.of()); }
    public List<Leg> legs() {
        var result = new ArrayList<Leg>();
        for (int index = 1; index < points.size(); index++) {
            var from = points.get(index - 1); var to = points.get(index);
            boolean same = from.dimension().equals(to.dimension());
            result.add(new Leg(index - 1, index, same, same ? Math.hypot((double) to.x() - from.x(), (double) to.z() - from.z()) : Double.NaN));
        }
        return List.copyOf(result);
    }
    public record Leg(int fromIndex, int toIndex, boolean sameDimension, double horizontalDistance) {}
}
