package firefly520.fireflymc.ai.integration.maid.transport;

import java.util.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;

/** Only an approved, exact maid/bauble native pass is intercepted. Ordinary wireless IO is untouched. */
public final class NativeMaidTransportScope {
    public interface ContainerDirtyHook {}
    public interface Job {
        boolean before(Object maid, ItemStack bauble);
        void helper(Object nativeBauble, boolean maidToChest, IItemHandler maid, IItemHandler chest,
                    boolean blacklist, IItemHandler filter, List<Boolean> excluded);
        void after();
        void aborted();
        void blockEntityChanged(BlockEntity entity);
    }
    private static final Map<Object, Job> OWNED = new IdentityHashMap<>();
    private static final ThreadLocal<Job> ACTIVE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> VIRTUAL = ThreadLocal.withInitial(() -> false);
    private NativeMaidTransportScope() {}
    public static void bind(Object maid, Job job) {
        synchronized (OWNED) { if (OWNED.putIfAbsent(maid, job) != null) throw new IllegalStateException("MAID_TRANSPORT_ALREADY_ARMED"); }
    }
    public static void unbind(Job job) { synchronized (OWNED) { OWNED.values().removeIf(value -> value == job); } }
    public static boolean active() { return ACTIVE.get() != null; }
    public static void blockEntityChanged(BlockEntity entity) { var job = ACTIVE.get(); if (job != null) job.blockEntityChanged(entity); }
    public static boolean before(Object maid, ItemStack bauble) {
        Job job; synchronized (OWNED) { job = OWNED.get(maid); }
        if (job == null) return false;
        if (!(maid instanceof net.minecraft.world.entity.Entity entity) || entity.tickCount % 100 != 0) return false;
        if (ACTIVE.get() != null) { var stale = ACTIVE.get(); ACTIVE.remove(); stale.aborted(); }
        if (!job.before(maid, bauble)) return true;
        ACTIVE.set(job); return false;
    }
    public static boolean helper(Object nativeBauble, boolean toChest, IItemHandler maid, IItemHandler chest,
            boolean blacklist, IItemHandler filter, List<Boolean> excluded) {
        var job = ACTIVE.get(); if (job == null || VIRTUAL.get()) return false;
        job.helper(nativeBauble, toChest, maid, chest, blacklist, filter, excluded); return true;
    }
    public static void after() { var job = ACTIVE.get(); ACTIVE.remove(); if (job != null) job.after(); }
    public static void discardAborted(Job expected) {
        if (ACTIVE.get() == expected) { ACTIVE.remove(); expected.aborted(); }
    }
    public static void virtual(Runnable body) {
        if (VIRTUAL.get()) throw new IllegalStateException("MAID_TRANSPORT_VIRTUAL_SCOPE_NESTED");
        VIRTUAL.set(true); try { body.run(); } finally { VIRTUAL.remove(); }
    }
}
