package firefly520.fireflymc.ai.presentation;

/** UTF-16 cursors on the wire must never cut a Unicode surrogate pair in half. */
public final class MessageTextChunks {
    private MessageTextChunks() {}
    public static int end(String text, int offset, int maximum) {
        if (offset < 0 || offset > text.length() || maximum < 1
                || offset > 0 && offset < text.length() && Character.isLowSurrogate(text.charAt(offset))
                && Character.isHighSurrogate(text.charAt(offset - 1))) throw new IllegalArgumentException("OFFSET");
        int end = offset + Math.min(maximum, text.length() - offset);
        if (end > offset && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) end--;
        if (end == offset && end < text.length()) end += Character.charCount(text.codePointAt(offset));
        return end;
    }
}
