package firefly520.fireflymc.ai;

import firefly520.fireflymc.client.ai.workspace.ScrollGeometry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScrollGeometryTest {
    @Test void dragReachesBothEndsAndKeepsTheGrabPointForLongHistory() {
        var geometry = ScrollGeometry.of(80, 200, 2000, 900);
        assertTrue(geometry.visible());
        assertEquals(900, geometry.drag(geometry.thumbTop() + 7, 7));
        assertEquals(0, geometry.drag(-100, 7));
        assertEquals(1800, geometry.drag(1000, 7));
        assertEquals(1800, ScrollGeometry.of(80, 200, 2000, 1800).maximum());
    }
    @Test void shortOrTinyViewportsCannotProduceDivisionByZeroOrOffscreenThumbs() {
        assertFalse(ScrollGeometry.of(80, 200, 120, 100).visible());
        var tiny = ScrollGeometry.of(80, 10, 10000, 9000);
        assertEquals(10, tiny.thumbHeight());
        assertEquals(0, tiny.drag(1000, 0));
    }
}
