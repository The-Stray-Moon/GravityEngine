package cc.sighs.gravityengine.gravity.debug;

/** Logical Level side, never physical distribution. */
public enum DebugSide {
    CLIENT, SERVER, BOTH;

    public boolean matches(boolean clientSide) {
        return this == BOTH || (this == CLIENT) == clientSide;
    }
}
