package cc.sighs.gravityengine.gravity.debug;

import org.slf4j.Logger;

/** Semantic severity with an INFO transport fallback for explicit JVM opt-ins.
 * Does not change the application's logger configuration or suppress requested traces. */
final class DebugLogPolicy {
    private DebugLogPolicy() {}

    static void summary(Logger logger, String message, Object... args) {
        if (logger.isDebugEnabled()) logger.debug(message, args);
        else logger.info("[DEBUG] " + message, args);
    }

    static void trace(Logger logger, String message, Object... args) {
        if (logger.isTraceEnabled()) logger.trace(message, args);
        else logger.info("[TRACE] " + message, args);
    }
}
