package firefly520.fireflymc.client.ai.workspace;

/** Text-sized top controls in one bounded flow; rows grow only when the available width requires them. */
public final class CompactButtonFlow {
    public static final int HEIGHT = 14, GAP = 3, PADDING = 12, MINIMUM_WIDTH = 20;
    public record Bounds(int x, int y, int width, int height) { }
    private final int left, top, width;
    private int x, y, bottom;

    public CompactButtonFlow(int left, int top, int width) {
        if (width < 1) throw new IllegalArgumentException("BUTTON_FLOW_WIDTH");
        this.left = left; this.top = top; this.width = width; x = left; y = top; bottom = top;
    }
    public Bounds next(int textWidth) {
        if (textWidth < 0) throw new IllegalArgumentException("BUTTON_FLOW_TEXT_WIDTH");
        int desired = (int) Math.min(Integer.MAX_VALUE, (long) textWidth + PADDING);
        int buttonWidth = Math.min(width, Math.max(MINIMUM_WIDTH, desired));
        if (x > left && (long) x + buttonWidth > (long) left + width) { x = left; y += HEIGHT + GAP; }
        var result = new Bounds(x, y, buttonWidth, HEIGHT);
        x += buttonWidth + GAP; bottom = y + HEIGHT;
        return result;
    }
    public void sectionGap() { if (x > left) x += GAP; }
    public int bottom() { return bottom; }
    public int height() { return bottom - top; }
}
