package firefly520.fireflymc.client.ai;

/** Compact two-line composer with two small actions sharing its height. */
public record WorkspaceComposerLayout(int inputX, int inputY, int inputWidth, int inputHeight,
                                      int actionX, int actionWidth, int buttonHeight, int copyY,
                                      int contentBottom, int hintY) {
    public static WorkspaceComposerLayout of(int x, int width, int screenHeight, int labelWidth, boolean hint) {
        int actionWidth = Math.min(Math.max(32, labelWidth + 12), Math.max(1, width / 3));
        int top = Math.max(0, screenHeight - 48);
        return new WorkspaceComposerLayout(x, top, Math.max(1, width - actionWidth - 4), 34,
                x + width - actionWidth, actionWidth, 16, top + 18,
                top - (hint ? 17 : 5), top - 12);
    }
}
