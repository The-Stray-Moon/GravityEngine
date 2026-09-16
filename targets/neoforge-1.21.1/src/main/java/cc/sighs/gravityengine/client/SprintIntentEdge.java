package cc.sighs.gravityengine.client;

/** Non-destructive key observation. Lost capture requires release before rearming. */
public final class SprintIntentEdge {
    private boolean held;
    private boolean blocked;
    public boolean observe(boolean down, boolean acceptsInput) {
        if (!acceptsInput) { blocked = true; held = down; return false; }
        if (blocked) {
            held = down;
            if (!down) blocked = false;
            return false;
        }
        boolean pressed = down && !held;
        held = down;
        return pressed;
    }
}
