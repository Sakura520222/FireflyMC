package firefly520.fireflymc.story.engine;

import com.mojang.logging.LogUtils;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

/**
 * Server-thread-confined, non-recursive queue of quests to evaluate in one transition.
 *
 * <p>A processor receives the queue itself, so nested invalidations are appended rather than
 * called recursively. Work is counted when it leaves the queue whether or not it completes.</p>
 */
public final class TransitionQueue {
    public static final int MAX_WORK_ITEMS_PER_TRANSACTION = 1024;

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Processes one dequeued quest; nested invalidations are appended to {@code queue}. */
    @FunctionalInterface
    public interface Processor {
        void process(ResourceLocation questId, TransitionQueue queue);
    }

    private final Deque<ResourceLocation> pending = new ArrayDeque<>();
    private final Set<ResourceLocation> queued = new LinkedHashSet<>();
    private final Processor processor;
    private final Thread owner;
    private int workItemsProcessed;
    private boolean limitExceeded;

    public TransitionQueue(Processor processor) {
        this(processor, Thread.currentThread());
    }

    TransitionQueue(Processor processor, Thread owner) {
        this.processor = Objects.requireNonNull(processor, "processor");
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    public void enqueue(ResourceLocation questId) {
        requireOwnerThread();
        Objects.requireNonNull(questId, "questId");
        if (queued.add(questId)) {
            pending.addLast(questId);
        }
    }

    /**
     * Removes the next ID from the pending queue and its queued-membership set. Returns null only
     * when the queue is empty or the work-item cap has already stopped this transaction.
     */
    public ResourceLocation removeNext() {
        requireOwnerThread();
        ResourceLocation questId = pending.poll();
        if (questId == null) {
            return null;
        }
        queued.remove(questId);
        if (workItemsProcessed >= MAX_WORK_ITEMS_PER_TRANSACTION) {
            if (!limitExceeded) {
                limitExceeded = true;
                LOGGER.error(
                    "Story transition aborted after {} work items; quest={}",
                    MAX_WORK_ITEMS_PER_TRANSACTION,
                    questId
                );
            }
            return null;
        }
        workItemsProcessed++;
        return questId;
    }

    /** Returns false only when the defensive work-item cap aborted the transaction. */
    public boolean drain() {
        requireOwnerThread();
        ResourceLocation questId;
        while (!limitExceeded && (questId = removeNext()) != null) {
            processor.process(questId, this);
        }
        return !limitExceeded;
    }

    public int workItemsProcessed() {
        requireOwnerThread();
        return workItemsProcessed;
    }

    public boolean limitExceeded() {
        requireOwnerThread();
        return limitExceeded;
    }

    public boolean isQueued(ResourceLocation questId) {
        requireOwnerThread();
        Objects.requireNonNull(questId, "questId");
        return queued.contains(questId);
    }

    public List<ResourceLocation> queuedIds() {
        requireOwnerThread();
        return List.copyOf(pending);
    }

    private void requireOwnerThread() {
        Thread current = Thread.currentThread();
        if (owner != current) {
            throw new IllegalThreadStateException(
                "TransitionQueue access is confined to its server thread");
        }
    }
}
