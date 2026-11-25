package firefly520.fireflymc.ai.recipe.view;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static firefly520.fireflymc.ai.recipe.view.RecipeViewData.*;

/** Identity/epoch fence for display references. It contains no Minecraft or optional-mod classes. */
public final class RecipeViewSession<T> {
    private static final int MAX_REFERENCES = 128;
    private record Reference<T>(Viewer viewer, T value) {}
    private final Map<Viewer, Object> runtimes = new EnumMap<>(Viewer.class);
    private final LinkedHashMap<String, Reference<T>> references = new LinkedHashMap<>();
    private Object connection;
    private Authority authority;
    private long epoch;

    public boolean bind(Object connection, Authority authority) {
        Objects.requireNonNull(connection); Objects.requireNonNull(authority);
        if (this.connection == connection && authority.equals(this.authority)) return false;
        if (this.connection == connection && this.authority != null
                && this.authority.serverIdentity().equals(authority.serverIdentity())
                && authority.recipeGeneration() < this.authority.recipeGeneration()) return false;
        this.connection = connection;
        this.authority = authority;
        runtimes.clear();
        invalidate();
        return true;
    }
    public void require(Object connection, Authority requested) {
        if (this.connection == null || this.connection != connection || !Objects.equals(authority, requested))
            throw new IllegalStateException("RECIPE_AUTHORITY_STALE");
    }
    public boolean observeRuntime(Viewer viewer, Object runtime) {
        boolean changed = runtimes.containsKey(viewer) && runtimes.get(viewer) != runtime;
        runtimes.put(viewer, runtime);
        if (changed) invalidate();
        return changed;
    }
    public String remember(Viewer viewer, T value) {
        if (authority == null || runtimes.get(viewer) == null) throw new IllegalStateException("RECIPE_RUNTIME_UNAVAILABLE");
        String key = UUID.randomUUID().toString();
        references.put(key, new Reference<>(viewer, Objects.requireNonNull(value)));
        while (references.size() > MAX_REFERENCES) references.remove(references.keySet().iterator().next());
        return key;
    }
    public T resolve(Object connection, Authority requested, Viewer viewer, String key, long expectedEpoch) {
        require(connection, requested);
        Reference<T> reference = references.get(key);
        if (expectedEpoch != epoch || reference == null || reference.viewer() != viewer)
            throw new IllegalStateException("RECIPE_REFERENCE_STALE");
        return reference.value();
    }
    public void invalidate() { epoch++; references.clear(); }
    public void disconnect() { connection = null; authority = null; runtimes.clear(); invalidate(); }
    public long epoch() { return epoch; }
    public Authority authority() { return authority; }
}
