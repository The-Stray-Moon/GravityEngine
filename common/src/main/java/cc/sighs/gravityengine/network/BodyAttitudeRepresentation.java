package cc.sighs.gravityengine.network;

import org.joml.Quaterniond;

/** Numeric representation required by BodyAttitudeState, independent of designer control limits. */
final class BodyAttitudeRepresentation {
    private BodyAttitudeRepresentation() {}

    static Quaterniond normalizedCopyOrUnusable(Quaterniond q) {
        double scale = Math.max(Math.max(Math.abs(q.x), Math.abs(q.y)), Math.max(Math.abs(q.z), Math.abs(q.w)));
        if (!q.isFinite() || scale == 0) return new Quaterniond(q);
        // Scaling first avoids overflow/underflow for otherwise normalizable finite encodings.
        return new Quaterniond(q.x / scale, q.y / scale, q.z / scale, q.w / scale).normalize();
    }

    static boolean usable(Quaterniond body, Quaterniond controller) {
        return usable(body) && usable(controller);
    }
    private static boolean usable(Quaterniond q) {
        return q.isFinite() && Double.isFinite(q.lengthSquared()) && q.lengthSquared() > 1e-24;
    }
}
