package firefly520.fireflymc.client.ai.integration.map;

import java.util.*;

/** Private in-memory drafts are invalidated by server, owner or connection identity changes. */
public final class MapSessionState {
    public record Scope(UUID serverInstanceId, UUID playerId, UUID connectionId) {
        public Scope { Objects.requireNonNull(serverInstanceId); Objects.requireNonNull(playerId); Objects.requireNonNull(connectionId); }
    }
    private Scope scope;
    private MapPoint selected;
    private MapRoute route = MapRoute.empty();
    private final LinkedHashSet<UUID> submitted = new LinkedHashSet<>();
    public boolean bind(Scope next) {
        if (Objects.equals(scope, next)) return false;
        clear(); scope = next; return true;
    }
    public void clear() { scope = null; selected = null; route = MapRoute.empty(); submitted.clear(); }
    public Scope scope() { return scope; }
    public Optional<MapPoint> selected() { return Optional.ofNullable(selected); }
    void submissionFailed(Scope current, UUID pointId) {
        if (Objects.equals(scope, current)) submitted.remove(pointId);
    }
    public MapRoute route() { return route; }
    public void select(Scope current, MapPoint point) { require(current); selected = Objects.requireNonNull(point); }
    public void setRoute(Scope current, MapRoute value) {
        require(current); Objects.requireNonNull(value);
        if (selected != null && !Objects.equals(selected.taskId(), value.taskId())) selected = null;
        route = value;
    }
    public void appendSelected(Scope current) {
        require(current); if (selected == null) throw new IllegalStateException("MAP_POINT_NOT_SELECTED");
        route = route.append(selected);
    }
    public void removeLast(Scope current) { require(current); route = route.removeLast(); }
    public void clearRoute(Scope current) { require(current); route = route.clear(); }
    public MapPoint confirmSelected(Scope current) {
        require(current); if (selected == null) throw new IllegalStateException("MAP_POINT_NOT_SELECTED");
        if (!submitted.add(selected.id())) throw new IllegalStateException("MAP_POINT_ALREADY_SUBMITTED");
        while (submitted.size() > 64) submitted.remove(submitted.iterator().next());
        return selected;
    }
    public void require(Scope current) {
        if (current == null || !current.equals(scope)) throw new IllegalStateException("MAP_SESSION_CHANGED");
    }
}
