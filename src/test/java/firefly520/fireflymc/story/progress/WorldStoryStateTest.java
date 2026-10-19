package firefly520.fireflymc.story.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

class WorldStoryStateTest {
    @Test
    void defaultsAreFalseAndZero() {
        WorldStoryState state = new WorldStoryState();

        assertFalse(state.getFlag("firefly.open"));
        assertEquals(0, state.getVar("firefly.power"));
        assertTrue(state.flags().isEmpty());
        assertTrue(state.vars().isEmpty());
        assertFalse(state.isDirty());
    }

    @Test
    void accessIsConfinedToTheOwnerThread() throws Throwable {
        WorldStoryState state = createdOnAnotherThread(WorldStoryState::new);

        assertThrows(IllegalThreadStateException.class, () -> state.getFlag(null));
        assertThrows(IllegalThreadStateException.class,
            () -> state.setFlag("firefly.open", true));
        assertThrows(IllegalThreadStateException.class, () -> state.flags());
        assertThrows(IllegalThreadStateException.class, () -> state.serializeNBT(null));
        assertThrows(IllegalThreadStateException.class, state::isDirty);
        assertThrows(IllegalThreadStateException.class, state::setDirty);
        assertThrows(IllegalThreadStateException.class, () -> state.setDirty(false));
    }

    @Test
    void flagChangesMarkSavedDataDirtyOnlyOnAnActualChange() {
        WorldStoryState state = new WorldStoryState();

        assertFalse(state.setFlag("firefly.open", false));
        assertFalse(state.isDirty());
        assertTrue(state.setFlag("firefly.open", true));
        assertTrue(state.isDirty());

        state.setDirty(false);
        assertFalse(state.setFlag("firefly.open", true));
        assertFalse(state.isDirty());
    }

    @Test
    void varChangesClampAfterOperationAndMarkDirtyOnlyOnAnActualChange() {
        WorldStoryState state = new WorldStoryState();

        assertTrue(state.setVar("firefly.power", 8, 0, 10));
        assertTrue(state.isDirty());
        state.setDirty(false);
        assertTrue(state.addVar("firefly.power", 5, 0, 10));
        assertEquals(10, state.getVar("firefly.power"));
        assertTrue(state.isDirty());

        state.setDirty(false);
        assertFalse(state.setVar("firefly.power", 10, 0, 10));
        assertFalse(state.addVar("firefly.power", 0, 0, 10));
        assertFalse(state.isDirty());
        assertTrue(state.setVar("firefly.power", -1, 0, 10));
        assertEquals(0, state.getVar("firefly.power"));

        assertThrows(IllegalArgumentException.class,
            () -> state.setVar("Invalid", 1, 0, 10));
        assertThrows(IllegalArgumentException.class,
            () -> state.addVar("Invalid", 1, 0, 10));
        assertThrows(IllegalArgumentException.class,
            () -> state.setVar("firefly.power", 1, 10, 0));
    }

    @Test
    void immutableViewsAreCopySafeSnapshots() {
        WorldStoryState state = new WorldStoryState();
        state.setFlag("firefly.open", true);
        state.setVar("firefly.power", 3, 0, 10);

        var flags = state.flags();
        var vars = state.vars();
        assertThrows(UnsupportedOperationException.class, () -> flags.put("firefly.open", false));
        assertThrows(UnsupportedOperationException.class, () -> vars.put("firefly.power", 0));

        state.setFlag("firefly.second", true);
        assertEquals(Map.of("firefly.open", true), flags);
        assertEquals(Map.of("firefly.power", 3), vars);
    }

    @Test
    void structuredNbtRoundTripPreservesWorldState() {
        WorldStoryState state = new WorldStoryState();
        state.setFlag("firefly.open", true);
        state.setVar("firefly.power", 4, 0, 10);

        CompoundTag nbt = state.serializeNBT(RegistryAccess.EMPTY);
        assertEquals(1, nbt.getCompound("flags").getInt("firefly.open"));
        assertEquals(4, nbt.getCompound("vars").getInt("firefly.power"));

        WorldStoryState restored = new WorldStoryState();
        restored.deserializeNBT(RegistryAccess.EMPTY, nbt);
        assertEquals(state.flags(), restored.flags());
        assertEquals(state.vars(), restored.vars());
        assertTrue(restored.getFlag("firefly.open"));
        assertEquals(4, restored.getVar("firefly.power"));
    }

    @Test
    void missingOptionalContainersDefaultAndMalformedWorldContainersAreRejected() {
        WorldStoryState state = new WorldStoryState();
        state.deserializeNBT(RegistryAccess.EMPTY, new CompoundTag());
        assertEquals(Map.of(), state.flags());
        assertEquals(Map.of(), state.vars());

        WorldStoryState first = new WorldStoryState();
        CompoundTag invalidFlags = new CompoundTag();
        invalidFlags.put("flags", IntTag.valueOf(2));
        assertThrows(IllegalArgumentException.class,
            () -> first.deserializeNBT(RegistryAccess.EMPTY, invalidFlags));

        WorldStoryState second = new WorldStoryState();
        CompoundTag invalidVars = new CompoundTag();
        invalidVars.put("vars", new ListTag());
        assertThrows(IllegalArgumentException.class,
            () -> second.deserializeNBT(RegistryAccess.EMPTY, invalidVars));
    }

    private static WorldStoryState createdOnAnotherThread(Supplier<WorldStoryState> supplier)
        throws Throwable {
        AtomicReference<WorldStoryState> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                result.set(supplier.get());
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        thread.start();
        thread.join();
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }
}
