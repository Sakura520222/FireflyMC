package firefly520.fireflymc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import org.junit.jupiter.api.Test;

class FireflyMCModConfigTest {
    @Test
    void serverLoadingAndReloadingReadStoryEnabled() {
        for (ModConfigEvent event : new ModConfigEvent[] {
            new ModConfigEvent.Loading(null),
            new ModConfigEvent.Reloading(null)
        }) {
            AtomicInteger typeReads = new AtomicInteger();
            AtomicInteger valueReads = new AtomicInteger();
            Supplier<ModConfig.Type> type = () -> {
                typeReads.incrementAndGet();
                return ModConfig.Type.SERVER;
            };
            BooleanSupplier enabled = () -> {
                valueReads.incrementAndGet();
                return false;
            };

            Boolean result = FireflyMCMod.resolveStoryEnabled(event, type, enabled);

            assertFalse(result);
            assertEquals(1, typeReads.get());
            assertEquals(1, valueReads.get());
        }
    }

    @Test
    void nonServerLoadingDoesNotReadStoryEnabled() {
        AtomicInteger valueReads = new AtomicInteger();

        Boolean result = FireflyMCMod.resolveStoryEnabled(
            new ModConfigEvent.Loading(null),
            () -> ModConfig.Type.CLIENT,
            () -> {
                valueReads.incrementAndGet();
                return true;
            });

        assertEquals(null, result);
        assertEquals(0, valueReads.get());
    }

    @Test
    void serverUnloadingReturnsBeforeReadingConfigOrValueAndDoesNotThrow() {
        Supplier<ModConfig.Type> failingType = () -> {
            throw new AssertionError("Unloading must not read ModConfig");
        };
        BooleanSupplier failingValue = () -> {
            throw new AssertionError("Unloading must not read storyEnabled");
        };

        Boolean result = assertDoesNotThrow(() -> FireflyMCMod.resolveStoryEnabled(
            new ModConfigEvent.Unloading(null),
            failingType,
            failingValue));

        assertEquals(null, result);
    }
}
