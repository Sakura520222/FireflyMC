package firefly520.fireflymc.ai.integration.maid;

import java.util.*;

/** Bounded identity/source audit for the active native farm callback; it never authorizes a store. */
public final class MaidFarmSavedDataScope implements AutoCloseable {
    private static final ThreadLocal<MaidFarmSavedDataScope> ACTIVE = new ThreadLocal<>();
    private final Map<Object, List<String>> observed = new IdentityHashMap<>();
    private MaidFarmSavedDataScope() {
        if (ACTIVE.get() != null) throw new IllegalStateException("MAID_FARM_SAVED_DATA_SCOPE_NESTED"); ACTIVE.set(this);
    }
    static MaidFarmSavedDataScope begin() { return new MaidFarmSavedDataScope(); }
    /** The ordinary unadapted-SavedData guard remains active. This only records exact native evidence. */
    public static void dirty(Object store, boolean dirty) {
        var scope = ACTIVE.get(); if (scope == null || !dirty || scope.observed.size() >= 32) return;
        scope.observed.computeIfAbsent(store, ignored -> StackWalker.getInstance().walk(stream -> stream
                .filter(frame -> !frame.getClassName().startsWith("firefly520.fireflymc.ai.integration.maid.MaidFarmSavedDataScope"))
                .limit(12).map(frame -> frame.getClassName() + "#" + frame.getMethodName()).toList()));
    }
    List<String> descriptions() {
        return observed.entrySet().stream().map(entry -> entry.getKey().getClass().getName() + ":" + String.join(" <- ", entry.getValue())).sorted().toList();
    }
    @Override public void close() { if (ACTIVE.get() == this) ACTIVE.remove(); }
}
