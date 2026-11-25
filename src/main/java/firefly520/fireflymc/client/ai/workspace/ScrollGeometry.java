package firefly520.fireflymc.client.ai.workspace;

/** Pure viewport geometry shared by rendering and pointer dragging. */
public record ScrollGeometry(int top, int height, int maximum, int thumbTop, int thumbHeight) {
    public static ScrollGeometry of(int top, int viewportHeight, int contentHeight, int offset) {
        int height = Math.max(0, viewportHeight);
        int maximum = Math.max(0, contentHeight - height);
        int thumb = maximum == 0 ? height : Math.min(height, Math.max(18, (int) ((long) height * height / contentHeight)));
        int travel = height - thumb;
        int position = maximum == 0 ? 0 : (int) ((long) Math.clamp(offset, 0, maximum) * travel / maximum);
        return new ScrollGeometry(top, height, maximum, top + position, thumb);
    }
    public boolean visible() { return maximum > 0 && height > 0; }
    public int drag(double pointerY, double grabOffset) {
        int travel = height - thumbHeight;
        return travel == 0 ? 0 : (int) Math.round(Math.clamp((pointerY - grabOffset - top) / travel, 0, 1) * maximum);
    }
}
