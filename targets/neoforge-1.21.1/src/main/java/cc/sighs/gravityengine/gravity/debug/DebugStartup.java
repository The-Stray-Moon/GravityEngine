package cc.sighs.gravityengine.gravity.debug;

/** One low-frequency startup summary, called from the common-side mod constructor. */
public final class DebugStartup {
    private static boolean reported;
    private DebugStartup() {}

    public static synchronized void report() {
        if (reported) return;
        reported = true;
        var options = BootDebugOptions.options();
        if (!options.anyTextEnabled()) return;
        org.slf4j.LoggerFactory.getLogger("GravityEngine/Debug").info(
                "[GravityEngine/Debug] gravity={} movement={} spatialState={} view={} viewStacks={} entityFilter={} side={} traceTransport=level-or-INFO-fallback",
                options.gravity(), options.movement(), options.spatialState(), options.view(), options.viewStacks(),
                options.entity() == null ? "<none>" : "<configured>", options.side());
    }
}
