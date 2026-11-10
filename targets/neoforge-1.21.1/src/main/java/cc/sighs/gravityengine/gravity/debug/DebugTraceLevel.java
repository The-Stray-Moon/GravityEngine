package cc.sighs.gravityengine.gravity.debug;

/** Internal domain levels accepted by the JVM-only boot configuration. */
public enum DebugTraceLevel {
    OFF, ANOMALY, MUTATION, FULL;

    public boolean enabled() { return this != OFF; }
}
