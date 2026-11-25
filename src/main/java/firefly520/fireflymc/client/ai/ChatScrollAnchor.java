package firefly520.fireflymc.client.ai;

/** Keeps an explicit jump/send anchored through asynchronous history and streaming layout growth. */
public final class ChatScrollAnchor {
    private boolean following = true;
    public void latest() { following = true; }
    public void history() { following = false; }
    public boolean following() { return following; }
    public void manual(int position, int maximum) { following = position >= Math.max(0, maximum - 2); }
    public int layout(int position, int maximum) { return following ? Math.max(0, maximum) : Math.clamp(position, 0, Math.max(0, maximum)); }
}
