package firefly520.fireflymc.ai;

import firefly520.fireflymc.client.ai.workspace.CompactButtonFlow;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompactButtonFlowTest {
    @Test void navigationAndActionsShareACompactRowWhenTheirTextFits() {
        var flow = new CompactButtonFlow(30, 26, 854);
        var controls = new ArrayList<CompactButtonFlow.Bounds>();
        for (int i = 0; i < 6; i++) controls.add(flow.next(18));
        flow.sectionGap();
        for (int textWidth : List.of(27, 18, 18, 36, 36, 36, 36, 27, 18, 36, 36, 36, 36, 18)) controls.add(flow.next(textWidth));
        assertEquals(20, controls.size());
        assertTrue(controls.stream().allMatch(button -> button.y() == 26 && button.height() == 14));
        assertEquals(14, flow.height());
        assertNoOverlapAndWithin(controls, 30, 854);
    }
    @Test void narrowWidthsWrapEveryControlWithoutChangingItsActionOrderOrOverlapping() {
        for (int width : List.of(120, 204, 284, 580)) {
            var flow = new CompactButtonFlow(18, 26, width);
            var controls = new ArrayList<CompactButtonFlow.Bounds>();
            for (int i = 0; i < 6; i++) controls.add(flow.next(18));
            flow.sectionGap();
            for (int i = 0; i < 14; i++) controls.add(flow.next(i % 2 == 0 ? 36 : 27));
            assertEquals(20, controls.size()); assertNoOverlapAndWithin(controls, 18, width);
            assertTrue(controls.getLast().y() > 26);
            assertEquals(controls.getLast().y() + 14, flow.bottom());
        }
    }
    @Test void anOversizedLabelStaysInsideItsOwnRowAndTheFollowingControlWraps() {
        var flow = new CompactButtonFlow(8, 10, 56);
        var first = flow.next(500); flow.sectionGap(); var second = flow.next(9);
        assertEquals(56, first.width()); assertEquals(8, first.x()); assertEquals(27, second.y());
        assertEquals(8, second.x()); assertNoOverlapAndWithin(List.of(first, second), 8, 56);
    }
    @Test void minimumMinecraftViewportKeepsTheBottomComposerAndAReadableBodyBelowAllTopControls() {
        // A 320x240 scaled Minecraft screen has 284px of inner width and keeps the
        // existing 88px composer reservation; the longest current toolbar is the data page.
        var flow = new CompactButtonFlow(18, 26, 284);
        for (int i = 0; i < 6; i++) flow.next(18);
        flow.sectionGap();
        for (int textWidth : List.of(27, 18, 18, 36, 36, 36, 36, 27, 18, 36, 36, 36, 36, 18)) flow.next(textWidth);
        int contentStart = flow.bottom() + 5;
        assertTrue(240 - 88 - contentStart >= 60);
    }
    private static void assertNoOverlapAndWithin(List<CompactButtonFlow.Bounds> controls, int left, int width) {
        CompactButtonFlow.Bounds previous = null;
        for (var button : controls) {
            assertTrue(button.x() >= left && button.x() + button.width() <= left + width);
            if (previous != null) assertTrue(button.y() > previous.y() || button.x() >= previous.x() + previous.width() + CompactButtonFlow.GAP);
            previous = button;
        }
    }
}
