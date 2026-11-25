package firefly520.fireflymc.ai.processing.advanced;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdvancedReflectionTest {
    public static final class NativeApi {
        public static void fail() { throw new IllegalStateException("isolated-native-diagnostic"); }
    }

    @Test void nativeFailureKeepsAnInternalCauseWithoutChangingThePublicErrorCode() {
        var failure = assertThrows(IllegalStateException.class,
                () -> AdvancedReflection.invoke(NativeApi.class, null, "fail", new Class<?>[0]));
        assertEquals("ADVANCED_NATIVE_OPERATION_FAILED", failure.getMessage());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals("isolated-native-diagnostic", failure.getCause().getMessage());
        assertFalse(failure.toString().contains("isolated-native-diagnostic"));
    }
}
